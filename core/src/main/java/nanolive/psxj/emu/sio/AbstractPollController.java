package nanolive.psxj.emu.sio;

import java.util.Arrays;

/** Common 42h poll-command shift protocol used by simple PSX controllers. */
abstract class AbstractPollController implements ControllerDevice {

    private static final int RESPONSE_MARKER = 0x5A;

    private boolean commandActive;
    private int responseIndex;
    private int[] latchedPayload = new int[0];

    protected abstract int controllerId();

    protected abstract int[] capturePollPayload();

    protected void receivePollByte(int payloadIndex, int value) {
    }

    @Override
    public final CommandResult beginCommand(int command) {
        if ((command & 0xFF) != 0x42) {
            cancelTransaction();
            return CommandResult.last(0xFF);
        }
        commandActive = true;
        responseIndex = -1;
        latchedPayload = capturePollPayload();
        return CommandResult.more(controllerId());
    }

    @Override
    public final CommandResult exchangeCommandByte(int value) {
        if (!commandActive) {
            return CommandResult.last(0xFF);
        }
        if (responseIndex < 0) {
            responseIndex = 0;
            return CommandResult.more(RESPONSE_MARKER);
        }

        int index = responseIndex++;
        int response = index < latchedPayload.length ? latchedPayload[index] : 0xFF;
        receivePollByte(index, value & 0xFF);
        if (responseIndex >= latchedPayload.length) {
            cancelTransaction();
            return CommandResult.last(response);
        }
        return CommandResult.more(response);
    }

    @Override
    public void cancelTransaction() {
        commandActive = false;
        responseIndex = 0;
    }

    protected final int[] copyProtocolState() {
        int[] state = new int[3 + latchedPayload.length];
        state[0] = commandActive ? 1 : 0;
        state[1] = responseIndex;
        state[2] = latchedPayload.length;
        System.arraycopy(latchedPayload, 0, state, 3, latchedPayload.length);
        return state;
    }

    protected final void loadProtocolState(int[] state) {
        if (state == null || state.length < 3) {
            cancelTransaction();
            latchedPayload = new int[0];
            return;
        }
        commandActive = state[0] != 0;
        responseIndex = state[1];
        int length = Math.clamp(state[2], 0, state.length - 3);
        latchedPayload = Arrays.copyOfRange(state, 3, 3 + length);
    }

    protected static int value(int[] values, int index, int fallback) {
        return values != null && index >= 0 && index < values.length
            ? values[index]
            : fallback;
    }
}
