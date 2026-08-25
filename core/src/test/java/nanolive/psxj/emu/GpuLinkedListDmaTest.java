package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GpuLinkedListDmaTest {

    @Test
    void shouldExecuteGpuLinkedList() {
        Bus bus = new Bus();
        Gpu gpu = new Gpu(new InterruptController());

        int base = 0x1000;
        bus.write32(base, 0x0380_0000);
        bus.write32(base + 4, 0x0200_FF00);
        bus.write32(base + 8, 0x0000_0000);
        bus.write32(base + 12, 0x0004_0004);

        gpu.dmaLinkedList(bus, base);
        while ((gpu.status() & (1 << 26)) == 0) {
            gpu.tick(1);
        }
        assertNotEquals(0xFF00_0000, gpu.captureFrame().pixels()[0]);
    }
}
