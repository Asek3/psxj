package nanolive.psxj.emu;

import nanolive.psxj.emu.cop0.Cop0;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.gte.Gte;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CpuBusErrorTest {

    @Test
    void unmappedLoadRaisesBusError() {
        Bus bus = new Bus();
        Cop0 cop0 = new Cop0();
        R3000Cpu cpu = new R3000Cpu(bus, cop0, new Gte());

        bus.write32(0x0000_0000, 0x3C08_1F90); // lui t0, 0x1f90
        bus.write32(0x0000_0004, 0x8D09_0000); // lw t1, 0(t0)

        cpu.reset(0x0000_0000);
        cpu.step();
        cpu.step();

        assertEquals(0x07 << 2, cop0.readRegister(13) & 0x7C);
        assertEquals(0x0000_0004, cop0.readRegister(14));
    }

    @Test
    void scratchpadFetchRaisesBusErrorInsteadOfAddressError() {
        Bus bus = new Bus();
        Cop0 cop0 = new Cop0();
        R3000Cpu cpu = new R3000Cpu(bus, cop0, new Gte());

        cpu.reset(0x1F80_0000);
        cpu.step();

        assertEquals(0x06 << 2, cop0.readRegister(13) & 0x7C);
        assertEquals(0x1F80_0000, cop0.readRegister(14));
    }

    @Test
    void expansion2GapRaisesBusError() {
        Bus bus = new Bus();
        Cop0 cop0 = new Cop0();
        R3000Cpu cpu = new R3000Cpu(bus, cop0, new Gte());

        bus.write32(0x0000_0000, 0x3C08_1F80); // lui t0, 0x1f80
        bus.write32(0x0000_0004, 0x3508_2080); // ori t0, t0, 0x2080
        bus.write32(0x0000_0008, 0x8D09_0000); // lw t1, 0(t0)

        cpu.reset(0x0000_0000);
        cpu.step();
        cpu.step();
        cpu.step();

        assertEquals(0x07 << 2, cop0.readRegister(13) & 0x7C);
        assertEquals(0x0000_0008, cop0.readRegister(14));
    }
}
