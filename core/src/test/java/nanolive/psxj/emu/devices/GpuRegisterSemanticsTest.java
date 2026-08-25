package nanolive.psxj.emu.devices;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GpuRegisterSemanticsTest {

    private static final int GPUSTAT_DMA_BLOCK_READY = 1 << 28;

    @Test
    void gp1ResetClearsGpuIrqAndDrawingAreaButKeepsGp1Nine() {
        InterruptController interrupts = new InterruptController();
        Gpu gpu = new Gpu(interrupts);
        gpu.gp0(0xE300_0007);
        gpu.gp0(0xE400_1409);
        gpu.gp0(0x1F00_0000);
        gpu.gp1(0x0900_0001);

        assertEquals(1 << 24, gpu.status() & (1 << 24));
        assertEquals(1 << 1, interrupts.status() & (1 << 1));

        gpu.gp1(0x0000_0000);

        assertEquals(0, gpu.status() & (1 << 24));
        gpu.gp1(0x1000_0003);
        assertEquals(0, gpu.gpuread());
        gpu.gp1(0x1000_0004);
        assertEquals(0, gpu.gpuread());
        assertTrue(gpu.copyState().allowSecondVramBank);

        interrupts.writeStatus(~(1 << 1));
        assertEquals(0, interrupts.status() & (1 << 1));
        gpu.gp0(0x1F00_0000);
        assertEquals(1 << 1, interrupts.status() & (1 << 1));
    }

    @Test
    void gp1CommandsFortyThroughFfMirrorZeroThroughThreeF() {
        Gpu gpu = new Gpu(new InterruptController());

        for (int resetOpcode : new int[] {0x40, 0x80, 0xC0}) {
            gpu.gp0(0xE400_1409);
            gpu.gp0(0x1F00_0000);
            gpu.gp1(resetOpcode << 24);
            gpu.gp1(0x1000_0004);
            assertEquals(0, gpu.gpuread());
            assertEquals(0, gpu.status() & (1 << 24));
        }

        for (int infoOpcode : new int[] {0x50, 0x90, 0xD0}) {
            gpu.gp1((infoOpcode << 24) | 0x7);
            assertEquals(2, gpu.gpuread());
        }
    }

    @Test
    void versionTwoGpuIgnoresVersionOneVramSizeCommand() {
        Gpu gpu = new Gpu(new InterruptController());

        gpu.gp1(0x2000_0001);
        assertFalse(gpu.copyState().allowSecondVramBank);

        gpu.gp1(0x0900_0001);
        assertTrue(gpu.copyState().allowSecondVramBank);
    }

    @Test
    void polygonCommandDropsDmaBlockRequestBeforeVerticesArrive() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        assertTrue((gpu.status() & GPUSTAT_DMA_BLOCK_READY) != 0);
        gpu.gp0(0x2000_0000);

        assertEquals(0, gpu.status() & GPUSTAT_DMA_BLOCK_READY);
    }

    @Test
    void cpuToVramPacketKeepsDmaRequestForFollowingPixelData() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x0400_0002);

        gpu.gp0(0xA000_0000);
        assertTrue((gpu.status() & GPUSTAT_DMA_BLOCK_READY) != 0);
        gpu.gp0(0);
        assertTrue((gpu.status() & GPUSTAT_DMA_BLOCK_READY) != 0);
        gpu.gp0(0x0001_0002);

        assertTrue((gpu.status() & GPUSTAT_DMA_BLOCK_READY) != 0);
    }
}
