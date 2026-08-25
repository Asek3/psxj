package nanolive.psxj.emu;

import nanolive.psxj.emu.cop0.CpuException;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.Mdec;
import nanolive.psxj.emu.devices.Sio1Controller;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.devices.Spu;
import nanolive.psxj.emu.devices.TimerController;
import nanolive.psxj.emu.sio.LoopbackSio1LinkEndpoint;
import nanolive.psxj.emu.sio.MemoryCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SaveStateDeviceTest {

    @Test
    void saveStatesDoNotReplacePersistentMemoryCards(@TempDir Path tempDir) throws Exception {
        PsxEmulator source = emulator();
        String json = source.saveStateJson();
        assertFalse(json.contains("memCard1"));
        assertFalse(json.contains("memCard2"));

        PsxEmulator target = emulator();
        Path slot1 = tempDir.resolve("slot1.mcd");
        Path slot2 = tempDir.resolve("slot2.mcd");
        target.attachMemoryCards(slot1, slot2);
        SioController sio = field(target, "sio", SioController.class);
        byte[] current1 = sio.copyMemoryCard(0);
        byte[] current2 = sio.copyMemoryCard(1);
        current1[0x2000] = 0x5A;
        current2[0x2000] = (byte) 0xA5;
        sio.loadMemoryCard(0, current1);
        sio.loadMemoryCard(1, current2);
        sio.flush();

        byte[] stale = new byte[128 * 1024];
        stale[0] = 'M';
        stale[1] = 'C';
        String legacyCards = "\"memCard1\":\"" + Base64.getEncoder().encodeToString(stale)
            + "\",\"memCard2\":\"" + Base64.getEncoder().encodeToString(stale) + "\",";
        target.loadStateJson(json.replaceFirst("\\{", "{" + legacyCards));

        assertEquals(0x5A, sio.copyMemoryCard(0)[0x2000] & 0xFF);
        assertEquals(0xA5, sio.copyMemoryCard(1)[0x2000] & 0xFF);
        assertEquals(0x5A, MemoryCard.openOrCreate(slot1).readByte(0x2000));
        assertEquals(0xA5, MemoryCard.openOrCreate(slot2).readByte(0x2000));
    }

    @Test
    void emulatorRejectsMissingOrDifferentSaveStateVersions() {
        PsxEmulator emulator = emulator();

        IllegalArgumentException missing = assertThrows(
            IllegalArgumentException.class,
            () -> emulator.loadStateJson("{}")
        );
        IllegalArgumentException different = assertThrows(
            IllegalArgumentException.class,
            () -> emulator.loadStateJson("{\"version\":2}")
        );

        assertTrue(missing.getMessage().contains("expected 1"));
        assertTrue(different.getMessage().contains("Unsupported save-state version 2"));
    }

    @Test
    void emulatorJsonRoundTripRestoresIrqDmaAndTimerState() throws Exception {
        PsxEmulator source = emulator();
        PsxEmulator target = emulator();

        InterruptController interrupts = field(source, "interrupts", InterruptController.class);
        DmaController dma = field(source, "dma", DmaController.class);
        Gpu gpu = field(source, "gpu", Gpu.class);
        Mdec mdec = field(source, "mdec", Mdec.class);
        SioController sio = field(source, "sio", SioController.class);
        Sio1Controller sio1 = field(source, "sio1", Sio1Controller.class);
        Spu spu = field(source, "spu", Spu.class);
        TimerController timers = field(source, "timers", TimerController.class);

        interrupts.writeMask(1 << 3);
        interrupts.raise(3);
        dma.write32(0x1F80_10F0, 0x0F65_4321);
        dma.write32(0x1F80_1080, 0x0000_1234);
        dma.write32(0x1F80_1084, 0x0002_0004);
        dma.write32(0x1F80_1088, 0x0100_0201);
        gpu.tick(4096);
        gpu.gp0(0xA000_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0002);
        mdec.writeControl(0x2000_0000);
        mdec.writeParameter(0x2800_0001);
        mdec.writeParameter(0xFE00_0008);
        mdec.tick(64);
        Mdec.State mdecState = mdec.copyState();
        int expectedMdecWord = mdec.read();
        mdec.loadState(mdecState);
        sio.write16(0x1F80104A, 0x1007);
        sio.write8(0x1F801040, 0x01);
        sio.tick(2_000);
        sio1.setLinkEndpoint(new LoopbackSio1LinkEndpoint());
        sio1.write16(0x1F80_1058, 0x004E);
        sio1.write16(0x1F80_105E, 0x00DC);
        sio1.write16(0x1F80_105A, 0x0027);
        sio1.write8(0x1F80_1050, 0xA6);
        sio1.tick(10_000);
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8010);
        spu.write16(0x1F80_1DA8, 0x2468);
        timers.write16(0x1F80_1124, 0x0200);
        timers.tick(27);

        String json = source.saveStateJson();

        InterruptController targetInterrupts = field(target, "interrupts", InterruptController.class);
        DmaController targetDma = field(target, "dma", DmaController.class);
        Gpu targetGpu = field(target, "gpu", Gpu.class);
        Mdec targetMdec = field(target, "mdec", Mdec.class);
        SioController targetSio = field(target, "sio", SioController.class);
        Sio1Controller targetSio1 = field(target, "sio1", Sio1Controller.class);
        Spu targetSpu = field(target, "spu", Spu.class);
        TimerController targetTimers = field(target, "timers", TimerController.class);
        targetInterrupts.writeMask(0);
        targetDma.write32(0x1F80_10F0, 0);
        targetGpu.gp1(0x0000_0000);
        targetMdec.writeControl(0x8000_0000);
        targetSio.write16(0x1F80104A, 0x0040);
        targetSio1.setLinkEndpoint(new LoopbackSio1LinkEndpoint());
        targetSpu.write16(0x1F80_1DA6, 0x0000);
        targetTimers.write16(0x1F80_1120, 0x7777);

        target.loadStateJson(json);

        assertEquals(1 << 3, targetInterrupts.status() & (1 << 3));
        assertEquals(1 << 3, targetInterrupts.mask() & (1 << 3));
        assertEquals(0x0F65_4321, targetDma.read32(0x1F80_10F0));
        assertEquals(0x0000_1234, targetDma.read32(0x1F80_1080));
        assertEquals(0x0002_0004, targetDma.read32(0x1F80_1084));
        assertTrue((targetDma.read32(0x1F80_1088) & (1 << 24)) != 0);
        targetGpu.gp0(0x03E0_001F);
        assertEquals(0x001F, targetGpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x03E0, targetGpu.copyVram()[1] & 0xFFFF);
        assertEquals(gpu.frameCounter(), targetGpu.frameCounter());
        assertEquals(expectedMdecWord, targetMdec.read());
        targetSio.write8(0x1F801040, 0x42);
        targetSio.tick(2_000);
        assertEquals(0xFF, targetSio.read8(0x1F801040));
        assertEquals(0x41, targetSio.read8(0x1F801040));
        targetSio1.tick(25_200);
        assertEquals(0xA6, targetSio1.read8(0x1F80_1050));
        targetSpu.tick(768);
        assertEquals(0x2468, targetSpu.copyRam()[0x800] & 0xFFFF);
        assertEquals(timers.read16(0x1F80_1120), targetTimers.read16(0x1F80_1120));
    }

    @Test
    void emulatorJsonRoundTripPreservesDelayedCpuGteWrite() throws Exception {
        PsxEmulator source = emulator();
        Bus sourceBus = field(source, "bus", Bus.class);
        R3000Cpu sourceCpu = field(source, "cpu", R3000Cpu.class);
        int[] instructions = {
            0x2408_0007, // addiu t0, zero, 7
            0x4888_4800, // mtc2 t0, IR1
            0x0000_0000, // first GTE write-delay clock
            0x0000_0000, // second GTE write-delay clock
            0x4809_4800, // mfc2 t1, IR1
            0x0000_0000  // nop
        };
        for (int i = 0; i < instructions.length; i++) {
            sourceBus.write32(i * 4, instructions[i]);
        }
        sourceBus.fetchInstruction(0x0000_0000, false);
        sourceBus.fetchInstruction(0x0000_0010, false);
        sourceCpu.setPcState(0x0000_0000, 0x0000_0004, false);
        sourceCpu.cop0().writeRegister(12, sourceCpu.cop0().readRegister(12) | (1 << 30));
        sourceCpu.step();
        sourceCpu.step();
        assertEquals(0, sourceCpu.gte().readData(9));
        assertEquals(1, sourceCpu.copyState().pendingGteWriteCount);

        PsxEmulator target = emulator();
        target.loadStateJson(source.saveStateJson());
        R3000Cpu targetCpu = field(target, "cpu", R3000Cpu.class);

        assertEquals(1, targetCpu.step());
        assertEquals(1, targetCpu.step());
        assertEquals(7, targetCpu.gte().readData(9));
        assertEquals(0, targetCpu.copyState().pendingGteWriteCount);
        assertEquals(1, targetCpu.step());
        assertEquals(1, targetCpu.step());
        assertEquals(7, targetCpu.register(9));
    }

    @Test
    void emulatorJsonRoundTripPreservesAnInFlightGteCommandAndLatchedInputs() throws Exception {
        PsxEmulator source = emulator();
        Bus sourceBus = field(source, "bus", Bus.class);
        R3000Cpu sourceCpu = field(source, "cpu", R3000Cpu.class);
        int[] instructions = {
            0x2408_0003, // addiu t0, zero, 3
            0x4A00_0028, // SQR, sf=0, lm=0
            0x4888_5800, // mtc2 t0, IR3 (inside its latch window)
            0x4809_D800, // mfc2 t1, MAC3
            0x0000_0000  // load-delay nop
        };
        for (int i = 0; i < instructions.length; i++) {
            sourceBus.write32(i * 4, instructions[i]);
        }
        sourceBus.fetchInstruction(0x0000_0000, false);
        sourceBus.fetchInstruction(0x0000_0010, false);
        sourceCpu.setPcState(0x0000_0000, 0x0000_0004, false);
        sourceCpu.cop0().writeRegister(12, sourceCpu.cop0().readRegister(12) | (1 << 30));
        sourceCpu.gte().writeData(11, 2);
        sourceCpu.step();
        sourceCpu.step();
        sourceCpu.step();

        R3000Cpu.State sourceState = sourceCpu.copyState();
        assertTrue(sourceState.gteCommandPending);

        PsxEmulator target = emulator();
        target.loadStateJson(source.saveStateJson());
        R3000Cpu targetCpu = field(target, "cpu", R3000Cpu.class);

        targetCpu.step();
        targetCpu.step();

        assertEquals(9, targetCpu.register(9));
    }

    @Test
    void emulatorJsonRoundTripRestoresCop0ExceptionRegisters() throws Exception {
        PsxEmulator source = emulator();
        Bus sourceBus = field(source, "bus", Bus.class);
        R3000Cpu sourceCpu = field(source, "cpu", R3000Cpu.class);
        sourceBus.write32(0x0000_0000, 0x8C09_0001); // lw t1, 1(zero)
        sourceCpu.setPcState(0x0000_0000, 0x0000_0004, false);

        sourceCpu.step();

        assertEquals(CpuException.ADDRESS_ERROR_LOAD.code() << 2, sourceCpu.cop0().readRegister(13) & 0x7C);
        assertEquals(0x0000_0000, sourceCpu.cop0().readRegister(14));
        assertEquals(0x0000_0001, sourceCpu.cop0().readRegister(8));

        PsxEmulator target = emulator();
        target.loadStateJson(source.saveStateJson());
        R3000Cpu targetCpu = field(target, "cpu", R3000Cpu.class);

        assertEquals(CpuException.ADDRESS_ERROR_LOAD.code() << 2, targetCpu.cop0().readRegister(13) & 0x7C);
        assertEquals(0x0000_0000, targetCpu.cop0().readRegister(14));
        assertEquals(0x0000_0001, targetCpu.cop0().readRegister(8));
    }

    @Test
    void emulatorJsonRoundTripPreservesCop0HardwareBreakpointState() throws Exception {
        PsxEmulator source = emulator();
        Bus sourceBus = field(source, "bus", Bus.class);
        R3000Cpu sourceCpu = field(source, "cpu", R3000Cpu.class);
        int[] instructions = {
            0x0000_0000, // nop
            0x0000_0000, // nop
            0x2408_0007  // addiu t0, zero, 7 (must be intercepted)
        };
        for (int i = 0; i < instructions.length; i++) {
            sourceBus.write32(i * 4, instructions[i]);
        }
        sourceBus.fetchInstruction(0x0000_0000, false);
        sourceCpu.setPcState(0x0000_0000, 0x0000_0004, false);
        sourceCpu.cop0().writeRegister(12, sourceCpu.cop0().status() & ~(1 << 22));
        sourceCpu.cop0().writeRegister(3, 0x0000_0008);
        sourceCpu.cop0().writeRegister(11, -1);
        sourceCpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 24) | (1 << 30) | (1 << 31));
        sourceCpu.step();

        PsxEmulator target = emulator();
        target.loadStateJson(source.saveStateJson());
        R3000Cpu targetCpu = field(target, "cpu", R3000Cpu.class);

        targetCpu.step();
        targetCpu.step();

        assertEquals(0, targetCpu.register(8));
        assertEquals(0x8000_0040, targetCpu.pc());
        assertEquals(0x0000_0008, targetCpu.cop0().epc());
        assertEquals(CpuException.BREAKPOINT.code() << 2, targetCpu.cop0().cause() & 0x7C);
        assertEquals(0x3, targetCpu.cop0().readRegister(7) & 0x3);
    }

    private static PsxEmulator emulator() {
        return new PsxEmulator(null, 100);
    }

    private static <T> T field(PsxEmulator emulator, String name, Class<T> type) throws Exception {
        Field field = PsxEmulator.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(emulator));
    }
}
