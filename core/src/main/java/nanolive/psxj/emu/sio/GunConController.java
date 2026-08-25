package nanolive.psxj.emu.sio;

/** Namco NPC-103/G-Con 45 cinch-video lightgun. */
public final class GunConController extends AbstractPollController {

    public static final String TYPE = "namco-guncon";
    public static final int X_ERROR = 0x0001;
    public static final int Y_UNEXPECTED_LIGHT = 0x0005;
    public static final int Y_NO_LIGHT_OR_BUSY = 0x000A;

    private static final int ALLOWED_BUTTONS = (1 << 3) | (1 << 13) | (1 << 14);

    private int pressedMask;
    private int x = X_ERROR;
    private int y = Y_NO_LIGHT_OR_BUSY;

    public void setButtonState(int mask, boolean pressed) {
        int allowed = mask & ALLOWED_BUTTONS;
        pressedMask = pressed ? pressedMask | allowed : pressedMask & ~allowed;
    }

    public void setPosition(int x, int y) {
        this.x = Math.clamp(x, 0, 0xFFFF);
        this.y = Math.clamp(y, 0, 0xFFFF);
    }

    public void setState(int pressedMask, int x, int y) {
        this.pressedMask = pressedMask & ALLOWED_BUTTONS;
        setPosition(x, y);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    protected int controllerId() {
        return 0x63;
    }

    @Override
    protected int[] capturePollPayload() {
        int buttons = 0xFFFF & ~(pressedMask & ALLOWED_BUTTONS);
        return new int[] {
            buttons & 0xFF,
            (buttons >>> 8) & 0xFF,
            x & 0xFF,
            (x >>> 8) & 0xFF,
            y & 0xFF,
            (y >>> 8) & 0xFF
        };
    }

    @Override
    public ControllerDeviceState copyState() {
        ControllerDeviceState state = new ControllerDeviceState();
        state.type = TYPE;
        state.values = new int[] {pressedMask, x, y};
        state.protocol = copyProtocolState();
        return state;
    }

    @Override
    public void loadState(ControllerDeviceState state) {
        int[] values = state == null ? null : state.values;
        setState(
            value(values, 0, 0),
            value(values, 1, X_ERROR),
            value(values, 2, Y_NO_LIGHT_OR_BUSY)
        );
        loadProtocolState(state == null ? null : state.protocol);
    }
}
