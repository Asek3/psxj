package nanolive.psxj.emu.sio;

/**
 * Serialization-neutral snapshot shared by all SIO0 controller devices.
 *
 * <p>The emulator save-state format cannot safely deserialize an interface
 * field.  Devices therefore expose a small tagged state container rather than
 * leaking implementation-specific classes into the machine snapshot.</p>
 */
public final class ControllerDeviceState {

    public String type;
    public int[] values;
    public int[] protocol;
    public ControllerDeviceState[] children;
}
