package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.TimerController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TimerControllerTest {

    @Test
    void counterWriteIntroducesHardwareStyleHoldCycles() {
        TimerController timers = new TimerController(new InterruptController());

        timers.write16(0x1F80_1124, 0x0000);
        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));

        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));

        timers.tick(1);
        assertEquals(1, timers.read16(0x1F80_1120));
    }

    @Test
    void targetValueIsObservableBeforeTwoCycleResetHold() {
        TimerController timers = new TimerController(new InterruptController());
        timers.write16(0x1F80_1128, 1);
        timers.write16(0x1F80_1124, 1 << 3);

        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));
        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));
        timers.tick(1);
        assertEquals(1, timers.read16(0x1F80_1120));

        int modeAtTarget = timers.read16(0x1F80_1124);
        assertTrue((modeAtTarget & (1 << 11)) != 0);
        assertEquals(0, timers.read16(0x1F80_1124) & (1 << 11));

        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));
        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));
        timers.tick(1);
        assertEquals(1, timers.read16(0x1F80_1120));
    }

    @Test
    void overflowValueIsObservableAndWrapHasOneZeroCycle() {
        TimerController timers = new TimerController(new InterruptController());
        timers.write16(0x1F80_1124, 0);
        timers.write16(0x1F80_1120, 0xFFFE);

        timers.tick(2);
        assertEquals(0xFFFE, timers.read16(0x1F80_1120));
        timers.tick(1);
        assertEquals(0xFFFF, timers.read16(0x1F80_1120));
        assertTrue((timers.read16(0x1F80_1124) & (1 << 12)) != 0);

        timers.tick(1);
        assertEquals(0, timers.read16(0x1F80_1120));
        timers.tick(1);
        assertEquals(1, timers.read16(0x1F80_1120));
    }

    @Test
    void counter2UsesDedicatedSyncSemantics() {
        TimerController timers = new TimerController(new InterruptController());

        timers.write16(0x1F80_1124, 0x0001);
        timers.tick(128);
        assertEquals(0, timers.read16(0x1F80_1120));

        timers.write16(0x1F80_1124, 0x0003);
        timers.tick(128);
        assertTrue(timers.read16(0x1F80_1120) > 0);

        timers.write16(0x1F80_1124, 0x0005);
        timers.tick(128);
        assertTrue(timers.read16(0x1F80_1120) > 0);

        timers.write16(0x1F80_1124, 0x0007);
        timers.tick(128);
        assertEquals(0, timers.read16(0x1F80_1120));
    }

    @Test
    void counter1Mode3StartsAfterFirstVblank() {
        Gpu gpu = new Gpu(new InterruptController());
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1114, 0x0007);

        for (int i = 0; i < 500_000; i++) {
            gpu.tick(1);
            timers.tick(1);
        }
        assertEquals(0, timers.read16(0x1F80_1110));

        while (!gpu.inVblank()) {
            gpu.tick(1);
            timers.tick(1);
        }
        for (int i = 0; i < 64; i++) {
            gpu.tick(1);
            timers.tick(1);
        }

        assertTrue(timers.read16(0x1F80_1110) > 0);
    }

    @Test
    void counter1Mode3AllowsBatchingAfterItsOneShotVblankGate() {
        Gpu gpu = new Gpu(new InterruptController());
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1114, 0x0107); // HBlank clock, wait for first VBlank.

        while (gpu.inVblank()) {
            tickOne(gpu, timers);
        }
        while (!gpu.inVblank()) {
            tickOne(gpu, timers);
        }
        for (int i = 0; i < 20_000; i++) {
            tickOne(gpu, timers);
        }

        int guard = 0;
        while (!timers.permitsBatchedGpuInterval(256) && guard++ < 100_000) {
            tickOne(gpu, timers);
        }

        assertTrue(timers.permitsBatchedGpuInterval(256),
            "mode 3 is permanently free-running after its first gate edge");
    }

    @Test
    void accumulatedGpuEdgesMatchFineGrainedTimerStepping() {
        Gpu exactGpu = new Gpu(new InterruptController());
        Gpu batchedGpu = new Gpu(new InterruptController());
        TimerController exact = new TimerController(new InterruptController());
        TimerController batched = new TimerController(new InterruptController());
        exact.setGpu(exactGpu);
        batched.setGpu(batchedGpu);
        exact.write16(0x1F80_1114, 0x0100); // HBlank source, no gate.
        batched.write16(0x1F80_1114, 0x0100);
        for (int i = 0; i < 20_000; i++) {
            tickOne(exactGpu, exact);
            tickOne(batchedGpu, batched);
        }

        int cycles = 256;
        assertTrue(batched.permitsBatchedGpuInterval(cycles));
        int dotTicks = 0;
        int hblankRises = 0;
        for (int i = 0; i < cycles; i++) {
            tickOne(exactGpu, exact);
            batchedGpu.tick(1);
            dotTicks += batchedGpu.dotClockTicksLastTick();
            hblankRises += batchedGpu.hblankRisesLastTick();
        }
        batched.tickBatchedGpuInterval(cycles, dotTicks, hblankRises);

        assertEquals(exact.read16(0x1F80_1110), batched.read16(0x1F80_1110));
        assertEquals(exact.read16(0x1F80_1100), batched.read16(0x1F80_1100));
        assertEquals(exact.read16(0x1F80_1120), batched.read16(0x1F80_1120));
    }

    @Test
    void counter0SyncModeOneResetsAtHblankStartButNotAtHblankEnd() {
        Gpu gpu = new Gpu(new InterruptController());
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);

        while (gpu.inHblank()) {
            tickOne(gpu, timers);
        }

        timers.write16(0x1F80_1104, 0x0003);
        timers.write16(0x1F80_1100, 0x4000);
        tickOne(gpu, timers);
        tickOne(gpu, timers);
        tickOne(gpu, timers);
        assertTrue(timers.read16(0x1F80_1100) > 0x4000);

        while (!gpu.inHblank()) {
            tickOne(gpu, timers);
        }
        assertEquals(1, timers.read16(0x1F80_1100),
            "mode 1 must reset on the rising HBlank edge");

        while (gpu.inHblank()) {
            tickOne(gpu, timers);
        }
        assertTrue(timers.read16(0x1F80_1100) > 1,
            "the falling HBlank edge must not reset mode 1");
    }

    @Test
    void hblankClockedCounterAllowsBatchesBeforeItsNextEdge() {
        Gpu gpu = new Gpu(new InterruptController());
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1118, 4);
        timers.write16(0x1F80_1114, 0x0158); // HBlank clock, reset/IRQ at target, repeat
        int guard = 0;
        while (timers.read16(0x1F80_1110) < 3) {
            tickOne(gpu, timers);
            assertTrue(++guard < 20_000);
        }

        while (gpu.hblankRisesWithinSystemClocks(256) != 0) {
            tickOne(gpu, timers);
        }
        assertTrue(timers.permitsBatchedGpuInterval(256));

        while (gpu.hblankRisesWithinSystemClocks(256) == 0) {
            tickOne(gpu, timers);
        }
        assertFalse(timers.permitsBatchedGpuInterval(256));
    }

    @Test
    void hblankClockedResetHoldConsumesOnlyHblankEdges() {
        Gpu gpu = new Gpu(new InterruptController());
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1118, 1);
        timers.write16(0x1F80_1114, 0x0148); // HBlank clock, reset at target

        int rises = 0;
        int guard = 0;
        while (rises < 3) {
            tickOne(gpu, timers);
            rises += gpu.hblankRisesLastTick();
            assertTrue(++guard < 20_000);
        }
        assertEquals(1, timers.read16(0x1F80_1110));
        tickOne(gpu, timers);
        tickOne(gpu, timers);
        assertEquals(0, timers.read16(0x1F80_1110));

        assertEquals(0, gpu.hblankRisesWithinSystemClocks(256));
        assertTrue(timers.permitsBatchedGpuInterval(256));
        gpu.tick(256);
        timers.tick(256);
        assertEquals(0, timers.read16(0x1F80_1110));

        while (gpu.hblankRisesWithinSystemClocks(1) == 0) {
            tickOne(gpu, timers);
            assertTrue(++guard < 40_000);
        }
        tickOne(gpu, timers);
        assertEquals(0, timers.read16(0x1F80_1110));
    }

    @Test
    void pulseIrqDropsModeBit10ForOnlyShortWindow() {
        InterruptController interrupts = new InterruptController();
        TimerController timers = new TimerController(interrupts);

        timers.write16(0x1F80_1108, 0x0001);
        timers.write16(0x1F80_1104, 0x0018);
        timers.tick(4);

        assertEquals(0, timers.read16(0x1F80_1104) & (1 << 10));
        assertEquals(1 << 4, interrupts.status() & (1 << 4));

        timers.tick(4);

        assertTrue((timers.read16(0x1F80_1104) & (1 << 10)) != 0);
    }

    @Test
    void repeatedToggleIrqRaisesOnlyOnEveryOtherTarget() {
        InterruptController interrupts = new InterruptController();
        TimerController timers = new TimerController(interrupts);

        timers.write16(0x1F80_1108, 1);
        timers.write16(0x1F80_1104, 0x00D8);
        timers.tick(3);

        assertEquals(0, timers.read16(0x1F80_1104) & (1 << 10));
        assertEquals(1 << 4, interrupts.status() & (1 << 4));
        interrupts.writeStatus(~(1 << 4));

        timers.tick(3);
        assertTrue((timers.read16(0x1F80_1104) & (1 << 10)) != 0);
        assertEquals(0, interrupts.status() & (1 << 4));

        timers.tick(3);
        assertEquals(0, timers.read16(0x1F80_1104) & (1 << 10));
        assertEquals(1 << 4, interrupts.status() & (1 << 4));
    }

    @Test
    void timer0DotClockUsesGpuResolutionDivider() {
        assertDotClockTicks(0x00, 700, 111); // 256 pixels: video clock / 10
        assertDotClockTicks(0x01, 560, 111); // 320 pixels: video clock / 8
        assertDotClockTicks(0x40, 490, 111); // 368 pixels: video clock / 7
        assertDotClockTicks(0x02, 350, 111); // 512 pixels: video clock / 5
        assertDotClockTicks(0x03, 280, 111); // 640 pixels: video clock / 4
    }

    @Test
    void gpuDropsDotClockFractionAtEveryNtscScanline() {
        assertCompletedScanlineDots(0x00, 341);
        assertCompletedScanlineDots(0x01, 426);
        assertCompletedScanlineDots(0x40, 487);
        assertCompletedScanlineDots(0x02, 682);
        assertCompletedScanlineDots(0x03, 853);
        assertCompletedScanlineDots(0x41, 487);
        assertCompletedScanlineDots(0x42, 487);
        assertCompletedScanlineDots(0x43, 487);
    }

    @Test
    void palThreeHundredTwentyModeUsesHardwareRoundUpException() {
        assertCompletedScanlineDots(0x08, 340);
        assertCompletedScanlineDots(0x09, 426);
        assertCompletedScanlineDots(0x48, 486);
        assertCompletedScanlineDots(0x0A, 681);
        assertCompletedScanlineDots(0x0B, 851);
    }

    @Test
    void snapshotRestoresCountersAndTransientIrqPulse() {
        InterruptController interrupts = new InterruptController();
        TimerController timers = new TimerController(interrupts);

        timers.write16(0x1F80_1108, 0x0002);
        timers.write16(0x1F80_1104, 0x0018);
        timers.tick(5);

        TimerController.State snapshot = timers.copyState();
        assertEquals(0, timers.read16(0x1F80_1104) & (1 << 10));
        assertEquals(1 << 4, interrupts.status() & (1 << 4));

        timers.tick(16);
        assertTrue((timers.read16(0x1F80_1104) & (1 << 10)) != 0);

        timers.loadState(snapshot);

        assertEquals(0, timers.read16(0x1F80_1104) & (1 << 10));
        timers.tick(16);
        assertTrue((timers.read16(0x1F80_1104) & (1 << 10)) != 0);
    }

    private static void assertDotClockTicks(int displayMode, int systemCycles, int expectedTicks) {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0000 | displayMode);
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1104, 1 << 8);

        int divider = switch ((displayMode & 3) | (((displayMode >>> 6) & 1) << 2)) {
            case 0 -> 10;
            case 1 -> 8;
            case 2 -> 5;
            case 3 -> 4;
            case 4 -> 7;
            default -> throw new AssertionError();
        };
        long numerator = gpu.crtcClockNumerator();
        long denominator = (long) gpu.crtcClockDenominator() * divider;
        tickMany(gpu, timers, (int) ((2 * denominator + numerator - 1) / numerator));
        assertEquals(0, timers.read16(0x1F80_1100));
        tickMany(gpu, timers, systemCycles);
        assertEquals(expectedTicks, timers.read16(0x1F80_1100));
        assertFalse((timers.read16(0x1F80_1104) & (1 << 12)) != 0);
    }

    private static void tickOne(Gpu gpu, TimerController timers) {
        gpu.tick(1);
        timers.tick(1);
    }

    private static void tickMany(Gpu gpu, TimerController timers, int cycles) {
        for (int i = 0; i < cycles; i++) {
            tickOne(gpu, timers);
        }
    }

    private static void assertCompletedScanlineDots(int displayMode, int expected) {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0000 | displayMode);
        int guard = 10_000;
        while (gpu.completedScanlineDotClockTicks() == 0 && guard-- > 0) {
            gpu.tick(1);
        }
        assertTrue(guard > 0, "scanline did not complete");
        assertEquals(expected, gpu.completedScanlineDotClockTicks());
    }
}
