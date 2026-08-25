package nanolive.psxj.platform.audio;

import java.util.Arrays;

/** Streaming WSOLA stretcher for 44.1 kHz stereo PCM. */
final class PitchPreservingTimeStretcher {

    private static final int CHANNELS = 2;
    private static final int WINDOW_FRAMES = 1_024;
    private static final int OVERLAP_FRAMES = 256;
    private static final int SYNTHESIS_HOP_FRAMES = WINDOW_FRAMES - OVERLAP_FRAMES;
    private static final int SEARCH_FRAMES = 128;
    private static final short[] EMPTY = new short[0];

    private short[] input = new short[WINDOW_FRAMES * CHANNELS * 2];
    private final short[] previousOverlap = new short[OVERLAP_FRAMES * CHANNELS];
    private int inputFrames;
    private long inputFrameBase;
    private double nominalNextStart;
    private long nextOriginalFrame;
    private boolean seeded;

    short[] process(short[] interleavedStereo, int frames, double stretch) {
        if (frames <= 0) {
            return EMPTY;
        }
        append(interleavedStereo, frames);
        int initialCapacityFrames = Math.max(SYNTHESIS_HOP_FRAMES,
            (int) Math.ceil(frames * stretch) + SYNTHESIS_HOP_FRAMES);
        short[] output = new short[initialCapacityFrames * CHANNELS];
        int outputFrames = 0;

        if (!seeded) {
            if (inputFrames < WINDOW_FRAMES) {
                return EMPTY;
            }
            long firstStart = inputFrameBase;
            outputFrames = copyFrames(firstStart, 0, SYNTHESIS_HOP_FRAMES,
                output, outputFrames);
            rememberOverlap(firstStart);
            nextOriginalFrame = firstStart + SYNTHESIS_HOP_FRAMES;
            nominalNextStart = firstStart + SYNTHESIS_HOP_FRAMES / stretch;
            seeded = true;
        }

        while (hasCompleteSearchWindow()) {
            long selectedStart = findBestWindowStart();
            if (outputFrames + SYNTHESIS_HOP_FRAMES > output.length / CHANNELS) {
                int grownFrames = Math.max(outputFrames + SYNTHESIS_HOP_FRAMES,
                    output.length / CHANNELS + SYNTHESIS_HOP_FRAMES * 2);
                output = Arrays.copyOf(output, grownFrames * CHANNELS);
            }
            overlapInto(selectedStart, output, outputFrames);
            outputFrames += OVERLAP_FRAMES;
            outputFrames = copyFrames(selectedStart, OVERLAP_FRAMES,
                SYNTHESIS_HOP_FRAMES - OVERLAP_FRAMES, output, outputFrames);
            rememberOverlap(selectedStart);
            nextOriginalFrame = selectedStart + SYNTHESIS_HOP_FRAMES;
            // Do not let a periodic correlation peak cancel the requested stretch.
            nominalNextStart += SYNTHESIS_HOP_FRAMES / stretch;
        }

        discardConsumedPrefix();
        return outputFrames == 0
            ? EMPTY
            : Arrays.copyOf(output, outputFrames * CHANNELS);
    }

    short[] drainOriginal(short[] interleavedStereo, int frames) {
        append(interleavedStereo, frames);
        if (!seeded) {
            short[] result = Arrays.copyOf(input, inputFrames * CHANNELS);
            reset();
            return result;
        }
        int firstFrame = Math.clamp(
            Math.toIntExact(nextOriginalFrame - inputFrameBase),
            0,
            inputFrames
        );
        short[] result = Arrays.copyOfRange(
            input,
            firstFrame * CHANNELS,
            inputFrames * CHANNELS
        );
        reset();
        return result;
    }

    void reset() {
        inputFrames = 0;
        inputFrameBase = 0L;
        nominalNextStart = 0.0;
        nextOriginalFrame = 0L;
        seeded = false;
        Arrays.fill(previousOverlap, (short) 0);
    }

    private void append(short[] source, int frames) {
        int requiredSamples = (inputFrames + frames) * CHANNELS;
        if (requiredSamples > input.length) {
            int capacity = Math.max(requiredSamples, input.length + (input.length >>> 1));
            input = Arrays.copyOf(input, capacity);
        }
        System.arraycopy(source, 0, input, inputFrames * CHANNELS, frames * CHANNELS);
        inputFrames += frames;
    }

    private boolean hasCompleteSearchWindow() {
        long latestRequiredFrame = Math.round(nominalNextStart)
            + SEARCH_FRAMES + WINDOW_FRAMES;
        return latestRequiredFrame <= inputFrameBase + inputFrames;
    }

    private long findBestWindowStart() {
        long nominal = Math.round(nominalNextStart);
        long first = Math.max(inputFrameBase, nominal - SEARCH_FRAMES);
        long last = Math.min(inputFrameBase + inputFrames - WINDOW_FRAMES,
            nominal + SEARCH_FRAMES);
        long best = Math.clamp(nominal, first, last);
        double bestScore = Double.NEGATIVE_INFINITY;
        long bestDistance = Long.MAX_VALUE;
        for (long candidate = first; candidate <= last; candidate++) {
            double score = normalizedCorrelation(candidate);
            long distance = Math.abs(candidate - nominal);
            // Prefer the nominal position when correlation scores tie.
            score -= distance * (0.02 / SEARCH_FRAMES);
            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                bestScore = score;
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private double normalizedCorrelation(long candidateStart) {
        double cross = 0.0;
        double previousEnergy = 0.0;
        double candidateEnergy = 0.0;
        int localStart = Math.toIntExact(candidateStart - inputFrameBase);
        // Half-rate correlation is enough to align these short windows.
        for (int frame = 0; frame < OVERLAP_FRAMES; frame += 2) {
            int previousIndex = frame * CHANNELS;
            int candidateIndex = (localStart + frame) * CHANNELS;
            for (int channel = 0; channel < CHANNELS; channel++) {
                double previous = previousOverlap[previousIndex + channel];
                double candidate = input[candidateIndex + channel];
                cross += previous * candidate;
                previousEnergy += previous * previous;
                candidateEnergy += candidate * candidate;
            }
        }
        double denominator = Math.sqrt(previousEnergy * candidateEnergy);
        return denominator <= 1.0 ? 0.0 : cross / denominator;
    }

    private void overlapInto(long selectedStart, short[] output, int outputFrame) {
        int localStart = Math.toIntExact(selectedStart - inputFrameBase);
        int denominator = OVERLAP_FRAMES + 1;
        for (int frame = 0; frame < OVERLAP_FRAMES; frame++) {
            int previousWeight = OVERLAP_FRAMES - frame;
            int currentWeight = frame + 1;
            for (int channel = 0; channel < CHANNELS; channel++) {
                int previous = previousOverlap[frame * CHANNELS + channel];
                int current = input[(localStart + frame) * CHANNELS + channel];
                int mixed = (previous * previousWeight + current * currentWeight)
                    / denominator;
                output[outputFrame * CHANNELS + frame * CHANNELS + channel]
                    = (short) Math.clamp(mixed, Short.MIN_VALUE, Short.MAX_VALUE);
            }
        }
    }

    private int copyFrames(long windowStart, int windowOffset, int frames,
                           short[] output, int outputFrame) {
        int sourceFrame = Math.toIntExact(windowStart - inputFrameBase) + windowOffset;
        System.arraycopy(input, sourceFrame * CHANNELS,
            output, outputFrame * CHANNELS, frames * CHANNELS);
        return outputFrame + frames;
    }

    private void rememberOverlap(long selectedStart) {
        int sourceFrame = Math.toIntExact(selectedStart - inputFrameBase)
            + SYNTHESIS_HOP_FRAMES;
        System.arraycopy(input, sourceFrame * CHANNELS,
            previousOverlap, 0, previousOverlap.length);
    }

    private void discardConsumedPrefix() {
        long retainFrom = Math.max(inputFrameBase,
            (long) Math.floor(nominalNextStart) - SEARCH_FRAMES - 1L);
        int discardedFrames = Math.toIntExact(retainFrom - inputFrameBase);
        if (discardedFrames <= 0) {
            return;
        }
        int remainingFrames = inputFrames - discardedFrames;
        System.arraycopy(input, discardedFrames * CHANNELS,
            input, 0, remainingFrames * CHANNELS);
        inputFrames = remainingFrames;
        inputFrameBase = retainFrom;
    }
}
