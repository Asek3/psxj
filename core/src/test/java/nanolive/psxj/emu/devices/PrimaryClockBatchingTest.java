package nanolive.psxj.emu.devices;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrimaryClockBatchingTest {

    private static final Gson GSON = new Gson();

    @Test
    void idleCdAndSpuBatchMatchesExactClockStepping() {
        InterruptController exactInterrupts = new InterruptController();
        CdRomController exactCd = new CdRomController(exactInterrupts);
        Spu exactSpu = new Spu(exactInterrupts);
        InterruptController batchedInterrupts = new InterruptController();
        CdRomController batchedCd = new CdRomController(batchedInterrupts);
        Spu batchedSpu = new Spu(batchedInterrupts);

        int cycles = 120_031;
        for (int i = 0; i < cycles; i++) {
            exactCd.tick(1);
            exactSpu.tick(1);
        }
        batchedCd.tick(cycles);
        batchedSpu.tick(cycles);

        assertFalse(batchedCd.audioClockCoupled());
        assertTrue(batchedCd.audioInputStableFor(cycles));
        assertEquals(GSON.toJson(exactCd.copyState()), GSON.toJson(batchedCd.copyState()));
        assertEquals(GSON.toJson(exactSpu.copyState()), GSON.toJson(batchedSpu.copyState()));
        assertArrayEquals(exactSpu.drainMixedSamples(), batchedSpu.drainMixedSamples());
    }

    @Test
    void xaModeOnlyCouplesTheSectorBoundaryToTheSpu() {
        CdRomController cd = new CdRomController(new InterruptController());
        CdRomController.State state = cd.copyState();
        state.mode = 0x40;
        state.reading = true;
        state.readCyclesRemaining = 1_000;
        cd.loadState(state);

        assertTrue(cd.audioClockCoupled());
        assertTrue(cd.audioInputStableFor(999));
        assertFalse(cd.audioInputStableFor(1_000));

        state = cd.copyState();
        state.xaPcm = new short[]{1, -1};
        cd.loadState(state);
        assertFalse(cd.audioInputStableFor(1));
    }

    @Test
    void batchedSeekCompletionAdvancesReadOnlyByRemainingClocks(@TempDir Path tempDir)
        throws IOException {
        Path image = tempDir.resolve("disc.bin");
        Files.write(image, new byte[2 * 2_352]);
        CdRomController exact = new CdRomController(new InterruptController());
        CdRomController batched = new CdRomController(new InterruptController());
        exact.mount(image);
        batched.mount(image);

        CdRomController.State initial = exact.copyState();
        initial.motorOn = true;
        initial.seeking = true;
        initial.pendingReadStart = true;
        initial.seekCyclesRemaining = 3;
        initial.targetLba = 0;
        exact.loadState(initial);
        batched.loadState(initial);

        for (int i = 0; i < 5; i++) {
            exact.tick(1);
        }
        batched.tick(5);

        assertEquals(exact.copyState().readCyclesRemaining,
            batched.copyState().readCyclesRemaining);
        assertEquals(exact.copyState().currentLba, batched.copyState().currentLba);
        exact.close();
        batched.close();
    }

    @Test
    void systemClockTimersMatchAggregateSteppingAcrossOverflowAndResetEdges() {
        InterruptController exactInterrupts = new InterruptController();
        TimerController exactTimers = new TimerController(exactInterrupts);
        InterruptController batchedInterrupts = new InterruptController();
        TimerController batchedTimers = new TimerController(batchedInterrupts);

        int cycles = 150_017;
        for (int i = 0; i < cycles; i++) {
            exactTimers.tick(1);
        }
        batchedTimers.tick(cycles);

        assertEquals(GSON.toJson(exactTimers.copyState()), GSON.toJson(batchedTimers.copyState()));
        assertEquals(GSON.toJson(exactInterrupts.copyState()), GSON.toJson(batchedInterrupts.copyState()));
    }
}
