package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.core.BiosImage;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.TimerController;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BusArchitectureTest {

    @Test
    void timerWordWriteIsOneOnDieRegisterTransaction() {
        Bus bus = new Bus();
        TimerController timers = new TimerController(new InterruptController());
        bus.setTimerController(timers);

        bus.write32(0x1F80_1114, 0xA5A5_0100);

        assertEquals(0x0100, timers.read16(0x1F80_1114) & 0x03FF);
        assertEquals(0x0100, bus.read32(0x1F80_1114) & 0x03FF);
    }

    @Test
    void expansion2WindowDefaultsTo128Bytes() {
        Bus bus = new Bus();

        assertTrue(bus.canReadData(0x1F80_207F));
        assertFalse(bus.canReadData(0x1F80_2080));
    }

    @Test
    void expansion1BaseIsAlignedToConfiguredWindowSize() {
        Bus bus = new Bus();

        bus.write32(0x1F80_1008, 0x0008_0000);
        bus.write32(0x1F80_1000, 0x1F00_00FF);

        assertEquals(0x1F00_00FF, bus.read32(0x1F80_1000));
        assertTrue(bus.canReadData(0x1F00_0000));
        assertTrue(bus.canReadData(0x1F00_00FF));
        assertFalse(bus.canReadData(0x1F00_0100));
    }

    @Test
    void expansion2IsDisabledWhenBaseIsNotHardwareDefault() {
        Bus bus = new Bus();

        assertTrue(bus.canReadData(0x1F80_2000));

        bus.write32(0x1F80_1004, 0x1F80_2100);

        assertEquals(0x1F80_2100, bus.read32(0x1F80_1004));
        assertFalse(bus.canReadData(0x1F80_2000));
        assertFalse(bus.canReadData(0x1F80_2100));
    }

    @Test
    void cacheControlCanUnmapScratchpad() {
        Bus bus = new Bus();

        assertTrue(bus.canReadData(0x1F80_0000));
        bus.write32(0xFFFE_0130, 0x0000_1E10);
        assertFalse(bus.canReadData(0x1F80_0000));
    }

    @Test
    void memoryControlRegistersSupportLittleEndianPartialAccess() {
        Bus bus = new Bus();

        assertEquals(0x0000, bus.read16(0x1F80_1000));
        assertEquals(0x1F00, bus.read16(0x1F80_1002));
        assertEquals(0x1F, bus.read8(0x1F80_1003));

        bus.write16(0x1F80_1020, 0x132C);
        assertEquals(0x0000_132C, bus.read32(0x1F80_1020));

        bus.write8(0x1F80_1021, 0x31);
        assertEquals(0x0000_3100, bus.read32(0x1F80_1020));
    }

    @Test
    void memoryControlBaseRegistersKeepFixedUpperByteOnPartialWrites() {
        Bus bus = new Bus();

        bus.write16(0x1F80_1002, 0xABCD);

        assertEquals(0x1FCD_0000, bus.read32(0x1F80_1000));
    }

    @Test
    void cacheControlSupportsPartialAccessAndKeepsBit6Cleared() {
        Bus bus = new Bus();

        bus.write8(0xFFFE_0130, 0xFF);

        assertEquals(0xBF, bus.read8(0xFFFE_0130));
        assertEquals(0x0000_00BF, bus.read32(0xFFFE_0130));
    }

    @Test
    void cacheControlIgnoresUnalignedPartialWrites() {
        Bus bus = new Bus();
        int original = bus.read32(0xFFFE_0130);

        bus.write8(0xFFFE_0131, 0);
        bus.write16(0xFFFE_0132, 0);

        assertEquals(original, bus.read32(0xFFFE_0130));
    }

    @Test
    void expansion3WindowDefaultsToOneByte() {
        Bus bus = new Bus();

        assertTrue(bus.canReadData(0x1FA0_0000));
        assertFalse(bus.canReadData(0x1FA0_0001));
        assertTrue(bus.canReadData(0x1FA0_0000, 4));
        assertFalse(bus.canFetchInstruction(0x1FA0_0000));
    }

    @Test
    void dmaRamValidationUsesConfiguredRamWindow() {
        Bus bus = new Bus();

        assertTrue(bus.isDmaRamAddress(0x007F_FFFF));
        assertFalse(bus.isDmaRamAddress(0x0080_0000));

        bus.write32(0x1F80_1060, 0x0000_0888);

        assertTrue(bus.isDmaRamAddress(0x001F_FFFF));
        assertFalse(bus.isDmaRamAddress(0x0020_0000));
    }

    @Test
    void dmaAndSpuRegistersAreExecutableButOtherIoIsNot() {
        Bus bus = new Bus();

        assertTrue(bus.canFetchInstruction(0x1F80_1084));
        assertTrue(bus.canFetchInstruction(0x1F80_10F0));
        assertTrue(bus.canFetchInstruction(0x1F80_1C00));
        assertTrue(bus.canFetchInstruction(0x1F80_1FFC));
        assertFalse(bus.canFetchInstruction(0x1F80_1820));
        assertFalse(bus.canFetchInstruction(0x1F80_1070));
        assertFalse(bus.canFetchInstruction(0x1F80_0000));
    }

    @Test
    void interruptMaskDropsUnimplementedBitsElevenThroughFifteen() {
        Bus bus = new Bus();
        InterruptController interrupts = new InterruptController();
        bus.setInterruptController(interrupts);

        bus.write32(0x1F80_1074, 0xFFFF_FFFF);

        assertEquals(0x0000_07FF, interrupts.mask());
        assertEquals(0x0000_07FF, bus.read32(0x1F80_1074));
    }

    @Test
    void onDiePartialStoresLatchShiftedFullGprWithoutMerging() {
        Bus bus = new Bus();
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        bus.setInterruptController(interrupts);
        bus.setDma(dma);

        bus.write32(0x1F80_10F0, 0x1122_3344);
        bus.write8(0x1F80_10F1, 0xAABB_CCDD);
        assertEquals(0xBBCC_DD00, bus.read32(0x1F80_10F0));

        bus.write16(0x1F80_10F0, 0xAABB_CCDD);
        assertEquals(0xAABB_CCDD, bus.read32(0x1F80_10F0));

        bus.write16(0x1F80_10F2, 0xAABB_CCDD);
        assertEquals(0xCCDD_0000, bus.read32(0x1F80_10F0));

        bus.completeCpuWrite24(0x1F80_10F0, 0x00AA_BBCC);
        assertEquals(0x00AA_BBCC, bus.read32(0x1F80_10F0));
        bus.completeCpuWrite24(0x1F80_10F1, 0xAABB_CCDD);
        assertEquals(0xBBCC_DD00, bus.read32(0x1F80_10F0));
    }

    @Test
    void interruptMaskPartialStoresUseTheSameOnDieBusRule() {
        Bus bus = new Bus();
        InterruptController interrupts = new InterruptController();
        bus.setInterruptController(interrupts);

        bus.write8(0x1F80_1075, 0xAABB_CCDD);
        assertEquals(0x500, interrupts.mask());

        bus.write16(0x1F80_1074, 0xAABB_CCDD);
        assertEquals(0x4DD, interrupts.mask());

        bus.write16(0x1F80_1076, 0xAABB_CCDD);
        assertEquals(0, interrupts.mask());
    }

    @Test
    void unusedSpuWindowReadsAsFfInsteadOfOpenBus() {
        Bus bus = new Bus();

        bus.write32(0x0000_0000, 0x1234_5678);
        assertEquals(0xFF, bus.read8(0x1F80_1E80));
        bus.write32(0x0000_0000, 0x1234_5678);
        assertEquals(0xFF, bus.read8(0x1F80_1FFF));

        bus.write32(0x0000_0000, 0x1234_5678);
        assertEquals(0xFFFF, bus.read16(0x1F80_1E80));
        bus.write32(0x0000_0000, 0x1234_5678);
        assertEquals(0xFFFF, bus.read16(0x1F80_1FFE));

        bus.write32(0x0000_0000, 0x1234_5678);
        assertEquals(0xFFFF_FFFF, bus.read32(0x1F80_1E80));
        bus.write32(0x0000_0000, 0x1234_5678);
        assertEquals(0xFFFF_FFFF, bus.read32(0x1F80_1FFC));
    }

    @Test
    void timerGarbageAndCpuControlGarbageStayUnlocked() {
        Bus bus = new Bus();

        assertTrue(bus.canReadData(0x1F80_113F));
        assertFalse(bus.canReadData(0x1F80_1140));
        assertTrue(bus.canReadData(0xFFFE_0000));
        assertTrue(bus.canReadData(0xFFFE_013F));
        assertFalse(bus.canReadData(0xFFFE_0140));
    }

    @Test
    void dataCacheModeFillsOneWholeWordIntoScratchpad() {
        Bus bus = new Bus();
        bus.write32(0x0000_0020, 0x4433_2211);
        int bcc = bus.read32(0xFFFE_0130);
        bus.write32(0xFFFE_0130, (bcc & ~(1 << 3)) | (1 << 7));

        assertEquals(0x22, bus.read8(0x0000_0021));

        byte[] scratchpad = bus.copyScratchpad();
        assertEquals(0x11, Byte.toUnsignedInt(scratchpad[0x20]));
        assertEquals(0x22, Byte.toUnsignedInt(scratchpad[0x21]));
        assertEquals(0x33, Byte.toUnsignedInt(scratchpad[0x22]));
        assertEquals(0x44, Byte.toUnsignedInt(scratchpad[0x23]));
    }

    @Test
    void instructionFetchIsUnaffectedByCop0CacheIsolation() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0001);
        bus.setCacheIsolated(true);

        assertEquals(0x2408_0001, bus.fetchInstruction(0x0000_0000, true));
    }

    @Test
    void cacheIsolatedDataAccessBypassesExternalBusTiming() {
        Bus bus = new Bus();

        assertTrue(bus.cpuAccessCycles(0x0000_0000, false, 4) > 0);

        bus.setCacheIsolated(true);

        assertEquals(0, bus.cpuAccessCycles(0x0000_0000, false, 4));
        assertEquals(0, bus.cpuAccessCycles(0x0000_0000, true, 4));
    }

    @Test
    void cacheIsolationAcceptsCachedAddressesWithoutAnExternalResponder() {
        Bus bus = new Bus();

        assertFalse(bus.canReadData(0x0100_0000));
        assertFalse(bus.canWriteData(0x0100_0000));

        bus.setCacheIsolated(true);

        assertTrue(bus.canReadData(0x0100_0000));
        assertTrue(bus.canWriteData(0x0100_0000));
    }

    @Test
    void memoryDelayAddressErrorFlagCannotBeSetBySoftware() {
        Bus bus = new Bus();

        bus.write32(0x1F80_1010, 1 << 28);

        assertEquals(0, bus.read32(0x1F80_1010) & (1 << 28));
    }

    @Test
    void cachedInstructionFetchKeepsStaleLineUntilCacheIsInvalidated() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0001);

        assertEquals(0x2408_0001, bus.fetchInstruction(0x0000_0000, false));
        assertTrue(bus.lastInstructionFetchExtraCycles() > 0);

        bus.write32(0x0000_0000, 0x2408_0002);

        assertEquals(0x2408_0001, bus.fetchInstruction(0x0000_0000, false));
        assertEquals(0, bus.lastInstructionFetchExtraCycles());
        assertEquals(0x2408_0002, bus.fetchInstruction(0xA000_0000, false));
    }

    @Test
    void hostExecutableLoadCanInvalidateOnlyItsInstructionCacheRange() {
        Bus bus = new Bus();
        bus.write32(0xA000_0000, 0x2408_0001);
        bus.write32(0xA000_0010, 0x2409_0001);
        assertEquals(0x2408_0001, bus.fetchInstruction(0x8000_0000, false));
        assertEquals(0x2409_0001, bus.fetchInstruction(0x8000_0010, false));

        bus.write32(0xA000_0000, 0x2408_0002);
        bus.write32(0xA000_0010, 0x2409_0002);
        bus.invalidateInstructionCacheRange(0x8000_0000, 4);

        assertEquals(0x2408_0002, bus.fetchInstruction(0x8000_0000, false));
        assertEquals(0x2409_0001, bus.fetchInstruction(0x8000_0010, false));
    }

    @Test
    void ramInstructionCacheRefillUsesOneCycleBurstWords() {
        Bus bus = new Bus();
        for (int i = 0; i < 4; i++) {
            bus.write32(i * 4, 0x2408_0000 | i);
        }

        assertEquals(0x2408_0000, bus.fetchInstruction(0x0000_0000, false));
        assertEquals(4, bus.lastInstructionFetchExtraCycles());
        assertEquals(0x2408_0003, bus.fetchInstruction(0x0000_000C, false));
        assertEquals(0, bus.lastInstructionFetchExtraCycles());
    }

    @Test
    void instructionCacheMissWaitsForOverlappingQueuedStore() {
        Bus bus = new Bus();
        int exceptionVector = 0x8000_0080;

        assertEquals(0, bus.cpuAccessCycles(exceptionVector, true, 4));
        bus.advanceCpuCycles(1);
        bus.completeCpuWrite32(exceptionVector, 0x4200_0010);

        assertEquals(0x4200_0010, bus.fetchInstruction(exceptionVector, false));
        assertTrue(bus.lastInstructionFetchExtraCycles() > 4);
    }

    @Test
    void cacheIsolatedTagWriteInvalidatesInstructionCacheWithoutTouchingRam() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0001);
        assertEquals(0x2408_0001, bus.fetchInstruction(0x0000_0000, false));

        bus.write32(0xFFFE_0130, bus.read32(0xFFFE_0130) | (1 << 2));
        bus.setCacheIsolated(true);
        bus.completeCpuWrite32(0x0000_0000, 0);
        bus.setCacheIsolated(false);

        bus.write32(0x0000_0000, 0x2408_0002);

        assertEquals(0x2408_0002, bus.fetchInstruction(0x0000_0000, false));
        assertTrue(bus.lastInstructionFetchExtraCycles() > 0);
    }

    @Test
    void busStateRestoresInstructionCacheAndControlRegisters() {
        Bus bus = new Bus();
        bus.write32(0x0000_0000, 0x2408_0001);
        assertEquals(0x2408_0001, bus.fetchInstruction(0x0000_0000, false));
        bus.write32(0x0000_0000, 0x2408_0002);
        bus.write32(0x1F80_1020, 0x0000_132C);
        Bus.State state = bus.copyState();

        Bus restored = new Bus();
        restored.loadRam(bus.copyRam());
        restored.loadState(state);

        assertEquals(0x2408_0001, restored.fetchInstruction(0x0000_0000, false));
        assertEquals(0, restored.lastInstructionFetchExtraCycles());
        assertEquals(0x0000_132C, restored.read32(0x1F80_1020));
    }

    @Test
    void writeQueueIsEnabledForCachedSegmentsAndFlushedByKseg1Access() {
        Bus baseline = new Bus();
        int uncachedReadCycles = baseline.cpuAccessCycles(0xA000_0000, false, 4);

        Bus bus = new Bus();
        bus.cpuAccessCycles(0x0000_0000, true, 4);
        bus.cpuAccessCycles(0x8000_0004, true, 4);

        assertTrue(bus.cpuAccessCycles(0xA000_0000, false, 4) > uncachedReadCycles);
    }

    @Test
    void kseg1WritesBypassWriteQueue() {
        Bus baseline = new Bus();
        int uncachedReadCycles = baseline.cpuAccessCycles(0xA000_0000, false, 4);

        Bus bus = new Bus();
        bus.cpuAccessCycles(0xA000_0000, true, 4);

        assertEquals(uncachedReadCycles, bus.cpuAccessCycles(0xA000_0000, false, 4));
    }

    @Test
    void cachedStoresIssueThroughFourEntryWriteQueueAndFifthStoreStalls() {
        Bus bus = new Bus();

        for (int i = 0; i < 4; i++) {
            assertEquals(0, bus.cpuAccessCycles(i * 4, true, 4));
        }

        assertTrue(bus.cpuAccessCycles(0x10, true, 4) > 0);
    }

    @Test
    void fifthIssuedStoreWaitsOnlyForTheOldestBufferedStore() {
        Bus bus = new Bus();
        for (int i = 0; i < 4; i++) {
            assertEquals(0, bus.beginCpuWriteExtraCycles(i * 4, i + 1, 4));
            bus.completeCpuWrite32(i * 4, i + 1);
        }

        int stall = bus.beginCpuWriteExtraCycles(0x10, 5, 4);
        assertTrue(stall > 0);
        bus.advanceCpuCycles(stall);
        while ((stall = bus.pendingCpuWriteStallCycles()) > 0) {
            bus.advanceCpuCycles(stall);
        }
        bus.completeCpuWrite32(0x10, 5);

        assertEquals(1, bus.read32(0));
        assertEquals(0, bus.read32(4));
        assertEquals(0, bus.read32(0x10));

        bus.advanceCpuCycles(8);
        assertEquals(2, bus.read32(4));
        assertEquals(3, bus.read32(8));
        assertEquals(4, bus.read32(12));
        assertEquals(5, bus.read32(0x10));
    }

    @Test
    void cachedReadOfQueuedAddressWaitsForWriteQueue() {
        Bus baseline = new Bus();
        int ramReadCycles = baseline.cpuAccessCycles(0x0000_2000, false, 4);

        Bus bus = new Bus();
        assertEquals(0, bus.cpuAccessCycles(0x0000_2000, true, 4));
        bus.advanceCpuCycles(1);

        assertTrue(bus.cpuAccessCycles(0x8000_2000, false, 4) > ramReadCycles);
    }

    @Test
    void readOfAnotherByteInTheSameWordConflictsWithQueuedStore() {
        Bus baseline = new Bus();
        int readCycles = baseline.cpuAccessCycles(0x1F80_1803, false, 1);

        Bus bus = new Bus();
        bus.beginCpuWriteExtraCycles(0x1F80_1800, 1, 1);
        bus.advanceCpuCycles(1);

        assertTrue(bus.cpuAccessCycles(0x1F80_1803, false, 1) > readCycles);
    }

    @Test
    void queuedStoreBecomesVisibleWhenBusCompletesIt() {
        Bus bus = new Bus();

        assertEquals(0, bus.cpuAccessCycles(0x0000_2000, true, 4));
        bus.advanceCpuCycles(1);
        bus.completeCpuWrite32(0x0000_2000, 0x1234_5678);
        assertEquals(0, bus.read32(0x0000_2000));

        bus.advanceCpuCycles(6);

        assertEquals(0x1234_5678, bus.read32(0x0000_2000));
    }

    @Test
    void issuedStoreCarriesItsValueBeforeTheQueueCanRetire() {
        Bus bus = new Bus();

        assertEquals(0, bus.beginCpuWriteExtraCycles(0x0000_2000, 0x1234_5678, 4));
        bus.advanceCpuCycles(6);
        assertEquals(0x1234_5678, bus.read32(0x0000_2000));

        bus.write32(0xA000_2000, 0xCAFE_BABE);
        bus.completeCpuWrite32(0x0000_2000, 0x1234_5678);
        assertEquals(0xCAFE_BABE, bus.read32(0x0000_2000));
    }

    @Test
    void kusegMmioStoreUsesTheSameOrderedWriteQueue() {
        Bus bus = new Bus();
        InterruptController interrupts = new InterruptController();
        bus.setInterruptController(interrupts);
        interrupts.raise(2);

        assertEquals(0, bus.beginCpuWriteExtraCycles(0x1F80_1070, 0, 4));
        assertEquals(1 << 2, interrupts.status());
        bus.advanceCpuCycles(3);
        bus.completeCpuWrite32(0x1F80_1070, 0);

        assertEquals(0, interrupts.status());
    }

    @Test
    void storeQueuedBeforeCacheIsolationStillCompletesToRam() {
        Bus bus = new Bus();

        assertEquals(0, bus.cpuAccessCycles(0x8000_2000, true, 4));
        bus.advanceCpuCycles(1);
        bus.completeCpuWrite32(0x8000_2000, 0x4A05_8001);

        bus.setCacheIsolated(true);
        bus.advanceCpuCycles(6);
        bus.setCacheIsolated(false);

        assertEquals(0x4A05_8001, bus.read32(0x0000_2000));
    }

    @Test
    void uncachedAccessFlushesAndCommitsQueuedStores() {
        Bus bus = new Bus();
        bus.cpuAccessCycles(0x0000_2000, true, 4);
        bus.advanceCpuCycles(1);
        bus.completeCpuWrite32(0x0000_2000, 0x1234_5678);

        bus.cpuAccessCycles(0xA000_1000, false, 4);

        assertEquals(0x1234_5678, bus.read32(0x0000_2000));
    }

    @Test
    void hostMemoryReplacementCompletesOlderCpuStoresFirst() {
        Bus bus = new Bus();
        bus.cpuAccessCycles(0x0000_2000, true, 1);
        bus.advanceCpuCycles(1);
        bus.completeCpuWrite8(0x0000_2000, 0x8B);

        bus.completeCpuWritesBeforeHostMemoryReplacement();
        bus.write8(0xA000_2000, 0xFF);
        bus.advanceCpuCycles(8);

        assertEquals(0xFF, bus.read8(0x0000_2000));
    }

    @Test
    void dmaChannelControlIsMirroredAtRegisterC() {
        Bus bus = new Bus();
        DmaController dma = new DmaController(new InterruptController());
        bus.setDma(dma);

        bus.write32(0x1F80_108C, 0x1234_5678);

        assertEquals(0x1034_0600, bus.read32(0x1F80_1088));
        assertEquals(0x1034_0600, bus.read32(0x1F80_108C));
    }

    @Test
    void directOpenBusReadReturnsLastLatchedRamWrite() {
        Bus bus = new Bus();

        bus.write32(0x0000_1000, 0x1234_5678);

        assertEquals(0x1234_5678, bus.read32(0x1F80_2080));
        assertEquals(0x5678, bus.read16(0x1F80_2080));
        assertEquals(0x78, bus.read8(0x1F80_2080));
    }

    @Test
    void directOpenBusReadReturnsLastLatchedMappedRead() {
        Bus bus = new Bus();

        assertEquals(0x1F00_0000, bus.read32(0x1F80_1000));

        assertEquals(0x1F00_0000, bus.read32(0x1F80_2080));
    }

    @Test
    void biosCpuAccessTimingUsesMemoryControlAndAccessWidth() {
        Bus bus = new Bus();
        bus.setBios(new BiosImage(Path.of("dummy.bin"), ByteBuffer.allocate(512 * 1024)));

        int defaultByteRead = bus.cpuAccessCycles(0x1FC0_0000, false, 1);
        int defaultWordRead = bus.cpuAccessCycles(0x1FC0_0000, false, 4);

        assertEquals(18, defaultByteRead);
        assertEquals(72, defaultWordRead);

        bus.write32(0x1F80_1010, 0x0013_0000);

        assertEquals(defaultWordRead, bus.cpuAccessCycles(0x1FC0_0000, false, 4));
        assertEquals(defaultWordRead, bus.cpuAccessCycles(0x1FC0_0004, false, 4));

        int fastWordRead = bus.cpuAccessCycles(0x1FC0_0008, false, 4);
        assertTrue(fastWordRead < defaultWordRead);
    }

    @Test
    void internalIoCpuTimingIsSeparateFromExternalMemoryControl() {
        Bus bus = new Bus();

        int timerRead = bus.cpuAccessCycles(0x1F80_1100, false, 2);
        bus.write32(0x1F80_1010, 0x0013_0000);

        assertEquals(timerRead, bus.cpuAccessCycles(0x1F80_1100, false, 2));
    }

    @Test
    void spuRegisterTimingUsesItsSixteenBitHardwarePath() {
        Bus bus = new Bus();
        bus.write32(0x1F80_1014, 0x2009_31E1);
        bus.cpuAccessCycles(0x1F00_0000, false, 1);
        bus.cpuAccessCycles(0x1F00_0000, false, 1);

        assertEquals(17, bus.cpuAccessCycles(0x1F80_1DAA, false, 1));
        assertEquals(17, bus.cpuAccessCycles(0x1F80_1DAA, false, 2));
    }

    @Test
    void cacheIsolationRedirectsAllCpuDataSegmentsButNotFetchOrDmaTraffic() {
        Bus bus = new Bus();
        InterruptController interrupts = new InterruptController();
        bus.setInterruptController(interrupts);
        bus.write32(0x0000_0100, 0x1122_3344);

        assertEquals(0x1122_3344, bus.fetchInstruction(0x8000_0100, false));
        bus.write32(0x0000_0100, 0x5566_7788);
        bus.setCacheIsolated(true);

        Bus.CpuReadRequest uncachedAliasRead = bus.beginCpuRead(0xA000_0100, 4);
        assertEquals(0, uncachedAliasRead.extraCycles());
        assertEquals(0x1122_3344, bus.completeCpuRead(uncachedAliasRead));

        bus.completeCpuWrite32(0xBF80_1074, 0x0000_07FF);
        assertEquals(0, interrupts.mask());

        bus.completeCpuWrite32(0xA000_0100, 0xAABB_CCDD);

        assertEquals(0x5566_7788, bus.fetchInstruction(0xA000_0100, true));
        assertEquals(6, bus.lastInstructionFetchExtraCycles(),
            "IsC must not make an uncached instruction fetch look like an isolated data access");
        assertEquals(0x5566_7788, bus.read32(0x0000_0100));

        assertEquals(0xAABB_CCDD, bus.completeCpuRead(bus.beginCpuRead(0xA000_0100, 4)));
        bus.setCacheIsolated(false);
        assertEquals(0x5566_7788, bus.read32(0x0000_0100));
    }
}
