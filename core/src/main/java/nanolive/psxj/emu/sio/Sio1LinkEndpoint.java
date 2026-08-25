package nanolive.psxj.emu.sio;

/**
 * Byte-oriented boundary between the PlayStation SIO1 UART and a local link
 * transport. Bytes returned by {@link #pollReceived()} are complete UART
 * frames; bit timing remains the responsibility of the SIO1 device.
 */
public interface Sio1LinkEndpoint {

    int NO_DATA = -1;

    void setControlLines(boolean dataTerminalReady, boolean requestToSend);

    // Remote DTR as observed on the console's DSR input.
    boolean dataSetReady();

    // Remote RTS as observed on the console's CTS input.
    boolean clearToSend();

    // Accepts one fully transmitted byte from the console.
    void transmit(int value);

    int pollReceived();
}
