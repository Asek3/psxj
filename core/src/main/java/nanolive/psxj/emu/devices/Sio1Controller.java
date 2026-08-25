package nanolive.psxj.emu.devices;

import nanolive.psxj.emu.sio.Sio1LinkEndpoint;

import java.util.ArrayDeque;
import java.util.Iterator;

/** PlayStation SIO1 asynchronous serial interface (1F801050h-1F80105Fh). */
public final class Sio1Controller {

    private static final int STATUS_TX_READY = 0x0001;
    private static final int STATUS_RX_NOT_EMPTY = 0x0002;
    private static final int STATUS_TX_IDLE = 0x0004;
    private static final int STATUS_PARITY_ERROR = 0x0008;
    private static final int STATUS_RX_OVERRUN = 0x0010;
    private static final int STATUS_BAD_STOP_BIT = 0x0020;
    private static final int STATUS_RX_INPUT_LEVEL = 0x0040;
    private static final int STATUS_DSR_LEVEL = 0x0080;
    private static final int STATUS_CTS_LEVEL = 0x0100;
    private static final int STATUS_IRQ = 0x0200;

    private static final int CTRL_TX_ENABLE = 0x0001;
    private static final int CTRL_DTR = 0x0002;
    private static final int CTRL_RX_ENABLE = 0x0004;
    private static final int CTRL_TX_INVERT = 0x0008;
    private static final int CTRL_ACKNOWLEDGE = 0x0010;
    private static final int CTRL_RTS = 0x0020;
    private static final int CTRL_RESET = 0x0040;
    private static final int CTRL_UNKNOWN_7 = 0x0080;
    private static final int CTRL_TX_IRQ_ENABLE = 0x0400;
    private static final int CTRL_RX_IRQ_ENABLE = 0x0800;
    private static final int CTRL_DSR_IRQ_ENABLE = 0x1000;
    private static final int CTRL_READABLE_MASK = 0x1FAF;

    private static final int MODE_READABLE_MASK = 0x00FF;
    private static final int RX_FIFO_CAPACITY = 8;
    private static final int ENDPOINT_DRAIN_LIMIT = 256;

    private final InterruptController interruptController;
    private final ArrayDeque<Integer> rxFifo = new ArrayDeque<>(RX_FIFO_CAPACITY);

    private Sio1LinkEndpoint endpoint;
    private int mode;
    private int control;
    private int misc;
    private int miscWriteLatch;
    private int baudrate;
    private int baudrateTimer;
    private int baudrateReload;

    // Byte in the active shift register, or waiting for TXEN/CTS.
    private int queuedTxData = -1;
    // One-byte TX holding register.
    private int holdingTxData = -1;
    private boolean queuedTxEnableLatched;
    private boolean holdingTxEnableLatched;
    private int serialCyclesRemaining;
    // Clocks until the active frame has completely shifted its start bit.
    private int txStartBitCyclesRemaining;

    private boolean parityError;
    private boolean rxOverrun;
    private boolean badStopBit;
    private boolean rxInputLevel;
    private boolean irqPending;
    private int lastRxByte;
    private int emptyRxReads;

    public Sio1Controller(InterruptController interruptController) {
        this.interruptController = interruptController;
    }

    public void setLinkEndpoint(Sio1LinkEndpoint endpoint) {
        this.endpoint = endpoint;
        updateEndpointControlLines();
        startWaitingTransferIfEnabled();
        serviceEndpointReceive();
        evaluateLevelInterrupts();
    }

    public Sio1LinkEndpoint linkEndpoint() {
        return endpoint;
    }

    public int read8(int address) {
        int offset = address & 0x0F;
        if (offset <= 3) {
            return offset == 0 ? popRxByte() : peekRxByte(offset);
        }
        int register;
        int shift;
        if (offset >= 4 && offset <= 7) {
            register = status();
            shift = (offset - 4) * 8;
        } else if (offset >= 8 && offset <= 9) {
            register = mode;
            shift = (offset - 8) * 8;
        } else if (offset >= 0xA && offset <= 0xB) {
            register = readableControl();
            shift = (offset - 0xA) * 8;
        } else if (offset >= 0xC && offset <= 0xD) {
            register = misc;
            shift = (offset - 0xC) * 8;
        } else if (offset >= 0xE) {
            register = baudrate;
            shift = (offset - 0xE) * 8;
        } else {
            return 0xFF;
        }
        return (register >>> shift) & 0xFF;
    }

    public int read16(int address) {
        return switch (address & 0x0F) {
            case 0x0 -> {
                int value = peekRxByte(0) | (peekRxByte(1) << 8);
                popRxByte();
                yield value;
            }
            case 0x2 -> peekRxByte(1) | (peekRxByte(2) << 8);
            case 0x4 -> status() & 0xFFFF;
            case 0x6 -> (status() >>> 16) & 0xFFFF;
            case 0x8 -> mode & 0xFFFF;
            case 0xA -> readableControl();
            case 0xC -> misc & 0xFFFF;
            case 0xE -> baudrate & 0xFFFF;
            default -> 0xFFFF;
        };
    }

    public int read32(int address) {
        return switch (address & 0x0F) {
            case 0x0 -> {
                int value = peekRxByte(0)
                    | (peekRxByte(1) << 8)
                    | (peekRxByte(2) << 16)
                    | (peekRxByte(3) << 24);
                int removed = 0;
                while (removed < 4 && !rxFifo.isEmpty()) {
                    lastRxByte = rxFifo.removeFirst() & 0xFF;
                    removed++;
                }
                emptyRxReads = removed < 4 ? Math.min(3, 4 - removed) : 0;
                evaluateLevelInterrupts();
                yield value;
            }
            case 0x4 -> status();
            case 0x8 -> (mode & 0xFFFF) | (readableControl() << 16);
            case 0xC -> (misc & 0xFFFF) | ((baudrate & 0xFFFF) << 16);
            default -> 0xFFFF_FFFF;
        };
    }

    public void write8(int address, int value) {
        value &= 0xFF;
        switch (address & 0x0F) {
            case 0x0 -> startTransfer(value);
            case 0x8 -> writeMode((mode & 0xFF00) | value);
            case 0x9 -> writeMode((mode & 0x00FF) | (value << 8));
            case 0xA -> writeControl((control & 0xFF00) | value);
            case 0xB -> writeControl((control & 0x00FF) | (value << 8));
            case 0xC -> writeMisc((miscWriteLatch & 0xFF00) | value);
            case 0xD -> writeMisc((miscWriteLatch & 0x00FF) | (value << 8));
            case 0xE -> {
                baudrate = (baudrate & 0xFF00) | value;
                reloadBaudrateTimer();
            }
            case 0xF -> {
                baudrate = (baudrate & 0x00FF) | (value << 8);
                reloadBaudrateTimer();
            }
            default -> {
            }
        }
    }

    public void write16(int address, int value) {
        value &= 0xFFFF;
        switch (address & 0x0F) {
            case 0x0 -> startTransfer(value & 0xFF);
            case 0x8 -> writeMode(value);
            case 0xA -> writeControl(value);
            case 0xC -> writeMisc(value);
            case 0xE -> {
                baudrate = value;
                reloadBaudrateTimer();
            }
            default -> {
            }
        }
    }

    public int status() {
        int value = 0;
        if (txFifoReady()) {
            value |= STATUS_TX_READY;
        }
        if (!rxFifo.isEmpty()) {
            value |= STATUS_RX_NOT_EMPTY;
        }
        if (queuedTxData < 0 && holdingTxData < 0 && serialCyclesRemaining == 0
            && ((control & CTRL_TX_ENABLE) == 0 || clearToSend())) {
            value |= STATUS_TX_IDLE;
        }
        if (parityError) {
            value |= STATUS_PARITY_ERROR;
        }
        if (rxOverrun) {
            value |= STATUS_RX_OVERRUN;
        }
        if (badStopBit) {
            value |= STATUS_BAD_STOP_BIT;
        }
        if (rxInputLevel) {
            value |= STATUS_RX_INPUT_LEVEL;
        }
        if (dataSetReady()) {
            value |= STATUS_DSR_LEVEL;
        }
        if (clearToSend()) {
            value |= STATUS_CTS_LEVEL;
        }
        if (irqPending) {
            value |= STATUS_IRQ;
        }
        return value | ((baudrateTimer & 0x1F_FFFF) << 11);
    }

    public void tick(int cycles) {
        if (cycles <= 0) {
            return;
        }
        if (baudrateReload == 0
            && endpoint == null
            && serialCyclesRemaining <= 0
            && queuedTxData < 0) {
            return;
        }
        advanceBaudrateTimer(cycles);
        if (endpoint == null && serialCyclesRemaining <= 0 && queuedTxData < 0) {
            return;
        }
        serviceEndpointReceive();
        startWaitingTransferIfEnabled();

        int remaining = cycles;
        while (remaining > 0 && serialCyclesRemaining > 0) {
            int step = Math.min(remaining, serialCyclesRemaining);
            serialCyclesRemaining -= step;
            txStartBitCyclesRemaining = Math.max(0, txStartBitCyclesRemaining - step);
            remaining -= step;
            if (serialCyclesRemaining == 0) {
                completeTransfer();
            }
        }
        serviceEndpointReceive();
        startWaitingTransferIfEnabled();
        evaluateLevelInterrupts();
    }

    // Check for IRQ edges before batching clocks.
    public boolean interruptStableFor(int cycles) {
        if (cycles <= 0 || irqPending) {
            return true;
        }
        int irqControls = CTRL_TX_IRQ_ENABLE | CTRL_RX_IRQ_ENABLE | CTRL_DSR_IRQ_ENABLE;
        if ((control & irqControls) == 0) {
            return true;
        }
        return endpoint == null && serialCyclesRemaining <= 0 && queuedTxData < 0;
    }

    private void writeMode(int value) {
        mode = value & MODE_READABLE_MASK;
        if (baudrateFactor() == 0) {
            control &= ~CTRL_UNKNOWN_7;
        }
        reloadBaudrateTimer();
        startWaitingTransferIfEnabled();
        evaluateLevelInterrupts();
    }

    private void writeControl(int value) {
        if ((value & CTRL_RESET) != 0) {
            hardReset();
            return;
        }

        boolean acknowledge = (value & CTRL_ACKNOWLEDGE) != 0;
        control = value & CTRL_READABLE_MASK & ~(CTRL_ACKNOWLEDGE | CTRL_RESET);
        if (baudrateFactor() == 0) {
            control &= ~CTRL_UNKNOWN_7;
        }
        if ((control & CTRL_RX_ENABLE) == 0) {
            rxFifo.clear();
            emptyRxReads = 0;
        }
        updateEndpointControlLines();
        if (acknowledge) {
            parityError = false;
            rxOverrun = false;
            badStopBit = false;
            clearIrq();
        }
        serviceEndpointReceive();
        startWaitingTransferIfEnabled();
        evaluateLevelInterrupts();
    }

    private void writeMisc(int value) {
        // Hardware swaps the byte lanes on the internal MISC path.
        miscWriteLatch = value & 0xFFFF;
        misc = ((value >>> 8) | (value << 8)) & 0xFFFF;
    }

    private int readableControl() {
        return control & (baudrateFactor() == 0
            ? ~CTRL_UNKNOWN_7
            : 0xFFFF) & 0xFFFF;
    }

    private void startTransfer(int value) {
        boolean txEnabled = (control & CTRL_TX_ENABLE) != 0;
        if (serialCyclesRemaining > 0) {
            if (txStartBitCyclesRemaining > 0) {
                queuedTxData = value & 0xFF;
                queuedTxEnableLatched = txEnabled;
            } else {
                holdingTxData = value & 0xFF;
                holdingTxEnableLatched = txEnabled;
            }
            return;
        }
        if (queuedTxData >= 0) {
            queuedTxData = value & 0xFF;
            queuedTxEnableLatched = txEnabled;
            return;
        }
        queuedTxData = value & 0xFF;
        queuedTxEnableLatched = txEnabled;
        startWaitingTransferIfEnabled();
        evaluateLevelInterrupts();
    }

    private void startWaitingTransferIfEnabled() {
        if (serialCyclesRemaining != 0 || queuedTxData < 0 || baudrateFactor() == 0) {
            return;
        }
        if ((control & CTRL_TX_ENABLE) == 0 && !queuedTxEnableLatched) {
            return;
        }
        if (!clearToSend()) {
            return;
        }
        serialCyclesRemaining = transferCyclesPerFrame();
        txStartBitCyclesRemaining = cyclesPerBit();
    }

    private void completeTransfer() {
        int txByte = queuedTxData & characterMask();
        queuedTxData = -1;
        queuedTxEnableLatched = false;
        if (endpoint != null) {
            endpoint.transmit(txByte);
        }
        serviceEndpointReceive();

        if (holdingTxData >= 0) {
            queuedTxData = holdingTxData;
            queuedTxEnableLatched = holdingTxEnableLatched;
            holdingTxData = -1;
            holdingTxEnableLatched = false;
            startWaitingTransferIfEnabled();
        }
        evaluateLevelInterrupts();
    }

    private void serviceEndpointReceive() {
        if (endpoint == null) {
            return;
        }
        for (int count = 0; count < ENDPOINT_DRAIN_LIMIT; count++) {
            int value = endpoint.pollReceived();
            if (value == Sio1LinkEndpoint.NO_DATA) {
                return;
            }
            if ((control & CTRL_RX_ENABLE) != 0) {
                pushRxByte(value & characterMask());
            }
        }
    }

    private void pushRxByte(int value) {
        value &= 0xFF;
        if (rxFifo.size() >= RX_FIFO_CAPACITY) {
            rxFifo.removeLast();
            rxOverrun = true;
        }
        rxFifo.addLast(value);
        lastRxByte = value;
        emptyRxReads = 0;
        rxInputLevel = false;
        evaluateLevelInterrupts();
    }

    private int popRxByte() {
        if (!rxFifo.isEmpty()) {
            int value = rxFifo.removeFirst() & 0xFF;
            lastRxByte = value;
            emptyRxReads = 0;
            evaluateLevelInterrupts();
            return value;
        }
        if (emptyRxReads++ < 2) {
            return lastRxByte & 0xFF;
        }
        return 0;
    }

    private int peekRxByte(int index) {
        if (index < 0) {
            return 0;
        }
        Iterator<Integer> iterator = rxFifo.iterator();
        int current = 0;
        while (iterator.hasNext()) {
            int value = iterator.next() & 0xFF;
            if (current++ == index) {
                return value;
            }
        }
        return rxFifo.isEmpty() ? lastRxByte & 0xFF : rxFifo.peekLast() & 0xFF;
    }

    private boolean txFifoReady() {
        if (!clearToSend()) {
            return false;
        }
        if (serialCyclesRemaining > 0) {
            return txStartBitCyclesRemaining == 0 && holdingTxData < 0;
        }
        return queuedTxData < 0;
    }

    private int cyclesPerBit() {
        long cycles = Math.max(
            ((long) (baudrate & 0xFFFF) * baudrateFactor()) & ~1L,
            baudrateFactor()
        );
        return (int) Math.clamp(cycles, 1, Integer.MAX_VALUE);
    }

    private int transferCyclesPerFrame() {
        long bitCycles = cyclesPerBit();
        int dataBits = 5 + ((mode >>> 2) & 0x03);
        int parityBits = (mode & 0x10) != 0 ? 1 : 0;
        int stopHalfBits = switch ((mode >>> 6) & 0x03) {
            case 2 -> 3;
            case 3 -> 4;
            default -> 2;
        };
        int frameHalfBits = (1 + dataBits + parityBits) * 2 + stopHalfBits;
        long cycles = (bitCycles * frameHalfBits + 1) >>> 1;
        return (int) Math.clamp(cycles, 1, Integer.MAX_VALUE);
    }

    private int characterMask() {
        return (1 << (5 + ((mode >>> 2) & 0x03))) - 1;
    }

    private int baudrateFactor() {
        return switch (mode & 0x03) {
            case 1 -> 1;
            case 2 -> 16;
            case 3 -> 64;
            default -> 0;
        };
    }

    private void reloadBaudrateTimer() {
        int factor = baudrateFactor();
        baudrateReload = factor == 0
            ? 0
            : (int) ((((long) (baudrate & 0xFFFF) * factor) & ~1L) >>> 1);
        baudrateTimer = baudrateReload;
    }

    private void advanceBaudrateTimer(int cycles) {
        int reload = baudrateReload;
        if (reload == 0) {
            baudrateTimer = 0;
            return;
        }
        if (baudrateTimer <= 0 || baudrateTimer > reload) {
            baudrateTimer = reload;
        }
        if (cycles < baudrateTimer) {
            baudrateTimer -= cycles;
            return;
        }
        int afterReload = (cycles - baudrateTimer) % reload;
        baudrateTimer = afterReload == 0 ? reload : reload - afterReload;
    }

    private boolean dataSetReady() {
        return endpoint != null && endpoint.dataSetReady();
    }

    private boolean clearToSend() {
        return endpoint != null && endpoint.clearToSend();
    }

    private void updateEndpointControlLines() {
        if (endpoint != null) {
            endpoint.setControlLines(
                (control & CTRL_DTR) != 0,
                (control & CTRL_RTS) != 0
            );
        }
    }

    private void evaluateLevelInterrupts() {
        if ((control & CTRL_DSR_IRQ_ENABLE) != 0 && dataSetReady()) {
            setIrqPending();
        }
        if ((control & CTRL_TX_IRQ_ENABLE) != 0
            && (status() & (STATUS_TX_READY | STATUS_TX_IDLE)) != 0) {
            setIrqPending();
        }
        if ((control & CTRL_RX_IRQ_ENABLE) != 0
            && rxFifo.size() >= rxInterruptThreshold()) {
            setIrqPending();
        }
    }

    private int rxInterruptThreshold() {
        return 1 << ((control >>> 8) & 0x03);
    }

    private void setIrqPending() {
        if (!irqPending) {
            irqPending = true;
            interruptController.raise(8);
        }
    }

    private void clearIrq() {
        irqPending = false;
        interruptController.clear(8);
    }

    private void hardReset() {
        interruptController.clear(8);
        mode = 0;
        control = 0;
        misc = 0;
        miscWriteLatch = 0;
        baudrate = 0;
        baudrateTimer = 0;
        baudrateReload = 0;
        queuedTxData = -1;
        holdingTxData = -1;
        queuedTxEnableLatched = false;
        holdingTxEnableLatched = false;
        serialCyclesRemaining = 0;
        txStartBitCyclesRemaining = 0;
        parityError = false;
        rxOverrun = false;
        badStopBit = false;
        rxInputLevel = false;
        irqPending = false;
        rxFifo.clear();
        lastRxByte = 0;
        emptyRxReads = 0;
        updateEndpointControlLines();
    }

    public State copyState() {
        State state = new State();
        state.rxFifo = rxFifo.stream().mapToInt(Integer::intValue).toArray();
        state.mode = mode;
        state.control = control;
        state.misc = misc;
        state.miscWriteLatch = miscWriteLatch;
        state.baudrate = baudrate;
        state.baudrateTimer = baudrateTimer;
        state.queuedTxData = queuedTxData;
        state.holdingTxData = holdingTxData;
        state.queuedTxEnableLatched = queuedTxEnableLatched;
        state.holdingTxEnableLatched = holdingTxEnableLatched;
        state.serialCyclesRemaining = serialCyclesRemaining;
        state.txStartBitCyclesRemaining = txStartBitCyclesRemaining;
        state.parityError = parityError;
        state.rxOverrun = rxOverrun;
        state.badStopBit = badStopBit;
        state.rxInputLevel = rxInputLevel;
        state.irqPending = irqPending;
        state.lastRxByte = lastRxByte;
        state.emptyRxReads = emptyRxReads;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        rxFifo.clear();
        if (state.rxFifo != null) {
            for (int value : state.rxFifo) {
                if (rxFifo.size() == RX_FIFO_CAPACITY) {
                    break;
                }
                rxFifo.addLast(value & 0xFF);
            }
        }
        mode = state.mode & MODE_READABLE_MASK;
        control = state.control & CTRL_READABLE_MASK;
        misc = state.misc & 0xFFFF;
        miscWriteLatch = state.miscWriteLatch & 0xFFFF;
        baudrate = state.baudrate & 0xFFFF;
        int factor = baudrateFactor();
        baudrateReload = factor == 0
            ? 0
            : (int) ((((long) baudrate * factor) & ~1L) >>> 1);
        baudrateTimer = state.baudrateTimer;
        queuedTxData = state.queuedTxData;
        holdingTxData = state.holdingTxData;
        queuedTxEnableLatched = state.queuedTxEnableLatched;
        holdingTxEnableLatched = state.holdingTxEnableLatched;
        serialCyclesRemaining = state.serialCyclesRemaining;
        txStartBitCyclesRemaining = Math.clamp(
            state.txStartBitCyclesRemaining,
            0,
            Math.max(0, serialCyclesRemaining)
        );
        parityError = state.parityError;
        rxOverrun = state.rxOverrun;
        badStopBit = state.badStopBit;
        rxInputLevel = state.rxInputLevel;
        irqPending = state.irqPending;
        lastRxByte = state.lastRxByte;
        emptyRxReads = state.emptyRxReads;
        updateEndpointControlLines();
        if (irqPending) {
            interruptController.raise(8);
        } else {
            interruptController.clear(8);
        }
    }

    public static final class State {
        int[] rxFifo;
        int mode;
        int control;
        int misc;
        int miscWriteLatch;
        int baudrate;
        int baudrateTimer;
        int queuedTxData;
        int holdingTxData;
        boolean queuedTxEnableLatched;
        boolean holdingTxEnableLatched;
        int serialCyclesRemaining;
        int txStartBitCyclesRemaining;
        boolean parityError;
        boolean rxOverrun;
        boolean badStopBit;
        boolean rxInputLevel;
        boolean irqPending;
        int lastRxByte;
        int emptyRxReads;
    }
}
