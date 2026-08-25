package nanolive.psxj.platform.audio;

import nanolive.psxj.emu.api.AudioBackend;

import java.util.Objects;

/** Applies the user-facing host gain outside the emulated SPU signal path. */
final class GainAudioBackend implements AudioBackend, PumpedAudioBackend {

    private final AudioBackend delegate;
    private final int volumePercent;

    GainAudioBackend(AudioBackend delegate, int volumePercent) {
        this.delegate = Objects.requireNonNull(delegate);
        this.volumePercent = Math.clamp(volumePercent, 0, 200);
    }

    @Override
    public void open() {
        delegate.open();
    }

    @Override
    public void submitSamples(short[] interleavedStereo) {
        if (interleavedStereo == null || interleavedStereo.length == 0) {
            return;
        }
        if (volumePercent != 100) {
            for (int index = 0; index < interleavedStereo.length; index++) {
                int scaled = interleavedStereo[index] * volumePercent / 100;
                interleavedStereo[index] = (short) Math.clamp(
                    scaled,
                    Short.MIN_VALUE,
                    Short.MAX_VALUE
                );
            }
        }
        delegate.submitSamples(interleavedStereo);
    }

    @Override
    public void pause() {
        delegate.pause();
    }

    @Override
    public void resume() {
        delegate.resume();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void pump() {
        if (delegate instanceof PumpedAudioBackend pumped) {
            pumped.pump();
        }
    }
}
