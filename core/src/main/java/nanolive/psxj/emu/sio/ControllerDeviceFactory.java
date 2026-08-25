package nanolive.psxj.emu.sio;

/** Recreates the concrete controller type stored in a save-state tag. */
public final class ControllerDeviceFactory {

    private ControllerDeviceFactory() {
    }

    public static ControllerDevice restore(ControllerDeviceState state) {
        if (state == null || state.type == null) {
            return null;
        }
        ControllerDevice device = switch (state.type) {
            case DigitalController.TYPE -> new DigitalController();
            case MouseController.TYPE -> new MouseController();
            case NeGconController.TYPE -> new NeGconController();
            case GunConController.TYPE -> new GunConController();
            case JustifierController.TYPE -> new JustifierController();
            case MultitapController.TYPE -> new MultitapController();
            default -> null;
        };
        if (device != null) {
            device.loadState(state);
        }
        return device;
    }
}
