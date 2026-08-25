package nanolive.psxj.emu.devices;

import nanolive.psxj.util.Log;

import java.util.Arrays;

public final class Spu {

    private static final int VOICE_COUNT = 24;
    private static final int RAM_HALFWORDS = 512 * 1024 / 2;
    private static final int SAMPLE_PERIOD = 768;
    private static final int TRANSFER_FIFO_CAPACITY = 32;
    private static final int CD_AUDIO_CAPACITY_FRAMES = 44_100;
    private static final int CAPTURE_HALFWORDS = 0x200;
    private static final int CAPTURE_CD_LEFT = 0x000;
    private static final int CAPTURE_CD_RIGHT = 0x200;
    private static final int CAPTURE_VOICE1 = 0x400;
    private static final int CAPTURE_VOICE3 = 0x600;
    private static final int[] POS_FILTER = {0, 60, 115, 98, 122};
    private static final int[] NEG_FILTER = {0, 0, -52, -55, -60};
    private static final int[] GAUSS_TABLE = new int[512];
    private static final int[] REVERB_RESAMPLE_COEFFICIENTS = {
        -0x0001, 0x0002, -0x000A, 0x0023, -0x0067,
        0x010A, -0x0268, 0x0534, -0x0B90, 0x2806,
        0x2806, -0x0B90, 0x0534, -0x0268, 0x010A,
        -0x0067, 0x0023, -0x000A, 0x0002, -0x0001
    };

    private final Voice[] voices = new Voice[VOICE_COUNT];
    private final short[] ram = new short[RAM_HALFWORDS];
    private short[] mixedSamples = new short[4096];
    private int mixedSampleCount;
    private final HalfwordFifo transferFifo = new HalfwordFifo();
    private final HalfwordFifo transferReadFifo = new HalfwordFifo();
    private final InterruptController interruptController;
    private final short[] reverbRegs = new short[0x20];
    private final short[] unknownDbc = new short[2];
    private final short[] unknownE60 = new short[0x10];
    private final short[][] reverbDownsampleBuffer = new short[2][64];
    private final short[][] reverbUpsampleBuffer = new short[2][32];
    private short[] cdAudioBuffer = new short[4096];

    private int mainVolumeLeftRaw;
    private int mainVolumeRightRaw;
    private int mainVolumeLeftCurrent;
    private int mainVolumeRightCurrent;
    private int mainVolumeLeftCounter;
    private int mainVolumeRightCounter;
    private int reverbOutputVolumeLeft;
    private int reverbOutputVolumeRight;
    private int voiceKeyOn;
    private int voiceKeyOff;
    private int voiceFmMode;
    private int voiceNoiseMode;
    private int voiceReverbMode;
    private int voiceEndFlags;
    private int reverbWorkAreaStart;
    private int reverbCurrentAddress;
    private int cdInputVolumeLeft;
    private int cdInputVolumeRight;
    private int externalInputVolumeLeft;
    private int externalInputVolumeRight;
    private int transferAddress;
    private int transferCurrentAddress;
    private int transferControl = 0x0004;
    private int irqAddress;
    private int irqHalfwordAddress;
    private int control;
    private boolean irqEnabled;
    private boolean captureIrqEnabled;
    private int appliedModeBits;
    private int pendingModeBits;
    private int modeDelaySamples;
    private int cycleAccumulator;
    private int cdAudioReadIndex;
    private int cdAudioWriteIndex;
    private int cdAudioQueuedSamples;
    private int captureIndex;
    private int noiseLevel = 1;
    private int noiseTimer = 0x20000;
    private int reverbSampleLeft;
    private int reverbSampleRight;
    private int reverbInputLatchLeft;
    private int reverbInputLatchRight;
    private int reverbStageInput;
    private int reverbStageSameDelayed;
    private int reverbStageSamePrevious;
    private int reverbStageDiffDelayed;
    private int reverbStageDiffPrevious;
    private int reverbStageComb;
    private int reverbStageApf1Tap;
    private int reverbStageApf2Tap;
    private int reverbStageApfOutput;
    private int reverbResamplePosition;
    private int dmaReadRepeat;
    private int dmaReadLatched;
    private int dmaWriteRequestDelay;
    private int dmaReadRequestDelay;
    private int transferBusyDelay;
    private boolean irqFlag;
    private boolean reverbPhase;
    private boolean reverbStageLeft;
    private boolean reverbStageWriteEnabled;
    private boolean transferSlotServiceActive;
    private boolean sampleFrameStarted;
    private int frameCdRawLeft;
    private int frameCdRawRight;
    private int frameCdMixedLeft;
    private int frameCdMixedRight;
    private int frameNoiseSample;
    private int frameDryLeft;
    private int frameDryRight;
    private int frameReverbInLeft;
    private int frameReverbInRight;
    private int framePreviousVoice;
    private boolean frameSpuEnabled;
    private boolean frameVoicesUnmuted;
    private int debugControlLogs;
    private int debugKeyOnLogs;

    static {
        int[] values = {
            -0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,-0x001,
            0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0001,0x0001,0x0001,0x0001,0x0002,0x0002,0x0002,0x0003,0x0003,
            0x0003,0x0004,0x0004,0x0005,0x0005,0x0006,0x0007,0x0007,0x0008,0x0009,0x0009,0x000A,0x000B,0x000C,0x000D,0x000E,
            0x000F,0x0010,0x0011,0x0012,0x0013,0x0015,0x0016,0x0018,0x0019,0x001B,0x001C,0x001E,0x0020,0x0021,0x0023,0x0025,
            0x0027,0x0029,0x002C,0x002E,0x0030,0x0033,0x0035,0x0038,0x003A,0x003D,0x0040,0x0043,0x0046,0x0049,0x004D,0x0050,
            0x0054,0x0057,0x005B,0x005F,0x0063,0x0067,0x006B,0x006F,0x0074,0x0078,0x007D,0x0082,0x0087,0x008C,0x0091,0x0096,
            0x009C,0x00A1,0x00A7,0x00AD,0x00B3,0x00BA,0x00C0,0x00C7,0x00CD,0x00D4,0x00DB,0x00E3,0x00EA,0x00F2,0x00FA,0x0101,
            0x010A,0x0112,0x011B,0x0123,0x012C,0x0135,0x013F,0x0148,0x0152,0x015C,0x0166,0x0171,0x017B,0x0186,0x0191,0x019C,
            0x01A8,0x01B4,0x01C0,0x01CC,0x01D9,0x01E5,0x01F2,0x0200,0x020D,0x021B,0x0229,0x0237,0x0246,0x0255,0x0264,0x0273,
            0x0283,0x0293,0x02A3,0x02B4,0x02C4,0x02D6,0x02E7,0x02F9,0x030B,0x031D,0x0330,0x0343,0x0356,0x036A,0x037E,0x0392,
            0x03A7,0x03BC,0x03D1,0x03E7,0x03FC,0x0413,0x042A,0x0441,0x0458,0x0470,0x0488,0x04A0,0x04B9,0x04D2,0x04EC,0x0506,
            0x0520,0x053B,0x0556,0x0572,0x058E,0x05AA,0x05C7,0x05E4,0x0601,0x061F,0x063E,0x065C,0x067C,0x069B,0x06BB,0x06DC,
            0x06FD,0x071E,0x0740,0x0762,0x0784,0x07A7,0x07CB,0x07EF,0x0813,0x0838,0x085D,0x0883,0x08A9,0x08D0,0x08F7,0x091E,
            0x0946,0x096F,0x0998,0x09C1,0x09EB,0x0A16,0x0A40,0x0A6C,0x0A98,0x0AC4,0x0AF1,0x0B1E,0x0B4C,0x0B7A,0x0BA9,0x0BD8,
            0x0C07,0x0C38,0x0C68,0x0C99,0x0CCB,0x0CFD,0x0D30,0x0D63,0x0D97,0x0DCB,0x0E00,0x0E35,0x0E6B,0x0EA1,0x0ED7,0x0F0F,
            0x0F46,0x0F7F,0x0FB7,0x0FF1,0x102A,0x1065,0x109F,0x10DB,0x1116,0x1153,0x118F,0x11CD,0x120B,0x1249,0x1288,0x12C7,
            0x1307,0x1347,0x1388,0x13C9,0x140B,0x144D,0x1490,0x14D4,0x1517,0x155C,0x15A0,0x15E6,0x162C,0x1672,0x16B9,0x1700,
            0x1747,0x1790,0x17D8,0x1821,0x186B,0x18B5,0x1900,0x194B,0x1996,0x19E2,0x1A2E,0x1A7B,0x1AC8,0x1B16,0x1B64,0x1BB3,
            0x1C02,0x1C51,0x1CA1,0x1CF1,0x1D42,0x1D93,0x1DE5,0x1E37,0x1E89,0x1EDC,0x1F2F,0x1F82,0x1FD6,0x202A,0x207F,0x20D4,
            0x2129,0x217F,0x21D5,0x222C,0x2282,0x22DA,0x2331,0x2389,0x23E1,0x2439,0x2492,0x24EB,0x2545,0x259E,0x25F8,0x2653,
            0x26AD,0x2708,0x2763,0x27BE,0x281A,0x2876,0x28D2,0x292E,0x298B,0x29E7,0x2A44,0x2AA1,0x2AFF,0x2B5C,0x2BBA,0x2C18,
            0x2C76,0x2CD4,0x2D33,0x2D91,0x2DF0,0x2E4F,0x2EAE,0x2F0D,0x2F6C,0x2FCC,0x302B,0x308B,0x30EA,0x314A,0x31AA,0x3209,
            0x3269,0x32C9,0x3329,0x3389,0x33E9,0x3449,0x34A9,0x3509,0x3569,0x35C9,0x3629,0x3689,0x36E8,0x3748,0x37A8,0x3807,
            0x3867,0x38C6,0x3926,0x3985,0x39E4,0x3A43,0x3AA2,0x3B00,0x3B5F,0x3BBD,0x3C1B,0x3C79,0x3CD7,0x3D35,0x3D92,0x3DEF,
            0x3E4C,0x3EA9,0x3F05,0x3F62,0x3FBD,0x4019,0x4074,0x40D0,0x412A,0x4185,0x41DF,0x4239,0x4292,0x42EB,0x4344,0x439C,
            0x43F4,0x444C,0x44A3,0x44FA,0x4550,0x45A6,0x45FC,0x4651,0x46A6,0x46FA,0x474E,0x47A1,0x47F4,0x4846,0x4898,0x48E9,
            0x493A,0x498A,0x49D9,0x4A29,0x4A77,0x4AC5,0x4B13,0x4B5F,0x4BAC,0x4BF7,0x4C42,0x4C8D,0x4CD7,0x4D20,0x4D68,0x4DB0,
            0x4DF7,0x4E3E,0x4E84,0x4EC9,0x4F0E,0x4F52,0x4F95,0x4FD7,0x5019,0x505A,0x509A,0x50DA,0x5118,0x5156,0x5194,0x51D0,
            0x520C,0x5247,0x5281,0x52BA,0x52F3,0x532A,0x5361,0x5397,0x53CC,0x5401,0x5434,0x5467,0x5499,0x54CA,0x54FA,0x5529,
            0x5558,0x5585,0x55B2,0x55DE,0x5609,0x5632,0x565B,0x5684,0x56AB,0x56D1,0x56F6,0x571B,0x573E,0x5761,0x5782,0x57A3,
            0x57C3,0x57E2,0x57FF,0x581C,0x5838,0x5853,0x586D,0x5886,0x589E,0x58B5,0x58CB,0x58E0,0x58F4,0x5907,0x5919,0x592A,
            0x593A,0x5949,0x5958,0x5965,0x5971,0x597C,0x5986,0x598F,0x5997,0x599E,0x59A4,0x59A9,0x59AD,0x59B0,0x59B2,0x59B3
        };
        System.arraycopy(values, 0, GAUSS_TABLE, 0, values.length);
    }

    public Spu() { this(null); }

    public Spu(InterruptController interruptController) {
        this.interruptController = interruptController;
        for (int i = 0; i < VOICE_COUNT; i++) voices[i] = new Voice(this, i);
    }

    public int read16(int address) {
        int offset = address - 0x1F80_1C00;
        if (offset >= 0 && offset < VOICE_COUNT * 0x10) return voices[offset / 0x10].read(offset & 0xF);
        if (address >= 0x1F80_1DC0 && address <= 0x1F80_1DFE) return reverbRegs[(address - 0x1F80_1DC0) >>> 1] & 0xFFFF;
        if (address >= 0x1F80_1E00 && address < 0x1F80_1E00 + VOICE_COUNT * 4) {
            Voice voice = voices[(address - 0x1F80_1E00) >>> 2];
            return ((address & 0x2) == 0 ? voice.volumeLeftCurrent : voice.volumeRightCurrent) & 0xFFFF;
        }
        if (address >= 0x1F80_1DBC && address <= 0x1F80_1DBE) return unknownDbc[(address - 0x1F80_1DBC) >>> 1] & 0xFFFF;
        if (address >= 0x1F80_1E60 && address <= 0x1F80_1E7E) return unknownE60[(address - 0x1F80_1E60) >>> 1] & 0xFFFF;
        return switch (address) {
            case 0x1F80_1D80 -> mainVolumeLeftRaw & 0xFFFF;
            case 0x1F80_1D82 -> mainVolumeRightRaw & 0xFFFF;
            case 0x1F80_1D84 -> reverbOutputVolumeLeft & 0xFFFF;
            case 0x1F80_1D86 -> reverbOutputVolumeRight & 0xFFFF;
            case 0x1F80_1D88 -> voiceKeyOn & 0xFFFF;
            case 0x1F80_1D8A -> (voiceKeyOn >>> 16) & 0xFFFF;
            case 0x1F80_1D8C -> voiceKeyOff & 0xFFFF;
            case 0x1F80_1D8E -> (voiceKeyOff >>> 16) & 0xFFFF;
            case 0x1F80_1D90 -> voiceFmMode & 0xFFFF;
            case 0x1F80_1D92 -> (voiceFmMode >>> 16) & 0xFFFF;
            case 0x1F80_1D94 -> voiceNoiseMode & 0xFFFF;
            case 0x1F80_1D96 -> (voiceNoiseMode >>> 16) & 0xFFFF;
            case 0x1F80_1D98 -> voiceReverbMode & 0xFFFF;
            case 0x1F80_1D9A -> (voiceReverbMode >>> 16) & 0xFFFF;
            case 0x1F80_1D9C -> voiceEndFlags & 0xFFFF;
            case 0x1F80_1D9E -> (voiceEndFlags >>> 16) & 0xFFFF;
            case 0x1F80_1DA0 -> 0x9CF8;
            case 0x1F80_1DA2 -> reverbWorkAreaStart & 0xFFFF;
            case 0x1F80_1DA4 -> irqAddress & 0xFFFF;
            case 0x1F80_1DA6 -> transferAddress & 0xFFFF;
            case 0x1F80_1DA8 -> 0xFFFF;
            case 0x1F80_1DAA -> control & 0xFFFF;
            case 0x1F80_1DAC -> transferControl & 0xFFFF;
            case 0x1F80_1DAE -> status();
            case 0x1F80_1DB0 -> cdInputVolumeLeft & 0xFFFF;
            case 0x1F80_1DB2 -> cdInputVolumeRight & 0xFFFF;
            case 0x1F80_1DB4 -> externalInputVolumeLeft & 0xFFFF;
            case 0x1F80_1DB6 -> externalInputVolumeRight & 0xFFFF;
            case 0x1F80_1DB8 -> mainVolumeLeftCurrent & 0xFFFF;
            case 0x1F80_1DBA -> mainVolumeRightCurrent & 0xFFFF;
            default -> 0xFFFF;
        };
    }

    public void write16(int address, int value) {
        int offset = address - 0x1F80_1C00;
        if (offset >= 0 && offset < VOICE_COUNT * 0x10) { voices[offset / 0x10].write(offset & 0xF, value); return; }
        if (address >= 0x1F80_1DC0 && address <= 0x1F80_1DFE) { reverbRegs[(address - 0x1F80_1DC0) >>> 1] = (short) value; return; }
        if (address >= 0x1F80_1DBC && address <= 0x1F80_1DBE) { unknownDbc[(address - 0x1F80_1DBC) >>> 1] = (short) value; return; }
        if (address >= 0x1F80_1E60 && address <= 0x1F80_1E7E) { unknownE60[(address - 0x1F80_1E60) >>> 1] = (short) value; return; }
        switch (address) {
            case 0x1F80_1D80 -> {
                mainVolumeLeftRaw = value & 0xFFFF;
                mainVolumeLeftCounter = 0;
                if ((value & 0x8000) == 0) mainVolumeLeftCurrent = decodeFixedVolume(value);
            }
            case 0x1F80_1D82 -> {
                mainVolumeRightRaw = value & 0xFFFF;
                mainVolumeRightCounter = 0;
                if ((value & 0x8000) == 0) mainVolumeRightCurrent = decodeFixedVolume(value);
            }
            case 0x1F80_1D84 -> reverbOutputVolumeLeft = (short) value;
            case 0x1F80_1D86 -> reverbOutputVolumeRight = (short) value;
            case 0x1F80_1D88 -> latchKeyOn(value & 0xFFFF, 0);
            case 0x1F80_1D8A -> latchKeyOn(value & 0xFFFF, 16);
            case 0x1F80_1D8C -> latchKeyOff(value & 0xFFFF, 0);
            case 0x1F80_1D8E -> latchKeyOff(value & 0xFFFF, 16);
            case 0x1F80_1D90 -> voiceFmMode = (voiceFmMode & 0xFFFF0000) | (value & 0xFFFF);
            case 0x1F80_1D92 -> voiceFmMode = (voiceFmMode & 0xFFFF) | ((value & 0xFFFF) << 16);
            case 0x1F80_1D94 -> voiceNoiseMode = (voiceNoiseMode & 0xFFFF0000) | (value & 0xFFFF);
            case 0x1F80_1D96 -> voiceNoiseMode = (voiceNoiseMode & 0xFFFF) | ((value & 0xFFFF) << 16);
            case 0x1F80_1D98 -> voiceReverbMode = (voiceReverbMode & 0xFFFF0000) | (value & 0xFFFF);
            case 0x1F80_1D9A -> voiceReverbMode = (voiceReverbMode & 0xFFFF) | ((value & 0xFFFF) << 16);
            case 0x1F80_1DA2 -> {
                reverbWorkAreaStart = value & 0xFFFF;
                reverbCurrentAddress = spuAddressToHalfword(reverbWorkAreaStart);
            }
            case 0x1F80_1DA4 -> {
                irqAddress = value & 0xFFFF;
                refreshIrqConfiguration();
                checkForLateRamIrq();
            }
            case 0x1F80_1DA6 -> {
                transferAddress = value & 0xFFFF;
                transferCurrentAddress = spuAddressToHalfword(transferAddress);
                refreshTransferSlotService();
                checkForLateRamIrq();
            }
            case 0x1F80_1DA8 -> {
                if (transferFifoHasRoom()) {
                    transferFifo.add(value & 0xFFFF);
                    refreshTransferSlotService();
                }
            }
            case 0x1F80_1DAA -> writeControl(value & 0xFFFF);
            case 0x1F80_1DAC -> {
                transferControl = value & 0xFFFF;
                refreshIrqConfiguration();
                refreshTransferSlotService();
            }
            case 0x1F80_1DB0 -> cdInputVolumeLeft = (short) value;
            case 0x1F80_1DB2 -> cdInputVolumeRight = (short) value;
            case 0x1F80_1DB4 -> externalInputVolumeLeft = (short) value;
            case 0x1F80_1DB6 -> externalInputVolumeRight = (short) value;
            case 0x1F80_1DB8 -> mainVolumeLeftCurrent = (short) value;
            case 0x1F80_1DBA -> mainVolumeRightCurrent = (short) value;
            default -> { }
        }
    }

    public int dmaRead() {
        int type = transferType();
        transferBusyDelay = Math.max(transferBusyDelay, 1);
        if (appliedTransferMode() == 3 && !transferReadFifo.isEmpty()) {
            if (type == 3 || type == 4 || type == 5) {
                int repeat = type == 3 ? 2 : (type == 4 ? 4 : 8);
                if (dmaReadRepeat == 0) {
                    dmaReadLatched = transferReadFifo.remove();
                    dmaReadRepeat = repeat;
                }
                dmaReadRepeat--;
                refreshTransferSlotService();
                return dmaReadLatched;
            }
            int value = transferReadFifo.remove();
            dmaReadLatched = value;
            refreshTransferSlotService();
            return value;
        }
        // An empty read FIFO repeats the output latch.
        return dmaReadLatched;
    }

    public void dmaWrite(int value) {
        transferBusyDelay = Math.max(transferBusyDelay, 1);
        if (canAcceptTransferHalfword()) {
            transferFifo.add(value & 0xFFFF);
            refreshTransferSlotService();
        }
    }

    public boolean dmaWriteRequest() {
        return appliedTransferMode() == 2
            && dmaWriteRequestDelay == 0
            && transferFifo.isEmpty();
    }

    public boolean dmaReadRequest() {
        return appliedTransferMode() == 3
            && dmaReadRequestDelay == 0
            && transferReadFifo.size() == TRANSFER_FIFO_CAPACITY;
    }

    public boolean interruptStableFor(int cycles) {
        return cycles <= 0 || !irqEnabled || irqFlag;
    }

    public void tick(int cycles) {
        if (cycles == 1) {
            tickOneClock();
            return;
        }
        int remaining = Math.max(0, cycles);
        while (remaining > 0) {
            if (!sampleFrameStarted) {
                beginSampleFrame();
                sampleFrameStarted = true;
            }
            int untilSlot = 8 - (cycleAccumulator & 7);
            if (remaining < untilSlot) {
                cycleAccumulator += remaining;
                return;
            }
            int elapsed = Math.min(remaining, untilSlot);
            cycleAccumulator += elapsed;
            remaining -= elapsed;
            if ((cycleAccumulator & 7) == 0) {
                executeRamSlot((cycleAccumulator >>> 3) - 1);
            }
            if (cycleAccumulator == SAMPLE_PERIOD) {
                finishSampleFrame();
                cycleAccumulator = 0;
                sampleFrameStarted = false;
            }
        }
    }

    private void tickOneClock() {
        if (!sampleFrameStarted) {
            beginSampleFrame();
            sampleFrameStarted = true;
        }
        cycleAccumulator++;
        if ((cycleAccumulator & 7) == 0) {
            executeRamSlot((cycleAccumulator >>> 3) - 1);
        }
        if (cycleAccumulator == SAMPLE_PERIOD) {
            finishSampleFrame();
            cycleAccumulator = 0;
            sampleFrameStarted = false;
        }
    }

    public short[][] drainMixedFrames() {
        short[] samples = drainMixedSamples();
        short[][] frames = new short[samples.length / 2][2];
        for (int i = 0; i < frames.length; i++) {
            frames[i][0] = samples[i * 2];
            frames[i][1] = samples[i * 2 + 1];
        }
        return frames;
    }

    public short[] drainMixedSamples() {
        if (mixedSampleCount == 0) {
            return new short[0];
        }
        short[] result = Arrays.copyOf(mixedSamples, mixedSampleCount);
        mixedSampleCount = 0;
        return result;
    }

    public void discardMixedSamples() {
        mixedSampleCount = 0;
    }

    public short[] copyRam() {
        return ram.clone();
    }

    public void loadRam(short[] snapshot) {
        Arrays.fill(ram, (short) 0);
        if (snapshot != null) {
            System.arraycopy(snapshot, 0, ram, 0, Math.min(snapshot.length, ram.length));
        }
    }

    public State copyState() {
        State state = new State();
        state.voices = new VoiceState[voices.length];
        for (int i = 0; i < voices.length; i++) {
            state.voices[i] = voices[i].copyState();
        }
        state.mixedFrames = new short[mixedSampleCount / 2][2];
        for (int i = 0; i < state.mixedFrames.length; i++) {
            state.mixedFrames[i][0] = mixedSamples[i * 2];
            state.mixedFrames[i][1] = mixedSamples[i * 2 + 1];
        }
        state.transferFifo = transferFifo.toArray();
        state.transferReadFifo = transferReadFifo.toArray();
        state.reverbRegs = reverbRegs.clone();
        state.unknownDbc = unknownDbc.clone();
        state.unknownE60 = unknownE60.clone();
        state.reverbDownsampleBuffer = copyMatrix(reverbDownsampleBuffer);
        state.reverbUpsampleBuffer = copyMatrix(reverbUpsampleBuffer);
        state.cdAudioBuffer = cdAudioBuffer.clone();
        state.mainVolumeLeftRaw = mainVolumeLeftRaw;
        state.mainVolumeRightRaw = mainVolumeRightRaw;
        state.mainVolumeLeftCurrent = mainVolumeLeftCurrent;
        state.mainVolumeRightCurrent = mainVolumeRightCurrent;
        state.mainVolumeLeftCounter = mainVolumeLeftCounter;
        state.mainVolumeRightCounter = mainVolumeRightCounter;
        state.reverbOutputVolumeLeft = reverbOutputVolumeLeft;
        state.reverbOutputVolumeRight = reverbOutputVolumeRight;
        state.voiceKeyOn = voiceKeyOn;
        state.voiceKeyOff = voiceKeyOff;
        state.voiceFmMode = voiceFmMode;
        state.voiceNoiseMode = voiceNoiseMode;
        state.voiceReverbMode = voiceReverbMode;
        state.voiceEndFlags = voiceEndFlags;
        state.reverbWorkAreaStart = reverbWorkAreaStart;
        state.reverbCurrentAddress = reverbCurrentAddress;
        state.cdInputVolumeLeft = cdInputVolumeLeft;
        state.cdInputVolumeRight = cdInputVolumeRight;
        state.externalInputVolumeLeft = externalInputVolumeLeft;
        state.externalInputVolumeRight = externalInputVolumeRight;
        state.transferAddress = transferAddress;
        state.transferCurrentAddress = transferCurrentAddress;
        state.transferControl = transferControl;
        state.irqAddress = irqAddress;
        state.control = control;
        state.appliedModeBits = appliedModeBits;
        state.pendingModeBits = pendingModeBits;
        state.modeDelaySamples = modeDelaySamples;
        state.cycleAccumulator = cycleAccumulator;
        state.sampleFrameStarted = sampleFrameStarted;
        state.frameCdRawLeft = frameCdRawLeft;
        state.frameCdRawRight = frameCdRawRight;
        state.frameCdMixedLeft = frameCdMixedLeft;
        state.frameCdMixedRight = frameCdMixedRight;
        state.frameNoiseSample = frameNoiseSample;
        state.frameDryLeft = frameDryLeft;
        state.frameDryRight = frameDryRight;
        state.frameReverbInLeft = frameReverbInLeft;
        state.frameReverbInRight = frameReverbInRight;
        state.framePreviousVoice = framePreviousVoice;
        state.frameSpuEnabled = frameSpuEnabled;
        state.frameVoicesUnmuted = frameVoicesUnmuted;
        state.cdAudioReadIndex = cdAudioReadIndex;
        state.cdAudioWriteIndex = cdAudioWriteIndex;
        state.cdAudioQueuedSamples = cdAudioQueuedSamples;
        state.captureIndex = captureIndex;
        state.noiseLevel = noiseLevel;
        state.noiseTimer = noiseTimer;
        state.reverbSampleLeft = reverbSampleLeft;
        state.reverbSampleRight = reverbSampleRight;
        state.reverbInputLatchLeft = reverbInputLatchLeft;
        state.reverbInputLatchRight = reverbInputLatchRight;
        state.reverbStageInput = reverbStageInput;
        state.reverbStageSameDelayed = reverbStageSameDelayed;
        state.reverbStageSamePrevious = reverbStageSamePrevious;
        state.reverbStageDiffDelayed = reverbStageDiffDelayed;
        state.reverbStageDiffPrevious = reverbStageDiffPrevious;
        state.reverbStageComb = reverbStageComb;
        state.reverbStageApf1Tap = reverbStageApf1Tap;
        state.reverbStageApf2Tap = reverbStageApf2Tap;
        state.reverbStageApfOutput = reverbStageApfOutput;
        state.reverbResamplePosition = reverbResamplePosition;
        state.dmaReadRepeat = dmaReadRepeat;
        state.dmaReadLatched = dmaReadLatched;
        state.dmaWriteRequestDelay = dmaWriteRequestDelay;
        state.dmaReadRequestDelay = dmaReadRequestDelay;
        state.transferBusyDelay = transferBusyDelay;
        state.irqFlag = irqFlag;
        state.reverbPhase = reverbPhase;
        state.reverbStageLeft = reverbStageLeft;
        state.reverbStageWriteEnabled = reverbStageWriteEnabled;
        state.debugControlLogs = debugControlLogs;
        state.debugKeyOnLogs = debugKeyOnLogs;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        if (state.voices != null) {
            for (int i = 0; i < Math.min(voices.length, state.voices.length); i++) {
                voices[i].loadState(state.voices[i]);
            }
        }
        mixedSampleCount = 0;
        if (state.mixedFrames != null) {
            for (short[] frame : state.mixedFrames) {
                if (frame != null && frame.length >= 2) {
                    appendMixedSample(frame[0], frame[1]);
                }
            }
        }
        transferFifo.clear();
        if (state.transferFifo != null) {
            for (int value : state.transferFifo) {
                transferFifo.add(value & 0xFFFF);
            }
        }
        transferReadFifo.clear();
        if (state.transferReadFifo != null) {
            for (int value : state.transferReadFifo) {
                transferReadFifo.add(value & 0xFFFF);
            }
        }
        copyInto(state.reverbRegs, reverbRegs);
        copyInto(state.unknownDbc, unknownDbc);
        copyInto(state.unknownE60, unknownE60);
        copyInto(state.reverbDownsampleBuffer, reverbDownsampleBuffer);
        copyInto(state.reverbUpsampleBuffer, reverbUpsampleBuffer);
        cdAudioBuffer = state.cdAudioBuffer == null ? new short[4096] : state.cdAudioBuffer.clone();
        mainVolumeLeftRaw = state.mainVolumeLeftRaw;
        mainVolumeRightRaw = state.mainVolumeRightRaw;
        mainVolumeLeftCurrent = state.mainVolumeLeftCurrent;
        mainVolumeRightCurrent = state.mainVolumeRightCurrent;
        mainVolumeLeftCounter = state.mainVolumeLeftCounter;
        mainVolumeRightCounter = state.mainVolumeRightCounter;
        reverbOutputVolumeLeft = state.reverbOutputVolumeLeft;
        reverbOutputVolumeRight = state.reverbOutputVolumeRight;
        voiceKeyOn = state.voiceKeyOn;
        voiceKeyOff = state.voiceKeyOff;
        voiceFmMode = state.voiceFmMode;
        voiceNoiseMode = state.voiceNoiseMode;
        voiceReverbMode = state.voiceReverbMode;
        voiceEndFlags = state.voiceEndFlags;
        reverbWorkAreaStart = state.reverbWorkAreaStart;
        reverbCurrentAddress = state.reverbCurrentAddress;
        cdInputVolumeLeft = state.cdInputVolumeLeft;
        cdInputVolumeRight = state.cdInputVolumeRight;
        externalInputVolumeLeft = state.externalInputVolumeLeft;
        externalInputVolumeRight = state.externalInputVolumeRight;
        transferAddress = state.transferAddress;
        transferCurrentAddress = state.transferCurrentAddress;
        transferControl = state.transferControl;
        irqAddress = state.irqAddress;
        control = state.control;
        appliedModeBits = state.appliedModeBits;
        pendingModeBits = state.pendingModeBits;
        modeDelaySamples = state.modeDelaySamples;
        cycleAccumulator = Math.floorMod(state.cycleAccumulator, SAMPLE_PERIOD);
        sampleFrameStarted = state.sampleFrameStarted && cycleAccumulator != 0;
        frameCdRawLeft = state.frameCdRawLeft;
        frameCdRawRight = state.frameCdRawRight;
        frameCdMixedLeft = state.frameCdMixedLeft;
        frameCdMixedRight = state.frameCdMixedRight;
        frameNoiseSample = state.frameNoiseSample;
        frameDryLeft = state.frameDryLeft;
        frameDryRight = state.frameDryRight;
        frameReverbInLeft = state.frameReverbInLeft;
        frameReverbInRight = state.frameReverbInRight;
        framePreviousVoice = state.framePreviousVoice;
        frameSpuEnabled = state.frameSpuEnabled;
        frameVoicesUnmuted = state.frameVoicesUnmuted;
        cdAudioReadIndex = Math.floorMod(state.cdAudioReadIndex, cdAudioBuffer.length);
        cdAudioWriteIndex = Math.floorMod(state.cdAudioWriteIndex, cdAudioBuffer.length);
        cdAudioQueuedSamples = Math.clamp(state.cdAudioQueuedSamples, 0, cdAudioBuffer.length);
        captureIndex = state.captureIndex;
        noiseLevel = state.noiseLevel;
        noiseTimer = state.noiseTimer;
        reverbSampleLeft = state.reverbSampleLeft;
        reverbSampleRight = state.reverbSampleRight;
        reverbInputLatchLeft = state.reverbInputLatchLeft;
        reverbInputLatchRight = state.reverbInputLatchRight;
        reverbStageInput = state.reverbStageInput;
        reverbStageSameDelayed = state.reverbStageSameDelayed;
        reverbStageSamePrevious = state.reverbStageSamePrevious;
        reverbStageDiffDelayed = state.reverbStageDiffDelayed;
        reverbStageDiffPrevious = state.reverbStageDiffPrevious;
        reverbStageComb = state.reverbStageComb;
        reverbStageApf1Tap = state.reverbStageApf1Tap;
        reverbStageApf2Tap = state.reverbStageApf2Tap;
        reverbStageApfOutput = state.reverbStageApfOutput;
        reverbResamplePosition = state.reverbResamplePosition & 0x3F;
        dmaReadRepeat = state.dmaReadRepeat;
        dmaReadLatched = state.dmaReadLatched;
        dmaWriteRequestDelay = state.dmaWriteRequestDelay;
        dmaReadRequestDelay = state.dmaReadRequestDelay;
        transferBusyDelay = state.transferBusyDelay;
        irqFlag = state.irqFlag;
        reverbPhase = state.reverbPhase;
        reverbStageLeft = state.reverbStageLeft;
        reverbStageWriteEnabled = state.reverbStageWriteEnabled;
        refreshIrqConfiguration();
        refreshTransferSlotService();
        debugControlLogs = state.debugControlLogs;
        debugKeyOnLogs = state.debugKeyOnLogs;
        if (interruptController != null) {
            if (irqFlag) {
                interruptController.raise(9);
            } else {
                interruptController.clear(9);
            }
        }
    }

    private static void copyInto(short[] source, short[] target) {
        if (source == null) {
            return;
        }
        System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
    }

    private static short[][] copyMatrix(short[][] source) {
        short[][] copy = new short[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    private static void copyInto(short[][] source, short[][] target) {
        for (short[] row : target) {
            Arrays.fill(row, (short) 0);
        }
        if (source == null) {
            return;
        }
        for (int i = 0; i < Math.min(source.length, target.length); i++) {
            copyInto(source[i], target[i]);
        }
    }

    public void submitCdAudio(short[] interleavedStereo) {
        if (interleavedStereo == null || interleavedStereo.length < 2) return;
        int capacitySamples = CD_AUDIO_CAPACITY_FRAMES * 2;
        int sampleCount = interleavedStereo.length & ~1;
        int sourceOffset = 0;
        if (sampleCount > capacitySamples) {
            sourceOffset = sampleCount - capacitySamples;
            sampleCount = capacitySamples;
        }
        int overflow = cdAudioQueuedSamples + sampleCount - capacitySamples;
        if (overflow > 0) {
            discardCdSamples((overflow + 1) & ~1);
        }
        ensureCdCapacity(cdAudioQueuedSamples + sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            cdAudioBuffer[cdAudioWriteIndex] = interleavedStereo[sourceOffset + i];
            cdAudioWriteIndex = (cdAudioWriteIndex + 1) % cdAudioBuffer.length;
            cdAudioQueuedSamples++;
        }
    }

    public int queuedCdAudioFrames() {
        return cdAudioQueuedSamples / 2;
    }

    public Diagnostic diagnostic() {
        return new Diagnostic(control, status(), irqAddress, irqFlag, queuedCdAudioFrames());
    }

    public record Diagnostic(
        int control, int status, int irqAddress, boolean irqFlag, int queuedCdAudioFrames
    ) {
    }

    public void clearCdAudio() {
        cdAudioReadIndex = 0;
        cdAudioWriteIndex = 0;
        cdAudioQueuedSamples = 0;
    }

    private void beginSampleFrame() {
        if (modeDelaySamples > 0 && --modeDelaySamples == 0) {
            appliedModeBits = pendingModeBits;
            if (appliedTransferMode() == 2) {
                dmaWriteRequestDelay = 1;
                transferBusyDelay = Math.max(transferBusyDelay, 1);
            } else if (appliedTransferMode() == 3) {
                dmaReadRequestDelay = 2;
                transferBusyDelay = Math.max(transferBusyDelay, 2);
            }
            refreshTransferSlotService();
        }
        if (dmaWriteRequestDelay > 0) dmaWriteRequestDelay--;
        if (dmaReadRequestDelay > 0) dmaReadRequestDelay--;
        if (transferBusyDelay > 0) transferBusyDelay--;
        updateNoise();

        frameCdRawLeft = cdAudioQueuedSamples >= 2 ? readCdSample() : 0;
        frameCdRawRight = cdAudioQueuedSamples >= 1 ? readCdSample() : 0;
        frameCdMixedLeft = applySignedVolume(frameCdRawLeft, cdInputVolumeLeft);
        frameCdMixedRight = applySignedVolume(frameCdRawRight, cdInputVolumeRight);
        reverbDownsampleBuffer[0][reverbResamplePosition] = (short) reverbInputLatchLeft;
        reverbDownsampleBuffer[1][reverbResamplePosition] = (short) reverbInputLatchRight;
        frameNoiseSample = (short) noiseLevel;
        frameSpuEnabled = ((control >>> 15) & 1) != 0;
        frameVoicesUnmuted = ((control >>> 14) & 1) != 0;
        frameDryLeft = 0;
        frameDryRight = 0;
        frameReverbInLeft = 0;
        frameReverbInRight = 0;
        framePreviousVoice = 0;
        if ((control & 0x0001) != 0) {
            frameDryLeft += frameCdMixedLeft;
            frameDryRight += frameCdMixedRight;
        }
        if ((control & 0x0004) != 0) {
            frameReverbInLeft += frameCdMixedLeft;
            frameReverbInRight += frameCdMixedRight;
        }
    }

    private void executeRamSlot(int slot) {
        if (slot < 0 || slot >= 96) {
            return;
        }
        if (slot < 3) {
            executeVoiceRamSlot(0, slot);
            return;
        }
        switch (slot) {
            case 3 -> writeCaptureRam(CAPTURE_CD_LEFT + captureIndex, frameCdRawLeft);
            case 4 -> writeCaptureRam(CAPTURE_CD_RIGHT + captureIndex, frameCdRawRight);
            case 5 -> writeCaptureRam(CAPTURE_VOICE1 + captureIndex, voices[1].currentSample);
            case 6 -> writeCaptureRam(CAPTURE_VOICE3 + captureIndex, voices[3].currentSample);
            case 20 -> {
                executeReverbRamSlot(slot - 7);
                if (transferType() != 2) {
                    flushTransferFifoIfNeeded();
                }
            }
            default -> {
                if (slot >= 7 && slot < 20) {
                    executeReverbRamSlot(slot - 7);
                } else if (slot >= 21 && slot < 90) {
                    int relative = slot - 21;
                    executeVoiceRamSlot(1 + relative / 3, relative % 3);
                }
            }
        }
    }

    private void executeVoiceRamSlot(int voiceIndex, int operation) {
        Voice voice = voices[voiceIndex];
        if (operation == 0) {
            voice.observeHeaderRamRead();
            return;
        }
        if (operation == 1) {
            voice.observeSampleRamRead();
            renderVoiceForFrame(voiceIndex, voice);
            return;
        }
        if (transferSlotServiceActive) {
            serviceTransferSlot();
        }
    }

    private void renderVoiceForFrame(int voiceIndex, Voice voice) {
        boolean noiseMode = ((voiceNoiseMode >>> voiceIndex) & 1) != 0;
        boolean fmMode = ((voiceFmMode >>> voiceIndex) & 1) != 0;
        int sample = voice.step(noiseMode, fmMode, framePreviousVoice, frameNoiseSample);
        framePreviousVoice = voice.currentSample;
        int voiceLeft = applySignedVolume(sample, voice.volumeLeftCurrent);
        int voiceRight = applySignedVolume(sample, voice.volumeRightCurrent);
        if (!frameSpuEnabled) {
            return;
        }
        if (frameVoicesUnmuted) {
            frameDryLeft += voiceLeft;
            frameDryRight += voiceRight;
            if (((voiceReverbMode >>> voiceIndex) & 1) != 0) {
                frameReverbInLeft += voiceLeft;
                frameReverbInRight += voiceRight;
            }
        }
    }

    private void serviceTransferSlot() {
        int mode = appliedTransferMode();
        if (mode == 3) {
            if (transferReadFifo.size() < TRANSFER_FIFO_CAPACITY) {
                transferBusyDelay = Math.max(transferBusyDelay, 1);
                transferReadFifo.add(readRamTransferHalfword());
                refreshTransferSlotService();
            }
            return;
        }
        if ((mode != 1 && mode != 2) || transferType() != 2 || transferFifo.isEmpty()) {
            return;
        }
        transferBusyDelay = Math.max(transferBusyDelay, 1);
        writeRamTransferHalfword(transferFifo.remove());
        refreshTransferSlotService();
    }

    private void refreshTransferSlotService() {
        int mode = appliedTransferMode();
        int type = transferType();
        transferSlotServiceActive = (mode == 3
            && transferReadFifo.size() < TRANSFER_FIFO_CAPACITY)
            || ((mode == 1 || mode == 2) && type == 2 && !transferFifo.isEmpty());
    }


    private void finishSampleFrame() {
        captureIndex = (captureIndex + 1) & (CAPTURE_HALFWORDS - 1);
        updateReverbOutputSamples();

        int mixLeft = clamp16(frameDryLeft
            + applySignedVolume(reverbSampleLeft, reverbOutputVolumeLeft));
        int mixRight = clamp16(frameDryRight
            + applySignedVolume(reverbSampleRight, reverbOutputVolumeRight));
        int outLeft = applySignedVolume(mixLeft, mainVolumeLeftCurrent);
        int outRight = applySignedVolume(mixRight, mainVolumeRightCurrent);
        // The DAC advances at 44.1 kHz even when every mixer input is silent.
        appendMixedSample((short) clamp16(outLeft), (short) clamp16(outRight));
        tickVolumeRegisters();
        applyPendingKeys();
        reverbInputLatchLeft = clamp16(frameReverbInLeft);
        reverbInputLatchRight = clamp16(frameReverbInRight);
        reverbResamplePosition = (reverbResamplePosition + 1) & 0x3F;
    }

    private void appendMixedSample(short left, short right) {
        int required = mixedSampleCount + 2;
        if (required > mixedSamples.length) {
            mixedSamples = Arrays.copyOf(mixedSamples, Math.max(required, mixedSamples.length * 2));
        }
        mixedSamples[mixedSampleCount++] = left;
        mixedSamples[mixedSampleCount++] = right;
    }

    private void executeReverbRamSlot(int operation) {
        int sameReg = reverbStageLeft ? 0x0A : 0x0B;
        int sameSrcReg = reverbStageLeft ? 0x10 : 0x11;
        int diffReg = reverbStageLeft ? 0x12 : 0x13;
        int diffSrcReg = reverbStageLeft ? 0x19 : 0x18;
        int comb1Reg = reverbStageLeft ? 0x0C : 0x0D;
        int comb2Reg = reverbStageLeft ? 0x0E : 0x0F;
        int comb3Reg = reverbStageLeft ? 0x14 : 0x15;
        int comb4Reg = reverbStageLeft ? 0x16 : 0x17;
        int apf1Reg = reverbStageLeft ? 0x1A : 0x1B;
        int apf2Reg = reverbStageLeft ? 0x1C : 0x1D;

        switch (operation) {
            case 0 -> {
                reverbStageLeft = !reverbPhase;
                reverbPhase = !reverbPhase;
                reverbStageWriteEnabled = ((control >>> 7) & 1) != 0;
                reverbStageInput = downsampleReverbInput(reverbStageLeft ? 0 : 1);
                reverbStageComb = 0;
                sameSrcReg = reverbStageLeft ? 0x10 : 0x11;
                reverbStageSameDelayed = readReverbRelative(sameSrcReg);
            }
            case 1 -> reverbStageSamePrevious = readReverbRelativeHalfwordOffset(sameReg, -1);
            case 2 -> reverbStageDiffDelayed = readReverbRelative(diffSrcReg);
            case 3 -> {
                if (reverbStageWriteEnabled) {
                    int value = reverbReflectionValue(
                        reverbStageInput,
                        reverbStageSameDelayed,
                        reverbStageSamePrevious
                    );
                    writeReverbRelative(sameReg, value);
                } else {
                    readReverbRelative(sameReg);
                }
            }
            case 4 -> reverbStageDiffPrevious = readReverbRelativeHalfwordOffset(diffReg, -1);
            case 5 -> reverbStageComb = multiplyAndShift(readReverbRelative(comb1Reg), reverbRegs[0x03], 14);
            case 6 -> {
                if (reverbStageWriteEnabled) {
                    int value = reverbReflectionValue(
                        reverbStageInput,
                        reverbStageDiffDelayed,
                        reverbStageDiffPrevious
                    );
                    writeReverbRelative(diffReg, value);
                } else {
                    readReverbRelative(diffReg);
                }
            }
            case 7 -> reverbStageComb += multiplyAndShift(readReverbRelative(comb2Reg), reverbRegs[0x04], 14);
            case 8 -> reverbStageComb += multiplyAndShift(readReverbRelative(comb3Reg), reverbRegs[0x05], 14);
            case 9 -> reverbStageComb += multiplyAndShift(readReverbRelative(comb4Reg), reverbRegs[0x06], 14);
            case 10 -> reverbStageApf1Tap = readReverbRelativeOffset(apf1Reg, 0x00);
            case 11 -> reverbStageApf2Tap = readReverbRelativeOffset(apf2Reg, 0x01);
            case 12 -> {
                int feedbackAlpha = reverbRegs[0x08];
                int value = clamp16((reverbStageComb + multiplyAndShift(
                    reverbStageApf1Tap,
                    negateReverbVolume(feedbackAlpha),
                    14
                )) >> 1);
                if (reverbStageWriteEnabled) {
                    writeReverbRelative(apf1Reg, value);
                } else {
                    readReverbRelative(apf1Reg);
                }
                reverbStageApfOutput = value;
            }
            case 13 -> {
                int feedbackAlpha = reverbRegs[0x08];
                int feedbackX = reverbRegs[0x09];
                int value = clamp16(reverbStageApf1Tap + ((
                    multiplyAndShift(reverbStageApfOutput, feedbackAlpha, 14)
                        + multiplyAndShift(reverbStageApf2Tap, negateReverbVolume(feedbackX), 14)
                ) >> 1));
                if (reverbStageWriteEnabled) {
                    writeReverbRelative(apf2Reg, value);
                } else {
                    readReverbRelative(apf2Reg);
                }
                int output = clamp16(reverbStageApf2Tap
                    + multiplyAndShift(value, feedbackX, 15));
                int channel = reverbStageLeft ? 0 : 1;
                reverbUpsampleBuffer[channel][reverbResamplePosition >>> 1] = (short) output;
                if (!reverbStageLeft) {
                    reverbCurrentAddress = reverbNextAddress(reverbCurrentAddress + 1);
                }
            }
            default -> throw new IllegalArgumentException("Invalid SPU reverb RAM operation " + operation);
        }
    }

    private int reverbReflectionValue(int input, int delayed, int previous) {
        int inputVolume = reverbRegs[reverbStageLeft ? 0x1E : 0x1F];
        int iirInput = clamp16((
            multiplyAndShift(delayed, reverbRegs[0x07], 14)
                + multiplyAndShift(input, inputVolume, 14)
        ) >> 1);
        int alpha = reverbRegs[0x02];
        long previousTerm = (long) previous * (32_768L - alpha);
        int result = clamp16((int) ((
            multiplyAndShift(iirInput, alpha, 14)
                + (previousTerm >> 14)
        ) >> 1));
        return alpha == Short.MIN_VALUE ? clamp16(-result) : result;
    }

    private int downsampleReverbInput(int channel) {
        short[] history = reverbDownsampleBuffer[channel];
        long sum = (long) history[(reverbResamplePosition - 18) & 0x3F] * 0x4000;
        for (int i = 0; i < REVERB_RESAMPLE_COEFFICIENTS.length; i++) {
            int historyIndex = (reverbResamplePosition - 37 + i * 2) & 0x3F;
            sum += (long) history[historyIndex] * REVERB_RESAMPLE_COEFFICIENTS[i];
        }
        return clamp16((int) (sum >> 15));
    }

    private void updateReverbOutputSamples() {
        if ((reverbResamplePosition & 1) != 0) {
            int start = ((reverbResamplePosition >>> 1) - 19) & 0x1F;
            reverbSampleLeft = upsampleReverbOutput(0, start);
            reverbSampleRight = upsampleReverbOutput(1, start);
        } else {
            int index = ((reverbResamplePosition >>> 1) - 10) & 0x1F;
            reverbSampleLeft = reverbUpsampleBuffer[0][index];
            reverbSampleRight = reverbUpsampleBuffer[1][index];
        }
    }

    private int upsampleReverbOutput(int channel, int start) {
        long sum = 0;
        for (int i = 0; i < REVERB_RESAMPLE_COEFFICIENTS.length; i++) {
            sum += (long) reverbUpsampleBuffer[channel][(start + i) & 0x1F]
                * REVERB_RESAMPLE_COEFFICIENTS[i];
        }
        return clamp16((int) (sum >> 14));
    }

    private static int negateReverbVolume(int value) {
        return value == Short.MIN_VALUE ? Short.MAX_VALUE : -value;
    }

    private int readReverbRelative(int registerIndex) {
        return readSoundRam(reverbRelativeAddress(registerIndex));
    }

    private int readReverbRelativeOffset(int registerIndex, int dispRegisterIndex) {
        return readSoundRam(reverbRelativeAddressOffset(
            registerIndex,
            -((reverbRegs[dispRegisterIndex] & 0xFFFF) << 2)
        ));
    }

    private int readReverbRelativeHalfwordOffset(int registerIndex, int halfwordOffset) {
        return readSoundRam(reverbRelativeAddressOffset(registerIndex, halfwordOffset));
    }

    private void writeReverbRelative(int registerIndex, int value) {
        writeSoundRam(reverbRelativeAddress(registerIndex), value);
    }

    private int reverbRelativeAddress(int registerIndex) {
        return reverbRelativeAddressOffset(registerIndex, 0);
    }

    private int reverbRelativeAddressOffset(int registerIndex, int halfwordOffset) {
        int offset = ((reverbRegs[registerIndex] & 0xFFFF) << 2) + halfwordOffset;
        return reverbWrapAddress(reverbCurrentAddress + offset);
    }

    private int reverbWrapAddress(int address) {
        int base = spuAddressToHalfword(reverbWorkAreaStart);
        int size = ram.length - base;
        if (size <= 0) {
            return ram.length - 1;
        }
        int relative = Math.floorMod(address - base, size);
        return base + relative;
    }

    private int reverbNextAddress(int address) {
        return reverbWrapAddress(address);
    }

    private void updateNoise() {
        int step = 4 + ((control >>> 8) & 0x3);
        int reload = Math.max(1, 0x20000 >> ((control >>> 10) & 0xF));
        noiseTimer -= step;
        while (noiseTimer < 0) {
            int parity = (((noiseLevel >>> 15) ^ (noiseLevel >>> 12) ^ (noiseLevel >>> 11) ^ (noiseLevel >>> 10) ^ 1) & 1);
            noiseLevel = (short) ((noiseLevel << 1) | parity);
            noiseTimer += reload;
        }
    }

    private void tickVolumeRegisters() {
        long left = applyVolumeMode(mainVolumeLeftRaw, mainVolumeLeftCurrent, mainVolumeLeftCounter);
        mainVolumeLeftCurrent = sweepLevel(left);
        mainVolumeLeftCounter = sweepCounter(left);
        long right = applyVolumeMode(mainVolumeRightRaw, mainVolumeRightCurrent, mainVolumeRightCounter);
        mainVolumeRightCurrent = sweepLevel(right);
        mainVolumeRightCounter = sweepCounter(right);
        for (Voice voice : voices) voice.tickVolumes();
    }

    private long applyVolumeMode(int raw, int current, int counter) {
        if ((raw & 0x8000) == 0) {
            return packSweep(decodeFixedVolume(raw), 0);
        }
        return stepEnvelopeValue(
            current,
            counter,
            ((raw >>> 14) & 1) != 0,
            ((raw >>> 13) & 1) == 0,
            (raw >>> 2) & 0x1F,
            raw & 0x3,
            ((raw >>> 12) & 1) != 0,
            0x7F
        );
    }

    private void flushTransferFifoIfNeeded() {
        if (transferFifo.isEmpty()) return;
        if (appliedTransferMode() != 1 && transferFifo.size() < TRANSFER_FIFO_CAPACITY) return;
        drainTransferFifoToRam();
    }

    private void drainTransferFifoToRam() {
        transferBusyDelay = Math.max(transferBusyDelay, 1);
        int type = transferType();
        if (type == 2) {
            while (!transferFifo.isEmpty()) {
                writeRamTransferHalfword(transferFifo.remove());
            }
            refreshTransferSlotService();
            return;
        }
        int group = type == 3 ? 2 : (type == 4 ? 4 : (type == 5 ? 8 : transferFifo.size()));
        while (!transferFifo.isEmpty()) {
            int count = Math.min(group, transferFifo.size());
            int selected = transferFifo.remove();
            for (int index = 1; index < count; index++) {
                int next = transferFifo.remove();
                if (type == 5 || type == 0 || type == 1 || type == 6 || type == 7) {
                    selected = next;
                }
            }
            for (int index = 0; index < count; index++) {
                writeRamTransferHalfword(selected);
            }
        }
        refreshTransferSlotService();
    }

    private void writeControl(int value) {
        boolean oldEnabled = (control & (1 << 15)) != 0;
        int oldTransferMode = (control >>> 4) & 0x3;
        int newTransferMode = (value >>> 4) & 0x3;
        if (newTransferMode == 0 && newTransferMode != oldTransferMode) {
            if (oldTransferMode == 2 && !transferFifo.isEmpty()) {
                drainTransferFifoToRam();
            } else {
                transferFifo.clear();
            }
            transferReadFifo.clear();
        }
        control = value & 0xFFFF;
        if (oldEnabled && (control & (1 << 15)) == 0) {
            for (Voice voice : voices) voice.forceOff();
        }
        refreshIrqConfiguration();
        pendingModeBits = control & 0x3F;
        modeDelaySamples = 1;
        if (((pendingModeBits >>> 4) & 0x3) == 0) {
            dmaWriteRequestDelay = 0;
            dmaReadRequestDelay = 0;
            transferBusyDelay = 0;
            transferReadFifo.clear();
            refreshTransferSlotService();
        }
        if (((control >>> 15) & 1) == 0 || ((control >>> 6) & 1) == 0) {
            clearIrq();
        } else {
            checkForLateRamIrq();
        }
        if (debugControlLogs < 12 && Log.isDebugEnabled()) {
            debugControlLogs++;
            Log.debug("SPU control write: cnt=0x" + Integer.toHexString(control) + ", stat=0x" + Integer.toHexString(status()));
        }
    }

    private void latchKeyOn(int value, int shift) {
        voiceKeyOn = (voiceKeyOn & ~(0xFFFF << shift)) | ((value & 0xFFFF) << shift);
    }

    private void latchKeyOff(int value, int shift) {
        voiceKeyOff = (voiceKeyOff & ~(0xFFFF << shift)) | ((value & 0xFFFF) << shift);
    }

    private void applyPendingKeys() {
        int keyOff = voiceKeyOff;
        int keyOn = voiceKeyOn;
        voiceKeyOff = 0;
        voiceKeyOn = 0;
        for (int index = 0; index < VOICE_COUNT; index++) {
            int mask = 1 << index;
            // Handle KOFF before KON.
            if ((keyOff & mask) != 0) voices[index].keyOff();
            if ((keyOn & mask) == 0) continue;
            voiceEndFlags &= ~mask;
            voices[index].keyOn();
            if (debugKeyOnLogs < 12 && Log.isDebugEnabled()) {
                debugKeyOnLogs++;
                Log.debug("SPU key-on: voice=" + index + ", start=0x" + Integer.toHexString(voices[index].startAddress << 3));
            }
        }
    }

    private void writeSoundRam(int index, int value) {
        int masked = index & (ram.length - 1);
        ram[masked] = (short) value;
        maybeRaiseIrq(masked, false);
    }

    private int readSoundRam(int index) {
        int masked = index & (ram.length - 1);
        int value = ram[masked];
        maybeRaiseIrq(masked, false);
        return value;
    }

    private void writeCaptureRam(int index, int value) {
        int masked = index & (ram.length - 1);
        ram[masked] = (short) value;
        maybeRaiseIrq(masked, true);
    }

    private void maybeRaiseIrq(int ramIndex, boolean captureWrite) {
        if (!irqEnabled || irqFlag || (captureWrite && !captureIrqEnabled)) return;
        if (ramIndex == irqHalfwordAddress) {
            irqFlag = true;
            if (interruptController != null) interruptController.raise(9);
        }
    }

    private void checkForLateRamIrq() {
        if (!irqEnabled || irqFlag) return;
        if ((transferCurrentAddress & (ram.length - 1)) == irqHalfwordAddress) {
            maybeRaiseIrq(irqHalfwordAddress, false);
            return;
        }
        for (Voice voice : voices) {
            // A newly keyed voice has not fetched its first block yet.
            if (!voice.decodedBlockActive) continue;
            int block = voice.activeRamBlockAddress();
            if (block == irqHalfwordAddress
                || ((block + 4) & (ram.length - 1)) == irqHalfwordAddress) {
                maybeRaiseIrq(irqHalfwordAddress, false);
                return;
            }
        }
    }

    private void refreshIrqConfiguration() {
        irqHalfwordAddress = spuAddressToHalfword(irqAddress);
        irqEnabled = (control & ((1 << 15) | (1 << 6))) == ((1 << 15) | (1 << 6));
        captureIrqEnabled = (transferControl & 0x0C) != 0;
    }

    private void clearIrq() {
        irqFlag = false;
        if (interruptController != null) interruptController.clear(9);
    }

    private int readRamTransferHalfword() {
        int index = transferCurrentAddress & (ram.length - 1);
        int value = ram[index] & 0xFFFF;
        transferCurrentAddress = (transferCurrentAddress + 1) & (ram.length - 1);
        maybeRaiseIrq(transferCurrentAddress, false);
        return value;
    }

    private void writeRamTransferHalfword(int value) {
        int index = transferCurrentAddress & (ram.length - 1);
        ram[index] = (short) value;
        transferCurrentAddress = (transferCurrentAddress + 1) & (ram.length - 1);
        maybeRaiseIrq(transferCurrentAddress, false);
    }

    private int status() {
        int result = appliedModeBits & 0x3F;
        boolean dmaWriteReady = dmaWriteRequest();
        boolean dmaReadReady = dmaReadRequest();
        if (irqFlag) result |= 1 << 6;
        if ((appliedModeBits & 0x20) != 0) result |= 1 << 7;
        if (dmaWriteReady) result |= 1 << 8;
        if (dmaReadReady) result |= 1 << 9;
        if (transferBusyDelay > 0
            || (appliedTransferMode() == 3
                && transferReadFifo.size() < TRANSFER_FIFO_CAPACITY)
            || ((appliedTransferMode() == 1 || appliedTransferMode() == 2)
                && !transferFifo.isEmpty())) {
            result |= 1 << 10;
        }
        if ((transferControl & 0x0C) != 0 && captureIndex >= 0x100) result |= 1 << 11;
        return result & 0xFFFF;
    }

    private int appliedTransferMode() { return (appliedModeBits >>> 4) & 0x3; }
    private int transferType() { return (transferControl >>> 1) & 0x7; }
    private boolean canAcceptTransferHalfword() {
        return transferFifoHasRoom();
    }
    private boolean transferFifoHasRoom() {
        return transferFifo.size() < TRANSFER_FIFO_CAPACITY;
    }
    private int spuAddressToHalfword(int address) { return (address << 2) & (ram.length - 1); }

    private void ensureCdCapacity(int samples) {
        if (samples <= cdAudioBuffer.length) return;
        int capacity = cdAudioBuffer.length;
        int maximum = CD_AUDIO_CAPACITY_FRAMES * 2;
        while (capacity < samples && capacity < maximum) capacity <<= 1;
        capacity = Math.min(capacity, maximum);
        short[] expanded = new short[capacity];
        for (int i = 0; i < cdAudioQueuedSamples; i++) expanded[i] = cdAudioBuffer[(cdAudioReadIndex + i) % cdAudioBuffer.length];
        cdAudioBuffer = expanded;
        cdAudioReadIndex = 0;
        cdAudioWriteIndex = cdAudioQueuedSamples;
    }

    private void discardCdSamples(int samples) {
        int discarded = Math.min(samples, cdAudioQueuedSamples) & ~1;
        cdAudioReadIndex = (cdAudioReadIndex + discarded) % cdAudioBuffer.length;
        cdAudioQueuedSamples -= discarded;
    }

    private int readCdSample() {
        int sample = cdAudioBuffer[cdAudioReadIndex];
        cdAudioReadIndex = (cdAudioReadIndex + 1) % cdAudioBuffer.length;
        cdAudioQueuedSamples--;
        return sample;
    }

    private void setVoiceEnd(int index) { voiceEndFlags |= 1 << index; }

    private static int decodeFixedVolume(int raw) {
        int value = raw & 0x7FFF;
        if ((value & 0x4000) != 0) value |= ~0x7FFF;
        return (short) (value << 1);
    }

    private static int applySignedVolume(int sample, int volume) {
        return multiplyAndShift(sample, (short) volume, 15);
    }

    private static int multiplyAndShift(int left, int right, int shift) {
        return (int) (((long) left * right) >> shift);
    }
    private static int clamp16(int value) { return Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE); }

    private static long stepEnvelopeValue(
        int current,
        int counter,
        boolean exponential,
        boolean increasing,
        int shift,
        int stepValue,
        boolean phaseNegative,
        int rateMask
    ) {
        int rate = (shift << 2) | stepValue;
        boolean decreasing = !increasing;
        // Phase inversion is ignored by the hardware for exponential decrease.
        boolean effectivePhaseNegative = phaseNegative && !(decreasing && exponential);
        int step = 7 - (rate & 0x3);
        if ((decreasing ^ effectivePhaseNegative) || (decreasing && exponential)) {
            step = ~step;
        }

        int counterIncrement = 0x8000;
        if (rate < 44) {
            step <<= 11 - (rate >>> 2);
        } else if (rate >= 48) {
            counterIncrement >>>= (rate >>> 2) - 11;
            // Rate 7Fh (7Ch for decay/release) is the special stopped rate.
            counterIncrement = (rate & rateMask) == rateMask
                ? 0
                : Math.max(counterIncrement, 1);
        }

        if (exponential && decreasing) {
            step = (step * current) >> 15;
        } else if (exponential && increasing && current >= 0x6000) {
            if (rate < 40) {
                step >>= 2;
            } else if (rate >= 44) {
                counterIncrement >>>= 2;
            } else {
                step >>= 1;
                counterIncrement >>>= 1;
            }
        }

        int nextCounter = counter + counterIncrement;
        if ((nextCounter & 0x8000) == 0) {
            return packSweep(current, nextCounter);
        }
        nextCounter = 0;

        int next = current + step;
        int level;
        if (!decreasing) {
            level = Math.clamp(next, Short.MIN_VALUE, 0x7FFF);
        } else if (effectivePhaseNegative) {
            level = Math.clamp(next, Short.MIN_VALUE, 0);
        } else {
            level = Math.max(0, next);
        }
        return packSweep(level, nextCounter);
    }

    private static long packSweep(int level, int counter) {
        return ((long) level << 32) | (counter & 0xFFFF_FFFFL);
    }

    private static int sweepLevel(long packed) {
        return (int) (packed >> 32);
    }

    private static int sweepCounter(long packed) {
        return (int) packed;
    }

    /** The physical transfer FIFO is exactly 32 halfwords and never allocates. */
    private static final class HalfwordFifo {
        private final int[] values = new int[TRANSFER_FIFO_CAPACITY];
        private int head;
        private int size;

        boolean isEmpty() { return size == 0; }
        int size() { return size; }

        void clear() {
            head = 0;
            size = 0;
        }

        void add(int value) {
            if (size >= values.length) return;
            values[(head + size) & (values.length - 1)] = value & 0xFFFF;
            size++;
        }

        int remove() {
            if (size == 0) return 0;
            int value = values[head];
            head = (head + 1) & (values.length - 1);
            size--;
            return value;
        }

        int[] toArray() {
            int[] copy = new int[size];
            for (int index = 0; index < size; index++) {
                copy[index] = values[(head + index) & (values.length - 1)];
            }
            return copy;
        }
    }

    public static final class State {
        VoiceState[] voices;
        short[][] mixedFrames;
        int[] transferFifo;
        int[] transferReadFifo;
        short[] reverbRegs;
        short[] unknownDbc;
        short[] unknownE60;
        short[][] reverbDownsampleBuffer;
        short[][] reverbUpsampleBuffer;
        short[] cdAudioBuffer;
        int mainVolumeLeftRaw;
        int mainVolumeRightRaw;
        int mainVolumeLeftCurrent;
        int mainVolumeRightCurrent;
        int mainVolumeLeftCounter;
        int mainVolumeRightCounter;
        int reverbOutputVolumeLeft;
        int reverbOutputVolumeRight;
        int voiceKeyOn;
        int voiceKeyOff;
        int voiceFmMode;
        int voiceNoiseMode;
        int voiceReverbMode;
        int voiceEndFlags;
        int reverbWorkAreaStart;
        int reverbCurrentAddress;
        int cdInputVolumeLeft;
        int cdInputVolumeRight;
        int externalInputVolumeLeft;
        int externalInputVolumeRight;
        int transferAddress;
        int transferCurrentAddress;
        int transferControl;
        int irqAddress;
        int control;
        int appliedModeBits;
        int pendingModeBits;
        int modeDelaySamples;
        int cycleAccumulator;
        boolean sampleFrameStarted;
        int frameCdRawLeft;
        int frameCdRawRight;
        int frameCdMixedLeft;
        int frameCdMixedRight;
        int frameNoiseSample;
        int frameDryLeft;
        int frameDryRight;
        int frameReverbInLeft;
        int frameReverbInRight;
        int framePreviousVoice;
        boolean frameSpuEnabled;
        boolean frameVoicesUnmuted;
        int cdAudioReadIndex;
        int cdAudioWriteIndex;
        int cdAudioQueuedSamples;
        int captureIndex;
        int noiseLevel;
        int noiseTimer;
        int reverbSampleLeft;
        int reverbSampleRight;
        int reverbInputLatchLeft;
        int reverbInputLatchRight;
        int reverbStageInput;
        int reverbStageSameDelayed;
        int reverbStageSamePrevious;
        int reverbStageDiffDelayed;
        int reverbStageDiffPrevious;
        int reverbStageComb;
        int reverbStageApf1Tap;
        int reverbStageApf2Tap;
        int reverbStageApfOutput;
        int reverbResamplePosition;
        int dmaReadRepeat;
        int dmaReadLatched;
        int dmaWriteRequestDelay;
        int dmaReadRequestDelay;
        int transferBusyDelay;
        boolean irqFlag;
        boolean reverbPhase;
        boolean reverbStageLeft;
        boolean reverbStageWriteEnabled;
        int debugControlLogs;
        int debugKeyOnLogs;
    }

    public static final class VoiceState {
        short[] decoded;
        short[] interpolationWindow;
        int volumeLeftRaw;
        int volumeRightRaw;
        int volumeLeftCurrent;
        int volumeRightCurrent;
        int volumeLeftCounter;
        int volumeRightCounter;
        int pitch;
        int startAddress;
        int adsrLow;
        int adsrHigh;
        int repeatAddress;
        int currentSample;
        int envelope;
        int envelopeCounter;
        int phase;
        int blockAddress;
        int sampleIndex;
        int history1;
        int history2;
        int sampleFrac;
        int blockFlags;
        short prev25;
        short prev26;
        short prev27;
        boolean pendingLoopEnd;
        boolean pendingLoopRepeat;
        boolean firstBlock;
        boolean ignoreLoopAddress;
        boolean streaming;
        boolean decodedBlockActive;
    }

    private enum EnvelopePhase { ATTACK, DECAY, SUSTAIN, RELEASE, OFF }

    private static final class Voice {
        private final Spu owner;
        private final int index;
        private final short[] decoded = new short[28];
        private final short[] interpolationWindow = new short[31];
        private int volumeLeftRaw;
        private int volumeRightRaw;
        private int volumeLeftCurrent;
        private int volumeRightCurrent;
        private int volumeLeftCounter;
        private int volumeRightCounter;
        private int pitch;
        private int startAddress;
        private int adsrLow;
        private int adsrHigh;
        private int repeatAddress;
        private int currentSample;
        private int envelope;
        private int envelopeCounter;
        private EnvelopePhase phase = EnvelopePhase.OFF;
        private int blockAddress;
        private int sampleIndex = 28;
        private int history1;
        private int history2;
        private int sampleFrac;
        private int blockFlags;
        private short prev25;
        private short prev26;
        private short prev27;
        private boolean pendingLoopEnd;
        private boolean pendingLoopRepeat;
        private boolean firstBlock;
        private boolean ignoreLoopAddress;
        private boolean streaming;
        private boolean decodedBlockActive;

        private Voice(Spu owner, int index) { this.owner = owner; this.index = index; }

        VoiceState copyState() {
            VoiceState state = new VoiceState();
            state.decoded = decoded.clone();
            state.interpolationWindow = interpolationWindow.clone();
            state.volumeLeftRaw = volumeLeftRaw;
            state.volumeRightRaw = volumeRightRaw;
            state.volumeLeftCurrent = volumeLeftCurrent;
            state.volumeRightCurrent = volumeRightCurrent;
            state.volumeLeftCounter = volumeLeftCounter;
            state.volumeRightCounter = volumeRightCounter;
            state.pitch = pitch;
            state.startAddress = startAddress;
            state.adsrLow = adsrLow;
            state.adsrHigh = adsrHigh;
            state.repeatAddress = repeatAddress;
            state.currentSample = currentSample;
            state.envelope = envelope;
            state.envelopeCounter = envelopeCounter;
            state.phase = phase.ordinal();
            state.blockAddress = blockAddress;
            state.sampleIndex = sampleIndex;
            state.history1 = history1;
            state.history2 = history2;
            state.sampleFrac = sampleFrac;
            state.blockFlags = blockFlags;
            state.prev25 = prev25;
            state.prev26 = prev26;
            state.prev27 = prev27;
            state.pendingLoopEnd = pendingLoopEnd;
            state.pendingLoopRepeat = pendingLoopRepeat;
            state.firstBlock = firstBlock;
            state.ignoreLoopAddress = ignoreLoopAddress;
            state.streaming = streaming;
            state.decodedBlockActive = decodedBlockActive;
            return state;
        }

        void loadState(VoiceState state) {
            if (state == null) {
                return;
            }
            copyInto(state.decoded, decoded);
            copyInto(state.interpolationWindow, interpolationWindow);
            volumeLeftRaw = state.volumeLeftRaw;
            volumeRightRaw = state.volumeRightRaw;
            volumeLeftCurrent = state.volumeLeftCurrent;
            volumeRightCurrent = state.volumeRightCurrent;
            volumeLeftCounter = state.volumeLeftCounter;
            volumeRightCounter = state.volumeRightCounter;
            pitch = state.pitch;
            startAddress = state.startAddress;
            adsrLow = state.adsrLow;
            adsrHigh = state.adsrHigh;
            repeatAddress = state.repeatAddress;
            currentSample = state.currentSample;
            envelope = state.envelope;
            envelopeCounter = state.envelopeCounter;
            EnvelopePhase[] phases = EnvelopePhase.values();
            phase = state.phase >= 0 && state.phase < phases.length ? phases[state.phase] : EnvelopePhase.OFF;
            blockAddress = state.blockAddress;
            sampleIndex = state.sampleIndex;
            history1 = state.history1;
            history2 = state.history2;
            sampleFrac = state.sampleFrac;
            blockFlags = state.blockFlags;
            prev25 = state.prev25;
            prev26 = state.prev26;
            prev27 = state.prev27;
            pendingLoopEnd = state.pendingLoopEnd;
            pendingLoopRepeat = state.pendingLoopRepeat;
            firstBlock = state.firstBlock;
            ignoreLoopAddress = state.ignoreLoopAddress;
            streaming = state.streaming;
            decodedBlockActive = state.decodedBlockActive || (streaming && sampleIndex < 28);
        }

        int read(int offset) {
            return switch (offset) {
                case 0x0 -> volumeLeftRaw & 0xFFFF;
                case 0x2 -> volumeRightRaw & 0xFFFF;
                case 0x4 -> pitch & 0xFFFF;
                case 0x6 -> startAddress & 0xFFFF;
                case 0x8 -> adsrLow & 0xFFFF;
                case 0xA -> adsrHigh & 0xFFFF;
                case 0xC -> envelope & 0xFFFF;
                case 0xE -> repeatAddress & 0xFFFF;
                default -> 0;
            };
        }

        void write(int offset, int value) {
            switch (offset) {
                case 0x0 -> {
                    volumeLeftRaw = value & 0xFFFF;
                    volumeLeftCounter = 0;
                    if ((value & 0x8000) == 0) volumeLeftCurrent = decodeFixedVolume(value);
                }
                case 0x2 -> {
                    volumeRightRaw = value & 0xFFFF;
                    volumeRightCounter = 0;
                    if ((value & 0x8000) == 0) volumeRightCurrent = decodeFixedVolume(value);
                }
                case 0x4 -> pitch = value & 0xFFFF;
                case 0x6 -> startAddress = value & 0xFFFF;
                case 0x8 -> {
                    adsrLow = value & 0xFFFF;
                    if (phase != EnvelopePhase.OFF) envelopeCounter = 0;
                }
                case 0xA -> {
                    adsrHigh = value & 0xFFFF;
                    if (phase != EnvelopePhase.OFF) envelopeCounter = 0;
                }
                case 0xC -> envelope = (short) value;
                case 0xE -> {
                    repeatAddress = value & 0xFFFF;
                    ignoreLoopAddress |= phase == EnvelopePhase.OFF || !firstBlock;
                }
                default -> { }
            }
        }

        void keyOn() {
            blockAddress = owner.spuAddressToHalfword(startAddress & ~1);
            sampleIndex = 28;
            sampleFrac = 0;
            envelope = 0;
            envelopeCounter = 0;
            history1 = 0;
            history2 = 0;
            prev25 = 0;
            prev26 = 0;
            prev27 = 0;
            Arrays.fill(interpolationWindow, (short) 0);
            pendingLoopEnd = false;
            pendingLoopRepeat = false;
            firstBlock = true;
            ignoreLoopAddress = false;
            streaming = true;
            decodedBlockActive = false;
            phase = EnvelopePhase.ATTACK;
        }

        void keyOff() {
            if (phase != EnvelopePhase.OFF && phase != EnvelopePhase.RELEASE) {
                phase = EnvelopePhase.RELEASE;
                envelopeCounter = 0;
            }
        }

        void forceOff() {
            envelope = 0;
            envelopeCounter = 0;
            phase = EnvelopePhase.OFF;
        }

        void tickVolumes() {
            long left = owner.applyVolumeMode(volumeLeftRaw, volumeLeftCurrent, volumeLeftCounter);
            volumeLeftCurrent = sweepLevel(left);
            volumeLeftCounter = sweepCounter(left);
            long right = owner.applyVolumeMode(volumeRightRaw, volumeRightCurrent, volumeRightCounter);
            volumeRightCurrent = sweepLevel(right);
            volumeRightCounter = sweepCounter(right);
        }

        int step(boolean noiseMode, boolean fmMode, int modulatorSample, int noiseSample) {
            if (!streaming && phase == EnvelopePhase.OFF) {
                currentSample = 0;
                return 0;
            }
            if (sampleIndex >= 28) {
                int nextBlockSampleIndex = sampleIndex - 28;
                decodeBlock();
                decodedBlockActive = true;
                sampleIndex = Math.min(nextBlockSampleIndex, 27);
            }
            int sample = noiseMode ? noiseSample : interpolate((sampleFrac >>> 4) & 0xFF);
            int step = pitch & 0xFFFF;
            if (fmMode && index > 0) {
                step = (int) (((long) (short) step
                    * (modulatorSample + 0x8000L)) >> 15) & 0xFFFF;
            }
            if (step > 0x3FFF) {
                step = 0x4000;
            }
            sampleFrac += step;
            sampleIndex += sampleFrac >>> 12;
            sampleFrac &= 0x0FFF;
            currentSample = (sample * envelope) >> 15;
            stepEnvelope();
            if (sampleIndex >= 28) completeDecodedBlock(noiseMode);
            return currentSample;
        }

        private void observeHeaderRamRead() {
            owner.maybeRaiseIrq(activeRamBlockAddress(), false);
        }

        private void observeSampleRamRead() {
            owner.maybeRaiseIrq((activeRamBlockAddress() + 4) & (owner.ram.length - 1), false);
        }

        private int activeRamBlockAddress() {
            return decodedBlockActive && sampleIndex < 28
                ? (blockAddress - 8) & (owner.ram.length - 1)
                : blockAddress & (owner.ram.length - 1);
        }

        private void decodeBlock() {
            int base = blockAddress & (owner.ram.length - 1);
            int header = owner.ram[base] & 0xFFFF;
            int shift = header & 0x0F;
            if (shift > 12) {
                shift = 9;
            }
            int filter = (header >>> 4) & 0x0F;
            int positiveFilter = filter < POS_FILTER.length ? POS_FILTER[filter] : 0;
            int negativeFilter = filter < NEG_FILTER.length ? NEG_FILTER[filter] : 0;
            blockFlags = (header >>> 8) & 0xFF;
            int out = 0;
            for (int i = 0; i < 7; i++) {
                int packed = owner.ram[(base + 1 + i) & (owner.ram.length - 1)] & 0xFFFF;
                for (int half = 0; half < 4; half++) {
                    int nibble = (packed >>> (half * 4)) & 0xF;
                    int sample = (nibble >= 8 ? nibble - 16 : nibble) << 12;
                    sample >>= shift;
                    sample += (history1 * positiveFilter) >> 6;
                    sample += (history2 * negativeFilter) >> 6;
                    sample = clamp16(sample);
                    history2 = history1;
                    history1 = sample;
                    decoded[out++] = (short) sample;
                }
            }
            interpolationWindow[0] = prev25;
            interpolationWindow[1] = prev26;
            interpolationWindow[2] = prev27;
            System.arraycopy(decoded, 0, interpolationWindow, 3, decoded.length);
            prev25 = decoded[25];
            prev26 = decoded[26];
            prev27 = decoded[27];
            sampleIndex = 0;
            // A loop-start header always updates VxRepeat.
            if ((blockFlags & 0x04) != 0 && !ignoreLoopAddress) {
                repeatAddress = (base >>> 2) & 0xFFFF;
            }
            pendingLoopEnd = (blockFlags & 0x01) != 0;
            pendingLoopRepeat = (blockFlags & 0x02) != 0;
            blockAddress = (blockAddress + 8) & (owner.ram.length - 1);
        }

        private int interpolate(int i) {
            int o = Math.min(sampleIndex, 27);
            int oldest = interpolationWindow[o];
            int older = interpolationWindow[o + 1];
            int old = interpolationWindow[o + 2];
            int newest = interpolationWindow[o + 3];
            int out = (GAUSS_TABLE[0x0FF - i] * oldest) >> 15;
            out += (GAUSS_TABLE[0x1FF - i] * older) >> 15;
            out += (GAUSS_TABLE[0x100 + i] * old) >> 15;
            out += (GAUSS_TABLE[i] * newest) >> 15;
            return clamp16(out);
        }

        private void completeDecodedBlock(boolean noiseMode) {
            decodedBlockActive = false;
            firstBlock = false;
            if (!pendingLoopEnd) return;
            owner.setVoiceEnd(index);
            blockAddress = owner.spuAddressToHalfword(repeatAddress & ~1);
            if (!pendingLoopRepeat) {
                if (!noiseMode) forceOff();
            }
            pendingLoopEnd = false;
            pendingLoopRepeat = false;
        }

        private void stepEnvelope() {
            int sustainLevel = Math.min(0x7FFF, ((adsrLow & 0xF) + 1) << 11);
            switch (phase) {
                case ATTACK -> {
                    long result = stepEnvelopeValue(
                        envelope,
                        envelopeCounter,
                        ((adsrLow >>> 15) & 1) != 0,
                        true,
                        (adsrLow >>> 10) & 0x1F,
                        (adsrLow >>> 8) & 0x3,
                        false,
                        0x7F
                    );
                    envelope = sweepLevel(result);
                    envelopeCounter = sweepCounter(result);
                    if (envelope >= 0x7FFF) {
                        envelope = 0x7FFF;
                        envelopeCounter = 0;
                        phase = EnvelopePhase.DECAY;
                    }
                }
                case DECAY -> {
                    long result = stepEnvelopeValue(
                        envelope,
                        envelopeCounter,
                        true,
                        false,
                        (adsrLow >>> 4) & 0xF,
                        0,
                        false,
                        0x7C
                    );
                    envelope = sweepLevel(result);
                    envelopeCounter = sweepCounter(result);
                    if (envelope <= sustainLevel) {
                        envelopeCounter = 0;
                        phase = EnvelopePhase.SUSTAIN;
                    }
                }
                case SUSTAIN -> {
                    long result = stepEnvelopeValue(
                        envelope,
                        envelopeCounter,
                        ((adsrHigh >>> 15) & 1) != 0,
                        ((adsrHigh >>> 14) & 1) == 0,
                        (adsrHigh >>> 8) & 0x1F,
                        (adsrHigh >>> 6) & 0x3,
                        false,
                        0x7F
                    );
                    envelope = sweepLevel(result);
                    envelopeCounter = sweepCounter(result);
                }
                case RELEASE -> {
                    long result = stepEnvelopeValue(
                        envelope,
                        envelopeCounter,
                        ((adsrHigh >>> 5) & 1) != 0,
                        false,
                        adsrHigh & 0x1F,
                        0,
                        false,
                        0x7C
                    );
                    envelope = sweepLevel(result);
                    envelopeCounter = sweepCounter(result);
                    if (envelope <= 0) {
                        envelope = 0;
                        envelopeCounter = 0;
                        phase = EnvelopePhase.OFF;
                    }
                }
                case OFF -> {
                    envelope = 0;
                    envelopeCounter = 0;
                }
            }
        }
    }
}
