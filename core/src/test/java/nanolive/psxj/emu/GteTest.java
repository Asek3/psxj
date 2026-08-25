package nanolive.psxj.emu;

import nanolive.psxj.emu.gte.Gte;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GteTest {

    @Test
    void officialCommandTimingsMatchHardwareTable() {
        Gte gte = new Gte();

        assertEquals(15, gte.execute(0x0180_0001)); // RTPS
        assertEquals(8, gte.execute(0x1400_0006));  // NCLIP
        assertEquals(5, gte.execute(0x1580_002D));  // AVSZ3
        assertEquals(6, gte.execute(0x1680_002E));  // AVSZ4
        assertEquals(6, gte.execute(0x1700_000C));  // OP
        assertEquals(5, gte.execute(0x1900_003D));  // GPF
        assertEquals(5, gte.execute(0x1A00_003E));  // GPL
        assertEquals(23, gte.execute(0x0280_0030)); // RTPT
        assertEquals(14, gte.execute(0x0C80_041E)); // NCS
        assertEquals(30, gte.execute(0x0D80_0420)); // NCT
        assertEquals(17, gte.execute(0x1080_041B)); // NCCS
        assertEquals(39, gte.execute(0x1180_043F)); // NCCT
        assertEquals(11, gte.execute(0x1380_041C)); // CC
        assertEquals(8, gte.execute(0x0780_0010));  // DPCS
        assertEquals(17, gte.execute(0x0F80_002A)); // DPCT
        assertEquals(8, gte.execute(0x0980_0011));  // INTPL
        assertEquals(13, gte.execute(0x1280_0414)); // CDP
        assertEquals(19, gte.execute(0x0E80_0413)); // NCDS
        assertEquals(44, gte.execute(0x0F80_0416)); // NCDT
        assertEquals(8, gte.execute(0x0680_0029));  // DCPL
        assertEquals(8, gte.execute(0x0400_0012));  // MVMVA
        assertEquals(5, gte.execute(0x0A00_0428));  // SQR
    }

    @Test
    void rtpsHonorsLmBitForIrSaturation() {
        Gte negativeLmOff = identityRtpsGte(-1, -1, -1);
        negativeLmOff.execute(0x0008_0001);

        assertEquals(-1, negativeLmOff.readData(9));
        assertEquals(-1, negativeLmOff.readData(10));
        assertEquals(-1, negativeLmOff.readData(11));
        assertEquals(0, negativeLmOff.flags() & ((1 << 24) | (1 << 23) | (1 << 22)));

        Gte negativeLmOn = identityRtpsGte(-1, -1, -1);
        negativeLmOn.execute(0x0008_0401);

        assertEquals(0, negativeLmOn.readData(9));
        assertEquals(0, negativeLmOn.readData(10));
        assertEquals(0, negativeLmOn.readData(11));
        assertEquals((1 << 24) | (1 << 23),
            negativeLmOn.flags() & ((1 << 24) | (1 << 23) | (1 << 22)));
    }

    @Test
    void rtpsDepthCueUsesPerspectiveQuotient() {
        Gte gte = identityRtpsGte(0, 0, 0x1000);
        gte.writeControl(26, 0x1000);
        gte.writeControl(27, 0x0100);
        gte.writeControl(28, 0);

        gte.execute(0x0008_0001);

        assertEquals(0x1000, gte.readData(8));
    }

    @Test
    void mvmvaReservedMatrixUsesRgbcRedComponent() {
        Gte gte = new Gte();
        gte.writeData(6, 0x0000_0002);
        gte.writeData(9, 1);

        gte.execute(0x0007_E012);

        assertEquals(-32, gte.readData(9));
        assertEquals(-32, gte.readData(25));
    }

    @Test
    void registerWidthsAliasesAndReadOnlyBehaviorMatchTheGteRegisterFile() {
        Gte gte = new Gte();

        gte.writeData(1, 0x1234_8900);
        gte.writeData(7, 0x1234_8900);
        gte.writeControl(26, 0x1234_8900);
        assertEquals(0xFFFF_8900, gte.readData(1));
        assertEquals(0x0000_8900, gte.readData(7));
        assertEquals(0xFFFF_8900, gte.readControl(26));

        gte.writeData(12, 0x0002_0001);
        gte.writeData(13, 0x0004_0003);
        gte.writeData(14, 0x0006_0005);
        gte.writeData(15, 0x0008_0007);
        assertEquals(0x0004_0003, gte.readData(12));
        assertEquals(0x0006_0005, gte.readData(13));
        assertEquals(0x0008_0007, gte.readData(14));
        assertEquals(0x0008_0007, gte.readData(15));

        gte.writeData(28, 0xFFFF_7C1F);
        assertEquals(0x0F80, gte.readData(9));
        assertEquals(0x0000, gte.readData(10));
        assertEquals(0x0F80, gte.readData(11));
        assertEquals(0x7C1F, gte.readData(28));
        assertEquals(0x7C1F, gte.readData(29));
        gte.writeData(29, 0);
        assertEquals(0x7C1F, gte.readData(29));

        gte.writeData(30, 0xFFF0_0000);
        assertEquals(12, gte.readData(31));
        gte.writeData(31, 123);
        assertEquals(12, gte.readData(31));
    }

    @Test
    void writingFlagMasksReservedBitsAndRecomputesTheErrorSummary() {
        Gte gte = new Gte();

        gte.writeControl(31, 0xFFFF_FFFF);
        assertEquals(0xFFFF_F000, gte.readControl(31));

        gte.writeControl(31, (1 << 30) | (1 << 21) | 0xFFF);
        assertEquals(0xC020_0000, gte.readControl(31));

        gte.writeControl(31, 1 << 21);
        assertEquals(1 << 21, gte.readControl(31));
    }

    @Test
    void mvmvaFarColorSelectionKeepsTheHardwareBrokenCalculation() {
        Gte gte = new Gte();
        gte.writeData(0, pack16(2, 3));
        gte.writeData(1, 4);
        gte.writeControl(0, 1);
        gte.writeControl(2, 1);
        gte.writeControl(4, 1);
        gte.writeControl(21, 100);
        gte.writeControl(22, 200);
        gte.writeControl(23, 300);

        gte.execute(0x0000_4012); // MVMVA mx=RT, v=V0, cv=FC (bugged)

        assertEquals(0, gte.readData(9));
        assertEquals(3, gte.readData(10));
        assertEquals(4, gte.readData(11));
    }

    @Test
    void dpctReadsAndRotatesTheLiveRgbFifoThreeTimes() {
        Gte gte = new Gte();
        gte.writeData(6, 0x5A00_0000);
        gte.writeData(20, 0x1100_0001);
        gte.writeData(21, 0x2200_0002);
        gte.writeData(22, 0x3300_0003);
        gte.writeData(8, 0);

        gte.execute(0x0008_042A); // DPCT, sf=1, lm=1

        assertEquals(0x5A00_0001, gte.readData(20));
        assertEquals(0x5A00_0002, gte.readData(21));
        assertEquals(0x5A00_0003, gte.readData(22));
    }

    @Test
    void unrDivisionSaturatesItsRoundingOvershootWithoutDivideFlag() {
        Gte gte = identityRtpsGte(0, 0, 0x7F20);
        gte.writeControl(26, 0xFE3F);
        gte.writeControl(27, 1);

        gte.execute(0x0008_0001);

        assertEquals(0x0001_FFFF, gte.readData(24));
        assertEquals(0, gte.flags() & (1 << 17));
    }

    @Test
    void rtpsReportsPositiveAndNegativeIntermediateMac44Overflow() {
        Gte positive = new Gte();
        positive.writeData(0, pack16(0x7FFF, 0));
        positive.writeControl(0, 0x7FFF);
        positive.writeControl(5, Integer.MAX_VALUE);
        positive.execute(0x0008_0001);
        assertTrue((positive.flags() & (1 << 30)) != 0);

        Gte negative = new Gte();
        negative.writeData(0, pack16(0x7FFF, 0));
        negative.writeControl(0, 0x8000);
        negative.writeControl(5, Integer.MIN_VALUE);
        negative.execute(0x0008_0001);
        assertTrue((negative.flags() & (1 << 27)) != 0);
    }

    @Test
    void rtpsSfZeroUsesMac3ShiftedByTwelveOnlyForTheIr3Flag() {
        Gte gte = identityRtpsGte(0, 0, 8);
        gte.writeControl(26, 200);

        gte.execute(0x0000_0001);

        assertEquals(0x8000, gte.readData(27));
        assertEquals(0x7FFF, gte.readData(11));
        assertEquals(0, gte.flags() & (1 << 22));
    }

    @Test
    void nclipKeepsTheWrappedMac0AndPositiveOverflowFlag() {
        Gte gte = new Gte();
        gte.writeData(12, pack16(0x7FFF, 0x7FFF));
        gte.writeData(13, pack16(0x8000, 0x7FFF));
        gte.writeData(14, pack16(0x7FFF, 0x8000));

        gte.execute(0x0000_0006);

        assertEquals(-131071, gte.readData(24));
        assertTrue((gte.flags() & (1 << 16)) != 0);
    }

    private static Gte identityRtpsGte(int vx, int vy, int vz) {
        Gte gte = new Gte();
        gte.writeData(0, pack16(vx, vy));
        gte.writeData(1, vz);
        gte.writeControl(0, 0x0000_1000);
        gte.writeControl(2, 0x0000_1000);
        gte.writeControl(4, 0x0000_1000);
        return gte;
    }

    private static int pack16(int low, int high) {
        return (low & 0xFFFF) | ((high & 0xFFFF) << 16);
    }
}
