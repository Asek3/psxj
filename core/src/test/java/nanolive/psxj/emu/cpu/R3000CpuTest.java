package nanolive.psxj.emu.cpu;

import nanolive.psxj.emu.cop0.CpuException;
import nanolive.psxj.emu.core.BiosImage;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.TimerController;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class R3000CpuTest {

    @Test
    void executesAddiuAndStoreLoad() {
        var bus = new Bus();
        byte[] bios = new byte[512 * 1024];
        put32(bios, 0x0000, 0x2408002A); // addiu t0, zero, 42
        put32(bios, 0x0004, 0xAC080000); // sw t0, 0(zero)
        put32(bios, 0x0008, 0x8C090000); // lw t1, 0(zero)
        put32(bios, 0x000C, 0x00000000); // nop
        bus.setBios(new BiosImage(Path.of("dummy.bin"), ByteBuffer.wrap(bios)));
        var cpu = new R3000Cpu(bus);
        cpu.reset(bus.resetVector());

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(42, cpu.register(8));
        Assertions.assertEquals(42, cpu.register(9));
    }

    @Test
    void loadResultBecomesVisibleAfterOneInstruction() {
        var bus = new Bus();
        byte[] bios = new byte[512 * 1024];
        put32(bios, 0x0000, 0x8C080100); // lw t0, 0x100(zero)
        put32(bios, 0x0004, 0x01004821); // addu t1, t0, zero
        put32(bios, 0x0008, 0x00000000); // nop
        bus.setBios(new BiosImage(Path.of("dummy.bin"), ByteBuffer.wrap(bios)));
        bus.write32(0x0000_0100, 0x1234_5678);
        var cpu = new R3000Cpu(bus);
        cpu.reset(bus.resetVector());

        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x1234_5678, cpu.register(8));
        Assertions.assertEquals(0, cpu.register(9));
    }

    @Test
    void directWriteCancelsPendingLoadToSameRegister() {
        var bus = new Bus();
        byte[] bios = new byte[512 * 1024];
        put32(bios, 0x0000, 0x8C080100); // lw t0, 0x100(zero)
        put32(bios, 0x0004, 0x24080005); // addiu t0, zero, 5
        put32(bios, 0x0008, 0x00000000); // nop
        bus.setBios(new BiosImage(Path.of("dummy.bin"), ByteBuffer.wrap(bios)));
        bus.write32(0x0000_0100, 0x1234_5678);
        var cpu = new R3000Cpu(bus);
        cpu.reset(bus.resetVector());

        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(5, cpu.register(8));
    }

    @Test
    void cop1OpcodeRaisesCoprocessorUnusable() {
        var cpu = createCpu(
            0x4401_0000 // mfc1 at, f0
        );

        cpu.step();

        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(CpuException.COPROCESSOR_UNUSABLE.code() << 2, cpu.cop0().readRegister(13) & 0x7C);
        Assertions.assertEquals(1 << 28, cpu.cop0().readRegister(13) & 0x3000_0000);
        Assertions.assertEquals(0x0000_0000, cpu.cop0().readRegister(14));
    }

    @Test
    void undocumentedRegimmEncodingsAliasBltzAndBgez() {
        var cpu = createCpu(
            0x2408_FFFF, // addiu t0, zero, -1
            0x0502_0002, // undocumented rt=2 aliases bltz +2
            0x0000_0000, // delay slot
            0x2409_0001, // skipped
            0x0503_0001, // undocumented rt=3 aliases bgez +1 (not taken)
            0x240A_0002  // delay slot
        );

        for (int i = 0; i < 5; i++) {
            cpu.step();
        }

        Assertions.assertEquals(0, cpu.register(9));
        Assertions.assertEquals(2, cpu.register(10));
        Assertions.assertEquals(0, cpu.register(31));
        Assertions.assertEquals(0, cpu.cop0().readRegister(13) & 0x7C);
    }

    @Test
    void bc2fBranchesAndBc2tDoesNot() {
        var takenCpu = createCpu(
            0x4900_0002, // bc2f +2
            0x0000_0000, // nop
            0x2408_0001, // addiu t0, zero, 1
            0x2409_0002  // addiu t1, zero, 2
        );
        takenCpu.cop0().writeRegister(12, takenCpu.cop0().readRegister(12) | (1 << 30));

        takenCpu.step();
        takenCpu.step();
        takenCpu.step();

        Assertions.assertEquals(0, takenCpu.register(8));
        Assertions.assertEquals(2, takenCpu.register(9));

        var notTakenCpu = createCpu(
            0x4901_0002, // bc2t +2
            0x0000_0000, // nop
            0x2408_0001, // addiu t0, zero, 1
            0x2409_0002  // addiu t1, zero, 2
        );
        notTakenCpu.cop0().writeRegister(12, notTakenCpu.cop0().readRegister(12) | (1 << 30));

        notTakenCpu.step();
        notTakenCpu.step();
        notTakenCpu.step();

        Assertions.assertEquals(1, notTakenCpu.register(8));
        Assertions.assertEquals(0, notTakenCpu.register(9));
    }

    @Test
    void userModeKsegLoadRaisesAddressErrorLoad() {
        var cpu = createCpu(
            0x3C04_8000, // lui a0, 0x8000
            0x8C88_0000  // lw t0, 0(a0)
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | 0x2);

        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(CpuException.ADDRESS_ERROR_LOAD.code() << 2, cpu.cop0().readRegister(13) & 0x7C);
        Assertions.assertEquals(0x0000_0004, cpu.cop0().readRegister(14));
        Assertions.assertEquals(0x8000_0000, cpu.cop0().readRegister(8));
    }

    @Test
    void delaySlotExceptionCapturesBdBtAndTar() {
        var cpu = createCpu(
            0x1000_0002, // beq zero, zero, +2
            0x8C08_0001, // lw t0, 1(zero)
            0x0000_0000, // nop
            0x0000_0000  // nop
        );

        cpu.step();
        cpu.step();

        Assertions.assertEquals(CpuException.ADDRESS_ERROR_LOAD.code() << 2, cpu.cop0().readRegister(13) & 0x7C);
        Assertions.assertEquals(0xC000_0000, cpu.cop0().readRegister(13) & 0xC000_0000);
        Assertions.assertEquals(0x0000_0000, cpu.cop0().readRegister(14));
        Assertions.assertEquals(0x0000_000C, cpu.cop0().readRegister(6));
        Assertions.assertEquals(0x0000_0001, cpu.cop0().readRegister(8));
    }

    @Test
    void notTakenDelaySlotExceptionStillCapturesFallthroughTar() {
        var cpu = createCpu(
            0x1400_0002, // bne zero, zero, +2 (not taken)
            0x8C08_0001, // lw t0, 1(zero)
            0x0000_0000,
            0x0000_0000
        );

        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(0x8000_0000, cpu.cop0().cause() & 0xC000_0000);
        Assertions.assertEquals(0x0000_0000, cpu.cop0().epc());
        Assertions.assertEquals(0x0000_0008, cpu.cop0().readRegister(6));
    }

    @Test
    void addressExceptionCauseCeComesFromFaultingInstructionBits() {
        var cpu = createCpu(
            0x8C08_0001 // lw t0, 1(zero): opcode bits 27..26 are 3
        );

        cpu.step();

        Assertions.assertEquals(3 << 28, cpu.cop0().cause() & (3 << 28));
    }

    @Test
    void breakInstructionDoesNotLatchDcicComparatorStatus() {
        var cpu = createCpu(0x0000_000D); // break

        cpu.step();

        Assertions.assertEquals(0, cpu.cop0().readRegister(7));
        Assertions.assertEquals(CpuException.BREAKPOINT.code() << 2,
            cpu.cop0().cause() & 0x7C);
    }

    @Test
    void interruptBeforeDelaySlotSetsBdBtAndTar() {
        var bus = new Bus();
        var interrupts = new InterruptController();
        bus.setInterruptController(interrupts);
        var cpu = createCpu(bus,
            0x1000_0002, // beq zero, zero, +2
            0x2408_0001, // addiu t0, zero, 1
            0x2409_0002, // addiu t1, zero, 2
            0x240A_0003  // addiu t2, zero, 3
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | 0x0401);

        cpu.step();
        interrupts.writeMask(1);
        interrupts.raise(0);

        cpu.step();

        Assertions.assertEquals(0, cpu.register(8));
        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(CpuException.INTERRUPT.code() << 2, cpu.cop0().readRegister(13) & 0x7C);
        Assertions.assertEquals(0xC000_0000, cpu.cop0().readRegister(13) & 0xC000_0000);
        Assertions.assertEquals(0x0000_0000, cpu.cop0().readRegister(14));
        Assertions.assertEquals(0x0000_000C, cpu.cop0().readRegister(6));
    }

    @Test
    void undefinedCop0CommandCompletesWithoutReservedInstruction() {
        var cpu = createCpu(
            0x43E0_0000, // undefined COP0 command (rs=1f)
            0x2408_0001  // addiu t0, zero, 1
        );

        cpu.step();
        cpu.step();

        Assertions.assertEquals(1, cpu.register(8));
        Assertions.assertEquals(0, cpu.cop0().cause() & 0x7C);
    }

    @Test
    void enabledUnpopulatedCoprocessorTransfersUseInterfaceLatches() {
        var cpu = createCpu(
            0x2408_1234, // addiu t0, zero, 0x1234
            0x4488_0800, // mtc1 t0, cop1d1
            0x4409_0800, // mfc1 t1, cop1d1
            0x0000_0000  // load-delay nop
        );
        cpu.cop0().writeRegister(12, cpu.cop0().status() | (1 << 29));

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x1234, cpu.register(9));
        Assertions.assertEquals(0, cpu.cop0().cause() & 0x7C);
    }

    @Test
    void unconnectedCoprocessorMemoryPortsStillAccessMemory() {
        Bus storeBus = new Bus();
        var storeCpu = createCpu(storeBus,
            0xE101_0000 // swc0 cop0d1, 0(t0)
        );
        storeBus.write32(0x0000_0100, 0x1234_5678);
        int[] storeRegisters = storeCpu.copyRegisters();
        storeRegisters[8] = 0xA000_0100;
        storeCpu.loadRegisters(storeRegisters);
        storeCpu.cop0().writeRegister(12, storeCpu.cop0().status() | (1 << 28));

        storeCpu.step();
        storeCpu.step();

        Assertions.assertEquals(0, storeCpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0, storeBus.read32(0x0000_0100));

        Bus loadBus = new Bus();
        var loadCpu = createCpu(loadBus,
            0xC101_0000 // lwc0 cop0d1, 0(t0)
        );
        loadBus.write32(0x0000_0100, 0x8765_4321);
        int[] loadRegisters = loadCpu.copyRegisters();
        loadRegisters[8] = 0xA000_0100;
        loadCpu.loadRegisters(loadRegisters);
        loadCpu.cop0().writeRegister(12, loadCpu.cop0().status() | (1 << 28));

        loadCpu.step();

        Assertions.assertEquals(0, loadCpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0x8765_4321, loadBus.read32(0x0000_0100));
    }

    @Test
    void programBreakpointUsesMaskedComparisonAndDedicatedDebugVectors() {
        var ramVectorCpu = createCpuWithWarmInstructionCache(
            0x2408_0007 // addiu t0, zero, 7 (must not execute)
        );
        ramVectorCpu.cop0().writeRegister(3, 0x0000_0003); // BPC
        ramVectorCpu.cop0().writeRegister(11, 0xFFFF_FFFC); // BPCM ignores low two bits
        ramVectorCpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 24) | (1 << 30) | (1 << 31)); // DE,PCE,UD,TR
        ramVectorCpu.cop0().writeRegister(12, ramVectorCpu.cop0().status() & ~(1 << 22));

        ramVectorCpu.step();

        Assertions.assertEquals(0, ramVectorCpu.register(8));
        Assertions.assertEquals(0x8000_0040, ramVectorCpu.pc());
        Assertions.assertEquals(0, ramVectorCpu.cop0().epc());
        Assertions.assertEquals(CpuException.BREAKPOINT.code() << 2,
            ramVectorCpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0x3, ramVectorCpu.cop0().readRegister(7) & 0x3);

        var bootVectorCpu = createCpuWithWarmInstructionCache(0x0000_0000);
        bootVectorCpu.cop0().writeRegister(3, 0);
        bootVectorCpu.cop0().writeRegister(11, -1);
        bootVectorCpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 24) | (1 << 30) | (1 << 31));
        bootVectorCpu.cop0().writeRegister(12, bootVectorCpu.cop0().status() | (1 << 22));

        bootVectorCpu.step();

        Assertions.assertEquals(0xBFC0_0140, bootVectorCpu.pc());
    }

    @Test
    void programBreakpointPreemptsInstructionBusError() {
        var cpu = createCpuWithWarmInstructionCache(0x0000_0000);
        cpu.setPcState(0xC000_0000, 0xC000_0004, false);
        cpu.cop0().writeRegister(3, 0xC000_0000);
        cpu.cop0().writeRegister(11, -1);
        cpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 24) | (1 << 29) | (1 << 31));
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));

        cpu.step();

        Assertions.assertEquals(0x8000_0040, cpu.pc());
        Assertions.assertEquals(CpuException.BREAKPOINT.code() << 2,
            cpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0, cpu.cop0().cause() & (3 << 28));
    }

    @Test
    void misalignedProgramBreakpointLatchesBeforeAddressExceptionWins() {
        var cpu = createCpuWithWarmInstructionCache(0x0000_0000);
        cpu.setPcState(0x0000_0001, 0x0000_0005, false);
        cpu.cop0().writeRegister(3, 0x0000_0001);
        cpu.cop0().writeRegister(11, -1);
        cpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 24) | (1 << 30) | (1 << 31));
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));

        cpu.step();

        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(CpuException.ADDRESS_ERROR_LOAD.code() << 2,
            cpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0x0000_0001, cpu.cop0().readRegister(8));
        Assertions.assertEquals(0x3, cpu.cop0().readRegister(7) & 0x3);
    }

    @Test
    void dataBreakpointsDistinguishReadsAndWritesAndAbortTheAccess() {
        Bus readBus = new Bus();
        var readCpu = createCpu(readBus,
            0x8C09_0100 // lw t1, 0x100(zero)
        );
        readBus.write32(0x0000_0100, 0x1234_5678);
        readCpu.cop0().writeRegister(5, 0x0000_0103); // BDA
        readCpu.cop0().writeRegister(9, 0xFFFF_FFFC); // BDAM ignores low two bits
        readCpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 25) | (1 << 26) | (1 << 30) | (1 << 31));
        readCpu.cop0().writeRegister(12, readCpu.cop0().status() & ~(1 << 22));

        readCpu.step();

        Assertions.assertEquals(0x8000_0040, readCpu.pc());
        Assertions.assertFalse(readCpu.copyState().pendingDataReadValid);
        Assertions.assertEquals(0xD, readCpu.cop0().readRegister(7) & 0x1F); // DB,DA,R

        Bus writeBus = new Bus();
        var writeCpu = createCpu(writeBus,
            0xAD09_0000 // sw t1, 0(t0), uncached KSEG1 below
        );
        writeBus.write32(0x0000_0100, 0x1122_3344);
        int[] registers = writeCpu.copyRegisters();
        registers[8] = 0xA000_0100;
        registers[9] = 0xAABB_CCDD;
        writeCpu.loadRegisters(registers);
        writeCpu.cop0().writeRegister(5, 0xA000_0100);
        writeCpu.cop0().writeRegister(9, -1);
        writeCpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 25) | (1 << 27) | (1 << 29) | (1 << 31));
        writeCpu.cop0().writeRegister(12, writeCpu.cop0().status() & ~(1 << 22));

        writeCpu.step();

        Assertions.assertEquals(0x8000_0040, writeCpu.pc());
        Assertions.assertEquals(0x1122_3344, writeBus.read32(0x0000_0100));
        Assertions.assertEquals(0x15, writeCpu.cop0().readRegister(7) & 0x1F); // DB,DA,W
    }

    @Test
    void breakpointWithoutTrapEnableOnlyLatchesDcicStatus() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0007 // addiu t0, zero, 7
        );
        cpu.cop0().writeRegister(3, 0);
        cpu.cop0().writeRegister(11, -1);
        cpu.cop0().writeRegister(7, (1 << 23) | (1 << 24) | (1 << 30));

        cpu.step();

        Assertions.assertEquals(7, cpu.register(8));
        Assertions.assertEquals(4, cpu.pc());
        Assertions.assertEquals(0, cpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0x3, cpu.cop0().readRegister(7) & 0x3);
    }

    @Test
    void alignmentExceptionSupersedesDataBreakpointButKeepsDcicMatchFlags() {
        Bus bus = new Bus();
        var cpu = createCpu(bus,
            0xA409_0101 // sh t1, 0x101(zero)
        );
        int[] registers = cpu.copyRegisters();
        registers[9] = 0xAABB_CCDD;
        cpu.loadRegisters(registers);
        cpu.cop0().writeRegister(5, 0x0000_0100);
        cpu.cop0().writeRegister(9, 0xFFFF_FFF0);
        cpu.cop0().writeRegister(7,
            (1 << 23) | (1 << 25) | (1 << 27) | (1 << 30) | (1 << 31));
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));

        cpu.step();

        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(CpuException.ADDRESS_ERROR_STORE.code() << 2,
            cpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0x0000_0101, cpu.cop0().readRegister(8));
        Assertions.assertEquals(0x15, cpu.cop0().readRegister(7) & 0x1F);
    }

    @Test
    void disabledScratchpadInDataCacheModePermanentlyWedgesCpuWithoutDbe() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x8D09_0000); // lw t1, 0(t0)
        bus.fetchInstruction(0x0000_0000, false);
        var cpu = new R3000Cpu(bus);
        cpu.reset(0);
        int[] registers = cpu.copyRegisters();
        registers[8] = 0x1F80_0000;
        cpu.loadRegisters(registers);
        int bcc = bus.read32(0xFFFE_0130);
        bus.write32(0xFFFE_0130, (bcc & ~(1 << 3)) | (1 << 7)); // RAM=0, DS=1
        int[] schedulerClocks = {0};
        cpu.setCycleAdvancer(cycles -> {
            schedulerClocks[0] += cycles;
            return cycles;
        });

        Assertions.assertEquals(1, cpu.step());
        R3000Cpu.State wedged = cpu.copyState();
        Assertions.assertTrue(wedged.cpuWedged);
        Assertions.assertEquals(0, cpu.pc());
        Assertions.assertEquals(0, cpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0, wedged.retiredInstructionCount);
        Assertions.assertEquals(1, wedged.totalCycles);

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(0, cpu.pc());
        Assertions.assertEquals(0, cpu.copyState().retiredInstructionCount);
        Assertions.assertEquals(2, cpu.copyState().totalCycles);
        Assertions.assertEquals(2, schedulerClocks[0]);

        int[] cop0State = cpu.cop0().copyRawRegisters();
        var restored = new R3000Cpu(bus);
        restored.cop0().loadRawRegisters(cop0State);
        restored.loadState(cpu.copyState());
        restored.step();
        Assertions.assertTrue(restored.copyState().cpuWedged);
        Assertions.assertEquals(0, restored.pc());
        Assertions.assertEquals(3, restored.copyState().totalCycles);

        restored.reset(0);
        Assertions.assertFalse(restored.copyState().cpuWedged);

        Bus storeBus = new Bus();
        storeBus.write32(0x0000_0000, 0xAD09_0000); // sw t1, 0(t0)
        storeBus.fetchInstruction(0x0000_0000, false);
        var storeCpu = new R3000Cpu(storeBus);
        storeCpu.reset(0);
        int[] storeRegisters = storeCpu.copyRegisters();
        storeRegisters[8] = 0x1F80_0000;
        storeRegisters[9] = 0x1234_5678;
        storeCpu.loadRegisters(storeRegisters);
        int storeBcc = storeBus.read32(0xFFFE_0130);
        storeBus.write32(0xFFFE_0130, (storeBcc & ~(1 << 3)) | (1 << 7));

        storeCpu.step();

        Assertions.assertTrue(storeCpu.copyState().cpuWedged);
        Assertions.assertEquals(0, storeCpu.cop0().cause() & 0x7C);
    }

    @Test
    void mfloWaitsForMultiplyResult() {
        var cpu = createCpu(
            0x3C08_0010, // lui t0, 0x0010
            0x3508_0001, // ori t0, t0, 1
            0x0108_0019, // multu t0, t0
            0x0000_4821, // addu t1, zero, zero
            0x0000_5012  // mflo t2
        );

        cpu.step();
        int baselineCycles = cpu.step();
        cpu.step();
        cpu.step();
        int mfloCycles = cpu.step();

        Assertions.assertTrue(mfloCycles > baselineCycles);
        Assertions.assertEquals(cpu.lo(), cpu.register(10));
    }

    @Test
    void interruptAfterGteCommandUsesCommandEpc() {
        var bus = new Bus();
        var interrupts = new InterruptController();
        bus.setInterruptController(interrupts);
        var cpu = createCpu(bus,
            0x4A00_0001, // cop2cmd RTPS
            0x2408_0001  // addiu t0, zero, 1
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (0x0401 | (1 << 30)));

        cpu.step();
        interrupts.writeMask(1);
        interrupts.raise(0);

        cpu.step();

        Assertions.assertEquals(0x8000_0080, cpu.pc());
        Assertions.assertEquals(CpuException.INTERRUPT.code() << 2, cpu.cop0().readRegister(13) & 0x7C);
        Assertions.assertEquals(0x0000_0000, cpu.cop0().readRegister(14));
    }

    @Test
    void branchInDelaySlotTakesItsDelayInstructionFromTheFirstTarget() {
        var cpu = createCpu(
            0x0800_0004, // j 0x10
            0x0800_0005, // j 0x14
            0x2408_0001, // addiu t0, zero, 1
            0x0000_0000, // nop
            0x2409_0002, // addiu t1, zero, 2
            0x240A_0003  // addiu t2, zero, 3
        );

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0, cpu.register(8));
        Assertions.assertEquals(2, cpu.register(9));
        Assertions.assertEquals(3, cpu.register(10));
    }

    @Test
    void relativeBranchInDelaySlotUsesThePipelinePcAsItsBase() {
        var cpu = createCpu(
            0x1000_0002, // beq zero, zero, +2 -> 0x0c
            0x1000_0004, // beq zero, zero, +4 -> pipeline PC 0x0c + 0x10
            0x3508_0001, // ori t0, t0, 1
            0x3529_0001, // ori t1, t1, 1
            0x3508_0002, // ori t0, t0, 2
            0x3508_0004, // ori t0, t0, 4
            0x3508_0008, // ori t0, t0, 8
            0x3529_0002  // ori t1, t1, 2
        );

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0, cpu.register(8));
        Assertions.assertEquals(3, cpu.register(9));
    }

    @Test
    void notTakenBranchInDelaySlotContinuesAfterTheFirstTarget() {
        var cpu = createCpu(
            0x1000_0002, // beq zero, zero, +2 -> 0x0c
            0x1400_0004, // bne zero, zero, +4 (not taken)
            0x2408_0001, // skipped
            0x2409_0002, // first target
            0x240A_0003  // instruction after the first target
        );

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0, cpu.register(8));
        Assertions.assertEquals(2, cpu.register(9));
        Assertions.assertEquals(3, cpu.register(10));
    }

    @Test
    void linkInDelaySlotUsesThePipelineReturnAddress() {
        var cpu = createCpu(
            0x0800_0003, // j 0x0c
            0x0C00_0006, // jal 0x18
            0x0000_0000,
            0x2408_0001, // first target
            0x0000_0000,
            0x0000_0000,
            0x2409_0002  // second target
        );

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x10, cpu.register(31));
        Assertions.assertEquals(1, cpu.register(8));
        Assertions.assertEquals(2, cpu.register(9));
    }

    @Test
    void mtc0GarbageRegisterDoesNotTrapWhenCu0IsDisabledInUserMode() {
        var cpu = createCpu(
            0x2408_0001, // addiu t0, zero, 1
            0x4088_8000, // mtc0 t0, cop0r16 (garbage)
            0x2409_0002  // addiu t1, zero, 2
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | 0x2);

        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x0000_000C, cpu.pc());
        Assertions.assertEquals(2, cpu.register(9));
    }

    @Test
    void cachedInstructionFetchDoesNotAddRamBusPenalty() {
        var cpu = createCpu(
            0x2408_0001, // addiu t0, zero, 1
            0x2409_0002  // addiu t1, zero, 2
        );

        Assertions.assertTrue(cpu.step() > 1);
        Assertions.assertEquals(1, cpu.step());
    }

    @Test
    void uncachedInstructionFetchUsesBusPenalty() {
        var bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0001); // addiu t0, zero, 1
        var cpu = new R3000Cpu(bus);
        cpu.reset(0xA000_0000);

        Assertions.assertTrue(cpu.step() > 1);
    }

    @Test
    void isolatedCacheLoadUpdatesCop0CmOnHit() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x8C09_0000); // lw t1, 0(zero)
        bus.fetchInstruction(0x0000_0000, false);
        var cpu = new R3000Cpu(bus);
        cpu.reset(0x0000_0000);
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 16));

        cpu.step();

        Assertions.assertEquals(1 << 19, cpu.cop0().readRegister(12) & (1 << 19));
    }

    @Test
    void isolatedCacheLoadClearsCop0CmOnMiss() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x8C09_0100); // lw t1, 0x100(zero)
        bus.fetchInstruction(0x0000_0000, false);
        var cpu = new R3000Cpu(bus);
        cpu.reset(0x0000_0000);
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 16) | (1 << 19));

        cpu.step();

        Assertions.assertEquals(0, cpu.cop0().readRegister(12) & (1 << 19));
    }

    @Test
    void mtc0StatusReadsBackImmediatelyAndPipelineEffectFollowsOneInstruction() {
        var cpu = createCpuWithWarmInstructionCache(
            0x4088_6000, // mtc0 t0, Status
            0x4009_6000, // mfc0 t1, Status (raw register read)
            0x0000_0000  // mfc0 load-delay slot / second following instruction
        );
        int writtenStatus = cpu.cop0().status() | (1 << 16) | (1 << 1);
        int[] registers = cpu.copyRegisters();
        registers[8] = writtenStatus;
        cpu.loadRegisters(registers);

        cpu.step();
        Assertions.assertEquals(writtenStatus, cpu.cop0().status());
        Assertions.assertEquals(0, cpu.copyState().effectiveCop0Status & ((1 << 16) | (1 << 1)));

        cpu.step();
        Assertions.assertEquals((1 << 16) | (1 << 1),
            cpu.copyState().effectiveCop0Status & ((1 << 16) | (1 << 1)));
        cpu.step();
        Assertions.assertEquals(writtenStatus, cpu.register(9));
    }

    @Test
    void mtc0StatusIscEffectStartsAtSecondFollowingStore() {
        Bus bus = new Bus();
        var cpu = createCpu(bus,
            0x4089_6000, // mtc0 t1, Status
            0xAD0A_0000, // sw t2, 0(t0)  - first following instruction
            0xAD0B_0004, // sw t3, 4(t0)  - second following instruction
            0xAD0C_0008  // sw t4, 8(t0)  - IsC is now pipeline-visible
        );
        int[] registers = cpu.copyRegisters();
        registers[8] = 0xA000_0100;
        registers[9] = cpu.cop0().status() | (1 << 16);
        registers[10] = 0x1111_1111;
        registers[11] = 0x2222_2222;
        registers[12] = 0x3333_3333;
        cpu.loadRegisters(registers);

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0x1111_1111, bus.read32(0x0000_0100));
        Assertions.assertEquals(0, bus.read32(0x0000_0104));
        Assertions.assertEquals(0, bus.read32(0x0000_0108));
    }

    @Test
    void mtc0StatusInterruptEnableTakesEffectBeforeTheSecondFollowingInstruction() {
        Bus bus = new Bus();
        InterruptController interrupts = new InterruptController();
        bus.setInterruptController(interrupts);
        int[] instructions = {0x4088_6000, 0, 0, 0x2409_0001};
        for (int i = 0; i < instructions.length; i++) {
            bus.write32(i * 4, instructions[i]);
        }
        bus.fetchInstruction(0, false);
        var cpu = new R3000Cpu(bus);
        cpu.reset(0);
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));
        int[] registers = cpu.copyRegisters();
        registers[8] = cpu.cop0().status() | 0x0000_0401;
        cpu.loadRegisters(registers);
        interrupts.writeMask(1);
        interrupts.raise(0);

        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0, cpu.register(9));
        Assertions.assertEquals(CpuException.INTERRUPT.code() << 2, cpu.cop0().cause() & 0x7C);
        Assertions.assertEquals(0x8000_0080, cpu.pc());
    }

    @Test
    void pendingMtc0StatusEffectsSurviveCpuStateRoundTrip() {
        var source = createCpuWithWarmInstructionCache(
            0x4088_6000, // mtc0 t0, Status
            0x0000_0000,
            0x0000_0000,
            0x0000_0000
        );
        int[] registers = source.copyRegisters();
        registers[8] = source.cop0().status() | (1 << 16);
        source.loadRegisters(registers);
        source.step();

        R3000Cpu.State state = source.copyState();
        int[] cop0State = source.cop0().copyRawRegisters();
        var restored = createCpuWithWarmInstructionCache(0x4088_6000, 0, 0, 0);
        restored.cop0().loadRawRegisters(cop0State);
        restored.loadState(state);

        restored.step();
        Assertions.assertEquals(1 << 16, restored.copyState().effectiveCop0Status & (1 << 16));
    }

    @Test
    void immediateCop2ReadStallsForRemainingGteCommandCycles() {
        var cpu = createCpuWithWarmInstructionCache(
            0x4A00_0428, // SQR
            0x4808_4800  // mfc2 t0, IR1
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(6, cpu.step());
    }

    @Test
    void cop2ReadAfterCompletedCommandDoesNotPayTheInterlockPenalty() {
        var cpu = createCpuWithWarmInstructionCache(
            0x4A00_0428, // SQR
            0x0000_0000,
            0x0000_0000,
            0x0000_0000,
            0x0000_0000,
            0x4808_4800  // mfc2 t0, IR1
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals(1, cpu.step());
        }
        Assertions.assertEquals(1, cpu.step());
    }

    @Test
    void cop2ReadOnTheFinalBusyCyclePaysTheInterlockPenalty() {
        var cpu = createCpuWithWarmInstructionCache(
            0x4A00_002E, // AVSZ4 (6 clocks)
            0x0000_0000,
            0x0000_0000,
            0x0000_0000,
            0x0000_0000,
            0x4808_4800  // mfc2 t0, IR1
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals(1, cpu.step());
        }
        Assertions.assertEquals(3, cpu.step());
    }

    @Test
    void everyOfficialGteCommandExposesItsHardwareLatencyToTheCpu() {
        int[][] commandsAndCycles = {
            {0x0180_0001, 15}, // RTPS
            {0x1400_0006, 8},  // NCLIP
            {0x1580_002D, 5},  // AVSZ3
            {0x1680_002E, 6},  // AVSZ4
            {0x1700_000C, 6},  // OP
            {0x1900_003D, 5},  // GPF
            {0x1A00_003E, 5},  // GPL
            {0x0280_0030, 23}, // RTPT
            {0x0C80_041E, 14}, // NCS
            {0x0D80_0420, 30}, // NCT
            {0x1080_041B, 17}, // NCCS
            {0x1180_043F, 39}, // NCCT
            {0x1380_041C, 11}, // CC
            {0x0780_0010, 8},  // DPCS
            {0x0F80_002A, 17}, // DPCT
            {0x0980_0011, 8},  // INTPL
            {0x1280_0414, 13}, // CDP
            {0x0E80_0413, 19}, // NCDS
            {0x0F80_0416, 44}, // NCDT
            {0x0680_0029, 8},  // DCPL
            {0x0400_0012, 8},  // MVMVA
            {0x0A00_0428, 5}   // SQR
        };

        for (int[] commandAndCycles : commandsAndCycles) {
            int command = commandAndCycles[0];
            int expectedCycles = commandAndCycles[1];
            var cpu = createCpuWithWarmInstructionCache(
                0x4A00_0000 | (command & 0x01FF_FFFF),
                0x4808_4800 // mfc2 t0, IR1
            );
            cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

            int commandCycles = cpu.step();
            int dependentReadCycles = cpu.step();

            Assertions.assertEquals(1, commandCycles,
                () -> "GTE command 0x" + Integer.toHexString(command));
            Assertions.assertEquals(expectedCycles + 2, commandCycles + dependentReadCycles,
                () -> "GTE command 0x" + Integer.toHexString(command));
        }
    }

    @Test
    void mtc2WriteIsNotVisibleToImmediatelyFollowingMfc2() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0007, // addiu t0, zero, 7
            0x4888_4800, // mtc2 t0, IR1
            0x4809_4800, // mfc2 t1, IR1
            0x0000_0000  // nop
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(1, cpu.step());

        Assertions.assertEquals(0, cpu.register(9));
    }

    @Test
    void mtc2UpdatesGteInputRegisterOnItsSecondCachedClock() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0007, // addiu t0, zero, 7
            0x4888_4800, // mtc2 t0, IR1
            0x0000_0000, // second GTE write-path clock (issue was the first)
            0x0000_0000
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        cpu.step();
        cpu.step();
        Assertions.assertEquals(0, cpu.gte().readData(9));
        Assertions.assertEquals(1, cpu.copyState().pendingGteWriteCount);

        cpu.step();
        Assertions.assertEquals(7, cpu.gte().readData(9));
        Assertions.assertEquals(0, cpu.copyState().pendingGteWriteCount);
    }

    @Test
    void gteCommandImmediatelyAfterMtc2UsesTheOldInput() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0007, // addiu t0, zero, 7
            0x4888_4800, // mtc2 t0, IR1
            0x4A00_0428  // SQR, lm=1, sf=0
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(0, cpu.gte().readData(9));
    }

    @Test
    void gteCommandAfterTwoCachedClocksUsesTheNewMtc2Input() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0007, // addiu t0, zero, 7
            0x4888_4800, // mtc2 t0, IR1
            0x0000_0000, // first GTE write-delay clock
            0x0000_0000, // second GTE write-delay clock
            0x4A00_0428  // SQR, lm=1, sf=0
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(49, cpu.gte().readData(9));
    }

    @Test
    void inFlightSqrUsesIr3WrittenBeforeItsHardwareLatchBoundary() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0003, // addiu t0, zero, 3
            0x4A00_0028, // SQR, sf=0, lm=0
            0x4888_5800, // mtc2 t0, IR3
            0x4809_D800, // mfc2 t1, MAC3
            0x0000_0000  // load-delay nop
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));
        cpu.gte().writeData(11, 2);

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();

        Assertions.assertEquals(9, cpu.register(9));
    }

    @Test
    void inFlightSqrIgnoresIr3WrittenAfterItsHardwareLatchBoundary() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0003, // addiu t0, zero, 3
            0x4A00_0028, // SQR, sf=0, lm=0
            0x0000_0000, // boundary-crossing nop
            0x4888_5800, // mtc2 t0, IR3
            0x4809_D800, // mfc2 t1, MAC3
            0x0000_0000  // load-delay nop
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));
        cpu.gte().writeData(11, 2);

        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        Assertions.assertEquals(1, cpu.copyState().pendingGteWriteCount);
        cpu.step();
        cpu.step();

        Assertions.assertEquals(4, cpu.register(9));
        Assertions.assertEquals(3, cpu.gte().readData(11));
        Assertions.assertEquals(0, cpu.copyState().pendingGteWriteCount);
    }

    @Test
    void mtc2ToIrgbExposesBlueBeforeRedAndGreen() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0C41, // addiu t0, zero, blue=3/green=2/red=1 in RGB555
            0x4888_E000, // mtc2 t0, IRGB
            0x0000_0000, // second write-path clock: IR3 becomes visible
            0x0000_0000, // third write-path clock: IR1/IR2 become visible
            0x0000_0000
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        cpu.step();
        cpu.step();
        Assertions.assertEquals(0, cpu.gte().readData(9));
        Assertions.assertEquals(0, cpu.gte().readData(10));
        Assertions.assertEquals(0, cpu.gte().readData(11));

        cpu.step();
        Assertions.assertEquals(0, cpu.gte().readData(9));
        Assertions.assertEquals(0, cpu.gte().readData(10));
        Assertions.assertEquals(0x180, cpu.gte().readData(11));

        cpu.step();
        Assertions.assertEquals(0x80, cpu.gte().readData(9));
        Assertions.assertEquals(0x100, cpu.gte().readData(10));
        Assertions.assertEquals(0x180, cpu.gte().readData(11));
    }

    @Test
    void ctc2UpdatesGteControlRegisterOnItsSecondCachedClock() {
        var cpu = createCpuWithWarmInstructionCache(
            0x2408_0007, // addiu t0, zero, 7
            0x48C8_0000, // ctc2 t0, RT11RT12
            0x0000_0000, // second GTE write-path clock (issue was the first)
            0x0000_0000
        );
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        cpu.step();
        cpu.step();
        Assertions.assertEquals(0, cpu.gte().readControl(0));
        Assertions.assertEquals(1, cpu.copyState().pendingGteWriteCount);

        cpu.step();
        Assertions.assertEquals(7, cpu.gte().readControl(0));
        Assertions.assertEquals(0, cpu.copyState().pendingGteWriteCount);
    }

    @Test
    void lwc2ToIrgbStartsItsGteWriteDelayAfterTheMemoryReadCompletes() {
        Bus bus = new Bus();
        int[] instructions = {
            0x2408_0100, // addiu t0, zero, 0x100
            0xC91C_0000, // lwc2 IRGB, 0(t0)
            0x0000_0000, // second write-path clock: IR3 becomes visible
            0x0000_0000, // third write-path clock: IR1/IR2 become visible
            0x0000_0000,
            0x4809_4800, // mfc2 t1, IR1
            0x0000_0000  // load-delay nop
        };
        for (int i = 0; i < instructions.length; i++) {
            bus.write32(i * 4, instructions[i]);
        }
        bus.write32(0x0000_0100, 0x0000_0001);
        bus.fetchInstruction(0x0000_0000, false);
        bus.fetchInstruction(0x0000_0010, false);
        var cpu = new R3000Cpu(bus);
        cpu.reset(0x0000_0000);
        cpu.cop0().writeRegister(12, cpu.cop0().readRegister(12) | (1 << 30));

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertTrue(cpu.step() > 1);
        Assertions.assertEquals(0, cpu.gte().readData(9));

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(0, cpu.gte().readData(9));
        Assertions.assertEquals(0, cpu.gte().readData(11));

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(0x80, cpu.gte().readData(9));
        Assertions.assertEquals(0, cpu.gte().readData(10));
        Assertions.assertEquals(0, cpu.gte().readData(11));

        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(1, cpu.step());
        Assertions.assertEquals(1, cpu.step());

        Assertions.assertEquals(0x80, cpu.register(9));
    }

    @Test
    void rootCounterReadSamplesAtPendingLoadCompletion() {
        var bus = new Bus();
        var timers = new TimerController(new InterruptController());
        bus.setTimerController(timers);
        var cpu = createCpu(bus,
            0x3C08_1F80, // lui t0, 0x1f80
            0x8509_1120, // lh t1, 0x1120(t0)
            0x0000_0000, // load-delay slot
            0x0000_0000,
            0x0000_0000,
            0x0000_0000  // nop
        );
        cpu.setCycleAdvancer(cycles -> {
            timers.tick(cycles);
            return cycles;
        });

        int firstStepCycles = cpu.step();
        int expectedTimerValueAtRead = firstStepCycles + 5;
        cpu.step();
        for (int i = 0; i < 4; i++) {
            cpu.step();
        }

        Assertions.assertEquals(expectedTimerValueAtRead, cpu.register(9));
    }

    @Test
    void softwareExceptionsPreserveTheR3000StatusPipelineValue() {
        int[] secondInputValues = {0, 0xF000_0000, 1, 0xF000_0004, 0x10};
        int[] expectedStatusValues = {0, 0xF000_0000, 1, 0xF000_0014, 0};
        int[][] loadAndMtc0Registers = {
            {0, 0, 0, 0},
            {8, 8, 8, 8},
            {0, 8, 8, 0},
            {8, 0, 0, 8},
            {8, 9, 9, 8}
        };
        for (int exceptionCode : new int[] {
            CpuException.BREAKPOINT.code(), CpuException.SYSCALL.code()
        }) {
            int trapInstruction = 0x0115_9E04 | exceptionCode;
            for (int valueIndex = 0; valueIndex < secondInputValues.length; valueIndex++) {
                for (int patternIndex = 0; patternIndex < loadAndMtc0Registers.length; patternIndex++) {
                    int expectedStatus = patternIndex == 1 || patternIndex == 4
                        ? expectedStatusValues[valueIndex]
                        : 0;
                    int[] pattern = loadAndMtc0Registers[patternIndex];
                    assertSoftwareExceptionStatus(
                        trapInstruction,
                        exceptionCode,
                        secondInputValues[valueIndex],
                        pattern[0],
                        pattern[1],
                        pattern[2],
                        pattern[3],
                        expectedStatus
                    );
                }
            }
        }
    }

    private static void assertSoftwareExceptionStatus(
        int trapInstruction,
        int expectedCode,
        int secondInputValue,
        int firstLoadRegister,
        int secondLoadRegister,
        int mtc0Register,
        int mfc0Register,
        int expectedStatus
    ) {
        Bus bus = new Bus();
        int[] program = {
            0x8C80_0000 | (firstLoadRegister << 16),
            0x8C80_0004 | (secondLoadRegister << 16),
            0x0000_7821, // addu t7, zero, zero
            0x4080_6000 | (mtc0Register << 16),
            0x0000_0000, // nop
            trapInstruction,
            0x4000_6000 | (mfc0Register << 16),
            0x0000_0000, // load-delay nop
            0x1000_FFFF, // loop here
            0x0000_0000  // branch-delay nop
        };
        int[] handler = {
            0x401A_6800, // mfc0 k0, Cause
            0x0000_0000, // load-delay nop
            0x001A_7882, // srl t7, k0, 2
            0x31EF_001F, // andi t7, t7, 0x1f
            0x401B_7000, // mfc0 k1, EPC
            0x0000_0000, // load-delay nop
            0x277B_0004, // addiu k1, k1, 4
            0x0360_0008, // jr k1
            0x4200_0010  // rfe
        };
        for (int i = 0; i < program.length; i++) {
            bus.write32(0x100 + i * 4, program[i]);
        }
        for (int i = 0; i < handler.length; i++) {
            bus.write32(0x80 + i * 4, handler[i]);
        }
        bus.write32(0x300, 0xDEAD_BEEF);
        bus.write32(0x304, secondInputValue);
        for (int address = 0x80; address < 0x80 + handler.length * 4; address += 16) {
            bus.fetchInstruction(address, false);
        }
        for (int address = 0x100; address < 0x100 + program.length * 4; address += 16) {
            bus.fetchInstruction(address, false);
        }

        R3000Cpu cpu = new R3000Cpu(bus);
        int[] registers = cpu.copyRegisters();
        registers[4] = 0x300;
        cpu.loadRegisters(registers);
        cpu.setPcState(0x100, 0x104, false);
        cpu.cop0().writeRegister(12, 0);
        for (int i = 0; i < 20; i++) {
            cpu.step();
        }

        String context = "exception=" + expectedCode
            + ", secondInput=0x" + Integer.toHexString(secondInputValue)
            + ", loads=" + firstLoadRegister + "/" + secondLoadRegister
            + ", mtc0=" + mtc0Register + ", mfc0=" + mfc0Register;
        Assertions.assertEquals(expectedCode, cpu.register(15), context);
        Assertions.assertEquals(expectedStatus, cpu.register(mfc0Register), context);
    }

    private static R3000Cpu createCpu(int... instructions) {
        return createCpu(new Bus(), instructions);
    }

    private static R3000Cpu createCpuWithWarmInstructionCache(int... instructions) {
        Bus bus = new Bus();
        for (int i = 0; i < instructions.length; i++) {
            bus.write32(i * 4, instructions[i]);
        }
        for (int address = 0; address < instructions.length * 4; address += 16) {
            bus.fetchInstruction(address, false);
        }
        var cpu = new R3000Cpu(bus);
        cpu.reset(0x0000_0000);
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));
        return cpu;
    }

    private static R3000Cpu createCpu(Bus bus, int... instructions) {
        for (int i = 0; i < instructions.length; i++) {
            bus.write32(i * 4, instructions[i]);
        }
        var cpu = new R3000Cpu(bus);
        cpu.reset(0x0000_0000);
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));
        return cpu;
    }

    private static void put32(byte[] bios, int offset, int value) {
        bios[offset] = (byte) value;
        bios[offset + 1] = (byte) (value >>> 8);
        bios[offset + 2] = (byte) (value >>> 16);
        bios[offset + 3] = (byte) (value >>> 24);
    }
}
