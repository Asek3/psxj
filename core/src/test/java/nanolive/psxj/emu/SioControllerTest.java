package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.sio.MemoryCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SioControllerTest {

    private static final int DATA = 0x1F801040;
    private static final int STAT = 0x1F801044;
    private static final int MODE = 0x1F801048;
    private static final int CTRL = 0x1F80104A;
    private static final int BAUD = 0x1F80104E;
    private static final int CTRL_PORT1_DSR_IRQ = 0x1003;

    @Test
    void controllerHandshakeReturnsDigitalPadId() {
        InterruptController interrupts = new InterruptController();
        SioController sio = new SioController(interrupts);
        sio.write16(0x1F80104A, 0x1007);
        sio.write8(0x1F801040, 0x01);
        sio.tick(2_000);
        sio.write8(0x1F801040, 0x42);
        sio.tick(2_000);
        assertEquals(0xFF, sio.read8(0x1F801040));
        assertEquals(0x41, sio.read8(0x1F801040));
        sio.tick(1_000);
        assertTrue((interrupts.status() & (1 << 7)) != 0);
    }

    @Test
    void secondPhysicalPortCanHostAnIndependentController() {
        SioController sio = new SioController(new InterruptController());
        sio.setControllerConnected(1, true);
        sio.setControllerState(0, SioController.PAD_CIRCLE, 0x80, 0x80, 0x80, 0x80);
        sio.setControllerState(1, SioController.PAD_CROSS, 0x80, 0x80, 0x80, 0x80);
        sio.write16(MODE, 0x000D);
        sio.write16(BAUD, 0x0088);

        int port2Control = CTRL_PORT1_DSR_IRQ | 0x2000;
        sio.write16(CTRL, port2Control);
        int[] response = exchangeWithControl(
            sio,
            port2Control,
            0x01,
            0x42,
            0x00,
            0x00,
            0x00
        );

        assertArrayEquals(new int[] {0xFF, 0x41, 0x5A, 0xFF, 0xBF}, response);
    }

    @Test
    void snapshotRestoresSecondPortSelectionAndControllerTransaction() {
        SioController sio = new SioController(new InterruptController());
        sio.setControllerConnected(1, true);
        sio.write16(MODE, 0x000D);
        sio.write16(BAUD, 0x0088);
        sio.write16(CTRL, CTRL_PORT1_DSR_IRQ | 0x2000);
        sio.write8(DATA, 0x01);
        sio.tick(2_000);

        SioController.State state = sio.copyState();
        sio.write16(CTRL, 0x0040);
        sio.setControllerConnected(1, false);
        sio.loadState(state);
        sio.write8(DATA, 0x42);
        sio.tick(2_000);

        assertEquals(0xFF, sio.read8(DATA));
        assertEquals(0x41, sio.read8(DATA));
    }

    @Test
    void dualShockConfigurationEnablesAnalogAxes() {
        SioController sio = new SioController(new InterruptController());
        configureRetailBus(sio);

        assertArrayEquals(
            new int[] {0xFF, 0x41, 0x5A, 0xFF, 0xFF},
            exchange(sio, 0x01, 0x43, 0x00, 0x01, 0x00));
        exchange(sio, 0x01, 0x44, 0x00, 0x01, 0x03, 0, 0, 0, 0);
        exchange(sio, 0x01, 0x43, 0x00, 0x00, 0, 0, 0, 0, 0);

        sio.setControllerState(
            SioController.PAD_CROSS | SioController.PAD_L3,
            0x11,
            0x22,
            0x33,
            0x44
        );
        assertArrayEquals(
            new int[] {0xFF, 0x73, 0x5A, 0xFD, 0xBF, 0x33, 0x44, 0x11, 0x22},
            exchange(sio, 0x01, 0x42, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void dualShockReportsDpadAndAnalogSticksAsIndependentInputs() {
        SioController sio = new SioController(new InterruptController());
        configureRetailBus(sio);

        exchange(sio, 0x01, 0x43, 0x00, 0x01, 0x00);
        exchange(sio, 0x01, 0x44, 0x00, 0x01, 0x03, 0, 0, 0, 0);
        exchange(sio, 0x01, 0x43, 0x00, 0x00, 0, 0, 0, 0, 0);

        sio.setControllerState(0, 0x20, 0xE0, 0x40, 0xC0);

        assertArrayEquals(
            new int[] {0xFF, 0x73, 0x5A, 0xFF, 0xFF, 0x40, 0xC0, 0x20, 0xE0},
            exchange(sio, 0x01, 0x42, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void dualShockRumbleMapDrivesBothHostMotors() {
        SioController sio = new SioController(new InterruptController());
        configureRetailBus(sio);
        int[] rumble = new int[2];
        sio.setRumbleHandler((largeMotor, smallMotor) -> {
            rumble[0] = largeMotor;
            rumble[1] = smallMotor ? 1 : 0;
        });

        exchange(sio, 0x01, 0x43, 0x00, 0x01, 0x00);
        exchange(sio, 0x01, 0x4D, 0x00, 0x00, 0x01, 0xFF, 0xFF, 0xFF, 0xFF);
        exchange(sio, 0x01, 0x43, 0x00, 0x00, 0, 0, 0, 0, 0);
        exchange(sio, 0x01, 0x42, 0x00, 0x01, 0xC0);

        assertEquals(0xC0, rumble[0]);
        assertEquals(1, rumble[1]);
    }

    @Test
    void snapshotRestoresControllerTransactionInProgress() {
        SioController sio = new SioController(new InterruptController());
        sio.write16(0x1F80104A, 0x1007);
        sio.write8(0x1F801040, 0x01);
        sio.tick(2_000);

        SioController.State state = sio.copyState();

        sio.write16(0x1F80104A, 0x0040);
        sio.loadState(state);
        sio.write8(0x1F801040, 0x42);
        sio.tick(2_000);

        assertEquals(0xFF, sio.read8(0x1F801040));
        assertEquals(0x41, sio.read8(0x1F801040));
    }

    @Test
    void ackEdgeAndBaudTimerMatchRetailSio0Timing() {
        InterruptController interrupts = new InterruptController();
        SioController sio = new SioController(interrupts);
        configureRetailBus(sio);

        assertEquals(0x44, sio.status() >>> 11);
        sio.write8(DATA, 0x01);

        sio.tick(1_499);
        assertEquals(0, sio.read8(STAT) & 0x80);
        assertEquals(0, interrupts.status() & (1 << 7));

        sio.tick(1);
        assertEquals(0x80, sio.read8(STAT) & 0x80);
        assertTrue((interrupts.status() & (1 << 7)) != 0);

        sio.tick(100);
        assertEquals(0, sio.read8(STAT) & 0x80);
        assertEquals(0x200, sio.status() & 0x200);
        sio.write16(CTRL, CTRL_PORT1_DSR_IRQ | 0x10);
        assertEquals(0, sio.status() & 0x200);
    }

    @Test
    void acknowledgingIrqDuringActiveAckPulseClearsThenReassertsIt() {
        InterruptController interrupts = new InterruptController();
        SioController sio = new SioController(interrupts);
        configureRetailBus(sio);

        sio.write8(DATA, 0x01);
        sio.tick(1_500);
        assertEquals(0x80, sio.status() & 0x80);
        assertEquals(0x200, sio.status() & 0x200);

        interrupts.writeStatus(~(1 << 7));
        assertEquals(0, interrupts.status() & (1 << 7));

        sio.write16(CTRL, CTRL_PORT1_DSR_IRQ | 0x10);

        assertEquals(0x200, sio.status() & 0x200,
            "the active /ACK level must immediately restore STAT.9");
        assertEquals(1 << 7, interrupts.status() & (1 << 7),
            "the brief device-side clear must create a new IRQ edge");
    }

    @Test
    void sonyMemoryCardReadAndIdSequencesAreExact(@TempDir Path tempDir) throws Exception {
        SioController sio = new SioController(new InterruptController());
        sio.attachMemoryCards(tempDir.resolve("card.mcd"), null);
        configureRetailBus(sio);

        assertArrayEquals(
            new int[] {0xFF, 0x08, 0x5A, 0x5D, 0x5C, 0x5D, 0x04, 0x00, 0x00, 0x80},
            exchange(sio, 0x81, 0x53, 0, 0, 0, 0, 0, 0, 0, 0));

        deselectAndSelect(sio);
        int[] command = new int[10 + 128 + 2];
        command[0] = 0x81;
        command[1] = 0x52;
        command[4] = 0x00;
        command[5] = 0x00;
        int[] response = exchange(sio, command);

        assertArrayEquals(
            new int[] {0xFF, 0x08, 0x5A, 0x5D, 0x00, 0x00, 0x5C, 0x5D, 0x00, 0x00},
            java.util.Arrays.copyOf(response, 10));
        assertEquals('M', response[10]);
        assertEquals('C', response[11]);
        for (int i = 12; i < 10 + 127; i++) {
            assertEquals(0, response[i]);
        }
        assertEquals(0x0E, response[10 + 127]);
        assertEquals(0x00, response[10 + 128]);
        assertEquals(0x47, response[10 + 129]);
    }

    @Test
    void memoryCardWriteIsCommittedOnlyForValidChecksum(@TempDir Path tempDir) throws Exception {
        SioController sio = new SioController(new InterruptController());
        sio.attachMemoryCards(tempDir.resolve("card.mcd"), null);
        java.util.ArrayList<Integer> writtenSlots = new java.util.ArrayList<>();
        sio.setMemoryCardWriteListener(writtenSlots::add);
        configureRetailBus(sio);

        byte[] payload = new byte[128];
        java.util.Arrays.fill(payload, (byte) 0x3C);
        int[] valid = memoryCardWriteCommand(1, payload, false);
        int[] response = exchange(sio, valid);

        assertEquals(0x47, response[response.length - 1]);
        assertEquals(0x3C, sio.copyMemoryCard(0)[128] & 0xFF);
        assertEquals(0x3C, MemoryCard.openOrCreate(tempDir.resolve("card.mcd")).readByte(128));
        assertEquals(java.util.List.of(1), writtenSlots);

        deselectAndSelect(sio);
        byte[] replacement = new byte[128];
        java.util.Arrays.fill(replacement, (byte) 0x55);
        int[] invalid = memoryCardWriteCommand(1, replacement, true);
        response = exchange(sio, invalid);

        assertEquals(0x4E, response[response.length - 1]);
        assertEquals(0x3C, sio.copyMemoryCard(0)[128] & 0xFF);
        assertEquals(java.util.List.of(1), writtenSlots,
            "a rejected checksum must not emit a successful-write event");

        deselectAndSelect(sio);
        int[] id = exchange(sio, 0x81, 0x53, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0x00, id[1], "a write command clears Sony card FLAG bit 3");
    }

    @Test
    void invalidSonyCardSectorReturnsFfffAndAborts(@TempDir Path tempDir) throws Exception {
        SioController sio = new SioController(new InterruptController());
        sio.attachMemoryCards(tempDir.resolve("card.mcd"), null);
        configureRetailBus(sio);

        int[] response = exchange(sio, 0x81, 0x52, 0, 0, 0x04, 0x00, 0, 0, 0, 0);

        assertEquals(0xFF, response[8]);
        assertEquals(0xFF, response[9]);
        sio.write8(DATA, 0);
        sio.tick(2_000);
        assertFalse((sio.read8(STAT) & 0x80) != 0);
    }

    @Test
    void wordReadReturnsFourPreviewsAndConsumesFourRxEntries() {
        SioController sio = new SioController(new InterruptController());
        sio.write16(MODE, 0x000D);
        sio.write16(BAUD, 0x0088);
        sio.write16(CTRL, 0x0003);
        for (int value : new int[] {0x01, 0x42, 0x00, 0x00, 0x00}) {
            sio.write8(DATA, value);
            sio.tick(2_000);
        }

        assertEquals(0xFF5A41FF, sio.read32(DATA));
        assertEquals(0x02, sio.read8(STAT) & 0x02);
        assertEquals(0xFF, sio.read8(DATA));
        assertEquals(0, sio.read8(STAT) & 0x02);
    }

    private static void configureRetailBus(SioController sio) {
        sio.write16(MODE, 0x000D);
        sio.write16(BAUD, 0x0088);
        sio.write16(CTRL, CTRL_PORT1_DSR_IRQ);
    }

    private static void deselectAndSelect(SioController sio) {
        sio.write16(CTRL, 0);
        sio.write16(CTRL, CTRL_PORT1_DSR_IRQ);
    }

    private static int[] exchange(SioController sio, int... command) {
        return exchangeWithControl(sio, CTRL_PORT1_DSR_IRQ, command);
    }

    private static int[] exchangeWithControl(SioController sio, int control, int... command) {
        int[] response = new int[command.length];
        for (int i = 0; i < command.length; i++) {
            sio.write8(DATA, command[i]);
            sio.tick(40_000);
            response[i] = sio.read8(DATA);
            sio.write16(CTRL, control | 0x10);
        }
        return response;
    }

    private static int[] memoryCardWriteCommand(int sector, byte[] payload, boolean badChecksum) {
        int[] command = new int[6 + 128 + 4];
        command[0] = 0x81;
        command[1] = 0x57;
        command[4] = (sector >>> 8) & 0xFF;
        command[5] = sector & 0xFF;
        int checksum = command[4] ^ command[5];
        for (int i = 0; i < payload.length; i++) {
            command[6 + i] = payload[i] & 0xFF;
            checksum ^= command[6 + i];
        }
        command[6 + 128] = badChecksum ? checksum ^ 0xFF : checksum;
        return command;
    }
}
