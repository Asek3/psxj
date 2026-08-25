package nanolive.psxj.emu.api;

public interface GamepadBackend extends AutoCloseable {

    @FunctionalInterface
    interface StateSink {
        void update(int pressedMask, int leftX, int leftY, int rightX, int rightY);
    }

    void open();

    void poll(StateSink sink);

    default void rumble(int largeMotor, boolean smallMotor) {
    }

    @Override
    void close();
}
