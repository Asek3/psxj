package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.TimerController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GpuCrtcTimingTest {

    private static final int GPUSTAT_FIELD_BIT = 1 << 13;
    private static final int GPUSTAT_DISPLAY_LINE_BIT = 1 << 31;

    @Test
    void ntscInterlaceAlternates262And263HblankLinesWithoutDrift() {
        assertInterlacedFieldPattern(0x24, 262, 263);
    }

    @Test
    void palInterlaceAlternates312And313HblankLinesWithoutDrift() {
        assertInterlacedFieldPattern(0x2C, 312, 313);
    }

    @Test
    void nonInterlacedFieldsKeepTheirIntegerLineCounts() {
        assertNonInterlacedFieldLength(0x00, 263);
        assertNonInterlacedFieldLength(0x08, 314);
    }

    @Test
    void fieldStatusChangesAtTheHalfLineFieldBoundaryNotAtVblankRise() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0024); // NTSC, 480-line interlace

        assertTrue((gpu.status() & GPUSTAT_FIELD_BIT) != 0);
        waitForVblankEdge(gpu, false);
        assertEquals(0, gpu.status() & GPUSTAT_DISPLAY_LINE_BIT);

        waitForVblankEdge(gpu, true);
        assertTrue((gpu.status() & GPUSTAT_FIELD_BIT) != 0,
            "VBlank starts before the physical field boundary");
        assertEquals(0, gpu.status() & GPUSTAT_DISPLAY_LINE_BIT,
            "GPUSTAT.31 is forced low throughout VBlank");

        waitForVblankEdge(gpu, false);
        assertEquals(0, gpu.status() & GPUSTAT_FIELD_BIT);
        assertTrue((gpu.status() & GPUSTAT_DISPLAY_LINE_BIT) != 0);

        waitForVblankEdge(gpu, true);
        assertEquals(0, gpu.status() & GPUSTAT_FIELD_BIT);
        assertEquals(0, gpu.status() & GPUSTAT_DISPLAY_LINE_BIT);

        waitForVblankEdge(gpu, false);
        assertTrue((gpu.status() & GPUSTAT_FIELD_BIT) != 0);
        assertEquals(0, gpu.status() & GPUSTAT_DISPLAY_LINE_BIT);
    }

    @Test
    void timerZeroDotclockIsNotRephasedAtInterlacedFieldBoundary() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0024); // NTSC 256-dot, 480-line interlace

        List<Long> dotTicks = collectFieldDotTicks(gpu, 4);

        assertEquals(179_025L, dotTicks.getFirst() + dotTicks.get(1));
        assertEquals(179_025L, dotTicks.get(2) + dotTicks.get(3));
        assertTrue(Math.abs(dotTicks.getFirst() - dotTicks.get(1)) <= 1L);
        assertEquals(dotTicks.getFirst(), dotTicks.get(2));
        assertEquals(dotTicks.get(1), dotTicks.get(3));
    }

    @Test
    void timerZeroHblankGateDoesNotSeeASyntheticEdgeAtTheHalfLineBoundary() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0024);
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);

        waitForVblankEdge(gpu, timers, false);
        waitForVblankEdge(gpu, timers, true);
        assertTrue((gpu.status() & GPUSTAT_FIELD_BIT) != 0);
        timers.write16(0x1F80_1104, 0x0003); // Timer0: reset on HBlank rise

        int before = timers.read16(0x1F80_1100);
        int guard = 0;
        while ((gpu.status() & GPUSTAT_FIELD_BIT) != 0) {
            before = timers.read16(0x1F80_1100);
            gpu.tick(1);
            timers.tick(1);
            assertTrue(++guard < 100_000, "physical field boundary did not arrive");
        }

        assertFalse(gpu.inHblank(), "the interlaced field boundary is half-way through an active line");
        assertEquals(0, gpu.hblankRisesLastTick());
        assertEquals((before + 1) & 0xFFFF, timers.read16(0x1F80_1100),
            "field transition must not reset an HBlank-gated Timer0");
    }

    @Test
    void zeroWidthDisplayHoldsTimerHblankHighWithoutScanlineEdges() {
        Gpu gpu = new Gpu(new InterruptController());
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1114, 1 << 8); // Timer1 clocks on HBlank rises

        while (gpu.inHblank()) {
            gpu.tick(1);
            timers.tick(1);
        }
        gpu.gp1(0x0608_0080); // X1=X2=0x080: zero displayed pixels

        assertEquals(0, gpu.frameBufferWidth());
        assertTrue(gpu.inHblank());
        assertEquals(1, gpu.hblankRisesLastTick());
        int timerBefore = timers.read16(0x1F80_1110);

        for (int i = 0; i < 10_000; i++) {
            gpu.tick(1);
            timers.tick(1);
            assertTrue(gpu.inHblank());
            assertEquals(0, gpu.hblankRisesLastTick());
            assertEquals(0, gpu.hblankFallsLastTick());
        }
        assertEquals(timerBefore, timers.read16(0x1F80_1110));
    }

    @Test
    void restoringNonzeroDisplayWidthRestoresBeamDerivedHblankLevel() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0608_0080); // zero width
        assertTrue(gpu.inHblank());

        while (gpu.beamCrtcTick() < 1_000) {
            gpu.tick(1);
        }
        gpu.gp1(0x06C0_0200); // reset horizontal range: 0x200..0xC00

        assertEquals(256, gpu.frameBufferWidth());
        assertFalse(gpu.inHblank());
        assertEquals(1, gpu.hblankFallsLastTick());
    }

    @Test
    void timerHblankEdgesFollowProgrammedHorizontalDisplayRange() {
        Gpu gpu = new Gpu(new InterruptController());
        while (gpu.beamCrtcTick() < 700) {
            gpu.tick(1);
        }

        gpu.gp1(0x0600_0000 | (1_100 << 12) | 1_000);
        assertTrue(gpu.inHblank());

        while (gpu.beamCrtcTick() < 1_000) {
            gpu.tick(1);
        }
        assertFalse(gpu.inHblank());
        assertEquals(1, gpu.hblankFallsLastTick());

        while (gpu.beamCrtcTick() < 1_100) {
            gpu.tick(1);
        }
        assertTrue(gpu.inHblank());
        assertEquals(1, gpu.hblankRisesLastTick());
    }

    @Test
    void nonMutatingHblankHorizonMatchesCrtcEdgesAcrossBatchSizes() {
        Gpu gpu = new Gpu(new InterruptController());
        int[] batches = {1, 17, 256, 1_800, 5_000, 10_000, 31, 4_096};

        for (int batch : batches) {
            int predicted = gpu.hblankRisesWithinSystemClocks(batch);
            gpu.tick(batch);
            assertEquals(predicted, gpu.hblankRisesLastTick());
        }
    }

    @Test
    void outOfRangeVerticalEndDoesNotSynthesizeVblankAtFieldWrap() {
        InterruptController interrupts = new InterruptController();
        Gpu gpu = new Gpu(interrupts);
        gpu.gp1(0x07FF_FC10); // Y1=16, Y2=1023: Y2 is never reached in NTSC

        waitForVblankEdge(gpu, false);
        interrupts.writeStatus(0);
        int fieldTransitions = 0;
        int previousLine = gpu.beamScanline();
        int guard = 0;
        while (fieldTransitions < 3) {
            gpu.tick(1);
            int line = gpu.beamScanline();
            if (line < previousLine) {
                fieldTransitions++;
            }
            previousLine = line;
            assertEquals(0, gpu.vblankRisesLastTick());
            assertTrue(++guard < 2_000_000, "three physical fields did not pass");
        }

        assertFalse(gpu.inVblank());
        assertEquals(0, interrupts.status() & 1);
    }

    @Test
    void halfLineAndTimerPhasesSurviveSaveStateAcrossSeveralFields() {
        Gpu referenceGpu = new Gpu(new InterruptController());
        referenceGpu.gp1(0x0800_002C); // PAL, 480-line interlace
        TimerController referenceTimers = new TimerController(new InterruptController());
        referenceTimers.setGpu(referenceGpu);
        referenceTimers.write16(0x1F80_1104, 1 << 8); // Timer0 dotclock
        referenceTimers.write16(0x1F80_1114, 1 << 8); // Timer1 HBlank

        waitForVblankEdge(referenceGpu, referenceTimers, false);
        waitForVblankEdge(referenceGpu, referenceTimers, true);
        waitForVblankEdge(referenceGpu, referenceTimers, false);
        tick(referenceGpu, referenceTimers, 12_345);

        Gpu.State gpuState = referenceGpu.copyState();
        TimerController.State timerState = referenceTimers.copyState();

        Gpu restoredGpu = new Gpu(new InterruptController());
        restoredGpu.loadState(gpuState);
        TimerController restoredTimers = new TimerController(new InterruptController());
        restoredTimers.setGpu(restoredGpu);
        restoredTimers.loadState(timerState);

        int[] steps = {1, 7, 31, 113};
        int startingFrame = referenceGpu.frameCounter();
        int iteration = 0;
        while (referenceGpu.frameCounter() < startingFrame + 4) {
            int step = steps[iteration++ & 3];
            referenceGpu.tick(step);
            referenceTimers.tick(step);
            restoredGpu.tick(step);
            restoredTimers.tick(step);

            assertEquals(referenceGpu.status(), restoredGpu.status());
            assertEquals(referenceGpu.frameCounter(), restoredGpu.frameCounter());
            assertEquals(referenceGpu.inHblank(), restoredGpu.inHblank());
            assertEquals(referenceGpu.inVblank(), restoredGpu.inVblank());
            assertEquals(referenceGpu.hblankRisesLastTick(), restoredGpu.hblankRisesLastTick());
            assertEquals(referenceGpu.hblankFallsLastTick(), restoredGpu.hblankFallsLastTick());
            assertEquals(referenceGpu.vblankRisesLastTick(), restoredGpu.vblankRisesLastTick());
            assertEquals(referenceGpu.vblankFallsLastTick(), restoredGpu.vblankFallsLastTick());
            assertEquals(referenceGpu.dotClockTicksLastTick(), restoredGpu.dotClockTicksLastTick());
            assertEquals(referenceTimers.read16(0x1F80_1100), restoredTimers.read16(0x1F80_1100));
            assertEquals(referenceTimers.read16(0x1F80_1110), restoredTimers.read16(0x1F80_1110));
            assertTrue(iteration < 100_000, "four fields did not complete");
        }
    }

    private static void assertInterlacedFieldPattern(int displayMode, int shortField, int longField) {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0000 | displayMode);
        TimerController timers = new TimerController(new InterruptController());
        timers.setGpu(gpu);
        timers.write16(0x1F80_1114, 1 << 8); // Timer1 clocks on HBlank rises

        List<FieldInterval> fields = collectFieldIntervals(gpu, timers, 8);
        for (int i = 0; i < fields.size(); i++) {
            int expected = (i & 1) == 0 ? shortField : longField;
            int opposite = (i & 1) == 0 ? longField : shortField;
            assertEquals(expected, fields.get(i).hblankRises());
            assertEquals(opposite, fields.get(i).hblankFalls());
            assertEquals(expected, fields.get(i).timer1Ticks());
            assertEquals(1, fields.get(i).vblankFalls());
        }
        for (int i = 0; i < fields.size(); i += 2) {
            assertEquals(shortField + longField,
                fields.get(i).hblankRises() + fields.get(i + 1).hblankRises());
        }
    }

    private static void assertNonInterlacedFieldLength(int displayMode, int lines) {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0000 | displayMode);

        List<FieldInterval> fields = collectFieldIntervals(gpu, null, 4);
        for (FieldInterval field : fields) {
            assertEquals(lines, field.hblankRises());
            assertEquals(lines, field.hblankFalls());
            assertEquals(1, field.vblankFalls());
        }
        assertTrue((gpu.status() & GPUSTAT_FIELD_BIT) != 0,
            "GPU v2 reports field bit one when interlace is disabled");
    }

    private static List<FieldInterval> collectFieldIntervals(
        Gpu gpu, TimerController timers, int count
    ) {
        List<FieldInterval> result = new ArrayList<>();
        boolean anchored = false;
        int hblankRises = 0;
        int hblankFalls = 0;
        int vblankFalls = 0;
        int previousTimer = 0;
        int guard = 0;
        while (result.size() < count) {
            gpu.tick(32);
            if (timers != null) {
                timers.tick(32);
            }
            hblankRises += gpu.hblankRisesLastTick();
            hblankFalls += gpu.hblankFallsLastTick();
            vblankFalls += gpu.vblankFallsLastTick();
            if (gpu.vblankRisesLastTick() != 0) {
                int timerValue = timers == null ? 0 : timers.read16(0x1F80_1110);
                if (anchored) {
                    result.add(new FieldInterval(
                        hblankRises,
                        hblankFalls,
                        timers == null ? 0 : (timerValue - previousTimer) & 0xFFFF,
                        vblankFalls
                    ));
                }
                anchored = true;
                hblankRises = 0;
                hblankFalls = 0;
                vblankFalls = 0;
                previousTimer = timerValue;
            }
            assertTrue(++guard < 1_000_000, "field timing did not advance");
        }
        return result;
    }

    private static List<Long> collectFieldDotTicks(Gpu gpu, int count) {
        List<Long> result = new ArrayList<>();
        boolean anchored = false;
        long ticks = 0;
        int guard = 0;
        while (result.size() < count) {
            gpu.tick(1);
            ticks += gpu.dotClockTicksLastTick();
            if (gpu.vblankRisesLastTick() != 0) {
                if (anchored) {
                    result.add(ticks);
                }
                anchored = true;
                ticks = 0;
            }
            assertTrue(++guard < 5_000_000, "field timing did not advance");
        }
        return result;
    }

    private static void waitForVblankEdge(Gpu gpu, boolean rising) {
        int guard = 0;
        do {
            gpu.tick(32);
            assertTrue(++guard < 100_000, "VBlank edge did not arrive");
        } while ((rising ? gpu.vblankRisesLastTick() : gpu.vblankFallsLastTick()) == 0);
    }

    private static void waitForVblankEdge(
        Gpu gpu, TimerController timers, boolean rising
    ) {
        int guard = 0;
        do {
            gpu.tick(1);
            timers.tick(1);
            assertTrue(++guard < 1_000_000, "VBlank edge did not arrive");
        } while ((rising ? gpu.vblankRisesLastTick() : gpu.vblankFallsLastTick()) == 0);
    }

    private static void tick(Gpu gpu, TimerController timers, int cycles) {
        for (int i = 0; i < cycles; i++) {
            gpu.tick(1);
            timers.tick(1);
        }
    }

    private record FieldInterval(
        int hblankRises, int hblankFalls, int timer1Ticks, int vblankFalls
    ) {
    }
}
