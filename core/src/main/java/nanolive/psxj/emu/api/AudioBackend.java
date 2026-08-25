package nanolive.psxj.emu.api;

public interface AudioBackend extends AutoCloseable {

    void open();

    default void submitSamples(short[] interleavedStereo) {
    }

    default void pause() {
    }

    default void resume() {
    }

    @Override
    void close();
}
