package nanolive.psxj.emu;

import nanolive.psxj.emu.cd.CdSector;
import nanolive.psxj.emu.cd.XaAdpcmDecoder;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.Spu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpuAudioTest {

    @Test
    void silentMixerStillEmitsOneStereoFramePerSpuSamplePeriod() {
        Spu spu = new Spu();

        spu.tick(768);

        short[] samples = spu.drainMixedSamples();
        assertEquals(2, samples.length);
        assertEquals(0, samples[0]);
        assertEquals(0, samples[1]);
    }

    @Test
    void shouldMixCdAudioThroughSpuClock() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1D80, 0x3FFF);
        spu.write16(0x1F80_1D82, 0x3FFF);
        spu.write16(0x1F80_1DB0, 0x7FFF);
        spu.write16(0x1F80_1DB2, 0x7FFF);
        spu.write16(0x1F80_1DAA, 0x8001);

        spu.submitCdAudio(new short[]{1200, -900});
        spu.tick(768);

        short[][] frames = spu.drainMixedFrames();
        assertEquals(1, frames.length);
        assertTrue(frames[0][0] > 0);
        assertTrue(frames[0][1] < 0);
    }

    @Test
    void cdCaptureBuffersContainSamplesBeforeSpuInputVolume() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1DB0, 0x4000);
        spu.write16(0x1F80_1DB2, 0x4000);
        spu.submitCdAudio(new short[]{1000, -1000});

        spu.tick(768);

        short[] ram = spu.copyRam();
        assertEquals(1000, ram[0]);
        assertEquals(-1000, ram[0x200]);
    }

    @Test
    void signedVolumeUsesHardwareArithmeticShiftForNegativeSamples() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1D80, 0x3FFF);
        spu.write16(0x1F80_1D82, 0x3FFF);
        spu.write16(0x1F80_1DB0, 0x7FFF);
        spu.write16(0x1F80_1DB2, 0x7FFF);
        spu.write16(0x1F80_1DAA, 0x8001);
        spu.submitCdAudio(new short[]{-1, -1});

        spu.tick(768);

        assertArrayEquals(new short[]{-1, -1}, spu.drainMixedSamples());
    }

    @Test
    void cdAudioQueueCannotAccumulateBeyondOneSecond() {
        Spu spu = new Spu();
        spu.submitCdAudio(new short[44_100 * 2 + 64]);

        assertEquals(44_100, spu.queuedCdAudioFrames());
    }

    @Test
    void shouldResampleXaSectorToMixerRate() {
        XaAdpcmDecoder decoder = new XaAdpcmDecoder();
        byte[] raw = new byte[2352];
        raw[19] = 0x01;

        short[] pcm = decoder.decodeSector(new CdSector(0, 0, 0, raw, new byte[2048]));

        assertEquals(2352 * 2, pcm.length);
    }

    @Test
    void shouldLatchSpuModeBitsIntoStatusAfterOneSample() {
        Spu spu = new Spu();

        spu.write16(0x1F80_1DAA, 0x8020);
        assertEquals(0, spu.read16(0x1F80_1DAE) & 0x20);

        spu.tick(768);
        assertEquals(0x20, spu.read16(0x1F80_1DAE) & 0x20);
    }

    @Test
    void shouldApplyRepeat2TransferControlOnManualWrite() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAC, 0x0006);
        spu.write16(0x1F80_1DAA, 0x8010);
        spu.write16(0x1F80_1DA8, 0x1111);
        spu.write16(0x1F80_1DA8, 0x2222);
        spu.write16(0x1F80_1DA8, 0x3333);
        spu.write16(0x1F80_1DA8, 0x4444);

        spu.tick(768);

        spu.write16(0x1F80_1DAA, 0x8000);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAA, 0x8030);
        spu.tick(768 * 2);
        assertEquals(0x1111, spu.dmaRead());
        assertEquals(0x1111, spu.dmaRead());
        assertEquals(0x3333, spu.dmaRead());
        assertEquals(0x3333, spu.dmaRead());
    }

    @Test
    void shouldDelayDmaReadRequestStatusBit() {
        Spu spu = new Spu();

        spu.write16(0x1F80_1DAA, 0x8030);
        assertEquals(0, spu.read16(0x1F80_1DAE) & (1 << 9));

        spu.tick(768);
        assertEquals(0, spu.read16(0x1F80_1DAE) & (1 << 9));

        spu.tick(768);
        assertEquals(1 << 9, spu.read16(0x1F80_1DAE) & (1 << 9));
    }

    @Test
    void dmaWriteRequestDropsWhileTransferFifoIsFull() {
        Spu spu = new Spu();

        spu.write16(0x1F80_1DAC, 0x0000);
        spu.write16(0x1F80_1DAA, 0x8020);
        spu.tick(768);
        assertEquals(1 << 8, spu.read16(0x1F80_1DAE) & (1 << 8));

        for (int i = 0; i < 31; i++) {
            spu.dmaWrite(i);
        }

        assertEquals(0, spu.read16(0x1F80_1DAE) & (1 << 8));
        assertEquals(1 << 10, spu.read16(0x1F80_1DAE) & (1 << 10));

        spu.dmaWrite(31);
        spu.tick(768);
        assertEquals(1 << 8, spu.read16(0x1F80_1DAE) & (1 << 8));
    }

    @Test
    void dmaWriteReachesRamOnlyInAReservedVoiceTransferSlot() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8020);
        spu.dmaWrite(0x5A5A);

        assertEquals(0, spu.copyRam()[0x800] & 0xFFFF);
        spu.tick(23);
        assertEquals(0, spu.copyRam()[0x800] & 0xFFFF);
        spu.tick(1);

        assertEquals(0x5A5A, spu.copyRam()[0x800] & 0xFFFF);
    }

    @Test
    void stoppingDmaWriteDrainsAcceptedFifoTailIntoSoundRam() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8020);
        spu.dmaWrite(0x1234);
        spu.dmaWrite(0x5678);

        spu.write16(0x1F80_1DAA, 0x8000);

        short[] ram = spu.copyRam();
        assertEquals(0x1234, ram[0x800] & 0xFFFF);
        assertEquals(0x5678, ram[0x801] & 0xFFFF);
    }

    @Test
    void dmaReadTouchesRamOnlyInAReservedVoiceTransferSlot() {
        InterruptController interrupts = new InterruptController();
        Spu spu = new Spu(interrupts);
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DA4, 0x0201);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8070);

        spu.tick(239);
        assertEquals(0, interrupts.status() & (1 << 9));
        spu.tick(1);

        assertEquals(1 << 9, interrupts.status() & (1 << 9));
    }

    @Test
    void snapshotRestoresPrefetchedDmaReadData() {
        Spu spu = new Spu();
        short[] ram = spu.copyRam();
        ram[0x800] = (short) 0x6B4D;
        spu.loadRam(ram);
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8030);
        spu.tick(24);

        Spu.State state = spu.copyState();
        assertEquals(0x6B4D, spu.dmaRead());

        spu.loadState(state);
        assertEquals(0x6B4D, spu.dmaRead());
    }

    @Test
    void shouldRaiseIrqFromCaptureBufferWrites() {
        InterruptController interrupts = new InterruptController();
        Spu spu = new Spu(interrupts);
        spu.write16(0x1F80_1DA4, 0x0000);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8040);
        spu.submitCdAudio(new short[]{1, -1});

        spu.tick(768);

        assertEquals(1 << 9, interrupts.status() & (1 << 9));
        assertEquals(1 << 6, spu.read16(0x1F80_1DAE) & (1 << 6));
    }

    @Test
    void snapshotRestoresPendingTransferFifoAndSoundRam() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1DA6, 0x0200);
        spu.write16(0x1F80_1DAC, 0x0004);
        spu.write16(0x1F80_1DAA, 0x8010);
        spu.write16(0x1F80_1DA8, 0x1357);

        Spu.State state = spu.copyState();
        short[] ram = spu.copyRam();

        spu.tick(768);
        spu.loadRam(ram);
        spu.loadState(state);
        spu.tick(768);

        assertEquals(0x1357, spu.copyRam()[0x800] & 0xFFFF);
    }

    @Test
    void slowAdsrRateWaitsBetweenEnvelopeSteps() {
        Spu spu = configuredVoiceSpu(0x340F, 0x0000);

        spu.tick(768 * 3);
        assertEquals(0, spu.read16(0x1F80_1C0C));
        spu.tick(768);

        assertEquals(7, spu.read16(0x1F80_1C0C));
    }

    @Test
    void maximumAttackRateNeverAdvancesEnvelope() {
        Spu spu = configuredVoiceSpu(0x7F0F, 0x0000);

        spu.tick(768 * 128);

        assertEquals(0, spu.read16(0x1F80_1C0C));
    }

    @Test
    void sustainDirectionComesFromBit14() {
        Spu spu = configuredVoiceSpu(0x000F, 0x4000);

        spu.tick(768 * 4);
        assertEquals(0x3FFF, spu.read16(0x1F80_1C0C));
        spu.tick(768);

        assertTrue(spu.read16(0x1F80_1C0C) < 0x7FFF);
    }

    @Test
    void sustainBit13DoesNotSelectDecreaseDirection() {
        Spu spu = configuredVoiceSpu(0x000F, 0x2000);

        spu.tick(768 * 6);

        assertEquals(0x7FFF, spu.read16(0x1F80_1C0C));
    }

    @Test
    void slowMainVolumeSweepUsesItsRateCounter() {
        Spu spu = new Spu();
        spu.write16(0x1F80_1D80, 0x0000);
        spu.tick(768);
        spu.write16(0x1F80_1D80, 0x8034);

        spu.tick(768 * 3);
        assertEquals(0, spu.read16(0x1F80_1DB8));
        spu.tick(768);

        assertEquals(7, spu.read16(0x1F80_1DB8));
    }

    @Test
    void adpcmFetchChecksBothEightByteHalvesForIrq() {
        InterruptController interrupts = new InterruptController();
        Spu spu = configuredVoiceSpu(new Spu(interrupts), 0x0000, 0x0000);
        spu.write16(0x1F80_1DA4, 0x0201);
        spu.write16(0x1F80_1DAA, 0xC040);

        spu.tick(15);
        assertEquals(0, interrupts.status() & (1 << 9));
        spu.tick(1);

        assertEquals(1 << 9, interrupts.status() & (1 << 9));
        assertEquals(1 << 6, spu.read16(0x1F80_1DAE) & (1 << 6));
    }

    @Test
    void idleVoiceStillReadsSpuRamAtItsEightClockSlot() {
        InterruptController interrupts = new InterruptController();
        Spu spu = new Spu(interrupts);
        spu.write16(0x1F80_1DA6, 0x0300);
        spu.write16(0x1F80_1DA4, 0x0000);
        spu.write16(0x1F80_1DAA, 0x8040);

        spu.tick(7);
        assertEquals(0, interrupts.status() & (1 << 9));
        spu.tick(1);

        assertEquals(1 << 9, interrupts.status() & (1 << 9));
    }

    @Test
    void reverbRamReadsUseFourteenEightClockSlotsBetweenCaptureAndVoiceOne() {
        InterruptController interrupts = new InterruptController();
        Spu spu = new Spu(interrupts);
        spu.write16(0x1F80_1DA2, 0x0200);
        spu.write16(0x1F80_1DA4, 0x0200);
        spu.write16(0x1F80_1DAA, 0x8040);

        spu.tick(63);
        assertEquals(0, interrupts.status() & (1 << 9));
        spu.tick(1);

        assertEquals(1 << 9, interrupts.status() & (1 << 9));
    }

    @Test
    void voiceRamFetchesAreDistributedAcrossTheSevenHundredSixtyEightClockFrame() {
        InterruptController interrupts = new InterruptController();
        Spu spu = new Spu(interrupts);
        int voice23Base = 0x1F80_1C00 + 23 * 0x10;
        spu.write16(voice23Base + 0x6, 0x0200);
        spu.write16(0x1F80_1DA2, 0x0300);
        spu.write16(0x1F80_1DA4, 0x0200);
        spu.write16(0x1F80_1DAA, 0x8040);
        spu.write16(0x1F80_1D8A, 1 << 7);

        spu.tick(768);
        spu.tick(703);
        assertEquals(0, interrupts.status() & (1 << 9));
        spu.tick(1);

        assertEquals(1 << 9, interrupts.status() & (1 << 9));
    }

    @Test
    void highPitchKeepsSampleRemainderAcrossAdpcmBlockBoundary() {
        Spu fast = configuredPitchVoice(0x3000);
        Spu reference = configuredPitchVoice(0x1000);

        fast.tick(768 * 11);
        reference.tick(768 * 31);

        short[] fastPcm = fast.drainMixedSamples();
        short[] referencePcm = reference.drainMixedSamples();
        assertEquals(referencePcm[30 * 2], fastPcm[10 * 2]);
        assertEquals(referencePcm[30 * 2 + 1], fastPcm[10 * 2 + 1]);
    }

    @Test
    void maximumPitchUsesTheHardwareFourSampleStep() {
        Spu fast = configuredPitchVoice(0xFFFF);
        Spu reference = configuredPitchVoice(0x1000);

        fast.tick(768 * 7);
        reference.tick(768 * 25);

        short[] fastPcm = fast.drainMixedSamples();
        short[] referencePcm = reference.drainMixedSamples();
        assertEquals(referencePcm[24 * 2], fastPcm[6 * 2]);
        assertEquals(referencePcm[24 * 2 + 1], fastPcm[6 * 2 + 1]);
    }

    @Test
    void endMuteDoesNotSilenceNoiseVoiceOrChangeItsReverbEnableRegister() {
        Spu spu = configuredVoiceSpu(0x000F, 0x0000);
        short[] ram = spu.copyRam();
        ram[0x800] = 0x0100; // Loop end, without loop repeat.
        spu.loadRam(ram);
        spu.write16(0x1F80_1D94, 0x0001);
        spu.write16(0x1F80_1D98, 0x0001);
        spu.write16(0x1F80_1C0C, 0x7FFF);

        spu.tick(768 * 29);

        assertEquals(0x7FFF, spu.read16(0x1F80_1C0C));
        assertEquals(1, spu.read16(0x1F80_1D98) & 1);
    }

    @Test
    void mixerSaturatesVoiceSumBeforeApplyingMainVolume() {
        Spu spu = new Spu();
        short[] ram = new short[512 * 1024 / 2];
        ram[0x800] = 0x0000;
        for (int i = 1; i < 8; i++) {
            ram[0x800 + i] = 0x7777;
        }
        spu.loadRam(ram);
        for (int voice = 0; voice < 24; voice++) {
            int base = 0x1F80_1C00 + voice * 0x10;
            spu.write16(base, 0x3FFF);
            spu.write16(base + 0x2, 0x3FFF);
            spu.write16(base + 0x4, 0x1000);
            spu.write16(base + 0x6, 0x0200);
            spu.write16(base + 0x8, 0x000F);
            spu.write16(base + 0xA, 0x0000);
        }
        spu.write16(0x1F80_1DAA, 0xC000);
        spu.write16(0x1F80_1D88, 0xFFFF);
        spu.write16(0x1F80_1D8A, 0x00FF);
        spu.tick(768);
        spu.drainMixedSamples();
        for (int voice = 0; voice < 24; voice++) {
            spu.write16(0x1F80_1C0C + voice * 0x10, 0x7FFF);
        }
        spu.write16(0x1F80_1D80, 0x3FFF);
        spu.write16(0x1F80_1D82, 0x3FFF);

        spu.tick(768 * 8);

        short[] pcm = spu.drainMixedSamples();
        assertTrue(pcm[pcm.length - 2] > 32_000);
        assertTrue(pcm[pcm.length - 1] > 32_000);
    }

    @Test
    void voiceStartAddressIgnoresLowAlignmentBit() {
        Spu aligned = configuredPitchVoice(0x1000, 0x0200);
        Spu odd = configuredPitchVoice(0x1000, 0x0201);

        aligned.tick(768 * 8);
        odd.tick(768 * 8);

        assertArrayEquals(aligned.drainMixedSamples(), odd.drainMixedSamples());
    }

    @Test
    void softwareRepeatAddressWrittenAfterFirstBlockOverridesLaterLoopStart() {
        Spu spu = configuredVoiceSpu(0x000F, 0x0000);
        short[] ram = spu.copyRam();
        ram[0x810] = 0x0400; // Loop-start in the third ADPCM block.
        spu.loadRam(ram);

        spu.tick(768 * 29);
        spu.write16(0x1F80_1C0E, 0x0300);
        spu.tick(768 * 28);

        assertEquals(0x0300, spu.read16(0x1F80_1C0E));
    }

    private static Spu configuredPitchVoice(int pitch) {
        return configuredPitchVoice(pitch, 0x0200);
    }

    private static Spu configuredPitchVoice(int pitch, int startAddress) {
        Spu spu = new Spu();
        short[] ram = new short[512 * 1024 / 2];
        ram[0x800] = 0x000C;
        for (int i = 1; i < 8; i++) {
            ram[0x800 + i] = 0x1111;
        }
        ram[0x808] = 0x000C;
        for (int i = 1; i < 8; i++) {
            ram[0x808 + i] = (short) 0x4321;
        }
        spu.loadRam(ram);
        spu.write16(0x1F80_1C00, 0x3FFF);
        spu.write16(0x1F80_1C02, 0x3FFF);
        spu.write16(0x1F80_1C04, pitch);
        spu.write16(0x1F80_1C06, startAddress);
        spu.write16(0x1F80_1C08, 0x000F);
        spu.write16(0x1F80_1C0A, 0x0000);
        spu.write16(0x1F80_1DAA, 0xC000);
        spu.write16(0x1F80_1D88, 0x0001);
        spu.tick(768);
        spu.drainMixedSamples();
        spu.write16(0x1F80_1C0C, 0x7FFF);
        return spu;
    }

    private static Spu configuredVoiceSpu(int adsrLow, int adsrHigh) {
        return configuredVoiceSpu(new Spu(), adsrLow, adsrHigh);
    }

    private static Spu configuredVoiceSpu(Spu spu, int adsrLow, int adsrHigh) {
        short[] ram = new short[512 * 1024 / 2];
        ram[0x800] = 0;
        spu.loadRam(ram);
        spu.write16(0x1F80_1C00, 0x3FFF);
        spu.write16(0x1F80_1C02, 0x3FFF);
        spu.write16(0x1F80_1C04, 0x1000);
        spu.write16(0x1F80_1C06, 0x0200);
        spu.write16(0x1F80_1C08, adsrLow);
        spu.write16(0x1F80_1C0A, adsrHigh);
        spu.write16(0x1F80_1DAA, 0xC000);
        spu.write16(0x1F80_1D88, 0x0001);
        spu.tick(768);
        spu.drainMixedSamples();
        return spu;
    }
}
