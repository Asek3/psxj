package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.Mdec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MdecTest {

    private static final int MACROBLOCK_LATENCY = 448 * 6;
    private static final int[] STANDARD_SCALE = {
        0x5A82, 0x5A82, 0x5A82, 0x5A82, 0x5A82, 0x5A82, 0x5A82, 0x5A82,
        0x7D8A, 0x6A6D, 0x471C, 0x18F8, 0xE707, 0xB8E3, 0x9592, 0x8275,
        0x7641, 0x30FB, 0xCF04, 0x89BE, 0x89BE, 0xCF04, 0x30FB, 0x7641,
        0x6A6D, 0xE707, 0x8275, 0xB8E3, 0x471C, 0x7D8A, 0x18F8, 0x9592,
        0x5A82, 0xA57D, 0xA57D, 0x5A82, 0x5A82, 0xA57D, 0xA57D, 0x5A82,
        0x471C, 0x8275, 0x18F8, 0x6A6D, 0x9592, 0xE707, 0x7D8A, 0xB8E3,
        0x30FB, 0x89BE, 0x7641, 0xCF04, 0xCF04, 0x7641, 0x89BE, 0x30FB,
        0x18F8, 0xB8E3, 0x6A6D, 0x8275, 0x7D8A, 0x9592, 0x471C, 0xE707
    };

    @Test
    void resetProducesDocumentedStatusWord() {
        Mdec mdec = new Mdec();
        mdec.writeControl(0xE000_0000);

        assertEquals(0x8004_0000, mdec.status());
        assertFalse(mdec.inputDmaPort().dmaRequest());
        assertFalse(mdec.dmaRequest());
    }

    @Test
    void commandStatusCountsWordsAndInvalidCommandReflectsRawCount() {
        Mdec mdec = configuredMdec();
        mdec.writeControl(0x4000_0000);

        mdec.writeParameter(0x2800_0002);
        assertEquals(1, mdec.status() & 0xFFFF);
        assertTrue((mdec.status() & (1 << 29)) != 0);
        assertTrue(mdec.inputDmaPort().dmaRequest());

        mdec.writeParameter(0xFE00_0008);
        assertEquals(0, mdec.status() & 0xFFFF);
        mdec.writeParameter(0xFE00_0008);
        assertEquals(0xFFFF, mdec.status() & 0xFFFF);

        mdec.writeControl(0x8000_0000);
        mdec.writeParameter(0x9A00_1234);
        assertEquals(0x1234, mdec.status() & 0xFFFF);
        assertEquals((0x9A00_1234 & 0x1E00_0000) >>> 2, mdec.status() & 0x0780_0000);
        assertEquals(0, mdec.status() & (1 << 29));
    }

    @Test
    void eightBitDcBlockUsesFixedPointIdctAndHardwareLatency() {
        Mdec mdec = configuredMdec();
        mdec.writeControl(0x2000_0000);
        mdec.writeParameter(0x2800_0001);
        mdec.writeParameter(0xFE00_0008);

        mdec.tick(MACROBLOCK_LATENCY - 1);
        assertTrue((mdec.status() & (1 << 31)) != 0);
        assertFalse(mdec.dmaRequest());

        mdec.tick(1);
        assertTrue(mdec.dmaRequest());
        for (int i = 0; i < 16; i++) {
            assertEquals(0x8282_8282, mdec.read());
        }
        assertEquals(0x8204_FFFF, mdec.status());
    }

    @Test
    void fourBitOutputUsesUpperNibbleOfUnsignedSample() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x2000_0001);
        mdec.writeParameter(0xFE00_0008);
        mdec.tick(MACROBLOCK_LATENCY);

        for (int i = 0; i < 8; i++) {
            assertEquals(0x8888_8888, mdec.readData());
        }
    }

    @Test
    void qScaleZeroStoresAcCoefficientsInDirectOrder() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x2800_0002);
        mdec.writeParameter(0x05FF_0000); // DC=0, then run=1/value=+511: coefficient k=2
        mdec.writeParameter(0xFE00_FE00);

        mdec.tick(MACROBLOCK_LATENCY);

        for (int i = 0; i < 8; i++) {
            assertEquals(0x003B_C5FF, mdec.readData());
            assertEquals(0xFFC5_3B00, mdec.readData());
        }
    }

    @Test
    void zeroQuantTableEntryDoesNotSelectQScaleZeroPath() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x2800_0002);
        mdec.writeParameter(0x05FF_0400); // q_scale=1; coefficient k=2 has quant value zero
        mdec.writeParameter(0xFE00_FE00);

        mdec.tick(MACROBLOCK_LATENCY);

        for (int i = 0; i < 16; i++) {
            assertEquals(0x8080_8080, mdec.readData());
        }
    }

    @Test
    void colorOutputIsBlockOrderedAndRounds15BitChannels() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x3A00_0006);
        mdec.writeParameter(0xFE00_0000);
        mdec.writeParameter(0xFE00_0000);
        for (int i = 0; i < 4; i++) {
            mdec.writeParameter(0xFE00_0008);
        }
        mdec.tick(MACROBLOCK_LATENCY);

        assertEquals(4, (mdec.status() >>> 16) & 0x7);
        for (int tileRow = 0; tileRow < 2; tileRow++) {
            for (int row = 0; row < 8; row++) {
                for (int tileColumn = 0; tileColumn < 2; tileColumn++) {
                    for (int word = 0; word < 4; word++) {
                        assertEquals(0xC210_C210, mdec.readData());
                    }
                }
            }
        }
        assertEquals(0x8684_FFFF, mdec.status());
    }

    @Test
    void colorStatusDescribesTheNextDmaWordAtExactTileBoundaries() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x3A00_0006);
        mdec.writeParameter(0xFE00_0000); // Cr
        mdec.writeParameter(0xFE00_0000); // Cb
        mdec.writeParameter(0xFE00_0300); // Y1: -256 -> unsigned 64
        mdec.writeParameter(0xFE00_0380); // Y2: -128 -> unsigned 96
        mdec.writeParameter(0xFE00_0080); // Y3: +128 -> unsigned 160
        mdec.writeParameter(0xFE00_0100); // Y4: +256 -> unsigned 192
        mdec.tick(MACROBLOCK_LATENCY);

        int[] expectedWords = {
            0xA108_A108, 0xB18C_B18C, 0xD294_D294, 0xE318_E318
        };
        for (int tileRow = 0; tileRow < 2; tileRow++) {
            for (int row = 0; row < 8; row++) {
                for (int tileColumn = 0; tileColumn < 2; tileColumn++) {
                    int block = tileRow * 2 + tileColumn;
                    for (int word = 0; word < 4; word++) {
                        assertEquals(4, (mdec.status() >>> 16) & 0x7);
                        assertEquals(expectedWords[block], mdec.readData());
                    }
                }
            }
        }
        assertEquals(0x8684_FFFF, mdec.status());
    }

    @Test
    void twentyFourBitStatusChangesEveryFortyEightWordsNotAtDmaBlockBoundary() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x3000_0006);
        mdec.writeParameter(0xFE00_0000); // Cr
        mdec.writeParameter(0xFE00_0000); // Cb
        mdec.writeParameter(0xFE00_0300); // Y1
        mdec.writeParameter(0xFE00_0380); // Y2
        mdec.writeParameter(0xFE00_0080); // Y3
        mdec.writeParameter(0xFE00_0100); // Y4
        mdec.tick(MACROBLOCK_LATENCY);

        int[] expectedWords = {
            0x4040_4040, 0x6060_6060, 0xA0A0_A0A0, 0xC0C0_C0C0
        };
        for (int tileRow = 0; tileRow < 2; tileRow++) {
            for (int row = 0; row < 8; row++) {
                for (int tileColumn = 0; tileColumn < 2; tileColumn++) {
                    int block = tileRow * 2 + tileColumn;
                    for (int word = 0; word < 6; word++) {
                        assertEquals(4, (mdec.status() >>> 16) & 0x7);
                        assertEquals(expectedWords[block], mdec.readData());
                    }
                }
            }
        }
        assertEquals(0x8404_FFFF, mdec.status());
    }

    @Test
    void decoderCanProduceAStreamingBlockBeforeAllCommandWordsArrive() {
        Mdec mdec = configuredMdec();
        mdec.writeControl(0x6000_0000);
        mdec.writeParameter(0x2800_0002);
        mdec.writeParameter(0xFE00_0008);
        mdec.tick(MACROBLOCK_LATENCY);

        assertTrue(mdec.dmaRequest());
        assertTrue(mdec.inputDmaPort().dmaRequest());
        assertTrue((mdec.status() & (1 << 29)) != 0);
        for (int i = 0; i < 16; i++) {
            mdec.read();
        }

        assertTrue((mdec.status() & (1 << 31)) != 0);
        mdec.writeParameter(0xFE00_0008);
        mdec.tick(MACROBLOCK_LATENCY);
        for (int i = 0; i < 16; i++) {
            assertEquals(0x8282_8282, mdec.read());
        }
        assertEquals(0, mdec.status() & (1 << 29));
    }

    @Test
    void snapshotRestoresPartiallyReceivedRleAndPendingLatency() {
        Mdec mdec = configuredMdec();
        mdec.writeControl(0x6000_0000);
        mdec.writeParameter(0x2800_0002);
        mdec.writeParameter(0x0001_0008);

        Mdec.State partial = mdec.copyState();
        mdec.writeControl(0x8000_0000);
        mdec.loadState(partial);
        assertTrue((mdec.status() & (1 << 29)) != 0);

        mdec.writeParameter(0x0000_F800);
        Mdec.State pending = mdec.copyState();
        mdec.tick(MACROBLOCK_LATENCY);
        int expected = mdec.readData();

        mdec.writeControl(0x8000_0000);
        mdec.loadState(pending);
        mdec.tick(MACROBLOCK_LATENCY);
        assertEquals(expected, mdec.readData());
    }

    @Test
    void outputDmaRequestRemainsActiveWhileAnyOutputWordIsBuffered() {
        Mdec mdec = configuredMdec();
        mdec.writeControl(0x2000_0000);
        mdec.writeParameter(0x2800_0002);
        mdec.writeParameter(0xFE00_0008);
        mdec.writeParameter(0xFE00_0008);

        mdec.tick(MACROBLOCK_LATENCY);
        assertTrue(mdec.dmaRequest());

        mdec.readData();
        assertTrue(mdec.dmaRequest());
    }

    @Test
    void outputDmaRequestIsStableUntilTheExactDecodeBoundary() {
        Mdec mdec = configuredMdec();
        mdec.writeControl(0x2000_0000);
        mdec.writeParameter(0x2800_0001);
        mdec.writeParameter(0xFE00_0008);

        assertTrue(mdec.dmaRequestStableFor(false, MACROBLOCK_LATENCY - 1));
        assertFalse(mdec.dmaRequestStableFor(false, MACROBLOCK_LATENCY));
        mdec.tick(MACROBLOCK_LATENCY);
        assertTrue(mdec.dmaRequest());
        assertTrue(mdec.dmaRequestStableFor(false, 256));
    }

    @Test
    void newDecodeCommandDiscardsUnreadOutputFifo() {
        Mdec mdec = configuredMdec();
        mdec.writeParameter(0x2800_0001);
        mdec.writeParameter(0xFE00_0008);
        mdec.tick(MACROBLOCK_LATENCY);
        assertFalse((mdec.status() & (1 << 31)) != 0);

        mdec.writeParameter(0x2800_0001);

        assertTrue((mdec.status() & (1 << 31)) != 0);
    }

    private static Mdec configuredMdec() {
        Mdec mdec = new Mdec();
        mdec.writeParameter(0x6000_0000);
        for (int i = 0; i < STANDARD_SCALE.length; i += 2) {
            mdec.writeParameter(STANDARD_SCALE[i] | (STANDARD_SCALE[i + 1] << 16));
        }
        assertEquals(0, mdec.status() & (1 << 29));
        return mdec;
    }
}
