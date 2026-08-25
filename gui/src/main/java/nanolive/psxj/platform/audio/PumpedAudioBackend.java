package nanolive.psxj.platform.audio;

/** Host backends which need servicing even while no new emulated PCM arrives. */
interface PumpedAudioBackend {
    void pump();
}
