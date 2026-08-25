package nanolive.psxj.emu.sio;

/** A device connected to a PlayStation SIO0 controller socket. */
public interface ControllerDevice {

    // Stable save-state tag.
    String typeId();

    // Selects the device with the first SIO byte.
    default boolean beginTransaction(int address) {
        return (address & 0xFF) == 0x01;
    }

    CommandResult beginCommand(int command);

    CommandResult exchangeCommandByte(int value);

    void cancelTransaction();

    ControllerDeviceState copyState();

    void loadState(ControllerDeviceState state);

    default boolean lightgunIrqLine(BeamPosition position) {
        return false;
    }

    default boolean samplesLightgunIrqLine() {
        return false;
    }

    record CommandResult(int response, boolean acknowledge, boolean finished) {
        public static CommandResult more(int response) {
            return new CommandResult(response & 0xFF, true, false);
        }

        public static CommandResult last(int response) {
            return new CommandResult(response & 0xFF, false, true);
        }
    }

    /** Physical CRTC position, expressed in GPU/video-clock ticks. */
    record BeamPosition(int field, int scanline, int crtcTick) {
    }
}
