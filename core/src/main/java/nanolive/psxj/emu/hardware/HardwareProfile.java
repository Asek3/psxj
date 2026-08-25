package nanolive.psxj.emu.hardware;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Revision-specific clocks and externally observable identities for retail
 * PlayStation and PSone consoles.
 *
 * <p>Sony sometimes shipped one marketing model with more than one board. In
 * those cases each electrically distinct board is a separate enum value. Box
 * bundle suffixes which do not change the console (SCPH-102A/B/C) are accepted
 * as model aliases instead of pretending that they are new hardware.</p>
 */
public enum HardwareProfile {

    SCPH_1000_PU_7_NTSC_J("SCPH-1000", "PU-7", Region.JAPAN,
        CdDriveProfile.PU_7_C0_1994_09_19),
    SCPH_1001_EARLY_PU_8_NTSC_U("SCPH-1001", "EARLY-PU-8", Region.NORTH_AMERICA,
        CdDriveProfile.PU_7_C0_1994_11_18),
    SCPH_1001_LATE_PU_8_NTSC_U("SCPH-1001", "LATE-PU-8", Region.NORTH_AMERICA,
        CdDriveProfile.LATE_PU_8_C1_1995_07_24),
    SCPH_1002_EARLY_PU_8_PAL("SCPH-1002", "EARLY-PU-8", Region.EUROPE,
        CdDriveProfile.PU_7_C0_1994_11_18),
    SCPH_1002_LATE_PU_8_PAL("SCPH-1002", "LATE-PU-8", Region.EUROPE,
        CdDriveProfile.LATE_PU_8_C1_1995_07_24),
    SCPH_3000_LATE_PU_8_NTSC_J("SCPH-3000", "LATE-PU-8", Region.JAPAN,
        CdDriveProfile.LATE_PU_8_C1_1995_05_16),
    SCPH_3500_LATE_PU_8_NTSC_J("SCPH-3500", "LATE-PU-8", Region.JAPAN,
        CdDriveProfile.LATE_PU_8_C1_1995_07_24),
    SCPH_5000_LATE_PU_8_NTSC_J("SCPH-5000", "LATE-PU-8", Region.JAPAN,
        CdDriveProfile.LATE_PU_8_C1_1995_07_24),
    SCPH_5001_LATE_PU_8_NTSC_U("SCPH-5001", "LATE-PU-8", Region.NORTH_AMERICA,
        CdDriveProfile.LATE_PU_8_C1_1995_07_24),
    SCPH_5003_LATE_PU_8_NTSC_ASIA("SCPH-5003", "LATE-PU-8", Region.ASIA,
        CdDriveProfile.LATE_PU_8_C1_1995_07_24),

    SCPH_5500_PU_18_NTSC_J("SCPH-5500", "PU-18", Region.JAPAN,
        CdDriveProfile.PU_18_JAPAN_C2),
    SCPH_5501_PU_18_NTSC_U("SCPH-5501", "PU-18", Region.NORTH_AMERICA,
        CdDriveProfile.SCPH_5501_PU_18),
    SCPH_5502_PU_18_PAL("SCPH-5502", "PU-18", Region.EUROPE,
        CdDriveProfile.SCPH_5501_PU_18),
    SCPH_5552_PU_18_PAL("SCPH-5552", "PU-18", Region.EUROPE,
        CdDriveProfile.SCPH_5501_PU_18),
    SCPH_5903_PU_16_NTSC_ASIA("SCPH-5903", "PU-16", Region.ASIA,
        CdDriveProfile.PU_16_VCD_C2),

    SCPH_7000_PU_20_NTSC_J("SCPH-7000", "PU-20", Region.JAPAN,
        CdDriveProfile.PU_20_C2),
    SCPH_7001_PU_20_NTSC_U("SCPH-7001", "PU-20", Region.NORTH_AMERICA,
        CdDriveProfile.PU_20_C2),
    SCPH_7002_PU_20_PAL("SCPH-7002", "PU-20", Region.EUROPE,
        CdDriveProfile.PU_20_C2),
    SCPH_7003_PU_20_NTSC_ASIA("SCPH-7003", "PU-20", Region.ASIA,
        CdDriveProfile.PU_20_C2),
    SCPH_7000W_PU_20_NTSC_WORLD("SCPH-7000W", "PU-20", Region.REGION_FREE,
        CdDriveProfile.PU_20_C2),

    SCPH_7500_PU_22_NTSC_J("SCPH-7500", "PU-22", Region.JAPAN,
        CdDriveProfile.PU_22_C3),
    SCPH_7501_PU_22_NTSC_U("SCPH-7501", "PU-22", Region.NORTH_AMERICA,
        CdDriveProfile.PU_22_C3),
    SCPH_7502_PU_22_PAL("SCPH-7502", "PU-22", Region.EUROPE,
        CdDriveProfile.PU_22_C3),
    SCPH_7503_PU_22_NTSC_ASIA("SCPH-7503", "PU-22", Region.ASIA,
        CdDriveProfile.PU_22_C3),

    SCPH_9000_PU_23_NTSC_J("SCPH-9000", "PU-23", Region.JAPAN,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_9001_PU_23_NTSC_U("SCPH-9001", "PU-23", Region.NORTH_AMERICA,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_9002_PU_23_PAL("SCPH-9002", "PU-23", Region.EUROPE,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_9003_PU_23_NTSC_ASIA("SCPH-9003", "PU-23", Region.ASIA,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_9903_PU_23_NTSC_WORLD("SCPH-9903", "PU-23", Region.REGION_FREE,
        CdDriveProfile.PU_23_PM_41_C3),

    SCPH_100_PM_41_NTSC_J("SCPH-100", "PM-41/-11..-51", Region.JAPAN,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_100_PM_41_61_NTSC_J("SCPH-100", "PM-41/-61", Region.JAPAN,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_100_PM_41_2_NTSC_J("SCPH-100", "PM-41(2)/-71", Region.JAPAN,
        CdDriveProfile.PM_41_2_C3),
    SCPH_101_PM_41_NTSC_U("SCPH-101", "PM-41/-11..-51", Region.NORTH_AMERICA,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_101_PM_41_61_NTSC_U("SCPH-101", "PM-41/-61", Region.NORTH_AMERICA,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_101_PM_41_2_NTSC_U("SCPH-101", "PM-41(2)/-71", Region.NORTH_AMERICA,
        CdDriveProfile.PM_41_2_C3),
    SCPH_102_PM_41_PAL("SCPH-102", "PM-41/-11..-51", Region.EUROPE,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_102_PM_41_61_PAL("SCPH-102", "PM-41/-61", Region.EUROPE,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_102_PM_41_2_PAL("SCPH-102", "PM-41(2)/-71", Region.EUROPE,
        CdDriveProfile.PM_41_2_C3),
    SCPH_103_PM_41_NTSC_ASIA("SCPH-103", "PM-41/-11..-51", Region.ASIA,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_103_PM_41_61_NTSC_ASIA("SCPH-103", "PM-41/-61", Region.ASIA,
        CdDriveProfile.PU_23_PM_41_C3),
    SCPH_103_PM_41_2_NTSC_ASIA("SCPH-103", "PM-41(2)/-71", Region.ASIA,
        CdDriveProfile.PM_41_2_C3),

    // PSXONPSP is not tied to a retail motherboard.
    PSXONPSP_660("PSXONPSP660", "PSP virtual PS1", Region.REGION_FREE,
        CdDriveProfile.PU_20_C2);

    private static final int CPU_CLOCK_HZ = 33_868_800;
    private static final int NTSC_GPU_CLOCK_HZ = 53_693_175;
    private static final int PAL_GPU_CLOCK_HZ = 53_203_425;
    private static final int NTSC_GPU_RATIO_NUMERATOR = 715_909;
    private static final int PAL_GPU_RATIO_NUMERATOR = 709_379;
    private static final int GPU_RATIO_DENOMINATOR = 451_584;
    private static final int SPU_CLOCKS_PER_SAMPLE = 768;
    private static final Pattern MODEL_IN_FILENAME = Pattern.compile(
        "(?i)(?:SCPH)[-_ ]?(\\d{3,4}[A-Z]?)");

    private final String model;
    private final String mainboardRevision;
    private final Region region;
    private final VideoOscillator videoOscillator;
    private final int gpuClockHz;
    private final int gpuClockRatioNumerator;
    private final int cdControllerOscillatorHz;
    private final int cdControllerSystemClockHz;
    private final CdDriveProfile cdDriveProfile;

    HardwareProfile(String model, String mainboardRevision, Region region,
                    CdDriveProfile cdDriveProfile) {
        this.model = model;
        this.mainboardRevision = mainboardRevision;
        this.region = region;
        this.videoOscillator = region == Region.EUROPE
            ? VideoOscillator.PAL : VideoOscillator.NTSC;
        this.gpuClockHz = videoOscillator == VideoOscillator.PAL
            ? PAL_GPU_CLOCK_HZ : NTSC_GPU_CLOCK_HZ;
        this.gpuClockRatioNumerator = videoOscillator == VideoOscillator.PAL
            ? PAL_GPU_RATIO_NUMERATOR : NTSC_GPU_RATIO_NUMERATOR;
        boolean fourMegahertzCdClock = mainboardRevision.contains("PU-7")
            || mainboardRevision.contains("PU-8")
            || mainboardRevision.contains("PU-16");
        this.cdControllerOscillatorHz = fourMegahertzCdClock ? 4_000_000 : 4_233_600;
        this.cdControllerSystemClockHz = cdControllerOscillatorHz / 2;
        this.cdDriveProfile = Objects.requireNonNull(cdDriveProfile).withRegion(
            region.testRegionIdentity, region.requiredLicenseRegion);
    }

    public static HardwareProfile detect(Path biosPath) {
        HardwareProfile detected = detectKnown(biosPath);
        return detected != null ? detected : SCPH_5501_PU_18_NTSC_U;
    }

    public static HardwareProfile detectKnown(Path biosPath) {
        String override = System.getProperty("psxj.hardwareProfile", "").trim();
        if (!override.isEmpty()) {
            try {
                return valueOf(override.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                HardwareProfile byModel = forModel(override);
                if (byModel != null) {
                    return byModel;
                }
            }
        }

        if (biosPath != null && biosPath.getFileName() != null) {
            String fileName = biosPath.getFileName().toString();
            Matcher matcher = MODEL_IN_FILENAME.matcher(fileName);
            if (matcher.find()) {
                HardwareProfile byModel = forModel("SCPH-" + matcher.group(1));
                if (byModel != null) {
                    return byModel;
                }
            }
        }

        if (biosPath != null && Files.isRegularFile(biosPath)) {
            try {
                HardwareProfile byCrc = forBiosCrc32(crc32(biosPath));
                if (byCrc != null) {
                    return byCrc;
                }
            } catch (IOException ignored) {
                // BIOS loading will report the actual I/O error later.
            }
        }
        return null;
    }

    public static HardwareProfile forModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return null;
        }
        String normalized = requestedModel.toUpperCase(Locale.ROOT)
            .replace('_', '-')
            .replace(" ", "");
        if (!normalized.startsWith("SCPH-")) {
            normalized = "SCPH-" + normalized.replace("SCPH", "");
        }
        // A/B/C identify the PSone package/accessories, not new circuitry.
        if (normalized.matches("SCPH-102[ABC]")) {
            normalized = "SCPH-102";
        }
        for (HardwareProfile profile : values()) {
            if (profile.model.equals(normalized)) {
                return profile;
            }
        }
        return null;
    }

    public static HardwareProfile forBiosCrc32(long crc32) {
        return switch ((int) crc32) {
            case 0x3B601FC8 -> SCPH_1000_PU_7_NTSC_J;
            case 0x3539DEF6 -> SCPH_3000_LATE_PU_8_NTSC_J;
            case 0x9BB87C4B, 0x86C30531 -> SCPH_1002_EARLY_PU_8_PAL;
            case 0xBC190209 -> SCPH_3500_LATE_PU_8_NTSC_J;
            case 0xAFF00F2F, 0x37157331 -> SCPH_1001_EARLY_PU_8_NTSC_U;
            case 0x1E26792F -> SCPH_1002_LATE_PU_8_PAL;
            case 0x24FC7E17 -> SCPH_5000_LATE_PU_8_NTSC_J;
            case 0x446EC5B2 -> SCPH_5903_PU_16_NTSC_ASIA;
            case 0xFF3EEB8C -> SCPH_5500_PU_18_NTSC_J;
            case 0x8D8CB7E4 -> SCPH_5501_PU_18_NTSC_U;
            case 0xD786F0B9 -> SCPH_5502_PU_18_PAL;
            case 0xEC541CD0 -> SCPH_7000_PU_20_NTSC_J;
            case 0xB7C43DAD -> SCPH_7000W_PU_20_NTSC_WORLD;
            case 0x5660F34F -> PSXONPSP_660;
            case 0x502224B6 -> SCPH_7001_PU_20_NTSC_U;
            case 0x318178BF -> SCPH_7002_PU_20_PAL;
            case 0xF2AF798B -> SCPH_100_PM_41_NTSC_J;
            case 0x6A0E22A0, 0x171BDCEC -> SCPH_101_PM_41_NTSC_U;
            case 0x0BAD7EA9, 0x76B880E5 -> SCPH_102_PM_41_PAL;
            default -> null;
        };
    }

    private static long crc32(Path path) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    public String model() { return model; }
    public String mainboardRevision() { return mainboardRevision; }
    public Region region() { return region; }
    public VideoOscillator videoOscillator() { return videoOscillator; }
    public int cpuClockHz() { return CPU_CLOCK_HZ; }
    public int gpuClockHz() { return gpuClockHz; }

    // Numerator of the exact GPU-clock/CPU-clock conversion ratio.
    public int gpuClockRatioNumerator() { return gpuClockRatioNumerator; }

    // Denominator of the exact GPU-clock/CPU-clock conversion ratio.
    public int gpuClockRatioDenominator() { return GPU_RATIO_DENOMINATOR; }
    public int spuClocksPerSample() { return SPU_CLOCKS_PER_SAMPLE; }
    public int cdControllerOscillatorHz() { return cdControllerOscillatorHz; }
    public int cdControllerSystemClockHz() { return cdControllerSystemClockHz; }
    public CdDriveProfile cdDriveProfile() { return cdDriveProfile; }

    public enum VideoOscillator { NTSC, PAL }

    public enum Region {
        JAPAN("for Japan", "SCEI"),
        NORTH_AMERICA("for U/C", "SCEA"),
        EUROPE("for Europe", "SCEE"),
        ASIA("for Japan", "SCEI"),
        REGION_FREE("for World", "*");

        private final String testRegionIdentity;
        private final String requiredLicenseRegion;

        Region(String testRegionIdentity, String requiredLicenseRegion) {
            this.testRegionIdentity = testRegionIdentity;
            this.requiredLicenseRegion = requiredLicenseRegion;
        }
    }
}
