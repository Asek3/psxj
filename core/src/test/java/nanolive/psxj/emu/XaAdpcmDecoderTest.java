package nanolive.psxj.emu;

import nanolive.psxj.emu.cd.CdSector;
import nanolive.psxj.emu.cd.XaAdpcmDecoder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XaAdpcmDecoderTest {

    @Test
    void decoderUsesAuthoritativeParameterCopyAtGroupOffsetFour() {
        byte[] raw = new byte[2352];
        raw[15] = 2;
        raw[19] = 1; // 4-bit, 37.8kHz, stereo
        for (int group = 0; group < 18; group++) {
            int base = 24 + group * 128;
            Arrays.fill(raw, base, base + 4, (byte) 0x0C);
            Arrays.fill(raw, base + 4, base + 12, (byte) 0x00);
            Arrays.fill(raw, base + 16, base + 128, (byte) 0x11);
        }

        short[] pcm = new XaAdpcmDecoder().decodeSector(sector(raw));
        int peak = 0;
        for (short sample : pcm) {
            peak = Math.max(peak, Math.abs((int) sample));
        }

        assertTrue(peak > 1_000,
            "using the mirrored bytes at +0 would incorrectly shift samples by 12");
    }

    @Test
    void reservedEightBitRangesThirteenToFifteenBehaveAsNine() {
        short[] shiftNine = new XaAdpcmDecoder().decodeSector(
            sector(eightBitMonoSector(0x09)));
        short[] reserved = new XaAdpcmDecoder().decodeSector(
            sector(eightBitMonoSector(0x0D)));

        assertArrayEquals(shiftNine, reserved);
    }

    @Test
    void invalidXaFilterDoesNotAliasAValidPredictor() {
        short[] filterZero = new XaAdpcmDecoder().decodeSector(
            sector(fourBitStereoSector(0x00)));
        short[] invalidFilterFive = new XaAdpcmDecoder().decodeSector(
            sector(fourBitStereoSector(0x50)));

        assertArrayEquals(filterZero, invalidFilterFive);
    }

    @Test
    void snapshotPreservesFilterAndResamplerHistoryAcrossSectors() {
        XaAdpcmDecoder original = new XaAdpcmDecoder();
        original.decodeSector(sector(eightBitMonoSector(0x00)));
        XaAdpcmDecoder.State state = original.copyState();
        short[] expected = original.decodeSector(sector(new byte[2352]));

        XaAdpcmDecoder restored = new XaAdpcmDecoder();
        restored.loadState(state);
        short[] actual = restored.decodeSector(sector(new byte[2352]));

        assertArrayEquals(expected, actual);
    }

    private static byte[] eightBitMonoSector(int range) {
        byte[] raw = new byte[2352];
        raw[15] = 2;
        raw[19] = 0x10; // 8-bit, 37.8kHz, mono
        for (int group = 0; group < 18; group++) {
            int base = 24 + group * 128;
            Arrays.fill(raw, base + 4, base + 8, (byte) range);
            Arrays.fill(raw, base + 16, base + 128, (byte) 0x40);
        }
        return raw;
    }

    private static byte[] fourBitStereoSector(int soundParameter) {
        byte[] raw = new byte[2352];
        raw[15] = 2;
        raw[19] = 0x01;
        for (int group = 0; group < 18; group++) {
            int base = 24 + group * 128;
            Arrays.fill(raw, base + 4, base + 12, (byte) soundParameter);
            Arrays.fill(raw, base + 16, base + 128, (byte) 0x11);
        }
        return raw;
    }

    private static CdSector sector(byte[] raw) {
        return new CdSector(0, 0, 0, raw, new byte[0]);
    }
}
