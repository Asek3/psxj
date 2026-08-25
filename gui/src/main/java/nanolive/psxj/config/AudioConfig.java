package nanolive.psxj.config;

public final class AudioConfig {

    private AudioBackendType backend = AudioBackendType.OPENAL;
    private int latencyMs = 80;
    private int volumePercent = 100;

    public AudioBackendType backend() {
        return backend;
    }

    public void setBackend(AudioBackendType backend) {
        this.backend = backend;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int volumePercent() {
        return volumePercent;
    }

    public void setVolumePercent(int volumePercent) {
        this.volumePercent = volumePercent;
    }

    void normalize() {
        if (backend == null) {
            backend = AudioBackendType.OPENAL;
        }
        latencyMs = Math.clamp(latencyMs, 8, 250);
        volumePercent = Math.clamp(volumePercent, 0, 200);
    }
}
