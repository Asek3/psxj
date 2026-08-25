package nanolive.psxj.emu.cd;

import nanolive.psxj.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdImage implements AutoCloseable {

    public static final int SECTOR_SIZE = 2352;
    private static final int USER_DATA_SECTOR_SIZE = 2048;
    private static final int MODE2_FORM2_DATA_SECTOR_SIZE = 2324;
    private static final int RAW_SECTOR_WITHOUT_SYNC_HEADER_SIZE = 2336;
    private static final int SECTOR_CACHE_SIZE = 32;

    private static final int[] EDC_LOOKUP = new int[256];
    private static final byte[] ECC_FORWARD_LOOKUP = new byte[256];
    private static final byte[] ECC_BACKWARD_LOOKUP = new byte[256];

    static {
        initializeErrorCodeTables();
    }

    // The CDROM command interface addresses sector 0 as MSF 00:02:00.
    private static final int LBA_MSF_OFFSET = 150;

    private static final int LICENCE_SECTOR_OFFSET = 4;

    private static final Pattern CUE_FILE_PATTERN    = Pattern.compile("^\\s*FILE\\s+\"([^\"]+)\".*$",               Pattern.CASE_INSENSITIVE);
    private static final Pattern CUE_TRACK_PATTERN   = Pattern.compile("^\\s*TRACK\\s+(\\d{1,2})\\s+(.+?)\\s*$",    Pattern.CASE_INSENSITIVE);
    private static final Pattern CUE_INDEX_PATTERN   = Pattern.compile("^\\s*INDEX\\s+(\\d{1,2})\\s+(\\d{2}:\\d{2}:\\d{2})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUE_PREGAP_PATTERN  = Pattern.compile("^\\s*PREGAP\\s+(\\d{2}:\\d{2}:\\d{2})\\s*$", Pattern.CASE_INSENSITIVE);

    private final Path   path;
    private final DiscLayout layout;
    private final Map<Integer, SbiPatch> sbiPatches;
    private final String regionCode;
    private final boolean licensedPlayStationDisc;
    private final int[] cachedSectorLbas = new int[SECTOR_CACHE_SIZE];
    private final CdSector[] cachedSectors = new CdSector[SECTOR_CACHE_SIZE];
    private final Map<Integer, CdSector> prefetchedSectors = new ConcurrentHashMap<>(16);
    private final Object prefetchMonitor = new Object();
    private volatile boolean closed;
    private Thread prefetchWorker;
    private int prefetchNextLba = -1;
    private int prefetchEndLba = -1;

    private CdImage(Path path, DiscLayout layout, Map<Integer, SbiPatch> sbiPatches) {
        this.path   = path;
        this.layout = layout;
        this.sbiPatches = Map.copyOf(sbiPatches);
        Arrays.fill(cachedSectorLbas, Integer.MIN_VALUE);
        LicenseInfo licence = detectLicence(layout);
        this.regionCode = licence.regionCode();
        this.licensedPlayStationDisc = licence.licensed();
    }

    public static CdImage open(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        DiscLayout layout = name.endsWith(".cue") ? parseCue(path) : openSingleTrack(path);
        Map<Integer, SbiPatch> sbiPatches = loadSbiPatches(path);
        Log.info("Mounted CD image: source=" + path
            + ", sectors=" + layout.totalSectors
            + ", invalidSubQ=" + sbiPatches.size());
        return new CdImage(path, layout, sbiPatches);
    }

    // Single .bin file without a cue sheet.
    private static DiscLayout openSingleTrack(Path path) throws IOException {
        DiscFile file = new DiscFile(path);
        int fileSectorSize = detectSingleTrackSectorSize(path, file.size());
        int fileSectors = Math.toIntExact(file.size() / fileSectorSize);

        List<TrackInfo> tracks = List.of(new TrackInfo(
            1, fileSectorSize == USER_DATA_SECTOR_SIZE ? "MODE1/2048" : "MODE2/2352",
            0,                       // pregapStartLba
            0,                       // index00StartLba
            0,                       // trackStartLba (INDEX 01)
            fileSectors,             // trackEndLbaExclusive
            file,
            fileSectorSize,
            0,                       // dataStartSectorInFile
            fileSectors));
        return new DiscLayout(tracks, fileSectors);
    }

    private static DiscLayout parseCue(Path path) throws IOException {
        List<String>          lines    = Files.readAllLines(path);
        List<CueTrackBuilder> builders = new ArrayList<>();
        Map<Path, DiscFile>   files    = new LinkedHashMap<>();
        Path              currentFile  = null;
        CueTrackBuilder   currentTrack = null;

        for (String line : lines) {
            Matcher fileMatcher = CUE_FILE_PATTERN.matcher(line);
            if (fileMatcher.matches()) {
                currentFile = path.getParent().resolve(fileMatcher.group(1)).normalize();
                if (!Files.exists(currentFile)) {
                    throw new IOException("Referenced track file not found: " + currentFile);
                }
                if (!files.containsKey(currentFile)) {
                    files.put(currentFile, new DiscFile(currentFile));
                }
                continue;
            }

            Matcher trackMatcher = CUE_TRACK_PATTERN.matcher(line);
            if (trackMatcher.matches()) {
                if (currentFile == null) {
                    throw new IOException("TRACK appears before FILE in CUE: " + path);
                }
                currentTrack = new CueTrackBuilder(
                    Integer.parseInt(trackMatcher.group(1)),
                    trackMatcher.group(2).trim(),
                    currentFile);
                builders.add(currentTrack);
                continue;
            }

            Matcher pregapMatcher = CUE_PREGAP_PATTERN.matcher(line);
            if (pregapMatcher.matches()) {
                if (currentTrack != null) {
                    currentTrack.directivePregapSectors = parseMsf(pregapMatcher.group(1));
                }
                continue;
            }

            Matcher indexMatcher = CUE_INDEX_PATTERN.matcher(line);
            if (indexMatcher.matches() && currentTrack != null) {
                int idx    = Integer.parseInt(indexMatcher.group(1));
                int sector = parseMsf(indexMatcher.group(2));
                if (idx == 0) currentTrack.index00Sector = sector;
                else if (idx == 1) currentTrack.index01Sector = sector;
            }
        }

        if (builders.isEmpty()) {
            throw new IOException("CUE has no TRACK entries: " + path);
        }
        for (CueTrackBuilder b : builders) {
            if (b.index01Sector < 0) {
                throw new IOException("TRACK " + b.number + " has no INDEX 01 in " + path);
            }
        }
        for (CueTrackBuilder b : builders) {
            DiscFile file = files.get(b.file);
            if (file == null) {
                throw new IOException("Missing file data for track " + b.number + ": " + b.file);
            }
            b.fileSectorSize = cueTrackSectorSize(b.type);
            b.fileSectors = Math.toIntExact(file.size() / b.fileSectorSize);
        }

        int discCursor = 0;
        List<TrackInfo> tracks = new ArrayList<>(builders.size());

        for (int i = 0; i < builders.size(); i++) {
            CueTrackBuilder builder = builders.get(i);

            int nextBoundary = builder.fileSectors;
            for (int j = i + 1; j < builders.size(); j++) {
                CueTrackBuilder candidate = builders.get(j);
                if (!candidate.file.equals(builder.file)) continue;
                nextBoundary = candidate.index00Sector >= 0
                    ? candidate.index00Sector
                    : candidate.index01Sector;
                break;
            }

            int index00Sectors = builder.index00Sector >= 0
                ? Math.max(0, builder.index01Sector - builder.index00Sector)
                : 0;
            int dataSectors = Math.max(0, nextBoundary - builder.index01Sector);

            int pregapStartLba  = discCursor;
            int index00StartLba;
            int trackStartLba;
            if (i == 0) {
                // Track 1 pregap lives before command LBA 0.
                index00StartLba = 0;
                trackStartLba   = 0;
                discCursor      = dataSectors;
            } else {
                discCursor     += builder.directivePregapSectors;
                index00StartLba = discCursor;
                trackStartLba   = index00StartLba + index00Sectors;
                discCursor      = trackStartLba + dataSectors;
            }

            tracks.add(new TrackInfo(
                builder.number, builder.type,
                pregapStartLba, index00StartLba, trackStartLba, discCursor,
                files.get(builder.file),
                builder.fileSectorSize,
                builder.index01Sector, dataSectors));
        }

        return new DiscLayout(tracks, discCursor);
    }

    public Path   path()         { return path; }
    public int    sectorCount()  { return layout.totalSectors; }
    public String regionCode()   { return regionCode; }
    public boolean isLicensedPlayStationDisc() { return licensedPlayStationDisc; }

    @Override
    public void close() {
        Thread worker;
        synchronized (prefetchMonitor) {
            closed = true;
            prefetchNextLba = -1;
            prefetchEndLba = -1;
            worker = prefetchWorker;
            prefetchMonitor.notifyAll();
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                // Sector reads are only a few KiB.
                worker.join(1_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        prefetchedSectors.clear();
        Arrays.fill(cachedSectorLbas, Integer.MIN_VALUE);
        Arrays.fill(cachedSectors, null);
        layout.close();
    }

    public void prefetch(int firstLba, int sectorCount) {
        int start = Math.max(0, firstLba);
        int end = (int) Math.min(layout.totalSectors,
            (long) start + Math.max(0, sectorCount));
        if (closed || start >= end) {
            return;
        }
        synchronized (prefetchMonitor) {
            if (closed) {
                return;
            }
            if (prefetchNextLba < 0) {
                prefetchNextLba = start;
                prefetchEndLba = end;
            } else if (start >= prefetchNextLba && start <= prefetchEndLba) {
                prefetchEndLba = Math.max(prefetchEndLba, end);
            } else {
                // A non-overlapping request is a seek.
                prefetchedSectors.clear();
                prefetchNextLba = start;
                prefetchEndLba = end;
            }
            if (prefetchWorker == null || !prefetchWorker.isAlive()) {
                prefetchWorker = Thread.ofPlatform()
                    .daemon(true)
                    .name("psxj-cd-prefetch")
                    .unstarted(this::runPrefetchWorker);
                prefetchWorker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                prefetchWorker.start();
            }
            prefetchMonitor.notifyAll();
        }
    }

    public int firstTrackNumber() { return layout.tracks.getFirst().number; }
    public int lastTrackNumber()  { return layout.tracks.getLast().number; }

    public boolean isAudioOnly() {
        return layout.firstDataTrack() == null;
    }

    public boolean hasAudioTracks() {
        for (TrackInfo track : layout.tracks) {
            if (!track.isDataTrack()) return true;
        }
        return false;
    }

    // TOC disc-type byte used by GetID (00h=audio/Mode1, 20h=Mode2/XA).
    public int discTypeCode() {
        TrackInfo dataTrack = layout.firstDataTrack();
        if (dataTrack == null) return 0;
        ResolvedSector first = layout.resolve(dataTrack.trackStartLba);
        return first != null && first.raw.length > 15 && (first.raw[15] & 0xFF) == 2
            ? 0x20 : 0x00;
    }

    public int trackStartLba(int trackNumber) {
        TrackInfo t = layout.findTrack(trackNumber);
        return t == null ? -1 : t.trackStartLba;
    }

    public int trackEndLba(int trackNumber) {
        TrackInfo t = layout.findTrack(trackNumber);
        return t == null ? -1 : t.trackEndLbaExclusive;
    }

    public int leadOutLba() { return layout.totalSectors; }

    // Synthesises the ten data bytes of an ADR=1 TOC Q entry from the cue sheet.
    public byte[] tocSubchannelQ(int adr, int point) {
        if ((adr & 0xFF) != 1) return null;

        TrackInfo referenceTrack;
        int payloadLba = -1;
        int payloadMinute;
        int payloadSecond;
        int payloadFrame;

        switch (point & 0xFF) {
            case 0xA0 -> {
                referenceTrack = layout.tracks.getFirst();
                payloadMinute = toBcd(firstTrackNumber());
                payloadSecond = discTypeCode();
                payloadFrame = 0;
            }
            case 0xA1 -> {
                referenceTrack = layout.tracks.getLast();
                payloadMinute = toBcd(lastTrackNumber());
                payloadSecond = 0;
                payloadFrame = 0;
            }
            case 0xA2 -> {
                referenceTrack = layout.tracks.getLast();
                payloadLba = leadOutLba();
                payloadMinute = payloadSecond = payloadFrame = 0;
            }
            default -> {
                if (!isPackedBcd(point)) return null;
                int trackNumber = fromPackedBcd(point);
                referenceTrack = layout.findTrack(trackNumber);
                if (referenceTrack == null) return null;
                payloadLba = referenceTrack.trackStartLba;
                payloadMinute = payloadSecond = payloadFrame = 0;
            }
        }

        if (payloadLba >= 0) {
            int absolute = payloadLba + LBA_MSF_OFFSET;
            payloadMinute = toBcd((absolute / (75 * 60)) % 100);
            payloadSecond = toBcd((absolute / 75) % 60);
            payloadFrame = toBcd(absolute % 75);
        }

        byte[] q = new byte[10];
        q[0] = (byte) (referenceTrack.isDataTrack() ? 0x41 : 0x01);
        q[1] = 0;                       // TNO=00 in lead-in
        q[2] = (byte) point;            // POINT
        q[3] = q[4] = q[5] = q[6] = 0;
        q[7] = (byte) payloadMinute;
        q[8] = (byte) payloadSecond;
        q[9] = (byte) payloadFrame;
        return q;
    }

    public TrackPosition locateLba(int lba) {
        TrackInfo track = layout.locateTrack(lba);
        if (track == null) return null;
        int index       = lba < track.trackStartLba ? 0 : 1;
        int relativeLba = Math.max(0, lba - track.trackStartLba);
        return new TrackPosition(track.number, index, relativeLba, lba);
    }

    public SubchannelQ subchannelQ(int lba) {
        TrackInfo track = layout.locateTrack(lba);
        if (track == null) return null;

        byte[] q = new byte[12];
        q[0] = (byte) (track.isDataTrack() ? 0x41 : 0x01);
        q[1] = (byte) toBcd(track.number);
        boolean inPregap = lba < track.trackStartLba;
        q[2] = (byte) toBcd(inPregap ? 0 : 1);
        int relative = inPregap
            ? Math.max(0, track.trackStartLba - lba)
            : Math.max(0, lba - track.trackStartLba);
        writeBcdMsf(q, 3, relative);
        q[6] = 0;
        writeBcdMsf(q, 7, Math.max(0, lba + LBA_MSF_OFFSET));

        SbiPatch patch = sbiPatches.get(lba);
        if (patch != null) {
            switch (patch.format) {
                case 1 -> System.arraycopy(patch.replacement, 0, q, 0, 10);
                case 2 -> System.arraycopy(patch.replacement, 0, q, 3, 3);
                case 3 -> System.arraycopy(patch.replacement, 0, q, 7, 3);
                default -> throw new IllegalStateException("Unsupported SBI entry format " + patch.format);
            }
        }

        int crc = subchannelQCrc(q);
        q[10] = (byte) (crc >>> 8);
        q[11] = (byte) crc;
        if (patch != null) {
            // SBI cannot store Q10/Q11.
            q[11] ^= 0x01;
        }
        return new SubchannelQ(q, patch == null);
    }

    public boolean isDataTrackLba(int lba) {
        TrackInfo t = layout.locateTrack(lba);
        return t != null && t.isDataTrack();
    }

    // Read a raw 2352-byte sector at the given absolute LBA.
    public CdSector readSector(int lba) {
        int cacheIndex = lba & (SECTOR_CACHE_SIZE - 1);
        if (cachedSectorLbas[cacheIndex] == lba) {
            return cachedSectors[cacheIndex];
        }
        CdSector sector = prefetchedSectors.remove(lba);
        if (sector == null) {
            sector = readSectorUncached(lba);
        }
        if (sector == null) {
            return null;
        }
        cachedSectorLbas[cacheIndex] = lba;
        cachedSectors[cacheIndex] = sector;
        return sector;
    }

    private CdSector readSectorUncached(int lba) {
        ResolvedSector resolved = layout.resolve(lba);
        if (resolved == null) return null;

        byte[] raw = resolved.raw;

        int minute, second, frame;
        if (hasSyncPattern(raw)) {
            minute = raw[12] & 0xFF;
            second = raw[13] & 0xFF;
            frame  = raw[14] & 0xFF;
        } else {
            int absolute = resolved.absoluteLba + LBA_MSF_OFFSET;
            minute = toBcd((absolute / (75 * 60)) % 100);
            second = toBcd((absolute / 75) % 60);
            frame  = toBcd(absolute % 75);
        }

        byte[] payload = extractUserData(raw);
        return new CdSector(minute, second, frame, raw, payload);
    }

    private void runPrefetchWorker() {
        while (true) {
            int lba;
            synchronized (prefetchMonitor) {
                while (!closed && prefetchNextLba < 0) {
                    try {
                        prefetchMonitor.wait(1_000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!closed && prefetchNextLba < 0) {
                        prefetchWorker = null;
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                lba = prefetchNextLba++;
                if (prefetchNextLba >= prefetchEndLba) {
                    prefetchNextLba = -1;
                    prefetchEndLba = -1;
                }
            }
            if (!prefetchedSectors.containsKey(lba)) {
                CdSector sector = readSectorUncached(lba);
                if (sector != null && !closed) {
                    prefetchedSectors.put(lba, sector);
                }
            }
        }
    }

    private static boolean hasSyncPattern(byte[] raw) {
        if (raw.length < 12) return false;
        if ((raw[0] & 0xFF) != 0x00 || (raw[11] & 0xFF) != 0x00) return false;
        for (int i = 1; i <= 10; i++) {
            if ((raw[i] & 0xFF) != 0xFF) return false;
        }
        return true;
    }

    private static byte[] extractUserData(byte[] raw) {
        if (raw.length < SECTOR_SIZE) return new byte[0];
        int mode = raw[15] & 0xFF;
        if (mode == 1) {
            return Arrays.copyOfRange(raw, 16, 16 + 2048);
        }
        if (mode == 2) {
            // Subheader at bytes 16-23; Form bit is submode bit 5 (raw[18]).
            int subMode  = raw[18] & 0xFF;
            boolean form2 = (subMode & 0x20) != 0;
            int size = form2 ? 2324 : 2048;
            return Arrays.copyOfRange(raw, 24, 24 + size);
        }
        // Unknown mode — return raw payload area as best-effort.
        return Arrays.copyOfRange(raw, 24, 24 + 2048);
    }

    private static LicenseInfo detectLicence(DiscLayout layout) {
        TrackInfo firstData = layout.firstDataTrack();
        if (firstData == null) return new LicenseInfo("SCEA", false);

        ResolvedSector licenceSector =
            layout.resolve(firstData.trackStartLba + LICENCE_SECTOR_OFFSET);
        if (licenceSector == null || licenceSector.raw.length < SECTOR_SIZE) {
            return new LicenseInfo("SCEA", false);
        }

        byte[] data = extractUserData(licenceSector.raw);
        String text = new String(data, StandardCharsets.US_ASCII).toUpperCase();
        String compact = text.replaceAll("\\s+", "");
        boolean marker = compact.contains("LICENSEDBY")
            && compact.contains("SONYCOMPUTERENTERTAINMENT");
        if (!marker) return new LicenseInfo("SCEA", false);

        if (compact.contains("SONYCOMPUTERENTERTAINMENTEUROPE")) {
            return new LicenseInfo("SCEE", true);
        }
        if (compact.contains("SONYCOMPUTERENTERTAINMENTAMERICA")) {
            return new LicenseInfo("SCEA", true);
        }
        if (compact.contains("SONYCOMPUTERENTERTAINMENTINC.")) {
            return new LicenseInfo("SCEI", true);
        }
        return new LicenseInfo("SCEA", false);
    }

    private static int toBcd(int value) {
        return ((value / 10) << 4) | (value % 10);
    }

    private static boolean isPackedBcd(int value) {
        value &= 0xFF;
        return (value & 0x0F) <= 9 && ((value >>> 4) & 0x0F) <= 9;
    }

    private static int fromPackedBcd(int value) {
        return ((value >>> 4) & 0x0F) * 10 + (value & 0x0F);
    }

    private static void writeBcdMsf(byte[] target, int offset, int sectors) {
        target[offset] = (byte) toBcd((sectors / (75 * 60)) % 100);
        target[offset + 1] = (byte) toBcd((sectors / 75) % 60);
        target[offset + 2] = (byte) toBcd(sectors % 75);
    }

    private static int subchannelQCrc(byte[] q) {
        int crc = 0;
        for (int i = 0; i < 10; i++) {
            crc ^= (q[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                    ? ((crc << 1) ^ 0x1021) & 0xFFFF
                    : (crc << 1) & 0xFFFF;
            }
        }
        return (~crc) & 0xFFFF;
    }

    private static Map<Integer, SbiPatch> loadSbiPatches(Path imagePath) throws IOException {
        String fileName = imagePath.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String stem = extension >= 0 ? fileName.substring(0, extension) : fileName;
        Path sbiPath = imagePath.resolveSibling(stem + ".sbi");
        if (!Files.exists(sbiPath)) {
            return Map.of();
        }

        byte[] bytes = Files.readAllBytes(sbiPath);
        if (bytes.length < 4
            || bytes[0] != 'S' || bytes[1] != 'B' || bytes[2] != 'I' || bytes[3] != 0) {
            throw new IOException("Invalid SBI header: " + sbiPath);
        }

        Map<Integer, SbiPatch> patches = new LinkedHashMap<>();
        int offset = 4;
        while (offset < bytes.length) {
            if (offset + 4 > bytes.length) {
                throw new IOException("Truncated SBI entry header: " + sbiPath);
            }
            int minute = fromBcd(bytes[offset++], sbiPath);
            int second = fromBcd(bytes[offset++], sbiPath);
            int frame = fromBcd(bytes[offset++], sbiPath);
            int format = bytes[offset++] & 0xFF;
            int payloadLength = switch (format) {
                case 1 -> 10;
                case 2, 3 -> 3;
                default -> throw new IOException(
                    "Unsupported SBI entry format " + format + ": " + sbiPath
                );
            };
            if (offset + payloadLength > bytes.length) {
                throw new IOException("Truncated SBI entry payload: " + sbiPath);
            }
            byte[] replacement = Arrays.copyOfRange(bytes, offset, offset + payloadLength);
            offset += payloadLength;
            int absoluteSector = minute * 60 * 75 + second * 75 + frame;
            int lba = absoluteSector - LBA_MSF_OFFSET;
            patches.put(lba, new SbiPatch(format, replacement));
        }
        return patches;
    }

    private static int fromBcd(byte value, Path source) throws IOException {
        int raw = value & 0xFF;
        int high = raw >>> 4;
        int low = raw & 0xF;
        if (high > 9 || low > 9) {
            throw new IOException("Invalid BCD address in SBI file: " + source);
        }
        return high * 10 + low;
    }

    private static int parseMsf(String msf) {
        String[] parts = msf.split(":");
        if (parts.length != 3) return 0;
        return Integer.parseInt(parts[0]) * 60 * 75
            + Integer.parseInt(parts[1]) * 75
            + Integer.parseInt(parts[2]);
    }

    private static int detectSingleTrackSectorSize(Path path, long byteSize) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".iso") && byteSize % USER_DATA_SECTOR_SIZE == 0) {
            return USER_DATA_SECTOR_SIZE;
        }
        if (byteSize % SECTOR_SIZE == 0) {
            return SECTOR_SIZE;
        }
        if (byteSize % USER_DATA_SECTOR_SIZE == 0) {
            return USER_DATA_SECTOR_SIZE;
        }
        return SECTOR_SIZE;
    }

    private static int cueTrackSectorSize(String type) {
        String upper = type == null ? "" : type.toUpperCase();
        if (upper.contains("/2048")) return USER_DATA_SECTOR_SIZE;
        if (upper.contains("/2324")) return MODE2_FORM2_DATA_SECTOR_SIZE;
        if (upper.contains("/2336")) return RAW_SECTOR_WITHOUT_SYNC_HEADER_SIZE;
        return SECTOR_SIZE;
    }

    public record TrackPosition(int trackNumber, int indexNumber, int relativeLba, int absoluteLba) {}

    public record SubchannelQ(byte[] data, boolean checksumValid) {
        public SubchannelQ {
            if (data == null || data.length != 12) {
                throw new IllegalArgumentException("Subchannel Q frame must contain exactly 12 bytes");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private static final class DiscLayout {
        private final List<TrackInfo> tracks;
        private final int             totalSectors;

        private DiscLayout(List<TrackInfo> tracks, int totalSectors) {
            this.tracks       = tracks;
            this.totalSectors = totalSectors;
        }

        private TrackInfo findTrack(int trackNumber) {
            for (TrackInfo t : tracks) {
                if (t.number == trackNumber) return t;
            }
            return null;
        }

        private TrackInfo firstDataTrack() {
            for (TrackInfo t : tracks) {
                if (t.isDataTrack()) return t;
            }
            return null;
        }

        // Locate the track that owns the given LBA.
        private TrackInfo locateTrack(int lba) {
            if (lba < 0 || lba >= totalSectors) return null;
            for (TrackInfo t : tracks) {
                if (t.containsLba(lba)) return t;
            }
            return null;
        }

        private ResolvedSector resolve(int lba) {
            TrackInfo t = locateTrack(lba);
            return t == null ? null : t.resolve(lba);
        }

        private void close() {
            Set<DiscFile> files = new LinkedHashSet<>();
            for (TrackInfo track : tracks) {
                files.add(track.file);
            }
            for (DiscFile file : files) {
                file.close();
            }
        }
    }

    private static final class TrackInfo {
        private final int    number;
        private final String type;
        private final int    pregapStartLba;
        private final int    index00StartLba;
        private final int    trackStartLba;       // INDEX 01
        private final int    trackEndLbaExclusive;
        private final DiscFile file;
        private final int    fileSectorSize;
        private final int    dataStartSectorInFile;
        private final int    dataSectors;

        private TrackInfo(int number, String type,
                          int pregapStartLba, int index00StartLba,
                          int trackStartLba, int trackEndLbaExclusive,
                          DiscFile file, int fileSectorSize,
                          int dataStartSectorInFile, int dataSectors) {
            this.number                = number;
            this.type                  = type;
            this.pregapStartLba        = pregapStartLba;
            this.index00StartLba       = index00StartLba;
            this.trackStartLba         = trackStartLba;
            this.trackEndLbaExclusive  = trackEndLbaExclusive;
            this.file                  = file;
            this.fileSectorSize        = fileSectorSize;
            this.dataStartSectorInFile = dataStartSectorInFile;
            this.dataSectors           = dataSectors;
        }

        private boolean containsLba(int lba) {
            return lba >= pregapStartLba && lba < trackEndLbaExclusive;
        }

        private boolean isDataTrack() {
            return type.toUpperCase().startsWith("MODE");
        }

        private ResolvedSector resolve(int lba) {
            if (!containsLba(lba)) return null;

            if (lba < index00StartLba) {
                return new ResolvedSector(lba, new byte[SECTOR_SIZE]);
            }

            // Between INDEX 00 and INDEX 01 — read from file, same as data.
            if (lba < trackStartLba) {
                int delta     = trackStartLba - index00StartLba;
                int fileSec   = (dataStartSectorInFile - delta) + (lba - index00StartLba);
                return new ResolvedSector(lba,
                    readRawSector(file, fileSec, fileSectorSize, type, lba));
            }

            int sectorOffset = lba - trackStartLba;
            if (sectorOffset >= dataSectors) {
                return new ResolvedSector(lba, new byte[SECTOR_SIZE]);
            }
            return new ResolvedSector(lba, readRawSector(
                file,
                dataStartSectorInFile + sectorOffset,
                fileSectorSize,
                type,
                lba
            ));
        }
    }

    private static final class CueTrackBuilder {
        private final int    number;
        private final String type;
        private final Path   file;
        private int directivePregapSectors;
        private int index00Sector = -1;
        private int index01Sector = -1;
        private int fileSectorSize = SECTOR_SIZE;
        private int fileSectors;

        private CueTrackBuilder(int number, String type, Path file) {
            this.number = number;
            this.type   = type;
            this.file   = file;
        }
    }

    private record ResolvedSector(int absoluteLba, byte[] raw) {}
    private record LicenseInfo(String regionCode, boolean licensed) {}
    private record SbiPatch(int format, byte[] replacement) {
        private SbiPatch {
            replacement = replacement.clone();
        }
    }

    /** One shared, lazily-opened positional reader per file referenced by a CUE. */
    private static final class DiscFile implements AutoCloseable {
        private final Path path;
        private final long size;
        private volatile FileChannel channel;
        private volatile boolean readFailureLogged;

        private DiscFile(Path path) throws IOException {
            this.path = path.toAbsolutePath().normalize();
            this.size = Files.size(this.path);
        }

        private long size() {
            return size;
        }

        private boolean readFully(long position, byte[] target, int offset, int length) {
            if (position < 0 || length < 0 || position > size - length) {
                return false;
            }
            try {
                FileChannel activeChannel = channel();
                ByteBuffer buffer = ByteBuffer.wrap(target, offset, length);
                long cursor = position;
                int zeroReads = 0;
                while (buffer.hasRemaining()) {
                    int read = activeChannel.read(buffer, cursor);
                    if (read < 0) {
                        return false;
                    }
                    if (read == 0) {
                        if (++zeroReads >= 8) {
                            return false;
                        }
                        Thread.onSpinWait();
                        continue;
                    }
                    zeroReads = 0;
                    cursor += read;
                }
                return true;
            } catch (IOException ex) {
                if (!readFailureLogged) {
                    readFailureLogged = true;
                    Log.error("Failed to read CD image " + path, ex);
                }
                return false;
            }
        }

        private FileChannel channel() throws IOException {
            FileChannel active = channel;
            if (active != null) {
                return active;
            }
            synchronized (this) {
                if (channel == null) {
                    channel = FileChannel.open(path, StandardOpenOption.READ);
                }
                return channel;
            }
        }

        @Override
        public void close() {
            if (channel == null) {
                return;
            }
            try {
                channel.close();
            } catch (IOException ex) {
                Log.error("Failed to close CD image " + path, ex);
            } finally {
                channel = null;
            }
        }
    }

    private static byte[] readRawSector(
        DiscFile file,
        int fileSector,
        int fileSectorSize,
        String trackType,
        int logicalLba
    ) {
        if (file == null || fileSector < 0) return new byte[SECTOR_SIZE];
        long offset = (long) fileSector * fileSectorSize;
        if (offset < 0 || offset > file.size() - fileSectorSize) {
            return new byte[SECTOR_SIZE];
        }
        if (fileSectorSize == SECTOR_SIZE) {
            byte[] raw = new byte[SECTOR_SIZE];
            return file.readFully(offset, raw, 0, raw.length) ? raw : new byte[SECTOR_SIZE];
        }
        boolean mode2 = trackType != null && trackType.toUpperCase().startsWith("MODE2");
        if (fileSectorSize == RAW_SECTOR_WITHOUT_SYNC_HEADER_SIZE && mode2) {
            byte[] raw = createRawSector(logicalLba, 2);
            return file.readFully(offset, raw, 16, RAW_SECTOR_WITHOUT_SYNC_HEADER_SIZE)
                ? raw : new byte[SECTOR_SIZE];
        }
        if (fileSectorSize == MODE2_FORM2_DATA_SECTOR_SIZE && mode2) {
            return synthesizeMode2RawSector(file, offset, logicalLba, true);
        }
        if (fileSectorSize == USER_DATA_SECTOR_SIZE && mode2) {
            return synthesizeMode2RawSector(file, offset, logicalLba, false);
        }
        return synthesizeMode1RawSector(file, offset, logicalLba);
    }

    private static byte[] synthesizeMode1RawSector(DiscFile file, long dataOffset, int logicalLba) {
        byte[] raw = createRawSector(logicalLba, 1);
        if (!file.readFully(dataOffset, raw, 16, USER_DATA_SECTOR_SIZE)) {
            return new byte[SECTOR_SIZE];
        }
        generateEdcAndEcc(raw);
        return raw;
    }

    private static byte[] synthesizeMode2RawSector(
        DiscFile file,
        long dataOffset,
        int logicalLba,
        boolean form2
    ) {
        byte[] raw = createRawSector(logicalLba, 2);
        int submode = form2 ? 0x28 : 0x08;
        raw[18] = (byte) submode;
        raw[22] = (byte) submode;
        int dataSize = form2 ? MODE2_FORM2_DATA_SECTOR_SIZE : USER_DATA_SECTOR_SIZE;
        if (!file.readFully(dataOffset, raw, 24, dataSize)) {
            return new byte[SECTOR_SIZE];
        }
        generateEdcAndEcc(raw);
        return raw;
    }

    private static byte[] createRawSector(int logicalLba, int mode) {
        byte[] raw = new byte[SECTOR_SIZE];
        raw[0] = 0x00;
        Arrays.fill(raw, 1, 11, (byte) 0xFF);
        raw[11] = 0x00;
        int absolute = logicalLba + LBA_MSF_OFFSET;
        raw[12] = (byte) toBcd((absolute / (75 * 60)) % 100);
        raw[13] = (byte) toBcd((absolute / 75) % 60);
        raw[14] = (byte) toBcd(absolute % 75);
        raw[15] = (byte) mode;
        return raw;
    }

    static void generateEdcAndEcc(byte[] sector) {
        if (sector == null || sector.length != SECTOR_SIZE) {
            throw new IllegalArgumentException("A raw CD sector must contain exactly 2352 bytes");
        }
        int mode = sector[15] & 0xFF;
        if (mode == 1) {
            writeEdc(sector, 0x000, 0x810, 0x810);
            Arrays.fill(sector, 0x814, 0x81C, (byte) 0);
            writeEcc(sector, false);
        } else if (mode == 2 && (sector[18] & 0x20) == 0) {
            writeEdc(sector, 0x010, 0x808, 0x818);
            writeEcc(sector, true);
        } else if (mode == 2) {
            writeEdc(sector, 0x010, 0x91C, 0x92C);
        }
    }

    private static void initializeErrorCodeTables() {
        for (int i = 0; i < 256; i++) {
            int product = (i << 1) ^ ((i & 0x80) != 0 ? 0x11D : 0);
            ECC_FORWARD_LOOKUP[i] = (byte) product;
            ECC_BACKWARD_LOOKUP[(i ^ product) & 0xFF] = (byte) i;

            int edc = i;
            for (int bit = 0; bit < 8; bit++) {
                edc = (edc >>> 1) ^ ((edc & 1) != 0 ? 0xD801_8001 : 0);
            }
            EDC_LOOKUP[i] = edc;
        }
    }

    private static void writeEdc(byte[] sector, int offset, int length, int destination) {
        int edc = 0;
        for (int i = 0; i < length; i++) {
            edc = (edc >>> 8) ^ EDC_LOOKUP[(edc ^ sector[offset + i]) & 0xFF];
        }
        sector[destination] = (byte) edc;
        sector[destination + 1] = (byte) (edc >>> 8);
        sector[destination + 2] = (byte) (edc >>> 16);
        sector[destination + 3] = (byte) (edc >>> 24);
    }

    private static void writeEcc(byte[] sector, boolean zeroAddress) {
        int address0 = sector[12] & 0xFF;
        int address1 = sector[13] & 0xFF;
        int address2 = sector[14] & 0xFF;
        int mode = sector[15] & 0xFF;
        if (zeroAddress) {
            Arrays.fill(sector, 12, 16, (byte) 0);
        }

        writeEccBlock(sector, 86, 24, 2, 86, 0x81C);
        writeEccBlock(sector, 52, 43, 86, 88, 0x8C8);

        if (zeroAddress) {
            sector[12] = (byte) address0;
            sector[13] = (byte) address1;
            sector[14] = (byte) address2;
            sector[15] = (byte) mode;
        }
    }

    private static void writeEccBlock(
        byte[] sector,
        int majorCount,
        int minorCount,
        int majorMultiplier,
        int minorIncrement,
        int destination
    ) {
        int size = majorCount * minorCount;
        for (int major = 0; major < majorCount; major++) {
            int index = (major >>> 1) * majorMultiplier + (major & 1);
            int parityA = 0;
            int parityB = 0;
            for (int minor = 0; minor < minorCount; minor++) {
                int value = sector[12 + index] & 0xFF;
                index += minorIncrement;
                if (index >= size) index -= size;
                parityA ^= value;
                parityB ^= value;
                parityA = ECC_FORWARD_LOOKUP[parityA] & 0xFF;
            }
            parityA = ECC_BACKWARD_LOOKUP[
                (ECC_FORWARD_LOOKUP[parityA] ^ parityB) & 0xFF
            ] & 0xFF;
            sector[destination + major] = (byte) parityA;
            sector[destination + major + majorCount] = (byte) (parityA ^ parityB);
        }
    }
}
