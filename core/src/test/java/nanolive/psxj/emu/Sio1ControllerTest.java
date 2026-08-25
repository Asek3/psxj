package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.Sio1Controller;
import nanolive.psxj.emu.sio.LoopbackSio1LinkEndpoint;
import nanolive.psxj.emu.sio.Sio1LinkEndpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class Sio1ControllerTest {

    private static final int DATA = 0x1F80_1050;
    private static final int STAT = 0x1F80_1054;
    private static final int MODE = 0x1F80_1058;
    private static final int CTRL = 0x1F80_105A;
    private static final int MISC = 0x1F80_105C;
    private static final int BAUD = 0x1F80_105E;
    private static final int MODE_8N1_MUL16 = 0x004E;
    private static final int CTRL_LOOPBACK_RX_IRQ = 0x0827;
    private static final int FRAME_CYCLES_9600_8N1 = 35_200;

    @Test
    void busMapsSio1AndLoopbackRaisesIrq8AfterACompleteFrame() {
        InterruptController interrupts = new InterruptController();
        Sio1Controller sio1 = new Sio1Controller(interrupts);
        sio1.setLinkEndpoint(new LoopbackSio1LinkEndpoint());
        Bus bus = new Bus();
        bus.setSio1Controller(sio1);

        bus.write32(MODE, MODE_8N1_MUL16 | (CTRL_LOOPBACK_RX_IRQ << 16));
        bus.write16(BAUD, 0x00DC);
        assertEquals(MODE_8N1_MUL16 | (CTRL_LOOPBACK_RX_IRQ << 16), bus.read32(MODE));
        assertEquals(0x0180, bus.read16(STAT) & 0x0180,
            "loopback mirrors DTR/RTS onto DSR/CTS");

        bus.write8(DATA, 0xA5);
        sio1.tick(FRAME_CYCLES_9600_8N1 - 1);
        assertEquals(0, bus.read16(STAT) & 0x0002);
        assertEquals(0, interrupts.status() & (1 << 8));

        sio1.tick(1);
        assertEquals(0x0002, bus.read16(STAT) & 0x0002);
        assertEquals(1 << 8, interrupts.status() & (1 << 8));
        assertEquals(0xA5, bus.read8(DATA));

        interrupts.writeStatus(~(1 << 8));
        bus.write16(CTRL, CTRL_LOOPBACK_RX_IRQ | 0x0010);
        assertEquals(0, bus.read16(STAT) & 0x0200);
        assertEquals(0, interrupts.status() & (1 << 8));
    }

    @Test
    void ctsGatesTransmissionAndDisablingRxClearsTheFifo() {
        Sio1Controller sio1 = new Sio1Controller(new InterruptController());
        sio1.setLinkEndpoint(new LoopbackSio1LinkEndpoint());
        sio1.write16(MODE, MODE_8N1_MUL16);
        sio1.write16(BAUD, 0x00DC);
        sio1.write16(CTRL, 0x0007); // TXEN, DTR and RXEN; RTS/CTS still low.

        sio1.write8(DATA, 0x5A);
        sio1.tick(FRAME_CYCLES_9600_8N1 * 2);
        assertEquals(0, sio1.status() & 0x0003);
        assertEquals(0, sio1.status() & 0x0004);

        sio1.write16(CTRL, 0x0027);
        sio1.tick(FRAME_CYCLES_9600_8N1);
        assertEquals(0x0002, sio1.status() & 0x0002);

        sio1.write16(CTRL, 0x0023); // RXEN=0 clears all queued receive data.
        assertEquals(0, sio1.status() & 0x0002);
    }

    @Test
    void ninthReceivedByteOverwritesFinalFifoEntryAndSetsOverrun() {
        InjectingEndpoint endpoint = new InjectingEndpoint();
        Sio1Controller sio1 = new Sio1Controller(new InterruptController());
        sio1.setLinkEndpoint(endpoint);
        sio1.write16(MODE, MODE_8N1_MUL16);
        sio1.write16(CTRL, 0x0004);
        for (int value = 0; value < 9; value++) {
            endpoint.inject(value);
        }

        sio1.tick(1);

        assertEquals(0x0010, sio1.status() & 0x0010);
        for (int value = 0; value < 7; value++) {
            assertEquals(value, sio1.read8(DATA));
        }
        assertEquals(8, sio1.read8(DATA));
        sio1.write16(CTRL, 0x0014);
        assertEquals(0, sio1.status() & 0x0010);
    }

    @Test
    void snapshotRestoresAnInFlightSerialFrame() {
        Sio1Controller source = new Sio1Controller(new InterruptController());
        source.setLinkEndpoint(new LoopbackSio1LinkEndpoint());
        configureLoopback(source);
        source.write8(DATA, 0xC3);
        source.tick(10_000);

        Sio1Controller.State state = source.copyState();
        Sio1Controller restored = new Sio1Controller(new InterruptController());
        restored.setLinkEndpoint(new LoopbackSio1LinkEndpoint());
        restored.loadState(state);

        restored.tick(FRAME_CYCLES_9600_8N1 - 10_001);
        assertEquals(0, restored.status() & 0x0002);
        restored.tick(1);
        assertEquals(0xC3, restored.read8(DATA));
    }

    @Test
    void miscRegisterExposesItsDocumentedByteLaneRotation() {
        Sio1Controller sio1 = new Sio1Controller(new InterruptController());

        sio1.write16(MISC, 0x1234);

        assertEquals(0x3412, sio1.read16(MISC));
    }

    @Test
    void txReadyRisesOnlyAfterTheStartBitAndEarlyWriteReplacesTheCharacter() {
        Sio1Controller sio1 = new Sio1Controller(new InterruptController());
        LoopbackSio1LinkEndpoint endpoint = new LoopbackSio1LinkEndpoint();
        sio1.setLinkEndpoint(endpoint);
        configureLoopback(sio1);

        sio1.write8(DATA, 0x12);
        assertEquals(0, sio1.status() & 0x0001);

        sio1.tick(3_519);
        sio1.write8(DATA, 0x34);
        assertEquals(0, sio1.status() & 0x0001);

        sio1.tick(1);
        assertEquals(0x0001, sio1.status() & 0x0001);
        sio1.tick(FRAME_CYCLES_9600_8N1 - 3_520);

        assertEquals(0x34, sio1.read8(DATA));
        assertEquals(0x0004, sio1.status() & 0x0004);
    }

    private static void configureLoopback(Sio1Controller sio1) {
        sio1.write16(MODE, MODE_8N1_MUL16);
        sio1.write16(BAUD, 0x00DC);
        sio1.write16(CTRL, 0x0027);
    }

    private static final class InjectingEndpoint implements Sio1LinkEndpoint {
        private final ArrayDeque<Integer> received = new ArrayDeque<>();

        void inject(int value) {
            received.addLast(value & 0xFF);
        }

        @Override
        public void setControlLines(boolean dataTerminalReady, boolean requestToSend) {
        }

        @Override
        public boolean dataSetReady() {
            return false;
        }

        @Override
        public boolean clearToSend() {
            return true;
        }

        @Override
        public void transmit(int value) {
        }

        @Override
        public int pollReceived() {
            return received.isEmpty() ? NO_DATA : received.removeFirst();
        }
    }
}
