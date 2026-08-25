package nanolive.psxj.platform.audio;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.emu.api.AudioBackend;

public final class AudioBackendFactory {

    private AudioBackendFactory() {
    }

    public static AudioBackend create(AppConfig config) {
        AudioBackend backend = switch (config.audio().backend()) {
            case SDL -> new SdlAudioBackend(config.audio().latencyMs());
            case OPENAL -> new OpenAlAudioBackend(config.audio().latencyMs());
        };
        backend = new GainAudioBackend(backend, config.audio().volumePercent());
        // Measure PCM production before it enters the native worker queue.
        backend = runOffMachineThread(backend);
        return new AdaptiveRateAudioBackend(backend);
    }

    static AudioBackend runOffMachineThread(AudioBackend backend) {
        return new ThreadedAudioBackend(backend);
    }
}
