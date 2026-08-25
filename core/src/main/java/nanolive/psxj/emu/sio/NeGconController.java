package nanolive.psxj.emu.sio;

/** Namco NPC-101 neGcon twist controller. */
public final class NeGconController extends AbstractPollController {

    public static final String TYPE = "namco-negcon";

    private static final int ALLOWED_BUTTONS =
        (1 << 3) | (0xF << 4) | (1 << 11) | (1 << 12) | (1 << 13);

    private int pressedMask;
    private int twist = 0x80;
    private int analogI;
    private int analogII;
    private int analogL;

    public void setButtonState(int mask, boolean pressed) {
        int allowed = mask & ALLOWED_BUTTONS;
        pressedMask = pressed ? pressedMask | allowed : pressedMask & ~allowed;
    }

    public void setState(int pressedMask, int twist, int analogI, int analogII, int analogL) {
        this.pressedMask = pressedMask & ALLOWED_BUTTONS;
        this.twist = Math.clamp(twist, 0, 255);
        this.analogI = Math.clamp(analogI, 0, 255);
        this.analogII = Math.clamp(analogII, 0, 255);
        this.analogL = Math.clamp(analogL, 0, 255);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    protected int controllerId() {
        return 0x23;
    }

    @Override
    protected int[] capturePollPayload() {
        int buttons = 0xFFFF & ~(pressedMask & ALLOWED_BUTTONS);
        return new int[] {
            buttons & 0xFF,
            (buttons >>> 8) & 0xFF,
            twist,
            analogI,
            analogII,
            analogL
        };
    }

    @Override
    public ControllerDeviceState copyState() {
        ControllerDeviceState state = new ControllerDeviceState();
        state.type = TYPE;
        state.values = new int[] {pressedMask, twist, analogI, analogII, analogL};
        state.protocol = copyProtocolState();
        return state;
    }

    @Override
    public void loadState(ControllerDeviceState state) {
        int[] values = state == null ? null : state.values;
        setState(
            value(values, 0, 0),
            value(values, 1, 0x80),
            value(values, 2, 0),
            value(values, 3, 0),
            value(values, 4, 0)
        );
        loadProtocolState(state == null ? null : state.protocol);
    }
}
