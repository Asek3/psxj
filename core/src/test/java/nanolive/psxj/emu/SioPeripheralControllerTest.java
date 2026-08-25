package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.sio.ControllerDevice;
import nanolive.psxj.emu.sio.DigitalController;
import nanolive.psxj.emu.sio.GunConController;
import nanolive.psxj.emu.sio.JustifierController;
import nanolive.psxj.emu.sio.MouseController;
import nanolive.psxj.emu.sio.MultitapController;
import nanolive.psxj.emu.sio.NeGconController;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SioPeripheralControllerTest {

    private static final int DATA = 0x1F801040;
    private static final int MODE = 0x1F801048;
    private static final int CTRL = 0x1F80104A;
    private static final int BAUD = 0x1F80104E;
    private static final int PORT1_CONTROL = 0x1003;

    @Test
    void mouseReturnsSignedMotionAndConsumesItOnce() {
        SioController sio = configuredSio();
        MouseController mouse = new MouseController();
        mouse.setButtons(true, false);
        mouse.move(10, -4);
        sio.setControllerDevice(0, mouse);

        assertArrayEquals(
            new int[] {0xFF, 0x12, 0x5A, 0xFF, 0xF4, 0x0A, 0xFC},
            exchange(sio, PORT1_CONTROL, 0x01, 0x42, 0, 0, 0, 0, 0)
        );
        reselect(sio, PORT1_CONTROL);
        assertArrayEquals(
            new int[] {0xFF, 0x12, 0x5A, 0xFF, 0xF4, 0x00, 0x00},
            exchange(sio, PORT1_CONTROL, 0x01, 0x42, 0, 0, 0, 0, 0)
        );
    }

    @Test
    void negconReturnsButtonsTwistAndThreeAnalogButtons() {
        SioController sio = configuredSio();
        NeGconController negcon = new NeGconController();
        negcon.setState(
            SioController.PAD_START | SioController.PAD_CIRCLE,
            0x21,
            0x43,
            0x65,
            0x87
        );
        sio.setControllerDevice(0, negcon);

        assertArrayEquals(
            new int[] {0xFF, 0x23, 0x5A, 0xF7, 0xDF, 0x21, 0x43, 0x65, 0x87},
            exchange(sio, PORT1_CONTROL, 0x01, 0x42, 0, 0, 0, 0, 0, 0, 0)
        );
    }

    @Test
    void gunconReturnsRawLittleEndianCoordinatesAndErrorCodes() {
        SioController sio = configuredSio();
        GunConController guncon = new GunConController();
        guncon.setState(
            SioController.PAD_CIRCLE,
            GunConController.X_ERROR,
            GunConController.Y_NO_LIGHT_OR_BUSY
        );
        sio.setControllerDevice(0, guncon);

        assertArrayEquals(
            new int[] {0xFF, 0x63, 0x5A, 0xFF, 0xDF, 0x01, 0x00, 0x0A, 0x00},
            exchange(sio, PORT1_CONTROL, 0x01, 0x42, 0, 0, 0, 0, 0, 0, 0)
        );
    }

    @Test
    void justifierEnablesIrq10AndPulsesAtTheGpuBeamCoordinate() {
        InterruptController interrupts = new InterruptController();
        SioController sio = configuredSio(interrupts);
        JustifierController gun = new JustifierController();
        gun.setButtonState(SioController.PAD_SQUARE, true);
        gun.setAim(140, 100, true);
        sio.setControllerDevice(0, gun);
        ControllerDevice.BeamPosition[] beam = {
            new ControllerDevice.BeamPosition(0, 99, 100)
        };
        sio.setBeamPositionSource(() -> beam[0]);

        assertArrayEquals(
            new int[] {0xFF, 0x31, 0x5A, 0xFF, 0x7F},
            exchange(sio, PORT1_CONTROL, 0x01, 0x42, 0, 0x10, 0)
        );
        assertTrue(gun.irqEnabled());

        beam[0] = new ControllerDevice.BeamPosition(0, 100, 139);
        sio.tick(1);
        assertEquals(0, interrupts.status() & (1 << 10));
        beam[0] = new ControllerDevice.BeamPosition(0, 100, 140);
        sio.tick(1);
        assertEquals(1 << 10, interrupts.status() & (1 << 10));

        beam[0] = new ControllerDevice.BeamPosition(0, 100, 141);
        sio.tick(1);
        interrupts.writeStatus(~(1 << 10));
        assertEquals(0, interrupts.status() & (1 << 10));
        beam[0] = new ControllerDevice.BeamPosition(0, 0, 0);
        sio.tick(1);
        beam[0] = new ControllerDevice.BeamPosition(0, 100, 140);
        sio.tick(1);
        assertEquals(1 << 10, interrupts.status() & (1 << 10));
    }

    @Test
    void multitapMethodOneReturnsFourFixedEightByteSlots() {
        SioController sio = configuredSio();
        MultitapController multitap = new MultitapController();
        DigitalController pad = new DigitalController();
        pad.setControllerState(SioController.PAD_CROSS, 0x80, 0x80, 0x80, 0x80);
        MouseController mouse = new MouseController();
        mouse.move(5, -2);
        NeGconController negcon = new NeGconController();
        negcon.setState(0, 0x11, 0x22, 0x33, 0x44);
        multitap.setController(0, pad);
        multitap.setController(1, mouse);
        multitap.setController(2, negcon);
        sio.setControllerDevice(0, multitap);

        assertArrayEquals(
            new int[] {0xFF, 0x41, 0x5A, 0xFF, 0xBF},
            exchange(sio, PORT1_CONTROL, 0x01, 0x42, 0x01, 0, 0)
        );
        reselect(sio, PORT1_CONTROL);

        int[] command = new int[35];
        command[0] = 0x01;
        command[1] = 0x42;
        int[] response = exchange(sio, PORT1_CONTROL, command);
        assertArrayEquals(new int[] {0xFF, 0x80, 0x5A}, Arrays.copyOf(response, 3));
        assertArrayEquals(
            new int[] {0x41, 0x5A, 0xFF, 0xBF, 0xFF, 0xFF, 0xFF, 0xFF},
            Arrays.copyOfRange(response, 3, 11)
        );
        assertArrayEquals(
            new int[] {0x12, 0x5A, 0xFF, 0xFC, 0x05, 0xFE, 0xFF, 0xFF},
            Arrays.copyOfRange(response, 11, 19)
        );
        assertArrayEquals(
            new int[] {0x23, 0x5A, 0xFF, 0xFF, 0x11, 0x22, 0x33, 0x44},
            Arrays.copyOfRange(response, 19, 27)
        );
        int[] empty = Arrays.copyOfRange(response, 27, 35);
        assertTrue(Arrays.stream(empty).allMatch(value -> value == 0xFF));
    }

    @Test
    void multitapMethodTwoWorksOnSecondPhysicalPort() {
        SioController sio = configuredSio();
        MultitapController multitap = new MultitapController();
        MouseController mouse = new MouseController();
        mouse.move(-1, 2);
        multitap.setController(1, mouse);
        sio.setControllerDevice(1, multitap);
        int port2Control = PORT1_CONTROL | 0x2000;
        sio.write16(CTRL, port2Control);

        assertArrayEquals(
            new int[] {0xFF, 0x12, 0x5A, 0xFF, 0xFC, 0xFF, 0x02},
            exchange(sio, port2Control, 0x02, 0x42, 0, 0, 0, 0, 0)
        );
    }

    @Test
    void snapshotRestoresConcreteDeviceAndMidPollShiftState() {
        SioController source = configuredSio();
        MouseController mouse = new MouseController();
        mouse.move(7, 8);
        source.setControllerDevice(0, mouse);
        transfer(source, PORT1_CONTROL, 0x01);
        transfer(source, PORT1_CONTROL, 0x42);

        SioController.State state = source.copyState();
        SioController restored = configuredSio();
        restored.loadState(state);
        transfer(restored, PORT1_CONTROL, 0x00);
        transfer(restored, PORT1_CONTROL, 0x00);
        transfer(restored, PORT1_CONTROL, 0x00);
        transfer(restored, PORT1_CONTROL, 0x00);
        transfer(restored, PORT1_CONTROL, 0x00);

        assertTrue(restored.controllerDevice(0) instanceof MouseController);
        assertArrayEquals(
            new int[] {0xFF, 0x12, 0x5A, 0xFF, 0xFC, 0x07, 0x08},
            readBytes(restored, 7)
        );
    }

    private static SioController configuredSio() {
        return configuredSio(new InterruptController());
    }

    private static SioController configuredSio(InterruptController interrupts) {
        SioController sio = new SioController(interrupts);
        sio.write16(MODE, 0x000D);
        sio.write16(BAUD, 0x0088);
        sio.write16(CTRL, PORT1_CONTROL);
        return sio;
    }

    private static int[] exchange(SioController sio, int control, int... command) {
        int[] response = new int[command.length];
        for (int i = 0; i < command.length; i++) {
            transfer(sio, control, command[i]);
            response[i] = sio.read8(DATA);
        }
        return response;
    }

    private static void transfer(SioController sio, int control, int value) {
        sio.write8(DATA, value);
        sio.tick(40_000);
        sio.write16(CTRL, control | 0x10);
    }

    private static int[] readBytes(SioController sio, int count) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = sio.read8(DATA);
        }
        return values;
    }

    private static void reselect(SioController sio, int control) {
        sio.write16(CTRL, 0);
        sio.write16(CTRL, control);
    }
}
