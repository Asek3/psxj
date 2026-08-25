package nanolive.psxj.emu;

import nanolive.psxj.emu.cd.CdSector;
import nanolive.psxj.emu.devices.CdRomController;
import nanolive.psxj.emu.devices.InterruptController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdRomXaTest {

    @Test
    void initCommandClearsPlaybackFlags() {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.write8(0x1F80_1801, 0x0A);
        cd.tick(40_000);

        assertFalse(cd.cddaPlaying());
        assertFalse(cd.dmaRequest());
    }

    @Test
    void xaDecoderLocksOntoOneInterleavedFileAndChannelUntilEof() throws Exception {
        CdRomController cd = new CdRomController(new InterruptController());
        enableXa(cd);

        assertTrue(deliverXa(cd, xaSector(1, 2, false)));
        int sectorSamples = cd.drainXaPcm().length;
        assertTrue(sectorSamples > 0);

        assertTrue(deliverXa(cd, xaSector(1, 3, false)));
        assertTrue(cd.drainXaPcm().length == 0);

        assertTrue(deliverXa(cd, xaSector(1, 2, true)));
        cd.drainXaPcm();
        assertTrue(deliverXa(cd, xaSector(1, 3, false)));
        assertTrue(cd.drainXaPcm().length == sectorSamples);
    }

    @Test
    void soundMapClearDoesNotEraseDiscXaAudio() throws Exception {
        CdRomController cd = new CdRomController(new InterruptController());
        enableXa(cd);
        assertTrue(deliverXa(cd, xaSector(1, 2, false)));

        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x20);

        assertTrue(cd.drainXaPcm().length > 0);
    }

    @Test
    void xaSectorIsDroppedBeforeDecodeWhenThreeDecoderSlotsAreOccupied() throws Exception {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.setQueuedAudioFramesSupplier(() -> 4_705);
        enableXa(cd);

        assertTrue(deliverXa(cd, xaSector(1, 2, false)));

        assertTrue(cd.drainXaPcm().length == 0);
    }

    @Test
    void smallPreviousSectorRemainderDoesNotCauseAFullXaSectorGap() throws Exception {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.setQueuedAudioFramesSupplier(() -> 11);
        enableXa(cd);

        assertTrue(deliverXa(cd, xaSector(1, 2, false)));

        assertTrue(cd.drainXaPcm().length > 0);
    }

    @Test
    void setfilterKeepsAlreadyDecodedXaAndPredictorPipeline() throws Exception {
        CdRomController cd = new CdRomController(new InterruptController());
        enableXa(cd);
        assertTrue(deliverXa(cd, xaSector(1, 2, false)));

        ack(cd, 3);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x03);
        cd.write8(0x1F80_1802, 0x04);
        cd.write8(0x1F80_1801, 0x0D);
        cd.tick(40_000);

        assertTrue(cd.drainXaPcm().length > 0);
    }

    @Test
    void xaSectorRejectedBySetfilterIsConsumedInsteadOfBecomingHostData() throws Exception {
        CdRomController cd = new CdRomController(new InterruptController());
        enableXa(cd);
        ack(cd, 3);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x01);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1801, 0x0D);
        cd.tick(40_000);
        ack(cd, 3);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x48); // XA + Setfilter.
        cd.write8(0x1F80_1801, 0x0E);
        cd.tick(40_000);

        assertTrue(deliverXa(cd, xaSector(7, 8, false)));
        assertTrue(cd.drainXaPcm().length == 0);
    }

    private static void enableXa(CdRomController cd) {
        cd.write8(0x1F80_1802, 0x40);
        cd.write8(0x1F80_1801, 0x0E);
        cd.tick(40_000);
    }

    private static void ack(CdRomController cd, int irq) {
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, irq);
    }

    private static boolean deliverXa(CdRomController cd, CdSector sector) throws Exception {
        Method method = CdRomController.class.getDeclaredMethod("consumeXaAudioSector", CdSector.class);
        method.setAccessible(true);
        return (boolean) method.invoke(cd, sector);
    }

    private static CdSector xaSector(int file, int channel, boolean eof) {
        byte[] raw = new byte[2352];
        raw[15] = 2;
        raw[16] = (byte) file;
        raw[17] = (byte) channel;
        raw[18] = (byte) (0x44 | (eof ? 0x80 : 0));
        raw[19] = 0x01; // 37.8 kHz, 4-bit stereo
        return new CdSector(0, 0, 0, raw, new byte[0]);
    }
}
