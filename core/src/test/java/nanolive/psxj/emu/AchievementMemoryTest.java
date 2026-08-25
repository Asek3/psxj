package nanolive.psxj.emu;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AchievementMemoryTest {

    @Test
    void readsOnlyTheRequestedObserverRange() {
        PsxEmulator emulator = new PsxEmulator(null, 100);
        byte[] destination = new byte[12];
        Arrays.fill(destination, (byte) 0x7F);

        assertEquals(3, emulator.readAchievementMemory(0, destination, 4, 3));
        assertEquals(0x7F, Byte.toUnsignedInt(destination[3]));
        assertEquals(0, Byte.toUnsignedInt(destination[4]));
        assertEquals(0, Byte.toUnsignedInt(destination[6]));
        assertEquals(0x7F, Byte.toUnsignedInt(destination[7]));
        assertEquals(0, emulator.readAchievementMemory(0x20_0400, destination, 0, 1));
    }
}
