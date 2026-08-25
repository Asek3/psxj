package nanolive.psxj.emu;

import nanolive.psxj.emu.cop0.Cop0;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.gte.Gte;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class R3000CpuTest {

    @Test
    void shouldExecuteBasicArithmeticProgram() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0005); // addiu t0, zero, 5
        bus.write32(0x0000_0004, 0x2409_0003); // addiu t1, zero, 3
        bus.write32(0x0000_0008, 0x0109_5021); // addu t2, t0, t1

        R3000Cpu cpu = new R3000Cpu(bus, new Cop0(), new Gte());
        cpu.reset(0x0000_0000);
        cpu.step();
        cpu.step();
        cpu.step();

        assertEquals(5, cpu.register(8));
        assertEquals(3, cpu.register(9));
        assertEquals(8, cpu.register(10));
    }

    @Test
    void unalignedWordLoadsTransferOnlyTheSelectedByteLanes() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0101); // addiu t0, zero, 0x101
        bus.write32(0x0000_0004, 0x8909_0003); // lwl t1, 3(t0)
        bus.write32(0x0000_0008, 0x9909_0000); // lwr t1, 0(t0)
        bus.write32(0x0000_000C, 0x0000_0000); // load-delay slot
        bus.write32(0x0000_0010, 0x0120_5021); // addu t2, t1, zero (waits for the scheduled read)
        bus.write8(0x0000_0101, 0x11);
        bus.write8(0x0000_0102, 0x22);
        bus.write8(0x0000_0103, 0x33);
        bus.write8(0x0000_0104, 0x44);

        R3000Cpu cpu = new R3000Cpu(bus, new Cop0(), new Gte());
        cpu.reset(0x0000_0000);
        for (int i = 0; i < 5; i++) {
            cpu.step();
        }

        assertEquals(0x4433_2211, cpu.register(9));
        assertEquals(0x4433_2211, cpu.register(10));
    }

    @Test
    void unalignedWordStoresDoNotReadOrOverwriteAdjacentLanes() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0101); // addiu t0, zero, 0x101
        bus.write32(0x0000_0004, 0x3C09_1122); // lui t1, 0x1122
        bus.write32(0x0000_0008, 0x3529_3344); // ori t1, t1, 0x3344
        bus.write32(0x0000_000C, 0xA909_0003); // swl t1, 3(t0)
        bus.write32(0x0000_0010, 0xB909_0000); // swr t1, 0(t0)
        bus.write8(0x0000_0100, 0xAA);
        bus.write8(0x0000_0105, 0xBB);

        R3000Cpu cpu = new R3000Cpu(bus, new Cop0(), new Gte());
        cpu.reset(0x0000_0000);
        for (int i = 0; i < 10; i++) {
            cpu.step();
        }
        bus.cpuAccessCycles(0xA000_0000, false, 4); // flush cached stores

        assertEquals(0xAA, bus.read8(0x0000_0100));
        assertEquals(0x44, bus.read8(0x0000_0101));
        assertEquals(0x33, bus.read8(0x0000_0102));
        assertEquals(0x22, bus.read8(0x0000_0103));
        assertEquals(0x11, bus.read8(0x0000_0104));
        assertEquals(0xBB, bus.read8(0x0000_0105));
    }

    @Test
    void dcicTraceBreaksBeforeControlTransferAndUsesDebugVector() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x1000_0001); // beq zero,zero,+1
        Cop0 cop0 = new Cop0();
        R3000Cpu cpu = new R3000Cpu(bus, cop0, new Gte());
        cpu.reset(0x0000_0000);
        cop0.writeRegister(7, (1 << 23) | (1 << 28) | (1 << 30) | (1 << 31));

        cpu.step();

        assertEquals(0xBFC0_0140, cpu.pc());
        assertEquals(0x21, cop0.readRegister(7) & 0x3F, "DCIC.DB+T must latch");
        assertEquals(9, (cop0.cause() >>> 2) & 0x1F);
        assertEquals(0, cop0.epc());
    }

    @Test
    void dcicJumpRedirectionSendsUnmodifiedLoadedIndirectTargetToZero() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x8C08_0100); // lw t0,100h(zero)
        bus.write32(0x0000_0004, 0x0000_0000); // load-delay slot
        bus.write32(0x0000_0008, 0x0100_0008); // jr t0
        bus.write32(0x0000_000C, 0x0000_0000); // branch-delay slot
        bus.write32(0x0000_0100, 0x0000_0200);
        Cop0 cop0 = new Cop0();
        R3000Cpu cpu = new R3000Cpu(bus, cop0, new Gte());
        cpu.reset(0x0000_0000);
        cop0.writeRegister(7, 1 << 12);

        cpu.step();
        cpu.step();
        cpu.step();

        assertEquals(0, cpu.nextPc());
        cpu.step();
        assertEquals(0, cpu.pc());
    }

    @Test
    void writingLoadedRegisterCancelsDcicJumpRedirectionProvenance() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x8C08_0100); // lw t0,100h(zero)
        bus.write32(0x0000_0004, 0x2408_0200); // addiu t0,zero,200h
        bus.write32(0x0000_0008, 0x0100_0008); // jr t0
        bus.write32(0x0000_000C, 0x0000_0000);
        bus.write32(0x0000_0100, 0x0000_0300);
        Cop0 cop0 = new Cop0();
        R3000Cpu cpu = new R3000Cpu(bus, cop0, new Gte());
        cpu.reset(0x0000_0000);
        cop0.writeRegister(7, 1 << 12);

        cpu.step();
        cpu.step();
        cpu.step();

        assertEquals(0x0000_0200, cpu.nextPc());
    }

    @Test
    void unimplementedCop0RegistersExposeTheRecentCop0ReadLatchThenTwenty() {
        Bus bus = new Bus();
        bus.write32(0x00, 0x4008_7800); // mfc0 t0,PRID
        bus.write32(0x04, 0x4009_8000); // mfc0 t1,r16 (garbage bank)
        bus.write32(0x08, 0x0000_0000);
        bus.write32(0x0C, 0x0000_0000);
        bus.write32(0x10, 0x0000_0000);
        bus.write32(0x14, 0x400A_8000); // mfc0 t2,r16 after latch drain
        bus.write32(0x18, 0x0000_0000);
        R3000Cpu cpu = new R3000Cpu(bus, new Cop0(), new Gte());
        cpu.reset(0);

        for (int i = 0; i < 7; i++) {
            cpu.step();
        }

        assertEquals(2, cpu.register(8));
        assertEquals(2, cpu.register(9));
        assertEquals(0x20, cpu.register(10));
    }
}
