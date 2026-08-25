package nanolive.psxj.platform.audio;

import nanolive.psxj.emu.api.AudioBackend;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Pitch-preserving correction when the emulator falls behind real time. */
public final class AdaptiveRateAudioBackend implements AudioBackend {

    private static final int SAMPLE_RATE = 44_100;
    private static final int CHANNELS = 2;
    private static final int RATE_WINDOW_COUNT = 3;
    private static final long ESTIMATE_WINDOW_NANOS = 40_000_000L;
    private static final double MAX_STRETCH = 1.25;
    private static final double STRETCH_DEADBAND = 1.001;
    private static final double ATTACK_PER_ESTIMATE = 0.05;
    private static final double RELEASE_PER_ESTIMATE = 0.006;
    private static final long DISCONTINUITY_NANOS = 250_000_000L;

    private final AudioBackend delegate;
    private final LongSupplier nanoTime;
    private final PitchPreservingTimeStretcher timeStretcher =
        new PitchPreservingTimeStretcher();
    private final double[] productionRatios = new double[RATE_WINDOW_COUNT];
    private final double[] sortedRatios = new double[RATE_WINDOW_COUNT];

    private long lastSubmitReturnNanos;
    private long estimateGenerationNanos;
    private long estimateInputFrames;
    private int productionRatioCount;
    private int productionRatioCursor;
    private double stretch = 1.0;
    private boolean timeStretchActive;
    private boolean open;

    public AdaptiveRateAudioBackend(AudioBackend delegate) {
        this(delegate, System::nanoTime);
    }

    AdaptiveRateAudioBackend(AudioBackend delegate, LongSupplier nanoTime) {
        this.delegate = Objects.requireNonNull(delegate);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override
    public synchronized void open() {
        if (open) {
            return;
        }
        delegate.open();
        resetRateEstimate(true);
        resetTimeStretcher();
        lastSubmitReturnNanos = nanoTime.getAsLong();
        open = true;
    }

    @Override
    public synchronized void submitSamples(short[] interleavedStereo) {
        if (!open || interleavedStereo == null || interleavedStereo.length < CHANNELS) {
            return;
        }
        int inputFrames = interleavedStereo.length / CHANNELS;
        updateRateEstimate(inputFrames);
        short[] corrected = correctDuration(interleavedStereo, inputFrames);
        if (corrected.length > 0) {
            delegate.submitSamples(corrected);
        }
        // Backend back-pressure is not part of the emulation-rate estimate.
        lastSubmitReturnNanos = nanoTime.getAsLong();
    }

    @Override
    public synchronized void pause() {
        if (!open) {
            return;
        }
        delegate.pause();
        resetRateEstimate(true);
        resetTimeStretcher();
    }

    @Override
    public synchronized void resume() {
        if (!open) {
            return;
        }
        delegate.resume();
        lastSubmitReturnNanos = nanoTime.getAsLong();
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        try {
            delegate.close();
        } finally {
            open = false;
            lastSubmitReturnNanos = 0L;
            resetRateEstimate(true);
            resetTimeStretcher();
        }
    }

    private void updateRateEstimate(int inputFrames) {
        long now = nanoTime.getAsLong();
        long generationNanos = Math.max(0L, now - lastSubmitReturnNanos);
        if (generationNanos >= DISCONTINUITY_NANOS) {
            // Ignore pauses and debugger stops without resetting stream phase.
            resetEstimateWindow();
            return;
        }

        estimateGenerationNanos += generationNanos;
        estimateInputFrames += inputFrames;
        if (estimateGenerationNanos < ESTIMATE_WINDOW_NANOS) {
            return;
        }

        double expectedNanos = estimateInputFrames * 1_000_000_000.0 / SAMPLE_RATE;
        if (expectedNanos <= 0.0) {
            resetEstimateWindow();
            return;
        }
        productionRatios[productionRatioCursor] = estimateGenerationNanos / expectedNanos;
        productionRatioCursor = (productionRatioCursor + 1) % RATE_WINDOW_COUNT;
        productionRatioCount = Math.min(RATE_WINDOW_COUNT, productionRatioCount + 1);
        resetEstimateWindow();
        if (productionRatioCount < RATE_WINDOW_COUNT) {
            return;
        }

        System.arraycopy(productionRatios, 0, sortedRatios, 0, RATE_WINDOW_COUNT);
        Arrays.sort(sortedRatios);
        double median = sortedRatios[RATE_WINDOW_COUNT / 2];
        double target = median <= STRETCH_DEADBAND
            ? 1.0
            : Math.clamp(median, 1.0, MAX_STRETCH);
        double maximumStep = target > stretch ? ATTACK_PER_ESTIMATE : RELEASE_PER_ESTIMATE;
        stretch += Math.clamp(target - stretch, -maximumStep, maximumStep);
        if (Math.abs(stretch - 1.0) < 0.0001) {
            stretch = 1.0;
        }
    }

    private short[] correctDuration(short[] input, int inputFrames) {
        if (!timeStretchActive && stretch == 1.0) {
            return input.length == inputFrames * CHANNELS
                ? input
                : Arrays.copyOf(input, inputFrames * CHANNELS);
        }
        if (timeStretchActive && stretch == 1.0) {
            timeStretchActive = false;
            return timeStretcher.drainOriginal(input, inputFrames);
        }
        timeStretchActive = true;
        return timeStretcher.process(input, inputFrames, stretch);
    }

    private void resetRateEstimate(boolean resetStretch) {
        if (resetStretch) {
            stretch = 1.0;
        }
        productionRatioCount = 0;
        productionRatioCursor = 0;
        Arrays.fill(productionRatios, 1.0);
        resetEstimateWindow();
    }

    private void resetEstimateWindow() {
        estimateGenerationNanos = 0L;
        estimateInputFrames = 0L;
    }

    private void resetTimeStretcher() {
        timeStretchActive = false;
        timeStretcher.reset();
    }

    double stretchForTesting() {
        return stretch;
    }
}
