package nanolive.psxj.emu.sio;

import java.util.ArrayDeque;

/**
 * Deterministic local SIO1 loopback. DTR is reflected as DSR, RTS as CTS,
 * and every transmitted byte is returned to the receive side in FIFO order.
 */
public final class LoopbackSio1LinkEndpoint implements Sio1LinkEndpoint {

    private final ArrayDeque<Integer> received = new ArrayDeque<>();
    private boolean dataTerminalReady;
    private boolean requestToSend;

    @Override
    public void setControlLines(boolean dataTerminalReady, boolean requestToSend) {
        this.dataTerminalReady = dataTerminalReady;
        this.requestToSend = requestToSend;
    }

    @Override
    public boolean dataSetReady() {
        return dataTerminalReady;
    }

    @Override
    public boolean clearToSend() {
        return requestToSend;
    }

    @Override
    public void transmit(int value) {
        received.addLast(value & 0xFF);
    }

    @Override
    public int pollReceived() {
        return received.isEmpty() ? NO_DATA : received.removeFirst();
    }
}
