package nanolive.psxj.emu.devices;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class SpuReverbTest {

    @Test
    void reverbOutputPassesThroughTheHardwareFirUpsampler() {
        Spu spu = new Spu();
        Spu.State state = spu.copyState();
        state.control = 0x8000;
        state.reverbOutputVolumeLeft = 0x7FFF;
        state.reverbOutputVolumeRight = 0x7FFF;
        state.mainVolumeLeftCurrent = 0x7FFE;
        state.mainVolumeRightCurrent = 0x7FFE;
        state.reverbResamplePosition = 1;
        state.reverbUpsampleBuffer[0][22] = 0x7FFF;
        state.reverbUpsampleBuffer[1][22] = 0x7FFF;
        spu.loadState(state);

        spu.tick(768);

        assertArrayEquals(new short[]{20_488, 20_488}, spu.drainMixedSamples());
    }

    @Test
    void spuMuteAlsoStopsVoiceReverbSend() {
        Spu audible = noiseVoiceWithReverb(0xC000);
        audible.tick(768);
        assertNotEquals(0, audible.copyState().reverbInputLatchLeft);

        Spu muted = noiseVoiceWithReverb(0x8000);
        muted.tick(768);
        assertEquals(0, muted.copyState().reverbInputLatchLeft);
        assertEquals(0, muted.copyState().reverbInputLatchRight);
    }

    private static Spu noiseVoiceWithReverb(int control) {
        Spu spu = new Spu();
        Spu.State state = spu.copyState();
        state.control = control;
        state.voiceNoiseMode = 1;
        state.voiceReverbMode = 1;
        state.noiseLevel = 0x4000;
        state.noiseTimer = Integer.MAX_VALUE;

        Spu.VoiceState voice = state.voices[0];
        voice.volumeLeftRaw = 0x3FFF;
        voice.volumeRightRaw = 0x3FFF;
        voice.volumeLeftCurrent = 0x7FFE;
        voice.volumeRightCurrent = 0x7FFE;
        voice.envelope = 0x7FFF;
        voice.phase = 2; // Sustain.
        voice.pitch = 0;
        voice.sampleIndex = 0;
        voice.streaming = true;
        spu.loadState(state);
        return spu;
    }
}
