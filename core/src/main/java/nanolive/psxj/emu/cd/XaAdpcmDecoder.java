package nanolive.psxj.emu.cd;

import java.util.Arrays;

/**
 * XA-ADPCM sector decoder. Predictor and resampler history belongs to the
 * stream and must survive sector boundaries.
 *
 * @see <a href="https://psx-spx.consoledev.net/cdromdrive/#xa-adpcm-audio-sectors">PSX-SPX XA-ADPCM notes</a>
 */
public final class XaAdpcmDecoder {

    private static final int MIXER_RATE = 44_100;

    // ADPCM predictor coefficients, fixed-point ×64.
    private static final int[] K0 = { 0, 60, 115, 98, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    private static final int[] K1 = { 0,  0, -52, -55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

    private final int[] prev1 = new int[2];
    private final int[] prev2 = new int[2];
    private final short[][] resampleRing = new short[2][32];
    private int resamplePointer;
    private int resampleSixStep = 6;

    // Seven phases of the CD decoder's 37.8 kHz -> 44.1 kHz zig-zag filter.
    private static final int[][] ZIGZAG_37800 = {
        {0, 0, 0, 0, 0, -0x0002, 0x000A, -0x0022, 0x0041, -0x0054,
            0x0034, 0x0009, -0x010A, 0x0400, -0x0A78, 0x234C, 0x6794,
            -0x1780, 0x0BCD, -0x0623, 0x0350, -0x016D, 0x006B, 0x000A,
            -0x0010, 0x0011, -0x0008, 0x0003, -0x0001},
        {0, 0, 0, -0x0002, 0, 0x0003, -0x0013, 0x003C, -0x004B,
            0x00A2, -0x00E3, 0x0132, -0x0043, -0x0267, 0x0C9D, 0x74BB,
            -0x11B4, 0x09B8, -0x05BF, 0x0372, -0x01A8, 0x00A6, -0x001B,
            0x0005, 0x0006, -0x0008, 0x0003, -0x0001, 0},
        {0, 0, -0x0001, 0x0003, -0x0002, -0x0005, 0x001F, -0x004A,
            0x00B3, -0x0192, 0x02B1, -0x039E, 0x04F8, -0x05A6, 0x7939,
            -0x05A6, 0x04F8, -0x039E, 0x02B1, -0x0192, 0x00B3, -0x004A,
            0x001F, -0x0005, -0x0002, 0x0003, -0x0001, 0, 0},
        {0, -0x0001, 0x0003, -0x0008, 0x0006, 0x0005, -0x001B, 0x00A6,
            -0x01A8, 0x0372, -0x05BF, 0x09B8, -0x11B4, 0x74BB, 0x0C9D,
            -0x0267, -0x0043, 0x0132, -0x00E3, 0x00A2, -0x004B, 0x003C,
            -0x0013, 0x0003, 0, -0x0002, 0, 0, 0},
        {-0x0001, 0x0003, -0x0008, 0x0011, -0x0010, 0x000A, 0x006B,
            -0x016D, 0x0350, -0x0623, 0x0BCD, -0x1780, 0x6794, 0x234C,
            -0x0A78, 0x0400, -0x010A, 0x0009, 0x0034, -0x0054, 0x0041,
            -0x0022, 0x000A, -0x0001, 0, 0x0001, 0, 0, 0},
        {0x0002, -0x0008, 0x0010, -0x0023, 0x002B, 0x001A, -0x00EB,
            0x027B, -0x0548, 0x0AFA, -0x16FA, 0x53E0, 0x3C07, -0x1249,
            0x080E, -0x0347, 0x015B, -0x0044, -0x0017, 0x0046, -0x0023,
            0x0011, -0x0005, 0, 0, 0, 0, 0, 0},
        {-0x0005, 0x0011, -0x0023, 0x0046, -0x0017, -0x0044, 0x015B,
            -0x0347, 0x080E, -0x1249, 0x3C07, 0x53E0, -0x16FA, 0x0AFA,
            -0x0548, 0x027B, -0x00EB, 0x001A, 0x002B, -0x0023, 0x0010,
            -0x0008, 0x0002, 0, 0, 0, 0, 0, 0}
    };

    // TODO: verify the 18.9 kHz coefficients on PU-18 hardware.
    private static final int[][] ZIGZAG_18900 = {
        {0, -0x5, 0x11, -0x23, 0x46, -0x17, -0x44, 0x15B, -0x347,
            0x80E, -0x1249, 0x3C07, 0x53E0, -0x16FA, 0xAFA, -0x548,
            0x27B, -0xEB, 0x1A, 0x2B, -0x23, 0x10, -0x8, 0x2, 0},
        {0, -0x2, 0xA, -0x22, 0x41, -0x54, 0x34, 0x9, -0x10A, 0x400,
            -0xA78, 0x234C, 0x6794, -0x1780, 0xBCD, -0x623, 0x350,
            -0x16D, 0x6B, 0xA, -0x10, 0x11, -0x8, 0x3, -0x1},
        {-0x2, 0, 0x3, -0x13, 0x3C, -0x4B, 0xA2, -0xE3, 0x132, -0x43,
            -0x267, 0xC9D, 0x74BB, -0x11B4, 0x9B8, -0x5BF, 0x372,
            -0x1A8, 0xA6, -0x1B, 0x5, 0x6, -0x8, 0x3, -0x1},
        {-0x1, 0x3, -0x2, -0x5, 0x1F, -0x4A, 0xB3, -0x192, 0x2B1,
            -0x39E, 0x4F8, -0x5A6, 0x7939, -0x5A6, 0x4F8, -0x39E,
            0x2B1, -0x192, 0xB3, -0x4A, 0x1F, -0x5, -0x2, 0x3, -0x1},
        {-0x1, 0x3, -0x8, 0x6, 0x5, -0x1B, 0xA6, -0x1A8, 0x372,
            -0x5BF, 0x9B8, -0x11B4, 0x74BB, 0xC9D, -0x267, -0x43,
            0x132, -0xE3, 0xA2, -0x4B, 0x3C, -0x13, 0x3, 0, -0x2},
        {-0x1, 0x3, -0x8, 0x11, -0x10, 0xA, 0x6B, -0x16D, 0x350,
            -0x623, 0xBCD, -0x1780, 0x6794, 0x234C, -0xA78, 0x400,
            -0x10A, 0x9, 0x34, -0x54, 0x41, -0x22, 0xA, -0x2, 0},
        {0, 0x2, -0x8, 0x10, -0x23, 0x2B, 0x1A, -0xEB, 0x27B,
            -0x548, 0xAFA, -0x16FA, 0x53E0, 0x3C07, -0x1249, 0x80E,
            -0x347, 0x15B, -0x44, -0x17, 0x46, -0x23, 0x11, -0x5, 0}
    };

    public void resetHistory() {
        prev1[0] = prev1[1] = 0;
        prev2[0] = prev2[1] = 0;
        Arrays.fill(resampleRing[0], (short) 0);
        Arrays.fill(resampleRing[1], (short) 0);
        resamplePointer = 0;
        resampleSixStep = 6;
    }

    public State copyState() {
        State state = new State();
        state.prev1 = prev1.clone();
        state.prev2 = prev2.clone();
        state.resampleLeft = resampleRing[0].clone();
        state.resampleRight = resampleRing[1].clone();
        state.resamplePointer = resamplePointer;
        state.resampleSixStep = resampleSixStep;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        Arrays.fill(prev1, 0);
        Arrays.fill(prev2, 0);
        Arrays.fill(resampleRing[0], (short) 0);
        Arrays.fill(resampleRing[1], (short) 0);
        if (state.prev1 != null) {
            System.arraycopy(state.prev1, 0, prev1, 0, Math.min(state.prev1.length, prev1.length));
        }
        if (state.prev2 != null) {
            System.arraycopy(state.prev2, 0, prev2, 0, Math.min(state.prev2.length, prev2.length));
        }
        if (state.resampleLeft != null) {
            System.arraycopy(state.resampleLeft, 0, resampleRing[0], 0,
                Math.min(state.resampleLeft.length, resampleRing[0].length));
        }
        if (state.resampleRight != null) {
            System.arraycopy(state.resampleRight, 0, resampleRing[1], 0,
                Math.min(state.resampleRight.length, resampleRing[1].length));
        }
        resamplePointer = state.resamplePointer & 31;
        resampleSixStep = state.resampleSixStep > 0 ? state.resampleSixStep : 6;
    }

    public static final class State {
        int[] prev1;
        int[] prev2;
        short[] resampleLeft;
        short[] resampleRight;
        int resamplePointer;
        int resampleSixStep;
    }

    public short[] decodeSector(CdSector sector) {
        byte[] raw = sector.raw2352();

        // 24-byte header + 18 × 128-byte groups = 2328 minimum.
        if (raw.length < 24 + 18 * 128) return new short[0];

        int  coding   = raw[19] & 0xFF;
        boolean stereo   = (coding & 0x01) != 0;
        int  sourceRate  = (coding & 0x04) != 0 ? 18_900 : 37_800;
        boolean eightBit = (coding & 0x10) != 0;

        short[] pcm = new short[18 * 8 * 28 * 2];
        int out = 0;

        for (int group = 0; group < 18; group++) {
            int groupBase = 24 + group * 128;
            out = eightBit
                ? decodeEightBitGroup(raw, groupBase, stereo, pcm, out)
                : decodeFourBitGroup(raw, groupBase, stereo, pcm, out);
        }

        short[] trimmed = new short[out];
        System.arraycopy(pcm, 0, trimmed, 0, out);
        return sourceRate == 18_900
            ? resample18900(trimmed)
            : resample37800(trimmed);
    }

    // 4-bit Level B/C groups contain eight sound units.
    private int decodeFourBitGroup(byte[] raw, int groupBase,
                                   boolean stereo, short[] out, int outIdx) {
        int paramBase  = groupBase + 4;    // authoritative SP bytes [4..11]
        int sampleBase = groupBase + 16;   // sample words [16..127]

        if (stereo) {
            for (int pair = 0; pair < 4; pair++) {
                int spL = raw[paramBase + pair * 2]     & 0xFF;  // SP for Left  SU
                int spR = raw[paramBase + pair * 2 + 1] & 0xFF;  // SP for Right SU

                for (int sd = 0; sd < 28; sd++) {
                    int packed = raw[sampleBase + sd * 4 + pair] & 0xFF;
                    int leftNibble  = sign4(packed & 0x0F);
                    int rightNibble = sign4((packed >>> 4) & 0x0F);
                    out[outIdx++] = decodeAdpcm4(leftNibble,  spL, 0);
                    out[outIdx++] = decodeAdpcm4(rightNibble, spR, 1);
                }
            }
        } else {
            // 8 mono sound units; byte within word = su/2, nibble = su&1.
            for (int su = 0; su < 8; su++) {
                int sp      = raw[paramBase + su] & 0xFF;
                int byteOff = su / 2;          // which byte in the 4-byte word
                int shift   = (su & 1) * 4;    // 0 → lo nibble, 4 → hi nibble

                for (int sd = 0; sd < 28; sd++) {
                    int packed = raw[sampleBase + sd * 4 + byteOff] & 0xFF;
                    int nibble = sign4((packed >>> shift) & 0x0F);
                    short sample = decodeAdpcm4(nibble, sp, 0);
                    out[outIdx++] = sample;
                    out[outIdx++] = sample;   // mono → duplicate to R
                }
            }
        }
        return outIdx;
    }

    // 8-bit Level A groups contain four sound units.
    private int decodeEightBitGroup(byte[] raw, int groupBase,
                                    boolean stereo, short[] out, int outIdx) {
        int paramBase  = groupBase + 4;
        int sampleBase = groupBase + 16;

        if (stereo) {
            for (int pair = 0; pair < 2; pair++) {
                int spL    = raw[paramBase + pair * 2]     & 0xFF;
                int spR    = raw[paramBase + pair * 2 + 1] & 0xFF;
                int byteL  = pair * 2;        // byte offset for Left  in word
                int byteR  = pair * 2 + 1;    // byte offset for Right in word

                for (int sd = 0; sd < 28; sd++) {
                    short left  = decodeAdpcm8(raw[sampleBase + sd * 4 + byteL], spL, 0);
                    short right = decodeAdpcm8(raw[sampleBase + sd * 4 + byteR], spR, 1);
                    out[outIdx++] = left;
                    out[outIdx++] = right;
                }
            }
        } else {
            // 4 mono units, one per byte in the word.
            for (int su = 0; su < 4; su++) {
                int sp = raw[paramBase + su] & 0xFF;

                for (int sd = 0; sd < 28; sd++) {
                    short sample = decodeAdpcm8(raw[sampleBase + sd * 4 + su], sp, 0);
                    out[outIdx++] = sample;
                    out[outIdx++] = sample;   // mono → duplicate to R
                }
            }
        }
        return outIdx;
    }

    private short decodeAdpcm4(int nibble, int sp, int channel) {
        int range  = sp & 0x0F;
        int filter = (sp >>> 4) & 0x0F;
        // Reserved range values 13-15 → act as 9 (per No$psx).
        if (range > 12) range = 9;
        // Expand nibble to 16-bit then right-shift by range.
        int sample = (nibble << 12) >> range;
        return applyFilter(sample, filter, channel);
    }

    private short decodeAdpcm8(byte raw8, int sp, int channel) {
        int range  = sp & 0x0F;
        int filter = (sp >>> 4) & 0x0F;
        if (range > 12) range = 9;
        // Expand byte to 16-bit then right-shift by range.
        int sample = ((int) raw8 << 8) >> range;
        return applyFilter(sample, filter, channel);
    }

    private short applyFilter(int sample, int filter, int channel) {
        // The CD decoder truncates each signed predictor product separately.
        sample += (prev1[channel] * K0[filter]) >> 6;
        sample += (prev2[channel] * K1[filter]) >> 6;
        sample  = clamp16(sample);
        prev2[channel] = prev1[channel];
        prev1[channel] = sample;
        return (short) sample;
    }

    // Six 37.8 kHz input frames produce seven output phases.
    private short[] resample37800(short[] pcm) {
        int sourceFrames = pcm.length / 2;
        short[] output = new short[((sourceFrames + 5) / 6 * 7) * 2];
        int outputIndex = 0;

        for (int frame = 0; frame < sourceFrames; frame++) {
            resampleRing[0][resamplePointer] = pcm[frame * 2];
            resampleRing[1][resamplePointer] = pcm[frame * 2 + 1];
            resamplePointer = (resamplePointer + 1) & 31;

            if (--resampleSixStep != 0) {
                continue;
            }
            resampleSixStep = 6;
            for (int phase = 0; phase < 7; phase++) {
                output[outputIndex++] =
                    interpolate37800(resampleRing[0], phase, resamplePointer);
                output[outputIndex++] =
                    interpolate37800(resampleRing[1], phase, resamplePointer);
            }
        }
        return Arrays.copyOf(output, outputIndex);
    }

    private short[] resample18900(short[] pcm) {
        int sourceFrames = pcm.length / 2;
        short[] output = new short[(sourceFrames * 7 / 3 + 8) * 2];
        int inputFrame = 0;
        int outputIndex = 0;

        while (inputFrame < sourceFrames) {
            if (resampleSixStep >= 7) {
                resampleSixStep -= 7;
                resamplePointer = (resamplePointer + 1) & 31;
                resampleRing[0][resamplePointer] = pcm[inputFrame * 2];
                resampleRing[1][resamplePointer] = pcm[inputFrame * 2 + 1];
                inputFrame++;
            }

            output[outputIndex++] =
                interpolate18900(resampleRing[0], resampleSixStep, resamplePointer);
            output[outputIndex++] =
                interpolate18900(resampleRing[1], resampleSixStep, resamplePointer);
            resampleSixStep += 3;
        }
        return Arrays.copyOf(output, outputIndex);
    }

    private static short interpolate37800(short[] ring, int phase, int pointer) {
        int sum = 0;
        int[] coefficients = ZIGZAG_37800[phase];
        for (int i = 0; i < coefficients.length; i++) {
            sum += (ring[(pointer - i) & 31] * coefficients[i]) >> 15;
        }
        return (short) clamp16(sum);
    }

    private static short interpolate18900(short[] ring, int phase, int pointer) {
        long sum = 0;
        int[] coefficients = ZIGZAG_18900[phase];
        for (int i = 0; i < coefficients.length; i++) {
            sum += (long) ring[(pointer + 32 - 25 + i) & 31] * coefficients[i];
        }
        return (short) clamp16((int) (sum >> 15));
    }

    private static int sign4(int value) {
        return (value << 28) >> 28;
    }

    private static int clamp16(int v) {
        return Math.clamp(v, Short.MIN_VALUE, Short.MAX_VALUE);
    }

}
