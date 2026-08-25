package nanolive.psxj.emu.sio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class MemoryCard {

    public static final int SIZE = 128 * 1024;
    private static final int FRAME_SIZE = 128;
    private static final int DIRECTORY_ENTRIES = 15;
    private static final int FREE_BLOCK_FLAG = 0xA0;
    private static final int NO_NEXT_BLOCK = 0xFFFF;
    private static final int DIRECTORY_UNREAD_FLAG = 0x08;
    private static final int VGS_HEADER_SIZE = 0x40;
    private static final int PSX_HEADER_SIZE = 0x100;
    private static final int GME_HEADER_SIZE = 0xF40;

    private final byte[] data;
    private final Path path;
    private final byte[] containerHeader;
    private boolean dirty;
    private int flag = DIRECTORY_UNREAD_FLAG;

    private MemoryCard(Path path, byte[] data, byte[] containerHeader) {
        this.path = path;
        this.data = data;
        this.containerHeader = containerHeader;
    }

    public static MemoryCard openOrCreate(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(path)) {
            byte[] blank = createBlankCard();
            Files.write(path, blank);
            return new MemoryCard(path, blank, new byte[0]);
        }
        CardImage image = decodeCardImage(Files.readAllBytes(path));
        byte[] bytes = image.data();
        if (bytes == null) {
            byte[] normalized = createBlankCard();
            byte[] fileBytes = Files.readAllBytes(path);
            System.arraycopy(fileBytes, 0, normalized, 0, Math.min(fileBytes.length, normalized.length));
            bytes = normalized;
        }
        if (!hasMagic(bytes)) {
            bytes = createBlankCard();
        }
        return new MemoryCard(path, bytes, image.header());
    }

    public int readByte(int offset) {
        return Byte.toUnsignedInt(data[offset & (SIZE - 1)]);
    }

    public void writeByte(int offset, int value) {
        data[offset & (SIZE - 1)] = (byte) value;
        dirty = true;
    }

    public byte[] copyData() {
        return data.clone();
    }

    public void load(byte[] snapshot) {
        Arrays.fill(data, (byte) 0);
        System.arraycopy(snapshot, 0, data, 0, Math.min(snapshot.length, data.length));
        dirty = true;
        flag = DIRECTORY_UNREAD_FLAG;
    }

    public void flush() throws IOException {
        if (!dirty) {
            return;
        }
        byte[] fileBytes = new byte[containerHeader.length + data.length];
        System.arraycopy(containerHeader, 0, fileBytes, 0, containerHeader.length);
        System.arraycopy(data, 0, fileBytes, containerHeader.length, data.length);
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, fileBytes);
            try {
                Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path path() {
        return path;
    }

    // Status byte returned alongside the first memory-card command byte.
    public int flag() {
        return flag & 0xFF;
    }

    public void clearDirectoryUnreadFlag() {
        flag &= ~DIRECTORY_UNREAD_FLAG;
    }

    public void restoreFlag(int value) {
        flag = value & 0xFF;
    }

    private static byte[] createBlankCard() {
        byte[] blank = new byte[SIZE];
        Arrays.fill(blank, (byte) 0x00);

        blank[0] = 'M';
        blank[1] = 'C';
        writeFrameChecksum(blank, 0);

        for (int block = 1; block <= DIRECTORY_ENTRIES; block++) {
            int frame = block * FRAME_SIZE;
            blank[frame] = (byte) FREE_BLOCK_FLAG;
            write16(blank, frame + 0x08, NO_NEXT_BLOCK);
            writeFrameChecksum(blank, frame);
        }

        // Frames 16..35 contain the broken-sector list.
        for (int frameNumber = 16; frameNumber <= 35; frameNumber++) {
            int frame = frameNumber * FRAME_SIZE;
            Arrays.fill(blank, frame, frame + 4, (byte) 0xFF);
            writeFrameChecksum(blank, frame);
        }

        Arrays.fill(blank, 36 * FRAME_SIZE, 63 * FRAME_SIZE, (byte) 0xFF);

        int writeTestFrame = 63 * FRAME_SIZE;
        blank[writeTestFrame] = 'M';
        blank[writeTestFrame + 1] = 'C';
        writeFrameChecksum(blank, writeTestFrame);
        return blank;
    }

    private static boolean hasMagic(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'C';
    }

    private static CardImage decodeCardImage(byte[] fileBytes) {
        if (fileBytes.length == SIZE) {
            return new CardImage(fileBytes, new byte[0]);
        }
        int headerSize = detectHeaderSize(fileBytes);
        if (headerSize >= 0 && fileBytes.length >= headerSize + SIZE) {
            return new CardImage(
                Arrays.copyOfRange(fileBytes, headerSize, headerSize + SIZE),
                Arrays.copyOfRange(fileBytes, 0, headerSize));
        }
        return new CardImage(null, new byte[0]);
    }

    private static int detectHeaderSize(byte[] bytes) {
        if (startsWith(bytes, "VgsM") && bytes.length >= VGS_HEADER_SIZE + SIZE) {
            return VGS_HEADER_SIZE;
        }
        if (startsWith(bytes, "PSV") && bytes.length >= PSX_HEADER_SIZE + SIZE) {
            return PSX_HEADER_SIZE;
        }
        if (startsWith(bytes, "123-456-STD") && bytes.length >= GME_HEADER_SIZE + SIZE) {
            return GME_HEADER_SIZE;
        }
        return -1;
    }

    private static boolean startsWith(byte[] bytes, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static void write16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void writeFrameChecksum(byte[] bytes, int frameOffset) {
        int checksum = 0;
        for (int i = 0; i < FRAME_SIZE - 1; i++) {
            checksum ^= bytes[frameOffset + i] & 0xFF;
        }
        bytes[frameOffset + FRAME_SIZE - 1] = (byte) checksum;
    }

    private record CardImage(byte[] data, byte[] header) {}
}
