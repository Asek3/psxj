package nanolive.psxj.emu.sio;

/** Sony SCPH-1030 two-button mouse. */
public final class MouseController extends AbstractPollController {

    public static final String TYPE = "sony-mouse";

    private boolean leftPressed;
    private boolean rightPressed;
    private int deltaX;
    private int deltaY;

    public void setButtons(boolean leftPressed, boolean rightPressed) {
        this.leftPressed = leftPressed;
        this.rightPressed = rightPressed;
    }

    public void move(int horizontal, int vertical) {
        deltaX = Math.clamp(deltaX + horizontal, -128, 127);
        deltaY = Math.clamp(deltaY + vertical, -128, 127);
    }

    public int pendingDeltaX() {
        return deltaX;
    }

    public int pendingDeltaY() {
        return deltaY;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    protected int controllerId() {
        return 0x12;
    }

    @Override
    protected int[] capturePollPayload() {
        int buttonsHigh = 0xFC;
        if (rightPressed) {
            buttonsHigh &= ~(1 << 2);
        }
        if (leftPressed) {
            buttonsHigh &= ~(1 << 3);
        }
        int[] payload = {0xFF, buttonsHigh, deltaX & 0xFF, deltaY & 0xFF};
        deltaX = 0;
        deltaY = 0;
        return payload;
    }

    @Override
    public ControllerDeviceState copyState() {
        ControllerDeviceState state = new ControllerDeviceState();
        state.type = TYPE;
        state.values = new int[] {
            leftPressed ? 1 : 0,
            rightPressed ? 1 : 0,
            deltaX,
            deltaY
        };
        state.protocol = copyProtocolState();
        return state;
    }

    @Override
    public void loadState(ControllerDeviceState state) {
        int[] values = state == null ? null : state.values;
        leftPressed = value(values, 0, 0) != 0;
        rightPressed = value(values, 1, 0) != 0;
        deltaX = Math.clamp(value(values, 2, 0), -128, 127);
        deltaY = Math.clamp(value(values, 3, 0), -128, 127);
        loadProtocolState(state == null ? null : state.protocol);
    }
}
