package nanolive.psxj.emu.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public record BiosImage(Path path, ByteBuffer data) {

    public static BiosImage load(Path path) throws IOException {
        var bytes = Files.readAllBytes(path);
        if (bytes.length < 256 * 1024) {
            throw new IOException("BIOS image is unexpectedly small: " + bytes.length + " bytes");
        }
        return new BiosImage(path, ByteBuffer.wrap(bytes).asReadOnlyBuffer());
    }

    public int size() {
        return data.capacity();
    }

    public int read8(int offset) {
        return Byte.toUnsignedInt(data.get(offset));
    }

    public int read16(int offset) {
        return read8(offset) | (read8(offset + 1) << 8);
    }

    public int read32(int offset) {
        return read16(offset) | (read16(offset + 2) << 16);
    }
}
