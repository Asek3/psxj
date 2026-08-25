package nanolive.psxj.emu.devices;

public final class InterruptController {

    private static final int VALID_INTERRUPT_BITS = 0x07FF;

    private static final String[] SOURCE_NAMES = {
        "VBLANK",
        "GPU",
        "CDROM",
        "DMA",
        "TMR0",
        "TMR1",
        "TMR2",
        "SIO0",
        "SIO1",
        "SPU",
        "PIO"
    };

    private int status;
    private int mask;
    private int lineState;

    public void raise(int bitIndex) {
        if (bitIndex < 0 || bitIndex >= Integer.bitCount(VALID_INTERRUPT_BITS)) {
            return;
        }
        int bit = 1 << bitIndex;
        if ((lineState & bit) == 0) {
            status |= bit;
            lineState |= bit;
        }
    }

    public void clear(int bitIndex) {
        lineState &= ~(1 << bitIndex);
    }

    public int status() {
        return status;
    }

    public int mask() {
        return mask;
    }

    public void writeStatus(int value) {
        status &= value & VALID_INTERRUPT_BITS;
    }

    public void writeMask(int value) {
        mask = value & VALID_INTERRUPT_BITS;
    }

    public boolean pending() {
        return (status & mask) != 0;
    }

    public String describePending() {
        int pending = status & mask;
        if (pending == 0) {
            return "<none>";
        }
        StringBuilder builder = new StringBuilder();
        for (int bit = 0; bit < SOURCE_NAMES.length; bit++) {
            if ((pending & (1 << bit)) == 0) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(SOURCE_NAMES[bit]);
        }
        int unknown = pending & -(1 << SOURCE_NAMES.length);
        if (unknown != 0) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append("0x").append(Integer.toHexString(unknown));
        }
        return builder.toString();
    }

    public State copyState() {
        State state = new State();
        state.status = status;
        state.mask = mask;
        state.lineState = lineState;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        status = state.status & VALID_INTERRUPT_BITS;
        mask = state.mask & VALID_INTERRUPT_BITS;
        lineState = state.lineState & VALID_INTERRUPT_BITS;
    }

    public static final class State {
        int status;
        int mask;
        int lineState;
    }
}
