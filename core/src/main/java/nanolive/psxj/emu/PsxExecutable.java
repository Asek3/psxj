package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public record PsxExecutable(
    Path path,
    int initialPc,
    int initialGp,
    int loadAddress,
    int fileSize,
    int memfillAddress,
    int memfillSize,
    int initialSpBase,
    int initialSpOffset,
    byte[] body
) {
    private static final int HEADER_SIZE = 0x800;
    private static final String MAGIC = "PS-X EXE";

    public static PsxExecutable tryLoad(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] headerBytes = input.readNBytes(HEADER_SIZE);
            if (headerBytes.length < HEADER_SIZE) {
                return null;
            }

            String magic = new String(headerBytes, 0, MAGIC.length(), StandardCharsets.US_ASCII);
            if (!MAGIC.equals(magic)) {
                return null;
            }

            ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            int initialPc = header.getInt(0x10);
            int initialGp = header.getInt(0x14);
            int loadAddress = header.getInt(0x18);
            int fileSize = header.getInt(0x1C);
            int memfillAddress = header.getInt(0x28);
            int memfillSize = header.getInt(0x2C);
            int initialSpBase = header.getInt(0x30);
            int initialSpOffset = header.getInt(0x34);

            if (fileSize < 0) {
                throw new IOException("Negative PS-X EXE payload size: " + fileSize);
            }
            validateRamRange(loadAddress, fileSize, "load");
            validateRamRange(memfillAddress, memfillSize, "memfill");

            byte[] body = input.readNBytes(fileSize);
            if (body.length < fileSize) {
                throw new IOException("Truncated PS-X EXE payload: expected " + fileSize + " bytes, got " + body.length);
            }

            return new PsxExecutable(
                path,
                initialPc,
                initialGp,
                loadAddress,
                fileSize,
                memfillAddress,
                memfillSize,
                initialSpBase,
                initialSpOffset,
                Arrays.copyOf(body, body.length)
            );
        }
    }

    public int initialSp() {
        return initialSpBase + initialSpOffset;
    }

    public boolean hasStack() {
        return initialSpBase != 0;
    }

    private static void validateRamRange(int address, int size, String regionName) throws IOException {
        if (size <= 0) {
            return;
        }
        long physical = normalizeAddress(address) & 0xFFFF_FFFFL;
        long end = physical + (long) size;
        if (physical >= Bus.RAM_SIZE || end > Bus.RAM_SIZE) {
            throw new IOException("PS-X EXE " + regionName + " range outside RAM: addr=0x"
                + Integer.toHexString(address) + " size=0x" + Integer.toHexString(size));
        }
    }

    private static int normalizeAddress(int address) {
        int top = address & 0xE000_0000;
        if (top == 0x8000_0000 || top == 0xA000_0000) {
            return address & 0x1FFF_FFFF;
        }
        return address;
    }
}
