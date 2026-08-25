package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class GpuTexturedPrimitiveTest {

    @Test
    void texturedRectangleSamplesTexelFromVram() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x00000000);
        gpu.gp0(0xA0000000);
        gpu.gp0(0x00000000);
        gpu.gp0(0x00010001);
        gpu.gp0(0x001F001F);
        gpu.gp0(0x64FFFFFF);
        gpu.gp0(0x00000000);
        gpu.gp0(0x00000000);
        gpu.gp0(0x00010001);
        finishGpuWork(gpu);

        int pixel = gpu.captureFrame().pixels()[0];
        assertNotEquals(0xFF000000, pixel);
    }

    @Test
    void texturedPolygonTexpageUpdatesDrawModeForFollowingRectangle() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x00000000);
        setFullDrawingArea(gpu);
        uploadPixel(gpu, 64, 0, 0x001F);

        emitRawTexturedTriangleWithTexpage(gpu, 0x0101);
        gpu.gp0(0x65FF_FFFF);
        gpu.gp0(0x000A_000A);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0001_0001);
        finishGpuWork(gpu);

        assertEquals(0x0101, gpu.drawMode() & 0x09FF);
        assertEquals(0x001F, gpu.copyVram()[(10 * 1024) + 10] & 0x7FFF);
    }

    @Test
    void texturedPolygonTexpagePreservesDrawModeBitsOutsideTexpageAttribute() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x00000000);
        gpu.gp0(0xE100_3600);

        emitRawTexturedTriangleWithTexpage(gpu, 0x0101);
        finishGpuWork(gpu);

        assertEquals(0x3701, gpu.drawMode() & 0x3FFF);
    }

    @Test
    void texturedQuadIncludesTopLeftAndExcludesBottomRightEdges() {
        Gpu gpu = new Gpu(new InterruptController());
        gpu.gp1(0x00000000);
        setFullDrawingArea(gpu);
        upload(
            gpu, 0, 0, 3, 3,
            0x03E0_001F,
            0x7C00_4210,
            0x4210_7FFF,
            0x4210_4210,
            0x0000_4210
        );

        int texpage = 2 << 7; // 16-bit direct-color texture at VRAM (0, 0)
        gpu.gp0(0x2DFF_FFFF); // opaque raw-textured quad
        gpu.gp0((100 << 16) | 100);
        gpu.gp0(0x0000_0000);
        gpu.gp0((100 << 16) | 102);
        gpu.gp0((texpage << 16) | 2);
        gpu.gp0((102 << 16) | 100);
        gpu.gp0(2 << 8);
        gpu.gp0((102 << 16) | 102);
        gpu.gp0((2 << 8) | 2);
        finishGpuWork(gpu);

        short[] vram = gpu.copyVram();
        assertEquals(0x001F, vram[100 * 1024 + 100] & 0xFFFF);
        assertEquals(0x03E0, vram[100 * 1024 + 101] & 0xFFFF);
        assertEquals(0x7C00, vram[101 * 1024 + 100] & 0xFFFF);
        assertEquals(0x7FFF, vram[101 * 1024 + 101] & 0xFFFF);
        assertEquals(0, vram[100 * 1024 + 102] & 0xFFFF);
        assertEquals(0, vram[102 * 1024 + 100] & 0xFFFF);
    }

    private static void uploadPixel(Gpu gpu, int x, int y, int color) {
        gpu.gp0(0xA000_0000);
        gpu.gp0((y << 16) | x);
        gpu.gp0(0x0001_0001);
        gpu.gp0(color & 0xFFFF);
    }

    private static void setFullDrawingArea(Gpu gpu) {
        gpu.gp0(0xE300_0000);
        gpu.gp0(0xE407_FFFF);
    }

    private static void emitRawTexturedTriangleWithTexpage(Gpu gpu, int texpage) {
        gpu.gp0(0x25FF_FFFF);
        gpu.gp0(0x0040_0040);
        gpu.gp0(0x0000_0000);
        gpu.gp0(0x0040_0041);
        gpu.gp0((texpage << 16) & 0xFFFF_0000);
        gpu.gp0(0x0041_0040);
        gpu.gp0(0x0000_0000);
    }

    private static void upload(Gpu gpu, int x, int y, int width, int height, int... data) {
        gpu.gp0(0xA000_0000);
        gpu.gp0((y << 16) | x);
        gpu.gp0((height << 16) | width);
        for (int word : data) {
            gpu.gp0(word);
        }
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
