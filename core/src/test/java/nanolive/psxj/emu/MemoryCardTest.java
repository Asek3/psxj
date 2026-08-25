package nanolive.psxj.emu;

import nanolive.psxj.emu.sio.MemoryCard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MemoryCardTest {

    @Test
    void shouldCreateFormattedCard() throws Exception {
        Path temp = Files.createTempFile("psxj-test-card", ".mcd");
        Files.deleteIfExists(temp);
        MemoryCard card = MemoryCard.openOrCreate(temp);
        assertEquals('M', card.readByte(0));
        assertEquals('C', card.readByte(1));
    }

    @Test
    void newCardHasValidHeaderChecksumAndFreeDirectoryEntries() throws Exception {
        Path temp = Files.createTempFile("psxj-test-card", ".mcd");
        Files.deleteIfExists(temp);
        MemoryCard card = MemoryCard.openOrCreate(temp);
        byte[] data = card.copyData();

        assertEquals(128 * 1024, data.length);
        assertEquals(0x0E, data[127] & 0xFF);
        for (int block = 1; block <= 15; block++) {
            int frame = block * 128;
            assertEquals(0xA0, data[frame] & 0xFF);
            assertEquals(0xFF, data[frame + 0x08] & 0xFF);
            assertEquals(0xFF, data[frame + 0x09] & 0xFF);
            assertEquals(frameChecksum(data, frame), data[frame + 127] & 0xFF);
        }
    }

    @Test
    void invalidExistingCardIsReformattedAsBlankCard() throws Exception {
        Path temp = Files.createTempFile("psxj-test-card-invalid", ".mcd");
        Files.write(temp, new byte[64]);

        MemoryCard card = MemoryCard.openOrCreate(temp);

        assertEquals('M', card.readByte(0));
        assertEquals('C', card.readByte(1));
        assertEquals(0xA0, card.readByte(128));
    }

    @Test
    void blankCardMatchesSonyDirectoryAndReplacementFrames() throws Exception {
        Path temp = Files.createTempFile("psxj-test-card-format", ".mcd");
        Files.deleteIfExists(temp);
        byte[] data = MemoryCard.openOrCreate(temp).copyData();

        for (int frameNumber = 16; frameNumber <= 35; frameNumber++) {
            int frame = frameNumber * 128;
            for (int i = 0; i < 4; i++) {
                assertEquals(0xFF, data[frame + i] & 0xFF);
            }
            assertEquals(frameChecksum(data, frame), data[frame + 127] & 0xFF);
        }
        for (int i = 36 * 128; i < 63 * 128; i++) {
            assertEquals(0xFF, data[i] & 0xFF);
        }
        assertEquals('M', data[63 * 128]);
        assertEquals('C', data[63 * 128 + 1]);
        assertEquals(0x0E, data[64 * 128 - 1] & 0xFF);
    }

    @Test
    void vgsContainerHeaderIsPreservedWhenFlushing() throws Exception {
        Path temp = Files.createTempFile("psxj-test-card-vgs", ".mem");
        byte[] raw = new byte[128 * 1024];
        raw[0] = 'M';
        raw[1] = 'C';
        raw[127] = 0x0E;
        byte[] image = new byte[0x40 + raw.length];
        image[0] = 'V';
        image[1] = 'g';
        image[2] = 's';
        image[3] = 'M';
        java.util.Arrays.fill(image, 4, 0x40, (byte) 0x6A);
        System.arraycopy(raw, 0, image, 0x40, raw.length);
        Files.write(temp, image);

        MemoryCard card = MemoryCard.openOrCreate(temp);
        card.writeByte(0x1234, 0xA5);
        card.flush();
        byte[] saved = Files.readAllBytes(temp);

        assertArrayEquals(java.util.Arrays.copyOf(image, 0x40),
            java.util.Arrays.copyOf(saved, 0x40));
        assertEquals(0xA5, saved[0x40 + 0x1234] & 0xFF);
    }

    private static int frameChecksum(byte[] data, int frameOffset) {
        int checksum = 0;
        for (int i = 0; i < 127; i++) {
            checksum ^= data[frameOffset + i] & 0xFF;
        }
        return checksum;
    }
}
