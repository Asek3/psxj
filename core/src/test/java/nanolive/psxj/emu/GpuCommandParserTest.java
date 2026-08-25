package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.hardware.HardwareProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuCommandParserTest {

    @Test
    void shouldDrawFilledRectangleIntoVramBackbuffer() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0x0200_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0004);
        finishGpuWork(gpu);

        var frame = gpu.captureFrame();
        assertTrue(frame.pixels()[0] != 0xFF00_0000);
    }

    @Test
    void quadStartsItsFirstTriangleBeforeTheFourthVertexArrives() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xE400_0000 | 100 | (100 << 10));

        gpu.gp0(0x2800_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0000_0010);
        gpu.gp0(0x0010_0000);
        gpu.tick(100_000);

        short[] firstTriangle = gpu.copyVram();
        assertEquals(0x001F, firstTriangle[2 * 1024 + 2] & 0xFFFF);
        assertEquals(0, firstTriangle[14 * 1024 + 14] & 0xFFFF);

        gpu.gp0(0x0010_0010);
        gpu.tick(100_000);

        assertEquals(0x001F, gpu.copyVram()[14 * 1024 + 14] & 0xFFFF);
    }

    @Test
    void polylineRendersEachSegmentWithoutWaitingForTheTerminator() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xE400_0000 | 100 | (100 << 10));

        gpu.gp0(0x4800_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0000_000A);
        gpu.tick(100_000);
        assertEquals(0x001F, gpu.copyVram()[5] & 0xFFFF);

        gpu.gp0(0x000A_000A);
        gpu.tick(100_000);
        assertEquals(0x001F, gpu.copyVram()[5 * 1024 + 10] & 0xFFFF);

        gpu.gp0(0x5000_5000);
        assertEquals(1 << 26, gpu.status() & (1 << 26));
    }

    @Test
    void disabledDisplayOutputsBlackInsteadOfDirtyVramUploads() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0000_0000);
        gpu.gp0(0x0200_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0004);
        finishGpuWork(gpu);

        var frame = gpu.captureFrame();
        for (int pixel : frame.pixels()) {
            assertEquals(0, pixel & 0x00FF_FFFF);
        }
    }

    @Test
    void snapshotRestoresPendingCpuToVramTransfer() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xA000_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0002);

        Gpu.State state = gpu.copyState();
        short[] vram = gpu.copyVram();

        gpu.gp1(0x0000_0000);
        gpu.loadVram(vram);
        gpu.loadState(state);
        gpu.gp0(0x03E0_001F);

        short[] restored = gpu.copyVram();
        assertEquals(0x001F, restored[0] & 0xFFFF);
        assertEquals(0x03E0, restored[1] & 0xFFFF);
    }

    @Test
    void fixedScph5501ProfileUsesNtscOutputTimingAfterReset() {
        Gpu gpu = new Gpu(new InterruptController());

        gpu.tick(1_200_000);
        assertEquals(2, gpu.frameCounter());
    }

    @Test
    void palOutputOnScph5501KeepsNtscOscillator() {
        Gpu gpu = new Gpu(new InterruptController());
        HardwareProfile profile = HardwareProfile.SCPH_5501_PU_18_NTSC_U;
        assertEquals(profile.gpuClockRatioNumerator(), gpu.crtcClockNumerator());
        assertEquals(profile.gpuClockRatioDenominator(), gpu.crtcClockDenominator());

        gpu.gp1(0x0800_0008);
        assertEquals(profile.gpuClockRatioNumerator(), gpu.crtcClockNumerator());
        assertEquals(profile.gpuClockRatioDenominator(), gpu.crtcClockDenominator());

        gpu.tick(1_200_000);

        assertEquals(1, gpu.frameCounter());
    }

    @Test
    void completedFrameDoesNotReadVramBeingDrawnForNextFrame() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0300_0000);

        gpu.tick(600_000);
        fill(gpu, 0x0200_00FF);
        gpu.tick(570_000);
        int completedPixel = gpu.captureFrame().pixels()[0];
        int completedVramPixel = gpu.copyVram()[0] & 0xFFFF;

        fill(gpu, 0x02FF_0000);

        assertNotEquals(completedVramPixel, gpu.copyVram()[0] & 0xFFFF);
        assertNotEquals(0xFF00_0000, completedPixel);
        assertEquals(completedPixel, gpu.captureFrame().pixels()[0]);
    }

    @Test
    void capturesPacked24BitDisplayRowsWithoutChangingByteOrder() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xA000_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0002);
        gpu.gp0(0x4433_2211);
        gpu.gp1(0x0800_0010);
        gpu.gp1(0x0300_0000);

        gpu.tick(600_000);

        assertEquals(0xFF11_2233, gpu.captureFrame().pixels()[0]);
    }

    @Test
    void interlacedRenderingSkipsTheDisplayedFieldUnlessExplicitlyEnabled() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0800_0024); // 480-line interlaced mode

        fillAt(gpu, 0x0200_00FF, 0, 0, 1, 2);
        short[] firstField = gpu.copyVram();
        assertEquals(0, firstField[0] & 0xFFFF);
        assertEquals(0x001F, firstField[1024] & 0xFFFF);

        gpu.tick(600_000);
        assertEquals(1, gpu.frameCounter());
        fillAt(gpu, 0x0200_FF00, 16, 0, 1, 2);
        short[] secondField = gpu.copyVram();
        assertEquals(0x03E0, secondField[16] & 0xFFFF);
        assertEquals(0, secondField[1024 + 16] & 0xFFFF);

        gpu.gp0(0xE100_0400); // draw to the displayed field
        fillAt(gpu, 0x02FF_0000, 32, 0, 1, 2);
        short[] bothFields = gpu.copyVram();
        assertEquals(0x7C00, bothFields[32] & 0xFFFF);
        assertEquals(0x7C00, bothFields[1024 + 32] & 0xFFFF);
    }

    private static void fill(Gpu gpu, int command) {
        gpu.gp0(command);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0004);
        finishGpuWork(gpu);
    }

    private static void fillAt(
        Gpu gpu, int command, int x, int y, int width, int height
    ) {
        gpu.gp0(command);
        gpu.gp0((y << 16) | x);
        gpu.gp0((height << 16) | width);
        finishGpuWork(gpu);
    }

    @Test
    void overlappingVramCopyToTheRightUsesHardwareCopyDirection() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 0, 0, 4, 1, 0x2222_1111, 0x4444_3333);

        gpu.gp0(0x8000_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0000_0001);
        gpu.gp0(0x0001_0004);
        finishGpuWork(gpu);

        short[] vram = gpu.copyVram();
        assertEquals(0x1111, vram[0] & 0xFFFF);
        assertEquals(0x1111, vram[1] & 0xFFFF);
        assertEquals(0x2222, vram[2] & 0xFFFF);
        assertEquals(0x3333, vram[3] & 0xFFFF);
        assertEquals(0x4444, vram[4] & 0xFFFF);
    }

    @Test
    void vramTransfersApplySetAndCheckMaskBits() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xE600_0001);
        upload(gpu, 0, 0, 2, 1, 0x1234_001F);

        short[] masked = gpu.copyVram();
        assertEquals(0x801F, masked[0] & 0xFFFF);
        assertEquals(0x9234, masked[1] & 0xFFFF);

        gpu.gp0(0xE600_0002);
        upload(gpu, 0, 0, 2, 1, 0x03E0_7C00);

        short[] protectedVram = gpu.copyVram();
        assertEquals(0x801F, protectedVram[0] & 0xFFFF);
        assertEquals(0x9234, protectedVram[1] & 0xFFFF);
    }

    @Test
    void textureAndClutReadsWrapAtTheVramEdge() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 0, 0, 1, 1, 0x0000_0001);
        upload(gpu, 16, 1, 2, 1, 0x001F_0000);

        gpu.gp0(0xE100_008F);
        drawRawTexturedPixel(gpu, 300, 100, 128, 0, 0x41);

        assertEquals(0x001F, gpu.copyVram()[100 * 1024 + 300] & 0xFFFF);
    }

    @Test
    void clutCacheSurvivesVramChangesUntilInvalidated() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 64, 0, 1, 1, 0x0000_0001);
        upload(gpu, 0, 1, 2, 1, 0x001F_0000);
        gpu.gp0(0xE100_0001);

        drawRawTexturedPixel(gpu, 200, 100, 0, 0, 0x40);
        upload(gpu, 0, 1, 2, 1, 0x03E0_0000);
        drawRawTexturedPixel(gpu, 201, 100, 0, 0, 0x40);
        gpu.gp1(0x0100_0000);
        drawRawTexturedPixel(gpu, 202, 100, 0, 0, 0x40);
        upload(gpu, 0, 1, 2, 1, 0x7C00_0000);
        drawRawTexturedPixel(gpu, 203, 100, 0, 0, 0x40);
        gpu.gp0(0x0100_0000);
        drawRawTexturedPixel(gpu, 204, 100, 0, 0, 0x40);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[100 * 1024 + 200] & 0xFFFF);
        assertEquals(0x001F, vram[100 * 1024 + 201] & 0xFFFF);
        assertEquals(0x03E0, vram[100 * 1024 + 202] & 0xFFFF);
        assertEquals(0x03E0, vram[100 * 1024 + 203] & 0xFFFF);
        assertEquals(0x7C00, vram[100 * 1024 + 204] & 0xFFFF);
    }

    @Test
    void fourBitDrawReusesWiderEightBitClutCache() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 0, 0, 1, 1, 0x0000_0001);
        upload(gpu, 0, 1, 2, 1, 0x001F_0000);
        gpu.gp0(0xE100_0080); // 8-bit texture mode loads all 256 CLUT entries.

        drawRawTexturedPixel(gpu, 200, 100, 0, 0, 0x40);
        upload(gpu, 0, 1, 2, 1, 0x03E0_0000);
        gpu.gp0(0xE100_0000); // Narrowing to 4-bit must not reload the CLUT.
        drawRawTexturedPixel(gpu, 201, 100, 0, 0, 0x40);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[100 * 1024 + 200] & 0xFFFF);
        assertEquals(0x001F, vram[100 * 1024 + 201] & 0xFFFF);
    }

    @Test
    void wideningFourBitClutToEightBitLoadsTheUpperEntries() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 0, 0, 1, 1, 0x0000_0011);
        upload(gpu, 0, 1, 18, 1,
            0x001F_0000, 0, 0, 0, 0, 0, 0, 0, 0x03E0_0000);

        gpu.gp0(0xE100_0000);
        drawRawTexturedPixel(gpu, 200, 100, 0, 0, 0x40);
        gpu.gp0(0xE100_0080);
        drawRawTexturedPixel(gpu, 201, 100, 0, 0, 0x40);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[100 * 1024 + 200] & 0xFFFF);
        assertEquals(0x03E0, vram[100 * 1024 + 201] & 0xFFFF);
    }

    @Test
    void changingClutSelectorReloadsThePalette() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 0, 0, 1, 1, 0x0000_0001);
        upload(gpu, 0, 1, 2, 1, 0x001F_0000);
        upload(gpu, 16, 1, 2, 1, 0x03E0_0000);
        gpu.gp0(0xE100_0000);

        drawRawTexturedPixel(gpu, 200, 100, 0, 0, 0x40);
        drawRawTexturedPixel(gpu, 201, 100, 0, 0, 0x41);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[100 * 1024 + 200] & 0xFFFF);
        assertEquals(0x03E0, vram[100 * 1024 + 201] & 0xFFFF);
    }

    @Test
    void eightBitClutWrapsAcrossTheRightVramEdge() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 64, 0, 1, 1, 0x0000_0010);
        upload(gpu, 0, 2, 1, 1, 0x0000_03E0);
        gpu.gp0(0xE100_0081);

        drawRawTexturedPixel(gpu, 200, 100, 0, 0, (2 << 6) | 63);

        assertEquals(0x03E0, gpu.copyVram()[100 * 1024 + 200] & 0xFFFF);
    }

    @Test
    void textureCacheSurvivesVramChangesUntilGp0CacheClear() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 64, 0, 1, 1, 0x0000_0001);
        upload(gpu, 0, 1, 3, 1, 0x001F_0000, 0x0000_03E0);
        gpu.gp0(0xE100_0001);

        drawRawTexturedPixel(gpu, 200, 100, 0, 0, 0x40);
        upload(gpu, 64, 0, 1, 1, 0x0000_0002);
        drawRawTexturedPixel(gpu, 201, 100, 0, 0, 0x40);
        gpu.gp0(0x0100_0000);
        drawRawTexturedPixel(gpu, 202, 100, 0, 0, 0x40);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[100 * 1024 + 200] & 0xFFFF);
        assertEquals(0x001F, vram[100 * 1024 + 201] & 0xFFFF);
        assertEquals(0x03E0, vram[100 * 1024 + 202] & 0xFFFF);
    }

    @Test
    void eightBitTextureCacheHasFourEntriesPerRowAndSixtyFourRows() {
        Gpu gpu = new Gpu(new InterruptController());
        int clutBits = 100 << 6;
        upload(gpu, 0, 0, 1, 1, 0x0000_0001);
        upload(gpu, 0, 64, 1, 1, 0x0000_0001);
        upload(gpu, 0, 100, 3, 1, 0x001F_0000, 0x0000_03E0);
        gpu.gp0(0xE100_0080);

        drawRawTexturedPixel(gpu, 200, 120, 0, 0, clutBits);
        upload(gpu, 0, 0, 1, 1, 0x0000_0002);
        drawRawTexturedPixel(gpu, 201, 120, 0, 64, clutBits);
        drawRawTexturedPixel(gpu, 202, 120, 0, 0, clutBits);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[120 * 1024 + 200] & 0xFFFF);
        assertEquals(0x001F, vram[120 * 1024 + 201] & 0xFFFF);
        assertEquals(0x03E0, vram[120 * 1024 + 202] & 0xFFFF,
            "row 64 must evict row 0 in the 32x64-pixel 8-bit cache");
    }

    @Test
    void drawModeChangesDoNotFlushTextureCache() {
        Gpu gpu = new Gpu(new InterruptController());
        int clutBits = 200 << 6;
        upload(gpu, 0, 0, 1, 1, 0x0000_0001);
        upload(gpu, 0, 200, 3, 1, 0x001F_0000, 0x0000_03E0);
        gpu.gp0(0xE100_0000);
        drawRawTexturedPixel(gpu, 200, 120, 0, 0, clutBits);

        upload(gpu, 0, 0, 1, 1, 0x0000_0002);
        gpu.gp0(0xE100_0001); // Select another page without sampling it.
        gpu.gp0(0xE100_0000);
        drawRawTexturedPixel(gpu, 201, 120, 0, 0, clutBits);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[120 * 1024 + 200] & 0xFFFF);
        assertEquals(0x001F, vram[120 * 1024 + 201] & 0xFFFF,
            "only GP0(01h), not a texpage change, invalidates cached texture data");
    }

    @Test
    void queuedImageWordsAreDrainedInFifoOrderWhenUploadStarts() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xE400_0000 | 1023 | (511 << 10));

        gpu.gp0(0x6000_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x00F0_0100);
        gpu.gp0(0xA000_0000);
        gpu.gp0(0x0028_0028);
        gpu.gp0(0x0001_0002);
        gpu.gp0(0x03E0_001F);

        finishGpuWork(gpu);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[40 * 1024 + 40] & 0xFFFF);
        assertEquals(0x03E0, vram[40 * 1024 + 41] & 0xFFFF);
        assertEquals(1 << 26, gpu.status() & (1 << 26),
            "pixel payload must not remain behind and become a GP0 command");
    }

    @Test
    void transferOpcodesIgnoreTheirLowFiveBits() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0xBF00_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0002);
        gpu.gp0(0x2222_1111);

        gpu.gp0(0x9F00_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0000_0002);
        gpu.gp0(0x0001_0002);
        finishGpuWork(gpu);

        short[] vram = gpu.copyVram();
        assertEquals(0x1111, vram[0] & 0xFFFF);
        assertEquals(0x2222, vram[1] & 0xFFFF);
        assertEquals(0x1111, vram[2] & 0xFFFF);
        assertEquals(0x2222, vram[3] & 0xFFFF);
    }

    @Test
    void v2UpperVramAddressSelectsUnpopulatedBankOnRetailPu18() {
        Gpu gpu = new Gpu(new InterruptController());
        upload(gpu, 0, 0, 1, 1, 0x0000_001F);

        gpu.gp1(0x0900_0001);
        upload(gpu, 0, 512, 1, 1, 0x0000_03E0);
        gpu.gp0(0xC000_0000);
        gpu.gp0(512 << 16);
        gpu.gp0(0x0001_0001);

        assertEquals(0, gpu.gpuread(),
            "PU-18 has no SGRAM on the v2 GPU's upper chip select");
        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
    }

    @Test
    void disabledV2UpperAddressBitMirrorsTransferCoordinatesIntoLowerBank() {
        Gpu gpu = new Gpu(new InterruptController());

        upload(gpu, 0, 512, 1, 1, 0x0000_03E0);

        assertEquals(0x03E0, gpu.copyVram()[0] & 0xFFFF);
    }

    @Test
    void rectangleCoordinatesAreTruncatedToElevenBitsAfterDrawingOffset() {
        Gpu gpu = new Gpu(new InterruptController());
        setFullDrawingArea(gpu);
        gpu.gp0(0xE500_0400); // drawing offset x=-1024, y=0

        gpu.gp0(0x6800_00FF); // opaque, untextured 1x1 rectangle
        gpu.gp0(0x0000_07FF); // x=-1; x+offset=-1025 wraps to +1023
        finishGpuWork(gpu);

        assertEquals(0x001F, gpu.copyVram()[1023] & 0xFFFF);
    }

    @Test
    void zeroSizedVariableRectangleDoesNotDraw() {
        Gpu gpu = new Gpu(new InterruptController());

        gpu.gp0(0x6000_00FF);
        gpu.gp0((10 << 16) | 10);
        gpu.gp0(0x0000_0000);
        finishGpuWork(gpu);

        assertEquals(0, gpu.copyVram()[10 * 1024 + 10] & 0xFFFF);
    }

    @Test
    void variableRectangleMasksWidthAndHeightToHardwareBitWidths() {
        Gpu gpu = new Gpu(new InterruptController());
        setFullDrawingArea(gpu);

        gpu.gp0(0x6000_00FF);
        gpu.gp0((20 << 16) | 20);
        gpu.gp0(0x0201_0401); // hardware sees 1x1 (10-bit width, 9-bit height)
        finishGpuWork(gpu);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[20 * 1024 + 20] & 0xFFFF);
        assertEquals(0, vram[20 * 1024 + 21] & 0xFFFF);
        assertEquals(0, vram[21 * 1024 + 20] & 0xFFFF);
    }

    @Test
    void gouraudPolylineOnlyRecognizesTerminatorInColorWordPositions() {
        Gpu gpu = new Gpu(new InterruptController());
        setFullDrawingArea(gpu);

        gpu.gp0(0x5800_00FF);
        gpu.gp0((10 << 16) | 10);
        gpu.gp0(0x0000_FF00);
        gpu.gp0(0x5010_5010); // valid XY word which resembles a terminator
        gpu.gp0(0x00FF_0000);
        gpu.gp0((20 << 16) | 20);
        gpu.gp0(0x5000_5000);

        gpu.gp0(0x6800_00FF);
        gpu.gp0((30 << 16) | 30);
        finishGpuWork(gpu);

        assertEquals(0x001F, gpu.copyVram()[30 * 1024 + 30] & 0xFFFF);
    }

    @Test
    void halfBlendRoundsEachFiveBitChannelBeforeAddition() {
        Gpu gpu = new Gpu(new InterruptController());
        setFullDrawingArea(gpu);
        upload(gpu, 40, 40, 1, 1, 0x0000_0421);

        gpu.gp0(0x6A08_0808); // semi-transparent 1x1, source RGB555=(1,1,1)
        gpu.gp0((40 << 16) | 40);
        finishGpuWork(gpu);

        assertEquals(0, gpu.copyVram()[40 * 1024 + 40] & 0x7FFF);
    }

    private static void drawRawTexturedPixel(
        Gpu gpu, int x, int y, int u, int v, int clutBits
    ) {
        setFullDrawingArea(gpu);
        gpu.gp0(0x6500_0000);
        gpu.gp0((y << 16) | x);
        gpu.gp0((clutBits << 16) | (v << 8) | u);
        gpu.gp0(0x0001_0001);
        finishGpuWork(gpu);
    }

    private static void upload(Gpu gpu, int x, int y, int width, int height, int... data) {
        gpu.gp0(0xA000_0000);
        gpu.gp0((y << 16) | x);
        gpu.gp0((height << 16) | width);
        for (int word : data) {
            gpu.gp0(word);
        }
    }

    private static void setFullDrawingArea(Gpu gpu) {
        gpu.gp0(0xE300_0000);
        gpu.gp0(0xE407_FFFF);
    }

    @Test
    void gp0InterruptCommandSetsAndClearsIrqState() {
        InterruptController interrupts = new InterruptController();
        Gpu gpu = new Gpu(interrupts);

        gpu.gp0(0x1F00_0000);

        assertEquals(1 << 24, gpu.status() & (1 << 24));
        assertEquals(1 << 1, interrupts.status() & (1 << 1));

        gpu.gp1(0x0200_0000);

        assertEquals(0, gpu.status() & (1 << 24));
        interrupts.writeStatus(~(1 << 1));
        assertEquals(0, interrupts.status() & (1 << 1));

        gpu.gp0(0x1F00_0000);

        assertEquals(1 << 1, interrupts.status() & (1 << 1));
    }

    @Test
    void vramReadbackTracksProgressiveRasterAndReadCommandWaitsForCompletion() {
        Gpu gpu = new Gpu(new InterruptController());

        gpu.gp0(0x0200_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0001); // 16x4 fill: 35 CPU cycles with this profile.
        gpu.gp0(0xC000_0000);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0001);

        assertEquals(0, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0, gpu.status() & (1 << 27));

        gpu.tick(30);
        assertEquals(0, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0, gpu.status() & (1 << 27));

        gpu.tick(1);
        short[] partial = gpu.copyVram();
        assertEquals(0x001F, partial[0] & 0xFFFF);
        assertEquals(0, partial[(3 * 1024) + 15] & 0xFFFF);
        assertEquals(0, gpu.status() & (1 << 27));

        gpu.tick(4);
        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x001F, gpu.copyVram()[(3 * 1024) + 15] & 0xFFFF);
        assertEquals(1 << 27, gpu.status() & (1 << 27));
        assertEquals(0x001F, gpu.gpuread() & 0xFFFF);
    }

    @Test
    void snapshotRestoresProgressiveRasterPrefixAndItsCompletionTime() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp0(0x0200_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0001);

        gpu.tick(31);
        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0, gpu.copyVram()[(3 * 1024) + 15] & 0xFFFF);

        Gpu.State state = gpu.copyState();
        short[] partial = gpu.copyVram();

        gpu.tick(4);
        short[] completed = gpu.copyVram();
        assertEquals(0x001F, completed[(3 * 1024) + 15] & 0xFFFF);

        gpu.gp1(0x0000_0000);
        gpu.loadVram(partial);
        gpu.loadState(state);
        gpu.tick(3);
        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0, gpu.copyVram()[(3 * 1024) + 15] & 0xFFFF);
        gpu.tick(1);
        assertArrayEquals(completed, gpu.copyVram());
    }

    @Test
    void vramCopyCommitsItsOrderedWritesAcrossBusyInterval() {
        Gpu gpu = new Gpu(new InterruptController());

        gpu.gp0(0xA000_0000);
        gpu.gp0(0x0000_0010); // source at (16, 0)
        gpu.gp0(0x0001_0004); // 4x1
        gpu.gp0(0x2222_1111);
        gpu.gp0(0x4444_3333);

        gpu.gp0(0x8000_0000);
        gpu.gp0(0x0000_0010);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0004);

        short[] before = gpu.copyVram();
        assertEquals(0, before[0] & 0xFFFF);
        assertEquals(0, before[3] & 0xFFFF);

        gpu.tick(3);
        short[] partial = gpu.copyVram();
        assertEquals(0x1111, partial[0] & 0xFFFF);
        assertEquals(0x2222, partial[1] & 0xFFFF);
        assertEquals(0, partial[2] & 0xFFFF);
        assertEquals(0, partial[3] & 0xFFFF);

        gpu.tick(3);
        short[] completed = gpu.copyVram();
        assertEquals(0x1111, completed[0] & 0xFFFF);
        assertEquals(0x2222, completed[1] & 0xFFFF);
        assertEquals(0x3333, completed[2] & 0xFFFF);
        assertEquals(0x4444, completed[3] & 0xFFFF);
    }

    @Test
    void dmaRequestStaysAssertedWhileGp0ParametersArePending() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        assertTrue(gpu.dmaRequest());

        gpu.gp0(0x0200_00FF);

        assertEquals(0, gpu.status() & (1 << 26));
        assertTrue(gpu.dmaRequest());
    }

    @Test
    void completedCpuToVramUploadReleasesGp0FifoForNextDmaPacket() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        gpu.gp0(0xA000_0000);
        gpu.gp0(0);
        gpu.gp0(0x0001_0020); // 32x1 pixels: exactly 16 data words.
        for (int i = 0; i < 16; i++) {
            gpu.gp0(0x1234_1234);
        }

        assertTrue(gpu.dmaRequest(),
            "the upload engine has consumed every word, so GP0 FIFO must be empty");

        gpu.gp0(0x0200_00FF);
        gpu.gp0(0);
        gpu.gp0(0x0001_0001);
        finishGpuWork(gpu);
        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
    }

    @Test
    void renderCommandDropsDmaRequestUntilBusyCyclesDrain() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        gpu.gp0(0x0200_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0001);
        for (int i = 0; i < 16; i++) {
            gpu.gp0(0x0300_0000);
        }

        assertEquals(0, gpu.status() & (1 << 25));
        assertEquals(0, gpu.status() & (1 << 26));
        assertEquals(0, gpu.status() & (1 << 28));

        gpu.gp0(0x1F00_0000);

        gpu.tick(34);
        assertEquals(0, gpu.status() & (1 << 25));
        gpu.tick(1);

        assertEquals(1 << 25, gpu.status() & (1 << 25));
        assertEquals(1 << 26, gpu.status() & (1 << 26));
        assertEquals(1 << 28, gpu.status() & (1 << 28));
        assertEquals(0, gpu.status() & (1 << 24));
    }

    @Test
    void activeRendererRequestsTheNextDmaBlockWhenItsFifoIsEmpty() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        gpu.gp0(0x0200_00FF);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0004_0001);

        assertEquals(0, gpu.status() & (1 << 26));
        assertEquals(1 << 28, gpu.status() & (1 << 28));
        assertTrue(gpu.dmaRequest());
    }

    @Test
    void clippedTexturedGouraudQuadStillPaysHardwareSetupTime() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        int outside = 0x0400_0400; // (-1024, -1024), outside the draw area.
        gpu.gp0(0x3C80_8080);
        gpu.gp0(outside);
        gpu.gp0(0);
        gpu.gp0(0x0080_8080);
        gpu.gp0(outside);
        gpu.gp0(0);
        gpu.gp0(0x0080_8080);
        gpu.gp0(outside);
        gpu.gp0(0);
        gpu.gp0(0x0080_8080);
        gpu.gp0(outside);
        gpu.gp0(0);
        for (int i = 0; i < 16; i++) {
            gpu.gp0(0x0300_0000);
        }

        gpu.tick(335);
        assertEquals(0, gpu.status() & (1 << 25));
        gpu.tick(1);

        assertEquals(1 << 25, gpu.status() & (1 << 25));
    }

    @Test
    void nopAndDrawingAreaCommandsDoNotOccupyGp0Fifo() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0001);

        for (int i = 0; i < 64; i++) {
            gpu.gp0(0x0000_0000);
            gpu.gp0(0xE300_0000 | i);
            gpu.gp0(0xE400_0000 | i);
            gpu.gp0(0xE500_0000 | i);
        }

        assertEquals(1 << 25, gpu.status() & (1 << 25));
    }

    @Test
    void fifoFreeCommandsBypassFifoWhileRasterizerIsBusy() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0001); // DMA request reflects whether the FIFO is full.
        gpu.gp0(0x0200_00FF);
        gpu.gp0(0);
        gpu.gp0(0x01FF_0400); // long 1024x511 fast fill

        assertEquals(1 << 25, gpu.status() & (1 << 25));
        for (int i = 0; i < 64; i++) {
            gpu.gp0(0x0000_0000);
            gpu.gp0(0xE300_0000 | i);
            gpu.gp0(0xE400_0000 | i);
            gpu.gp0(0xE500_0000 | i);
        }

        assertEquals(1 << 25, gpu.status() & (1 << 25));
    }

    private static void finishGpuWork(Gpu gpu) {
        for (int cycles = 0; cycles < (1 << 24); cycles++) {
            if ((gpu.status() & (1 << 26)) != 0) {
                return;
            }
            gpu.tick(1);
        }
        throw new AssertionError("GPU command engine did not become ready");
    }

}
