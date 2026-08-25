package nanolive.psxj.emu.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BusPeekTest {

    @Test
    void peeksRamAndScratchpadWithoutUsingMappedReads() {
        Bus bus = new Bus();
        bus.write8(0x0001_2345, 0xA5);
        byte[] scratchpad = new byte[Bus.SCRATCHPAD_SIZE];
        scratchpad[31] = 0x5A;
        bus.loadScratchpad(scratchpad);

        assertEquals(0xA5, bus.peekRam8(0x0001_2345));
        assertEquals(0x5A, bus.peekRam8(0x1F80_001F));
        assertEquals(-1, bus.peekRam8(0x1F80_1000));
    }

    @Test
    void copiesContiguousAchievementMemorySnapshot() {
        Bus bus = new Bus();
        bus.write8(Bus.RAM_SIZE - 1, 0xA5);
        byte[] scratchpad = new byte[Bus.SCRATCHPAD_SIZE];
        scratchpad[0] = 0x5A;
        scratchpad[Bus.SCRATCHPAD_SIZE - 1] = 0x33;
        bus.loadScratchpad(scratchpad);

        byte[] snapshot = new byte[Bus.RAM_SIZE + Bus.SCRATCHPAD_SIZE];
        bus.copyAchievementMemory(snapshot);

        assertEquals(0xA5, Byte.toUnsignedInt(snapshot[Bus.RAM_SIZE - 1]));
        assertEquals(0x5A, Byte.toUnsignedInt(snapshot[Bus.RAM_SIZE]));
        assertEquals(0x33, Byte.toUnsignedInt(snapshot[snapshot.length - 1]));
    }
}
