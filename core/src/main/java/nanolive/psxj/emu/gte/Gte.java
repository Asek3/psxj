package nanolive.psxj.emu.gte;

public final class Gte {
    private static final int[] RTPS_RESULTS = {
        8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 19, 24, 25, 26, 27
    };
    private static final int[] MAC_RESULTS = {9, 10, 11, 25, 26, 27};
    private static final int[] COLOR_RESULTS = {9, 10, 11, 20, 21, 22, 25, 26, 27};
    private static final int[] OTZ_RESULTS = {7, 24};
    private static final long MAC0_MIN_VALUE = -(1L << 31);
    private static final long MAC0_MAX_VALUE = (1L << 31) - 1;
    private static final long MAC123_MIN_VALUE = -(1L << 43);
    private static final long MAC123_MAX_VALUE = (1L << 43) - 1;
    private static final int IR0_MAX_VALUE = 0x1000;
    private static final int IR123_MIN_VALUE = -0x8000;
    private static final int IR123_MAX_VALUE = 0x7FFF;

    private static final int FLAG_ERROR = 1 << 31;
    private static final int FLAG_MAC1_POS = 1 << 30;
    private static final int FLAG_MAC2_POS = 1 << 29;
    private static final int FLAG_MAC3_POS = 1 << 28;
    private static final int FLAG_MAC1_NEG = 1 << 27;
    private static final int FLAG_MAC2_NEG = 1 << 26;
    private static final int FLAG_MAC3_NEG = 1 << 25;
    private static final int FLAG_IR1_SAT = 1 << 24;
    private static final int FLAG_IR2_SAT = 1 << 23;
    private static final int FLAG_IR3_SAT = 1 << 22;
    private static final int FLAG_COLOR_R_SAT = 1 << 21;
    private static final int FLAG_COLOR_G_SAT = 1 << 20;
    private static final int FLAG_COLOR_B_SAT = 1 << 19;
    private static final int FLAG_SZ_OTZ_SAT = 1 << 18;
    private static final int FLAG_DIVIDE = 1 << 17;
    private static final int FLAG_MAC0_POS = 1 << 16;
    private static final int FLAG_MAC0_NEG = 1 << 15;
    private static final int FLAG_SX2_SAT = 1 << 14;
    private static final int FLAG_SY2_SAT = 1 << 13;
    private static final int FLAG_IR0_SAT = 1 << 12;

    private static final int[] UNR_TABLE = {
        0xFF, 0xFD, 0xFB, 0xF9, 0xF7, 0xF5, 0xF3, 0xF1, 0xEF, 0xEE, 0xEC, 0xEA, 0xE8, 0xE6, 0xE4, 0xE3,
        0xE1, 0xDF, 0xDD, 0xDC, 0xDA, 0xD8, 0xD6, 0xD5, 0xD3, 0xD1, 0xD0, 0xCE, 0xCD, 0xCB, 0xC9, 0xC8,
        0xC6, 0xC5, 0xC3, 0xC1, 0xC0, 0xBE, 0xBD, 0xBB, 0xBA, 0xB8, 0xB7, 0xB5, 0xB4, 0xB2, 0xB1, 0xB0,
        0xAE, 0xAD, 0xAB, 0xAA, 0xA9, 0xA7, 0xA6, 0xA4, 0xA3, 0xA2, 0xA0, 0x9F, 0x9E, 0x9C, 0x9B, 0x9A,
        0x99, 0x97, 0x96, 0x95, 0x94, 0x92, 0x91, 0x90, 0x8F, 0x8D, 0x8C, 0x8B, 0x8A, 0x89, 0x87, 0x86,
        0x85, 0x84, 0x83, 0x82, 0x81, 0x7F, 0x7E, 0x7D, 0x7C, 0x7B, 0x7A, 0x79, 0x78, 0x77, 0x75, 0x74,
        0x73, 0x72, 0x71, 0x70, 0x6F, 0x6E, 0x6D, 0x6C, 0x6B, 0x6A, 0x69, 0x68, 0x67, 0x66, 0x65, 0x64,
        0x63, 0x62, 0x61, 0x60, 0x5F, 0x5E, 0x5D, 0x5D, 0x5C, 0x5B, 0x5A, 0x59, 0x58, 0x57, 0x56, 0x55,
        0x54, 0x53, 0x53, 0x52, 0x51, 0x50, 0x4F, 0x4E, 0x4D, 0x4D, 0x4C, 0x4B, 0x4A, 0x49, 0x48, 0x48,
        0x47, 0x46, 0x45, 0x44, 0x43, 0x43, 0x42, 0x41, 0x40, 0x3F, 0x3F, 0x3E, 0x3D, 0x3C, 0x3C, 0x3B,
        0x3A, 0x39, 0x39, 0x38, 0x37, 0x36, 0x36, 0x35, 0x34, 0x33, 0x33, 0x32, 0x31, 0x31, 0x30, 0x2F,
        0x2E, 0x2E, 0x2D, 0x2C, 0x2C, 0x2B, 0x2A, 0x2A, 0x29, 0x28, 0x28, 0x27, 0x26, 0x26, 0x25, 0x24,
        0x24, 0x23, 0x22, 0x22, 0x21, 0x20, 0x20, 0x1F, 0x1E, 0x1E, 0x1D, 0x1D, 0x1C, 0x1B, 0x1B, 0x1A,
        0x19, 0x19, 0x18, 0x18, 0x17, 0x16, 0x16, 0x15, 0x15, 0x14, 0x14, 0x13, 0x12, 0x12, 0x11, 0x11,
        0x10, 0x0F, 0x0F, 0x0E, 0x0E, 0x0D, 0x0D, 0x0C, 0x0C, 0x0B, 0x0A, 0x0A, 0x09, 0x09, 0x08, 0x08,
        0x07, 0x07, 0x06, 0x06, 0x05, 0x05, 0x04, 0x04, 0x03, 0x03, 0x02, 0x02, 0x01, 0x01, 0x00, 0x00,
        0x00
    };

    private final int[] data = new int[32];
    private final int[] control = new int[32];
    private int flags;

    public void writeData(int index, int value) {
        int reg = index & 31;
        switch (reg) {
            case 1, 3, 5, 8, 9, 10, 11 -> data[reg] = sign16(value);
            case 7, 16, 17, 18, 19 -> data[reg] = zero16(value);
            case 15 -> {
                data[12] = data[13];
                data[13] = data[14];
                data[14] = value;
            }
            case 28 -> {
                data[28] = value & 0x7FFF;
                data[9] = sign16((value & 0x1F) << 7);
                data[10] = sign16(((value >>> 5) & 0x1F) << 7);
                data[11] = sign16(((value >>> 10) & 0x1F) << 7);
            }
            case 29, 31 -> {
                return;
            }
            case 30 -> {
                data[30] = value;
                data[31] = countLeadingBits(value);
            }
            default -> data[reg] = value;
        }
    }

    public int readData(int index) {
        int reg = index & 31;
        return switch (reg) {
            case 15 -> data[14];
            case 28, 29 -> packOrgb();
            default -> data[reg];
        };
    }

    public void writeControl(int index, int value) {
        int reg = index & 31;
        switch (reg) {
            case 4, 12, 20, 26, 27, 29, 30 -> control[reg] = sign16(value);
            case 31 -> {
                flags = value & 0x7FFFF000;
                updateErrorFlag();
                control[31] = flags;
            }
            default -> control[reg] = value;
        }
    }

    public int readControl(int index) {
        return control[index & 31];
    }

    public int execute(int command) {
        int opcode = command & 0x3F;
        int shift = ((command >>> 19) & 1) != 0 ? 12 : 0;
        boolean lm = ((command >>> 10) & 1) != 0;
        flags = 0;
        int cycles = switch (opcode) {
            case 0x01 -> {
                rtps(0, shift, lm, true);
                yield 15;
            }
            case 0x06 -> nclip();
            case 0x0C -> op(shift, lm);
            case 0x10 -> dpcs(packedRgbc(), shift, lm);
            case 0x11 -> {
                intpl(shift, lm);
                yield 8;
            }
            case 0x12 -> mvmva(command, shift, lm);
            case 0x13 -> ncds(0, shift, lm);
            case 0x14 -> cdp(shift, lm);
            case 0x16 -> ncdt(shift, lm);
            case 0x1B -> nccs(0, shift, lm);
            case 0x1C -> cc(shift, lm);
            case 0x1E -> ncs(0, shift, lm);
            case 0x20 -> nct(shift, lm);
            case 0x28 -> sqr(shift, lm);
            case 0x29 -> dcpl(shift, lm);
            case 0x2A -> dpct(shift, lm);
            case 0x2D -> avsz3();
            case 0x2E -> avsz4();
            case 0x30 -> {
                rtpt(shift, lm);
                yield 23;
            }
            case 0x3D -> gpf(shift, lm);
            case 0x3E -> gpl(shift, lm);
            case 0x3F -> ncct(shift, lm);
            default -> 5;
        };
        updateErrorFlag();
        control[31] = flags;
        return cycles;
    }

    public static int commandCycles(int command) {
        return switch (command & 0x3F) {
            case 0x01 -> 15;
            case 0x06 -> 8;
            case 0x0C -> 6;
            case 0x10, 0x11, 0x12, 0x29 -> 8;
            case 0x13 -> 19;
            case 0x14 -> 13;
            case 0x16 -> 44;
            case 0x1B -> 17;
            case 0x1C -> 11;
            case 0x1E -> 14;
            case 0x20 -> 30;
            case 0x28, 0x2D, 0x3D, 0x3E -> 5;
            case 0x2A -> 17;
            case 0x2E -> 6;
            case 0x30 -> 23;
            case 0x3F -> 39;
            default -> 5;
        };
    }

    public int flags() {
        return flags;
    }

    public int[] copyRawDataRegisters() {
        return data.clone();
    }

    public int[] copyRawControlRegisters() {
        return control.clone();
    }

    public void loadRawState(int[] rawData, int[] rawControl) {
        if (rawData != null) {
            System.arraycopy(rawData, 0, data, 0, Math.min(data.length, rawData.length));
        }
        if (rawControl != null) {
            System.arraycopy(rawControl, 0, control, 0, Math.min(control.length, rawControl.length));
        }
        flags = control[31];
    }

    public void copyRawStateFrom(Gte source) {
        System.arraycopy(source.data, 0, data, 0, data.length);
        System.arraycopy(source.control, 0, control, 0, control.length);
        flags = source.flags;
    }

    public void commitCommandResults(Gte completedCommand, int command) {
        int opcode = command & 0x3F;
        switch (opcode) {
            case 0x01, 0x30 -> copyDataRegisters(completedCommand, RTPS_RESULTS);
            case 0x06 -> data[24] = completedCommand.data[24];
            case 0x0C, 0x12, 0x28 -> copyDataRegisters(completedCommand, MAC_RESULTS);
            case 0x10, 0x11, 0x13, 0x14, 0x16, 0x1B, 0x1C, 0x1E, 0x20,
                 0x29, 0x2A, 0x3D, 0x3E, 0x3F ->
                copyDataRegisters(completedCommand, COLOR_RESULTS);
            case 0x2D, 0x2E -> copyDataRegisters(completedCommand, OTZ_RESULTS);
            default -> {
            }
        }
        flags = completedCommand.flags;
        control[31] = completedCommand.control[31];
    }

    private void copyDataRegisters(Gte source, int... registers) {
        for (int register : registers) {
            data[register] = source.data[register];
        }
    }

    private int rtps(int vectorIndex, int shift, boolean lm, boolean last) {
        int vx = vectorComponent(vectorIndex, 0);
        int vy = vectorComponent(vectorIndex, 1);
        int vz = vectorComponent(vectorIndex, 2);

        long x = stepMac123(
            ((long) control[5] << 12) + (long) matrixElement(0, 0, 0) * vx,
            (long) matrixElement(0, 0, 1) * vy, 1)
            + (long) matrixElement(0, 0, 2) * vz;
        long y = stepMac123(
            ((long) control[6] << 12) + (long) matrixElement(0, 1, 0) * vx,
            (long) matrixElement(0, 1, 1) * vy, 2)
            + (long) matrixElement(0, 1, 2) * vz;
        long z = stepMac123(
            ((long) control[7] << 12) + (long) matrixElement(0, 2, 0) * vx,
            (long) matrixElement(0, 2, 1) * vy, 3)
            + (long) matrixElement(0, 2, 2) * vz;

        truncateAndSetMac(1, x, shift);
        truncateAndSetMac(2, y, shift);
        truncateAndSetMac(3, z, shift);

        setIr(1, data[25], lm);
        setIr(2, data[26], lm);
        int ir3FlagValue = shift == 0 ? (int) (z >> 12) : data[27];
        setIrSaturationFlagOnly(3, ir3FlagValue, false);
        data[11] = clampIr123Value(data[27], lm);

        pushSz((int) (z >> 12));

        long projection = unrDivide(zero16(control[26]), data[19]);
        long sx = projection * (long) data[9] + (long) control[24];
        long sy = projection * (long) data[10] + (long) control[25];
        checkMacOverflow(0, sx);
        checkMacOverflow(0, sy);
        pushSxy((int) (sx >> 16), (int) (sy >> 16));

        if (last) {
            // IR0 uses the perspective quotient (H/SZ3), not SZ3 itself.
            long depthCue = projection * (long) control[27] + (long) control[28];
            truncateAndSetMac(0, depthCue, 0);
            setIr(0, (int) (depthCue >> 12), true);
            return 15;
        }
        return 15;
    }

    private int rtpt(int shift, boolean lm) {
        rtps(0, shift, lm, false);
        rtps(1, shift, lm, false);
        rtps(2, shift, lm, true);
        return 23;
    }

    private int nclip() {
        long result = (long) sx(12) * sy(13)
            + (long) sx(13) * sy(14)
            + (long) sx(14) * sy(12)
            - (long) sx(12) * sy(14)
            - (long) sx(13) * sy(12)
            - (long) sx(14) * sy(13);
        truncateAndSetMac(0, result, 0);
        return 8;
    }

    private int op(int shift, boolean lm) {
        int d1 = matrixElement(0, 0, 0);
        int d2 = matrixElement(0, 1, 1);
        int d3 = matrixElement(0, 2, 2);
        int ir1 = data[9];
        int ir2 = data[10];
        int ir3 = data[11];
        truncateAndSetMacAndIr(1, (long) ir3 * d2 - (long) ir2 * d3, shift, lm);
        truncateAndSetMacAndIr(2, (long) ir1 * d3 - (long) ir3 * d1, shift, lm);
        truncateAndSetMacAndIr(3, (long) ir2 * d1 - (long) ir1 * d2, shift, lm);
        return 6;
    }

    private int sqr(int shift, boolean lm) {
        data[25] = (data[9] * data[9]) >> shift;
        data[26] = (data[10] * data[10]) >> shift;
        data[27] = (data[11] * data[11]) >> shift;
        setIr(1, data[25], lm);
        setIr(2, data[26], lm);
        setIr(3, data[27], lm);
        return 5;
    }

    private int mvmva(int command, int shift, boolean lm) {
        int matrixSel = (command >>> 17) & 0x3;
        int vectorSel = (command >>> 15) & 0x3;
        int translationSel = (command >>> 13) & 0x3;

        int vx;
        int vy;
        int vz;
        switch (vectorSel) {
            case 0 -> {
                vx = vectorComponent(0, 0);
                vy = vectorComponent(0, 1);
                vz = vectorComponent(0, 2);
            }
            case 1 -> {
                vx = vectorComponent(1, 0);
                vy = vectorComponent(1, 1);
                vz = vectorComponent(1, 2);
            }
            case 2 -> {
                vx = vectorComponent(2, 0);
                vy = vectorComponent(2, 1);
                vz = vectorComponent(2, 2);
            }
            default -> {
                vx = data[9];
                vy = data[10];
                vz = data[11];
            }
        }

        if (translationSel == 2) {
            mulMatVecBugged(matrixSel, vx, vy, vz, shift, lm);
        } else {
            mulMatVec(matrixSel, translationSel, vx, vy, vz, shift, lm);
        }
        return 8;
    }

    private int avsz3() {
        long result = (long) control[29] * (zero16(data[17]) + zero16(data[18]) + zero16(data[19]));
        truncateAndSetMac(0, result, 0);
        setOtz((int) (result >> 12));
        return 5;
    }

    private int avsz4() {
        long result = (long) control[30] * (zero16(data[16]) + zero16(data[17]) + zero16(data[18]) + zero16(data[19]));
        truncateAndSetMac(0, result, 0);
        setOtz((int) (result >> 12));
        return 6;
    }

    private int dpcs(int packedColor, int shift, boolean lm) {
        dpcsInternal(packedColor, shift, lm);
        return 8;
    }

    private int dpct(int shift, boolean lm) {
        for (int i = 0; i < 3; i++) {
            dpcsInternal(data[20], shift, lm);
        }
        return 17;
    }

    private int intpl(int shift, boolean lm) {
        interpolateColor((long) data[9] << 12, (long) data[10] << 12, (long) data[11] << 12, shift, lm);
        pushRgbFromMac();
        return 7;
    }

    private int dcpl(int shift, boolean lm) {
        long mac1 = ((long) rgbComponent(packedRgbc(), 0) * data[9]) << 4;
        long mac2 = ((long) rgbComponent(packedRgbc(), 1) * data[10]) << 4;
        long mac3 = ((long) rgbComponent(packedRgbc(), 2) * data[11]) << 4;
        interpolateColor(mac1, mac2, mac3, shift, lm);
        pushRgbFromMac();
        return 8;
    }

    private int gpf(int shift, boolean lm) {
        truncateAndSetMacAndIr(1, (long) data[9] * data[8], shift, lm);
        truncateAndSetMacAndIr(2, (long) data[10] * data[8], shift, lm);
        truncateAndSetMacAndIr(3, (long) data[11] * data[8], shift, lm);
        pushRgbFromMac();
        return 5;
    }

    private int gpl(int shift, boolean lm) {
        truncateAndSetMacAndIr(1, (long) data[9] * data[8] + ((long) data[25] << shift), shift, lm);
        truncateAndSetMacAndIr(2, (long) data[10] * data[8] + ((long) data[26] << shift), shift, lm);
        truncateAndSetMacAndIr(3, (long) data[11] * data[8] + ((long) data[27] << shift), shift, lm);
        pushRgbFromMac();
        return 5;
    }

    private int ncs(int vectorIndex, int shift, boolean lm) {
        ncsInternal(vectorIndex, shift, lm);
        return 14;
    }

    private int nct(int shift, boolean lm) {
        ncsInternal(0, shift, lm);
        ncsInternal(1, shift, lm);
        ncsInternal(2, shift, lm);
        return 30;
    }

    private int nccs(int vectorIndex, int shift, boolean lm) {
        nccsInternal(vectorIndex, shift, lm);
        return 17;
    }

    private int ncct(int shift, boolean lm) {
        nccsInternal(0, shift, lm);
        nccsInternal(1, shift, lm);
        nccsInternal(2, shift, lm);
        return 39;
    }

    private int ncds(int vectorIndex, int shift, boolean lm) {
        ncdsInternal(vectorIndex, shift, lm);
        return 19;
    }

    private int ncdt(int shift, boolean lm) {
        ncdsInternal(0, shift, lm);
        ncdsInternal(1, shift, lm);
        ncdsInternal(2, shift, lm);
        return 44;
    }

    private int cc(int shift, boolean lm) {
        mulMatVec(2, 1, data[9], data[10], data[11], shift, lm);
        truncateAndSetMacAndIr(1, ((long) rgbComponent(packedRgbc(), 0) * data[9]) << 4, shift, lm);
        truncateAndSetMacAndIr(2, ((long) rgbComponent(packedRgbc(), 1) * data[10]) << 4, shift, lm);
        truncateAndSetMacAndIr(3, ((long) rgbComponent(packedRgbc(), 2) * data[11]) << 4, shift, lm);
        pushRgbFromMac();
        return 11;
    }

    private int cdp(int shift, boolean lm) {
        mulMatVec(2, 1, data[9], data[10], data[11], shift, lm);
        long mac1 = ((long) rgbComponent(packedRgbc(), 0) * data[9]) << 4;
        long mac2 = ((long) rgbComponent(packedRgbc(), 1) * data[10]) << 4;
        long mac3 = ((long) rgbComponent(packedRgbc(), 2) * data[11]) << 4;
        interpolateColor(mac1, mac2, mac3, shift, lm);
        pushRgbFromMac();
        return 13;
    }

    private void ncsInternal(int vectorIndex, int shift, boolean lm) {
        int vx = vectorComponent(vectorIndex, 0);
        int vy = vectorComponent(vectorIndex, 1);
        int vz = vectorComponent(vectorIndex, 2);
        mulMatVec(1, 3, vx, vy, vz, shift, lm);
        mulMatVec(2, 1, data[9], data[10], data[11], shift, lm);
        pushRgbFromMac();
    }

    private void nccsInternal(int vectorIndex, int shift, boolean lm) {
        int vx = vectorComponent(vectorIndex, 0);
        int vy = vectorComponent(vectorIndex, 1);
        int vz = vectorComponent(vectorIndex, 2);
        mulMatVec(1, 3, vx, vy, vz, shift, lm);
        mulMatVec(2, 1, data[9], data[10], data[11], shift, lm);
        truncateAndSetMacAndIr(1, ((long) rgbComponent(packedRgbc(), 0) * data[9]) << 4, shift, lm);
        truncateAndSetMacAndIr(2, ((long) rgbComponent(packedRgbc(), 1) * data[10]) << 4, shift, lm);
        truncateAndSetMacAndIr(3, ((long) rgbComponent(packedRgbc(), 2) * data[11]) << 4, shift, lm);
        pushRgbFromMac();
    }

    private void ncdsInternal(int vectorIndex, int shift, boolean lm) {
        int vx = vectorComponent(vectorIndex, 0);
        int vy = vectorComponent(vectorIndex, 1);
        int vz = vectorComponent(vectorIndex, 2);
        mulMatVec(1, 3, vx, vy, vz, shift, lm);
        mulMatVec(2, 1, data[9], data[10], data[11], shift, lm);
        long mac1 = ((long) rgbComponent(packedRgbc(), 0) * data[9]) << 4;
        long mac2 = ((long) rgbComponent(packedRgbc(), 1) * data[10]) << 4;
        long mac3 = ((long) rgbComponent(packedRgbc(), 2) * data[11]) << 4;
        interpolateColor(mac1, mac2, mac3, shift, lm);
        pushRgbFromMac();
    }

    private void dpcsInternal(int packedColor, int shift, boolean lm) {
        truncateAndSetMac(1, (long) rgbComponent(packedColor, 0) << 16, 0);
        truncateAndSetMac(2, (long) rgbComponent(packedColor, 1) << 16, 0);
        truncateAndSetMac(3, (long) rgbComponent(packedColor, 2) << 16, 0);
        interpolateColor(data[25], data[26], data[27], shift, lm);
        pushRgbFromMac();
    }

    private void interpolateColor(long inMac1, long inMac2, long inMac3, int shift, boolean lm) {
        truncateAndSetMacAndIr(1, ((long) control[21] << 12) - inMac1, shift, false);
        truncateAndSetMacAndIr(2, ((long) control[22] << 12) - inMac2, shift, false);
        truncateAndSetMacAndIr(3, ((long) control[23] << 12) - inMac3, shift, false);
        truncateAndSetMacAndIr(1, (long) data[9] * data[8] + inMac1, shift, lm);
        truncateAndSetMacAndIr(2, (long) data[10] * data[8] + inMac2, shift, lm);
        truncateAndSetMacAndIr(3, (long) data[11] * data[8] + inMac3, shift, lm);
    }

    private void mulMatVec(int matrixSel, int translationSel, int vx, int vy, int vz, int shift, boolean lm) {
        for (int row = 0; row < 3; row++) {
            long value = ((long) translationComponent(translationSel, row) << 12) + (long) matrixElement(matrixSel, row, 0) * vx;
            value = stepMac123(value, (long) matrixElement(matrixSel, row, 1) * vy, row + 1);
            value += (long) matrixElement(matrixSel, row, 2) * vz;
            truncateAndSetMacAndIr(row + 1, value, shift, lm);
        }
    }

    private void mulMatVecBugged(int matrixSel, int vx, int vy, int vz, int shift, boolean lm) {
        for (int row = 0; row < 3; row++) {
            long partial = ((long) control[21 + row] << 12) + (long) matrixElement(matrixSel, row, 0) * vx;
            long partialExtended = signExtendMac123(signExtendMac123(partial, row + 1), row + 1);
            setIr(row + 1, (int) (partialExtended >> shift), false);
            long value = signExtendMac123((long) matrixElement(matrixSel, row, 1) * vy, row + 1)
                + (long) matrixElement(matrixSel, row, 2) * vz;
            truncateAndSetMacAndIr(row + 1, value, shift, lm);
        }
    }

    private void truncateAndSetMacAndIr(int index, long value, int shift, boolean lm) {
        truncateAndSetMac(index, value, shift);
        setIr(index, data[24 + index], lm);
    }

    private void truncateAndSetMac(int index, long value, int shift) {
        checkMacOverflow(index, value);
        data[24 + index] = (int) (value >> shift);
    }

    private void setIr(int index, int value, boolean lm) {
        setIrSaturationFlagOnly(index, value, lm);
        data[8 + index] = index == 0 ? clampIr0Value(value) : clampIr123Value(value, lm);
    }

    private void setIrSaturationFlagOnly(int index, int value, boolean lm) {
        int min = index == 0 ? 0 : (lm ? 0 : IR123_MIN_VALUE);
        int max = index == 0 ? IR0_MAX_VALUE : IR123_MAX_VALUE;
        if (value < min || value > max) {
            flags |= switch (index) {
                case 0 -> FLAG_IR0_SAT;
                case 1 -> FLAG_IR1_SAT;
                case 2 -> FLAG_IR2_SAT;
                default -> FLAG_IR3_SAT;
            };
        }
    }

    private int clampIr0Value(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, IR0_MAX_VALUE);
    }

    private int clampIr123Value(int value, boolean lm) {
        int min = lm ? 0 : IR123_MIN_VALUE;
        if (value < min) {
            return min;
        }
        return Math.min(value, IR123_MAX_VALUE);
    }

    private void setOtz(int value) {
        if (value < 0) {
            flags |= FLAG_SZ_OTZ_SAT;
            data[7] = 0;
        } else if (value > 0xFFFF) {
            flags |= FLAG_SZ_OTZ_SAT;
            data[7] = 0xFFFF;
        } else {
            data[7] = value;
        }
    }

    private void pushSxy(int x, int y) {
        if (x < -1024) {
            flags |= FLAG_SX2_SAT;
            x = -1024;
        } else if (x > 1023) {
            flags |= FLAG_SX2_SAT;
            x = 1023;
        }
        if (y < -1024) {
            flags |= FLAG_SY2_SAT;
            y = -1024;
        } else if (y > 1023) {
            flags |= FLAG_SY2_SAT;
            y = 1023;
        }
        data[12] = data[13];
        data[13] = data[14];
        data[14] = (x & 0xFFFF) | (y << 16);
    }

    private void pushSz(int value) {
        if (value < 0) {
            flags |= FLAG_SZ_OTZ_SAT;
            value = 0;
        } else if (value > 0xFFFF) {
            flags |= FLAG_SZ_OTZ_SAT;
            value = 0xFFFF;
        }
        data[16] = data[17];
        data[17] = data[18];
        data[18] = data[19];
        data[19] = value;
    }

    private void pushRgbFromMac() {
        int code = (packedRgbc() >>> 24) & 0xFF;
        int r = truncateRgb(0, data[25] >> 4);
        int g = truncateRgb(1, data[26] >> 4);
        int b = truncateRgb(2, data[27] >> 4);
        data[20] = data[21];
        data[21] = data[22];
        data[22] = r | (g << 8) | (b << 16) | (code << 24);
    }

    private int truncateRgb(int channel, int value) {
        if (value < 0) {
            flags |= channel == 0 ? FLAG_COLOR_R_SAT : channel == 1 ? FLAG_COLOR_G_SAT : FLAG_COLOR_B_SAT;
            return 0;
        }
        if (value > 0xFF) {
            flags |= channel == 0 ? FLAG_COLOR_R_SAT : channel == 1 ? FLAG_COLOR_G_SAT : FLAG_COLOR_B_SAT;
            return 0xFF;
        }
        return value;
    }

    private long unrDivide(int lhs, int rhs) {
        lhs &= 0xFFFF;
        rhs &= 0xFFFF;
        if ((rhs << 1) <= lhs) {
            flags |= FLAG_DIVIDE;
            return 0x1FFFFL;
        }

        int shift = rhs == 0 ? 16 : countLeadingZeros16(rhs);
        lhs <<= shift;
        rhs <<= shift;
        int divisor = (rhs & 0xFFFF) | 0x8000;
        int x = 0x101 + UNR_TABLE[((divisor & 0x7FFF) + 0x40) >> 7];
        int d = ((divisor * -x) + 0x80) >> 8;
        long reciprocal = ((long) x * (0x20000L + d) + 0x80) >> 8;
        long result = (((long) lhs & 0xFFFFFFFFL) * reciprocal + 0x8000) >> 16;
        return Math.min(0x1FFFFL, result);
    }

    private int packedRgbc() {
        return data[6];
    }

    private int rgbComponent(int packed, int index) {
        return (packed >>> (index * 8)) & 0xFF;
    }

    private int packOrgb() {
        int r = Math.clamp(data[9] / 0x80, 0, 0x1F);
        int g = Math.clamp(data[10] / 0x80, 0, 0x1F);
        int b = Math.clamp(data[11] / 0x80, 0, 0x1F);
        return r | (g << 5) | (b << 10);
    }

    private int vectorComponent(int vectorIndex, int component) {
        int base = vectorIndex << 1;
        if (component == 0) {
            return sign16(data[base]);
        }
        if (component == 1) {
            return sign16(data[base] >>> 16);
        }
        return sign16(data[base + 1]);
    }

    private int matrixElement(int matrixSel, int row, int col) {
        if (matrixSel == 3) {
            int redTimes16 = (data[6] & 0xFF) << 4;
            return switch (row * 3 + col) {
                case 0 -> -redTimes16;
                case 1 -> redTimes16;
                case 2 -> data[8];
                case 3, 4, 5 -> matrixElement(0, 0, 2);
                default -> matrixElement(0, 1, 1);
            };
        }
        int base = switch (matrixSel) {
            case 1 -> 8;
            case 2 -> 16;
            default -> 0;
        };
        return switch (row * 3 + col) {
            case 0 -> sign16(control[base]);
            case 1 -> sign16(control[base] >>> 16);
            case 2 -> sign16(control[base + 1]);
            case 3 -> sign16(control[base + 1] >>> 16);
            case 4 -> sign16(control[base + 2]);
            case 5 -> sign16(control[base + 2] >>> 16);
            case 6 -> sign16(control[base + 3]);
            case 7 -> sign16(control[base + 3] >>> 16);
            default -> sign16(control[base + 4]);
        };
    }

    private int translationComponent(int translationSel, int row) {
        return switch (translationSel) {
            case 0 -> control[5 + row];
            case 1 -> control[13 + row];
            default -> 0;
        };
    }

    private int sx(int reg) {
        return sign16(data[reg]);
    }

    private int sy(int reg) {
        return sign16(data[reg] >>> 16);
    }

    private long stepMac123(long current, long term, int index) {
        return signExtendMac123(signExtendMac123(current, index) + term, index);
    }

    private long signExtendMac123(long value, int index) {
        checkMacOverflow(index, value);
        return signExtend(value, 44);
    }

    private void checkMacOverflow(int index, long value) {
        if (index == 0) {
            if (value < MAC0_MIN_VALUE) {
                flags |= FLAG_MAC0_NEG;
            } else if (value > MAC0_MAX_VALUE) {
                flags |= FLAG_MAC0_POS;
            }
            return;
        }
        if (value < MAC123_MIN_VALUE) {
            flags |= switch (index) {
                case 1 -> FLAG_MAC1_NEG;
                case 2 -> FLAG_MAC2_NEG;
                default -> FLAG_MAC3_NEG;
            };
        } else if (value > MAC123_MAX_VALUE) {
            flags |= switch (index) {
                case 1 -> FLAG_MAC1_POS;
                case 2 -> FLAG_MAC2_POS;
                default -> FLAG_MAC3_POS;
            };
        }
    }

    private void updateErrorFlag() {
        int aggregate = flags & 0x7F87E000;
        if (aggregate != 0) {
            flags |= FLAG_ERROR;
        } else {
            flags &= ~FLAG_ERROR;
        }
    }

    private static int sign16(int value) {
        return (short) value;
    }

    private static int zero16(int value) {
        return value & 0xFFFF;
    }

    private static int countLeadingBits(int value) {
        int normalized = (value & 0x8000_0000) != 0 ? ~value : value;
        return normalized == 0 ? 32 : Integer.numberOfLeadingZeros(normalized);
    }

    private static int countLeadingZeros16(int value) {
        int masked = value & 0xFFFF;
        return masked == 0 ? 16 : Integer.numberOfLeadingZeros(masked) - 16;
    }

    private static long signExtend(long value, int bits) {
        int shift = 64 - bits;
        return (value << shift) >> shift;
    }
}
