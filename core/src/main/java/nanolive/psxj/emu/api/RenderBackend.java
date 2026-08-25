package nanolive.psxj.emu.api;

import nanolive.psxj.emu.video.GpuFrame;

public interface RenderBackend extends AutoCloseable {

    enum InputKey {
        UP,
        RIGHT,
        DOWN,
        LEFT,
        START,
        SELECT,
        CROSS,
        SQUARE,
        CIRCLE,
        TRIANGLE,
        L1,
        R1,
        L2,
        R2
    }

    /** Host-window keys which are not PlayStation controller buttons. */
    enum HostKey {
        SAVE_STATE,
        LOAD_STATE,
        CANCEL,
        CONFIRM,
        SLOT_1,
        SLOT_2,
        SLOT_3,
        SLOT_4,
        SLOT_5,
        SLOT_6,
        SLOT_7,
        SLOT_8,
        SLOT_9
    }

    enum PointerAction { MOVE, DOWN, UP }

    enum PointerCursor { DEFAULT, POINTING_HAND }

    record PointerEvent(float x, float y, int button, PointerAction action) {
        public PointerEvent {
            x = Math.clamp(x, 0.0f, 1.0f);
            y = Math.clamp(y, 0.0f, 1.0f);
        }
    }

    @FunctionalInterface
    interface KeyEventHandler {
        void handle(InputKey key, boolean pressed);
    }

    @FunctionalInterface
    interface HostKeyEventHandler {
        // Returns true when the host action consumed the physical key.
        boolean handle(HostKey key, boolean pressed);
    }

    @FunctionalInterface
    interface PointerEventHandler {
        // Returns true when the overlay consumed the pointer event.
        boolean handle(PointerEvent event);
    }

    @FunctionalInterface
    interface CloseRequestHandler {
        // Returns true when the frontend accepted the user's close request.
        boolean shouldClose();
    }

    void open();

    void presentFrame(GpuFrame frame);

    default void processEvents() {
        // Optional.
    }

    default boolean isRenderSurfaceAvailable() {
        return true;
    }

    default void requestAttention() {
        // Optional.
    }

    default void setCloseRequestHandler(CloseRequestHandler handler) {
        // Optional.
    }

    default void setKeyEventHandler(KeyEventHandler handler) {
        // Optional.
    }

    default void setHostKeyEventHandler(HostKeyEventHandler handler) {
        // Optional.
    }

    default void setPointerEventHandler(PointerEventHandler handler) {
        // Optional.
    }

    default void setPointerCursor(PointerCursor cursor) {
        // Optional.
    }

    @Override
    void close();
}
