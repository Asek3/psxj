package nanolive.psxj.emu.sio;

/** Konami Justifier/Hyperblaster controller-port IRQ10 lightgun. */
public final class JustifierController extends AbstractPollController {

    public static final String TYPE = "konami-justifier";

    private static final int ALLOWED_BUTTONS = (1 << 3) | (1 << 14) | (1 << 15);

    private int pressedMask;
    private boolean irqEnabled;
    private boolean illuminated;
    private int targetScanline;
    private int targetCrtcTick;
    private int lastField = -1;
    private int lastScanline = -1;
    private int lastCrtcTick = -1;
    private long beamEpoch;
    private long lastFiredEpoch = -1;

    public void setButtonState(int mask, boolean pressed) {
        int allowed = mask & ALLOWED_BUTTONS;
        pressedMask = pressed ? pressedMask | allowed : pressedMask & ~allowed;
    }

    public void setAim(int crtcTick, int scanline, boolean illuminated) {
        targetCrtcTick = Math.max(0, crtcTick);
        targetScanline = Math.max(0, scanline);
        this.illuminated = illuminated;
    }

    public boolean irqEnabled() {
        return irqEnabled;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    protected int controllerId() {
        return 0x31;
    }

    @Override
    protected int[] capturePollPayload() {
        int buttons = 0xFFFF & ~(pressedMask & ALLOWED_BUTTONS);
        return new int[] {buttons & 0xFF, (buttons >>> 8) & 0xFF};
    }

    @Override
    protected void receivePollByte(int payloadIndex, int value) {
        if (payloadIndex == 0) {
            irqEnabled = (value & 0x10) != 0;
        }
    }

    @Override
    public boolean lightgunIrqLine(BeamPosition position) {
        if (position == null) {
            return false;
        }

        int field = position.field();
        int scanline = Math.max(0, position.scanline());
        int tick = Math.max(0, position.crtcTick());
        if (lastScanline >= 0
            && (field != lastField || scanline < lastScanline)) {
            beamEpoch++;
        }

        boolean enteredTargetLine = scanline == targetScanline
            && lastScanline != targetScanline;
        boolean crossedWithinLine = scanline == targetScanline
            && lastScanline == targetScanline
            && lastCrtcTick < targetCrtcTick
            && tick >= targetCrtcTick;
        boolean crossed = (enteredTargetLine && tick >= targetCrtcTick)
            || crossedWithinLine;
        boolean pulse = irqEnabled
            && illuminated
            && crossed
            && lastFiredEpoch != beamEpoch;
        if (pulse) {
            lastFiredEpoch = beamEpoch;
        }

        lastField = field;
        lastScanline = scanline;
        lastCrtcTick = tick;
        return pulse;
    }

    @Override
    public boolean samplesLightgunIrqLine() {
        return true;
    }

    @Override
    public ControllerDeviceState copyState() {
        ControllerDeviceState state = new ControllerDeviceState();
        state.type = TYPE;
        state.values = new int[] {
            pressedMask,
            irqEnabled ? 1 : 0,
            illuminated ? 1 : 0,
            targetScanline,
            targetCrtcTick,
            lastField,
            lastScanline,
            lastCrtcTick,
            (int) beamEpoch,
            (int) (beamEpoch >>> 32),
            (int) lastFiredEpoch,
            (int) (lastFiredEpoch >>> 32)
        };
        state.protocol = copyProtocolState();
        return state;
    }

    @Override
    public void loadState(ControllerDeviceState state) {
        int[] values = state == null ? null : state.values;
        pressedMask = value(values, 0, 0) & ALLOWED_BUTTONS;
        irqEnabled = value(values, 1, 0) != 0;
        illuminated = value(values, 2, 0) != 0;
        targetScanline = Math.max(0, value(values, 3, 0));
        targetCrtcTick = Math.max(0, value(values, 4, 0));
        lastField = value(values, 5, -1);
        lastScanline = value(values, 6, -1);
        lastCrtcTick = value(values, 7, -1);
        beamEpoch = Integer.toUnsignedLong(value(values, 8, 0))
            | ((long) value(values, 9, 0) << 32);
        lastFiredEpoch = Integer.toUnsignedLong(value(values, 10, -1))
            | ((long) value(values, 11, -1) << 32);
        loadProtocolState(state == null ? null : state.protocol);
    }
}
