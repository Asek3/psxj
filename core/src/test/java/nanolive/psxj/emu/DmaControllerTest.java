package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.dma.DmaPort;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmaControllerTest {

    @Test
    void shouldAcknowledgeDmaDicrFlagWithoutClearingIStat() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        bus.setInterruptController(interrupts);
        bus.setDma(dma);

        dma.write32(0x1F80_10F0, 0x0F65_4321);
        dma.write32(0x1F80_10F4, 0x00C0_0000);
        dma.write32(0x1F80_10E0, 0x0000_1000);
        dma.write32(0x1F80_10E4, 0x0000_0004);
        dma.write32(0x1F80_10E8, 0x1100_0002);
        dma.tick(64);

        assertEquals(0x8, interrupts.status() & 0x8);
        assertEquals(0xC0C0_0000, dma.read32(0x1F80_10F4));

        dma.write32(0x1F80_10F4, 0x40C0_0000);

        assertEquals(0x8, interrupts.status() & 0x8);
        assertEquals(0x00C0_0000, dma.read32(0x1F80_10F4));
    }

    @Test
    void lowerPriorityValueRunsBeforeHigherPriorityValue() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        List<Integer> order = new ArrayList<>();

        dma.setBus(bus);
        dma.attachPort(0, new LoggingPort(0, order));
        dma.attachPort(1, new LoggingPort(1, order));
        bus.write32(0x0000_1000, 0xAAAA_AAAA);
        bus.write32(0x0000_2000, 0xBBBB_BBBB);

        dma.write32(0x1F80_10F0, 0x0000_0098);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0001);
        dma.write32(0x1F80_1088, 0x1100_0001);
        dma.write32(0x1F80_1090, 0x0000_2000);
        dma.write32(0x1F80_1094, 0x0000_0001);
        dma.write32(0x1F80_1098, 0x1100_0001);

        dma.tick(64);

        assertEquals(List.of(0, 1), order);
    }

    @Test
    void stableHigherPriorityDreqAllowsBatchingToTheNextWordBoundary() {
        DmaController dma = new DmaController(new InterruptController());
        Bus bus = new Bus();
        dma.setBus(bus);
        dma.attachPort(0, new StableRequestPort(true, true));
        dma.attachPort(1, new StableRequestPort(false, true));
        bus.write32(0x1000, 0x1234_5678);

        dma.write32(0x1F80_10F0, 0x0000_0089);
        dma.write32(0x1F80_1080, 0x1000);
        dma.write32(0x1F80_1084, 0x0001_0001);
        dma.write32(0x1F80_1088, 0x0100_0201);
        dma.write32(0x1F80_1090, 0x2000);
        dma.write32(0x1F80_1094, 0x0001_0001);
        dma.write32(0x1F80_1098, 0x0100_0201);

        assertEquals(2, dma.cyclesUntilNextArbitrationBoundary(32));
    }

    @Test
    void unstableHigherPriorityDreqKeepsExactClockArbitration() {
        DmaController dma = new DmaController(new InterruptController());
        Bus bus = new Bus();
        dma.setBus(bus);
        dma.attachPort(0, new StableRequestPort(true, true));
        dma.attachPort(1, new StableRequestPort(false, false));
        bus.write32(0x1000, 0x1234_5678);

        dma.write32(0x1F80_10F0, 0x0000_0089);
        dma.write32(0x1F80_1080, 0x1000);
        dma.write32(0x1F80_1084, 0x0001_0001);
        dma.write32(0x1F80_1088, 0x0100_0201);
        dma.write32(0x1F80_1090, 0x2000);
        dma.write32(0x1F80_1094, 0x0001_0001);
        dma.write32(0x1F80_1098, 0x0100_0201);

        assertEquals(1, dma.cyclesUntilNextArbitrationBoundary(32));
    }

    @Test
    void outOfRamDmaAddressRaisesBusErrorAndForcesIrq() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();

        dma.setBus(bus);
        dma.attachPort(6, new DmaPort() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public void write(int value) {
            }
        });

        dma.write32(0x1F80_10F0, 0x0800_0000);
        dma.write32(0x1F80_10F4, 0x00C0_0000);
        dma.write32(0x1F80_10E0, 0x0080_0000);
        dma.write32(0x1F80_10E4, 0x0000_0002);
        dma.write32(0x1F80_10E8, 0x1100_0002);

        dma.tick(64);

        assertEquals(1 << 15, dma.read32(0x1F80_10F4) & (1 << 15));
        assertEquals(1 << 31, dma.read32(0x1F80_10F4) & (1 << 31));
        assertEquals(0, dma.read32(0x1F80_10E8) & (1 << 24));
        assertEquals(0x8, interrupts.status() & 0x8);
    }

    @Test
    void syncMode1TransfersOneBlockPerRequestAndInterruptsOnlyAtEnd() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        bus.setInterruptController(interrupts);
        bus.setDma(dma);
        RequestingLoggingPort port = new RequestingLoggingPort();

        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        bus.write32(0x0000_1004, 0x2222_2222);
        bus.write32(0x0000_1008, 0x3333_3333);
        bus.write32(0x0000_100C, 0x4444_4444);
        bus.write32(0x0000_1010, 0x5555_5555);
        bus.write32(0x0000_1014, 0x6666_6666);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_10F4, 0x0081_0000);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0003_0002);
        dma.write32(0x1F80_1088, 0x0100_0201);

        dma.tick(32);

        assertEquals(List.of(0x1111_1111, 0x2222_2222), port.writes);
        assertEquals(0x0002_0002, dma.read32(0x1F80_1084));
        assertEquals(0, dma.read32(0x1F80_10F4) & (1 << 24));
        assertEquals(0, interrupts.status() & 0x8);
        assertEquals(1 << 24, dma.read32(0x1F80_1088) & (1 << 24));

        dma.tick(32);
        assertEquals(List.of(0x1111_1111, 0x2222_2222, 0x3333_3333, 0x4444_4444), port.writes);
        assertEquals(0x0001_0002, dma.read32(0x1F80_1084));
        assertEquals(0, dma.read32(0x1F80_10F4) & (1 << 24));

        dma.tick(32);
        assertEquals(List.of(0x1111_1111, 0x2222_2222, 0x3333_3333, 0x4444_4444,
            0x5555_5555, 0x6666_6666), port.writes);
        assertEquals(1 << 24, dma.read32(0x1F80_10F4) & (1 << 24));
        assertEquals(0x8, interrupts.status() & 0x8);
        assertEquals(0, dma.read32(0x1F80_1088) & (1 << 24));
    }

    @Test
    void syncMode1FinishesCurrentBlockWhenDeviceDropsDreq() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        MidBlockRequestPort port = new MidBlockRequestPort(2);

        dma.setBus(bus);
        dma.attachPort(3, port);
        dma.write32(0x1F80_10F0, 0x0000_8000);
        dma.write32(0x1F80_10B0, 0x0000_1000);
        dma.write32(0x1F80_10B4, 0x0002_0004);
        dma.write32(0x1F80_10B8, 0x0100_0200);

        dma.tick(256);

        assertEquals(4, port.reads);
        assertEquals(0x1111_0000, bus.read32(0x1000));
        assertEquals(0x1111_0001, bus.read32(0x1004));
        assertEquals(0x1111_0002, bus.read32(0x1008));
        assertEquals(0x1111_0003, bus.read32(0x100C));
        assertEquals(0x1010, dma.read32(0x1F80_10B0));
        assertEquals(0x0001_0004, dma.read32(0x1F80_10B4));
        assertEquals(1 << 24, dma.read32(0x1F80_10B8) & (1 << 24));

        port.request = true;
        dma.tick(256);

        assertEquals(8, port.reads);
        assertEquals(0x1111_0004, bus.read32(0x1010));
        assertEquals(0x1111_0007, bus.read32(0x101C));
        assertEquals(0, dma.read32(0x1F80_10B8) & (1 << 24));
    }

    @Test
    void syncMode0CanStartFromDeviceRequestWithoutManualTrigger() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        dma.setBus(bus);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0001);
        dma.write32(0x1F80_1088, 0x0100_0001);

        dma.tick(32);
        assertEquals(List.of(0x1111_1111), port.writes);
        assertEquals(0, dma.read32(0x1F80_1088) & (1 << 24));
    }

    @Test
    void syncMode1ZeroBlockAmountMeans65536Blocks() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setInterruptController(interrupts);
        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_10F4, 0x0081_0000);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0001);
        dma.write32(0x1F80_1088, 0x0100_0201);

        dma.tick(32);

        assertEquals(List.of(0x1111_1111), port.writes);
        assertEquals(0xFFFF_0001, dma.read32(0x1F80_1084));
        assertEquals(1 << 24, dma.read32(0x1F80_1088) & (1 << 24));
        assertEquals(0, dma.read32(0x1F80_10F4) & (1 << 24));
        assertEquals(0, interrupts.status() & 0x8);
    }

    @Test
    void dicrMasterFlagDependsOnLatchedFlagsNotCurrentChannelMask() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setInterruptController(interrupts);
        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_10F4, 0x0081_0000);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0001);
        dma.write32(0x1F80_1088, 0x1100_0001);
        dma.tick(32);

        assertEquals(0x8100_0000, dma.read32(0x1F80_10F4) & 0x8100_0000);

        dma.write32(0x1F80_10F4, 0x0080_0000);

        assertEquals(1 << 24, dma.read32(0x1F80_10F4) & (1 << 24));
        assertEquals(1 << 31, dma.read32(0x1F80_10F4) & (1 << 31));
    }

    @Test
    void choppingYieldsCpuWindowBetweenDmaSlices() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        bus.write32(0x0000_1004, 0x2222_2222);
        bus.write32(0x0000_1008, 0x3333_3333);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0003);
        dma.write32(0x1F80_1088, (1 << 28) | (1 << 24) | (1 << 20) | (1 << 8) | 0x1);

        dma.tick(2);
        assertEquals(List.of(0x1111_1111), port.writes);

        for (int i = 0; i < 8; i++) {
            dma.tick(1);
        }
        assertEquals(List.of(0x1111_1111), port.writes);

        dma.tick(1);
        assertEquals(List.of(0x1111_1111, 0x2222_2222), port.writes);
    }

    @Test
    void choppingWindowIsNotRestartedByFragmentedSchedulerTicks() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setDma(dma);
        dma.attachPort(0, port);
        for (int i = 0; i < 5; i++) {
            bus.write32(0x0000_1000 + i * 4, i + 1);
        }

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0005);
        dma.write32(0x1F80_1088,
            (1 << 28) | (1 << 24) | (2 << 16) | (1 << 8) | 0x1);

        dma.tick(1);
        dma.tick(1);
        dma.tick(1);
        dma.tick(1);
        assertEquals(List.of(1, 2, 3), port.writes);

        dma.tick(1);
        assertEquals(List.of(1, 2, 3, 4), port.writes);
        dma.tick(7);
        assertEquals(List.of(1, 2, 3, 4), port.writes);

        dma.tick(1);
        assertEquals(List.of(1, 2, 3, 4, 5), port.writes);
    }

    @Test
    void gpuRequestModeWaitsTenCyclesBetweenBlocks() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        dma.setBus(bus);
        dma.attachPort(2, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        bus.write32(0x0000_1004, 0x2222_2222);
        dma.write32(0x1F80_10F0, 0x0000_0800);
        dma.write32(0x1F80_10A0, 0x0000_1000);
        dma.write32(0x1F80_10A4, 0x0002_0001);
        dma.write32(0x1F80_10A8, 0x0100_0201);

        dma.tick(2);
        assertEquals(List.of(0x1111_1111), port.writes);

        dma.tick(10);
        assertEquals(List.of(0x1111_1111), port.writes);

        dma.tick(1);
        assertEquals(List.of(0x1111_1111, 0x2222_2222), port.writes);
    }

    @Test
    void gpuLinkedListDmaWaitsForGpuRequestBetweenNodes() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        Gpu gpu = new Gpu(interrupts);

        dma.setBus(bus);
        dma.setGpu(gpu);
        gpu.gp1(0x0400_0002);

        int first = 0x0000_1000;
        int second = 0x0000_2000;
        int third = 0x0000_3000;
        bus.write32(first, 0x0300_2000);
        bus.write32(first + 4, 0x0200_00FF);
        bus.write32(first + 8, 0x0000_0000);
        bus.write32(first + 12, 0x0004_0001);
        bus.write32(second, 0x1000_3000);
        for (int i = 0; i < 16; i++) {
            bus.write32(second + 4 + i * 4, 0);
        }
        bus.write32(third, 0x0380_0000);
        bus.write32(third + 4, 0x0200_FF00);
        bus.write32(third + 8, 0x0000_0010);
        bus.write32(third + 12, 0x0004_0001);

        dma.write32(0x1F80_10F0, 0x0000_0800);
        dma.write32(0x1F80_10A0, first);
        dma.write32(0x1F80_10A4, 0);
        dma.write32(0x1F80_10A8, 0x0100_0401);

        dma.tick(64);
        assertEquals(0x0000, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x0000, gpu.copyVram()[16] & 0xFFFF);
        assertTrue((dma.read32(0x1F80_10A8) & (1 << 24)) != 0);

        dma.tick(64);
        assertEquals(0x0000, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x0000, gpu.copyVram()[16] & 0xFFFF);

        runGpuDmaUntilIdle(dma, gpu);

        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x03E0, gpu.copyVram()[16] & 0xFFFF);
    }

    @Test
    void gpuLinkedListDmaStallsAndResumesInsideAnEntry() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        Gpu gpu = new Gpu(interrupts);

        dma.setBus(bus);
        dma.setGpu(gpu);
        gpu.gp1(0x0400_0002);

        int list = 0x0000_1000;
        bus.write32(list, 0x1580_0000);
        int cursor = list + 4;
        for (int i = 0; i < 7; i++) {
            bus.write32(cursor, (i & 1) == 0 ? 0x0200_00FF : 0x0200_FF00);
            bus.write32(cursor + 4, i * 16);
            bus.write32(cursor + 8, 0x0001_0001);
            cursor += 12;
        }

        dma.write32(0x1F80_10F0, 0x0000_0800);
        dma.write32(0x1F80_10A0, list);
        dma.write32(0x1F80_10A4, 0);
        dma.write32(0x1F80_10A8, 0x0100_0401);
        dma.tick(512);

        assertEquals(0x0000, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x0000, gpu.copyVram()[16] & 0xFFFF);
        assertTrue((dma.read32(0x1F80_10A8) & (1 << 24)) != 0);

        runGpuDmaUntilIdle(dma, gpu);

        assertEquals(0x001F, gpu.copyVram()[0] & 0xFFFF);
        assertEquals(0x03E0, gpu.copyVram()[16] & 0xFFFF);
        assertEquals(0, dma.read32(0x1F80_10A8) & (1 << 24));
    }

    @Test
    void gpuLinkedListFinishesPolygonAfterBlockRequestDrops() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        Gpu gpu = new Gpu(interrupts);

        dma.setBus(bus);
        dma.setGpu(gpu);
        gpu.gp1(0x0400_0002);
        gpu.gp0(0xE400_1004);

        int list = 0x0000_1000;
        bus.write32(list, 0x0580_0000);
        bus.write32(list + 4, 0x2800_00FF);
        bus.write32(list + 8, 0x0000_0000);
        bus.write32(list + 12, 0x0000_0004);
        bus.write32(list + 16, 0x0004_0000);
        bus.write32(list + 20, 0x0004_0004);

        dma.write32(0x1F80_10F0, 0x0000_0800);
        dma.write32(0x1F80_10A0, list);
        dma.write32(0x1F80_10A4, 0);
        dma.write32(0x1F80_10A8, 0x0100_0401);

        runGpuDmaUntilIdle(dma, gpu);

        assertEquals(0, dma.read32(0x1F80_10A8) & (1 << 24));
        assertEquals(0x001F, gpu.copyVram()[1024 + 1] & 0x7FFF);
    }

    @Test
    void gpuLinkedListKeepsOnePacketAcrossNodeBoundary() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        Gpu gpu = new Gpu(interrupts);

        dma.setBus(bus);
        dma.setGpu(gpu);
        gpu.gp1(0x0400_0002);

        int first = 0x0000_1000;
        int second = 0x0000_2000;
        int[] packet = {
            0x3CFF_FFFF, 0x0000_0000, 0x0000_0000,
            0x00FF_FFFF, 0x0000_0004, 0x0000_0000,
            0x00FF_FFFF, 0x0004_0000, 0x0000_0000,
            0x00FF_FFFF, 0x0004_0004, 0x0000_0000
        };
        bus.write32(first, (8 << 24) | second);
        for (int i = 0; i < 8; i++) bus.write32(first + 4 + i * 4, packet[i]);
        bus.write32(second, (4 << 24) | 0x00FF_FFFF);
        for (int i = 0; i < 4; i++) bus.write32(second + 4 + i * 4, packet[8 + i]);

        dma.write32(0x1F80_10F0, 0x0000_0800);
        dma.write32(0x1F80_10A0, first);
        dma.write32(0x1F80_10A4, 0);
        dma.write32(0x1F80_10A8, 0x0100_0401);

        runGpuDmaUntilIdle(dma, gpu);

        assertEquals(0, dma.read32(0x1F80_10A8) & (1 << 24));
        assertEquals(0, gpu.diagnostic().wordsRemaining());
    }

    @Test
    void emptyGpuLinkedListCompletesAtInitialEndMarker() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        Gpu gpu = new Gpu(interrupts);

        dma.setBus(bus);
        dma.setGpu(gpu);
        gpu.gp1(0x0400_0002);
        dma.write32(0x1F80_10F0, 0x0000_0800);
        dma.write32(0x1F80_10A0, 0x00FF_FFFF);
        dma.write32(0x1F80_10A4, 0);
        dma.write32(0x1F80_10A8, 0x0100_0401);
        dma.tick(1);

        assertEquals(0x00FF_FFFF, dma.read32(0x1F80_10A0));
        assertEquals(0, dma.read32(0x1F80_10A8) & (1 << 24));
        assertEquals(0, dma.read32(0x1F80_10F4) & (1 << 15));
    }

    @Test
    void cpuAccessPenaltyDropsDuringChoppingCpuWindow() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        Bus baseline = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        bus.write32(0x0000_1004, 0x2222_2222);
        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0002);
        dma.write32(0x1F80_1088, (1 << 28) | (1 << 24) | (1 << 20) | (1 << 8) | 0x1);

        int baselineCycles = baseline.cpuAccessCycles(0x0000_2000, false, 4);
        assertTrue(bus.cpuAccessCycles(0x0000_2000, false, 4) > baselineCycles);

        dma.tick(2);

        assertEquals(baselineCycles, bus.cpuAccessCycles(0x0000_2000, false, 4));
    }

    @Test
    void dmaDoesNotStallStoresWhileCpuWriteQueueHasRoom() {
        DmaController dma = new DmaController(new InterruptController());
        Bus bus = new Bus();
        DmaPort port = new DmaPort() {
            @Override
            public int read() {
                return 0x1234_5678;
            }

            @Override
            public void write(int value) {
            }
        };

        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0040);
        dma.write32(0x1F80_1088, 0x1100_0001);

        for (int i = 0; i < 4; i++) {
            assertEquals(0, bus.cpuAccessCycles(0x0000_2000 + i * 4, true, 4));
            bus.completeCpuWrite32(0x0000_2000 + i * 4, i + 1);
        }
        assertTrue(bus.cpuAccessCycles(0x0000_2010, true, 4) > 0);
    }

    @Test
    void cdromRecoveryTimingSelectsFortyCyclesPerWord() {
        DmaController dma = new DmaController(new InterruptController());
        Bus bus = new Bus();
        DmaPort port = new DmaPort() {
            @Override
            public int read() {
                return 0x1234_5678;
            }

            @Override
            public void write(int value) {
            }
        };

        bus.setDma(dma);
        dma.attachPort(3, port);
        bus.write32(0x1F80_1018, 0x0002_0943);
        bus.cpuAccessCycles(0x1F80_1800, false, 1);
        bus.cpuAccessCycles(0x1F80_1800, false, 1);

        dma.write32(0x1F80_10F0, 0x0000_8000);
        dma.write32(0x1F80_10B0, 0x0000_1000);
        dma.write32(0x1F80_10B4, 0x0000_0001);
        dma.write32(0x1F80_10B8, 0x1100_0000);

        dma.tick(39);
        assertEquals(0, bus.read32(0x0000_1000));
        dma.tick(2);
        assertEquals(0x1234_5678, bus.read32(0x0000_1000));
    }

    @Test
    void dmaRatesIncludeDocumentedDramHyperPageOverhead() {
        DmaController mdecDma = new DmaController(new InterruptController());
        Bus mdecBus = new Bus();
        RequestingLoggingPort mdecPort = new RequestingLoggingPort();
        mdecDma.setBus(mdecBus);
        mdecDma.attachPort(0, mdecPort);
        mdecDma.write32(0x1F80_10F0, 0x0000_0008);
        mdecDma.write32(0x1F80_1080, 0x0000_1000);
        mdecDma.write32(0x1F80_1084, 0x0000_0100);
        mdecDma.write32(0x1F80_1088, 0x1100_0001);

        mdecDma.tick(0x10F);
        assertEquals(255, mdecPort.writes.size());
        assertEquals(1 << 24, mdecDma.read32(0x1F80_1088) & (1 << 24));
        mdecDma.tick(1);
        assertEquals(256, mdecPort.writes.size());

        DmaController spuDma = new DmaController(new InterruptController());
        Bus spuBus = new Bus();
        RequestingLoggingPort spuPort = new RequestingLoggingPort();
        spuDma.setBus(spuBus);
        spuDma.attachPort(4, spuPort);
        spuDma.write32(0x1F80_10F0, 0x0008_0000);
        spuDma.write32(0x1F80_10C0, 0x0000_1000);
        spuDma.write32(0x1F80_10C4, 0x0000_0100);
        spuDma.write32(0x1F80_10C8, 0x1100_0001);

        spuDma.tick(0x41F);
        assertEquals(255, spuPort.writes.size());
        assertEquals(1 << 24, spuDma.read32(0x1F80_10C8) & (1 << 24));
        spuDma.tick(1);
        assertEquals(256, spuPort.writes.size());
    }

    @Test
    void snapshotRestoresPartiallyCompletedSyncModeTransfer() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        dma.setBus(bus);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        bus.write32(0x0000_1004, 0x2222_2222);
        bus.write32(0x0000_1008, 0x3333_3333);
        bus.write32(0x0000_100C, 0x4444_4444);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0002_0002);
        dma.write32(0x1F80_1088, 0x0100_0201);
        dma.tick(32);

        DmaController.State snapshot = dma.copyState();

        dma.tick(32);
        assertEquals(List.of(0x1111_1111, 0x2222_2222, 0x3333_3333, 0x4444_4444), port.writes);

        port.writes.clear();
        dma.loadState(snapshot);
        dma.tick(32);

        assertEquals(List.of(0x3333_3333, 0x4444_4444), port.writes);
        assertEquals(0, dma.read32(0x1F80_1088) & (1 << 24));
    }

    @Test
    void otcChannelMasksUnsupportedChcrBits() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);

        dma.write32(0x1F80_10E8, 0xFFFF_FFFF);

        assertEquals(0x5100_0002, dma.read32(0x1F80_10E8));
    }

    @Test
    void otcChannelChcrBitOneReadsHighFromReset() {
        DmaController dma = new DmaController(new InterruptController());

        assertEquals(0x0000_0002, dma.read32(0x1F80_10E8));
        assertEquals(0x0002, dma.read16(0x1F80_10E8));
        assertEquals(0x02, dma.read8(0x1F80_10E8));

        dma.write32(0x1F80_10E8, 0);
        assertEquals(0x0000_0002, dma.read32(0x1F80_10E8));
    }

    @Test
    void manualTransferKeepsMadrButUsesWordAlignedAddress() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        dma.setBus(bus);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1234_5678);

        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1003);
        assertEquals(0x0000_1003, dma.read32(0x1F80_1080));

        dma.write32(0x1F80_1084, 0x0000_0001);
        dma.write32(0x1F80_1088, 0x1100_0001);
        dma.tick(2);

        assertEquals(List.of(0x1234_5678), port.writes);
        assertEquals(0x0000_1003, dma.read32(0x1F80_1080));
    }

    @Test
    void dpcrPreservesCpuPriorityAndBit31() {
        DmaController dma = new DmaController(new InterruptController());

        dma.write32(0x1F80_10F0, 0xF765_4321);

        assertEquals(0xF765_4321, dma.read32(0x1F80_10F0));
    }

    @Test
    void dicrReadbackMatchesHardwareWritableBitMask() {
        DmaController dma = new DmaController(new InterruptController());

        dma.write32(0x1F80_10F4, 0);
        dma.write32(0x1F80_10F4, 0x1234_5678);

        assertEquals(0x0034_0078, dma.read32(0x1F80_10F4));
    }

    @Test
    void otcRequiresManualTrigger() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();

        bus.setDma(dma);
        bus.write32(0x0000_1000, 0x1234_5678);
        dma.write32(0x1F80_10F0, 0x0800_0000);
        dma.write32(0x1F80_10E0, 0x0000_1000);
        dma.write32(0x1F80_10E4, 0x0000_0001);
        dma.write32(0x1F80_10E8, 0x0100_0000);

        dma.tick(64);

        assertEquals(0x1234_5678, bus.read32(0x0000_1000));
        assertEquals(1 << 24, dma.read32(0x1F80_10E8) & (1 << 24));
    }

    @Test
    void otcKeepsMadrAfterManualTransfer() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();

        bus.setDma(dma);
        dma.write32(0x1F80_10F0, 0x0800_0000);
        dma.write32(0x1F80_10E0, 0x0000_100C);
        dma.write32(0x1F80_10E4, 0x0000_0004);
        dma.write32(0x1F80_10E8, 0x1100_0002);
        dma.tick(64);

        assertEquals(0x0000_100C, dma.read32(0x1F80_10E0));
        assertEquals(0x0000_1008, bus.read32(0x0000_100C));
        assertEquals(0x00FF_FFFF, bus.read32(0x0000_1000));
    }

    @Test
    void sliceModeWithChoppingBitSetHangsBusy() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1234_5678);
        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0001_0001);
        dma.write32(0x1F80_1088, 0x0100_0301);

        dma.tick(64);

        assertEquals(List.of(), port.writes);
        assertEquals(1 << 24, dma.read32(0x1F80_1088) & (1 << 24));
        assertEquals(0x0001_0001, dma.read32(0x1F80_1084));
    }

    @Test
    void completionFlagRequiresBothMasterAndChannelInterruptEnable() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1234_5678);
        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_10F4, 0x0001_0000);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0000_0001);
        dma.write32(0x1F80_1088, 0x1100_0001);

        dma.tick(1);

        assertEquals(0, dma.read32(0x1F80_10F4) & 0x8100_0000);
        assertEquals(0, interrupts.status() & 0x8);
    }

    @Test
    void dicrPerBlockBitRaisesBeforeSliceTransferCompletes() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();
        RequestingLoggingPort port = new RequestingLoggingPort();

        bus.setDma(dma);
        dma.attachPort(0, port);
        bus.write32(0x0000_1000, 0x1111_1111);
        bus.write32(0x0000_1004, 0x2222_2222);
        dma.write32(0x1F80_10F0, 0x0000_0008);
        dma.write32(0x1F80_10F4, 0x0081_0001);
        dma.write32(0x1F80_1080, 0x0000_1000);
        dma.write32(0x1F80_1084, 0x0002_0001);
        dma.write32(0x1F80_1088, 0x0100_0201);

        dma.tick(2);

        assertEquals(1 << 24, dma.read32(0x1F80_10F4) & (1 << 24));
        assertEquals(1 << 24, dma.read32(0x1F80_1088) & (1 << 24));
        assertEquals(0x8, interrupts.status() & 0x8);
    }

    @Test
    void busRoutesDmaUnknownRegistersAndPartialWritesLatchWholeWord() {
        InterruptController interrupts = new InterruptController();
        DmaController dma = new DmaController(interrupts);
        Bus bus = new Bus();

        bus.setDma(dma);

        assertEquals(0x7FFA_C68B, bus.read32(0x1F80_10F8));
        assertEquals(0x00FF_FFF7, bus.read32(0x1F80_10FC));

        bus.write16(0x1F80_1084, 0x0020);
        bus.write16(0x1F80_1086, 0x0003);
        assertEquals(0x0003_0000, bus.read32(0x1F80_1084));

        bus.write8(0x1F80_108B, 0x01);
        assertEquals(1 << 24, bus.read32(0x1F80_1088) & (1 << 24));
    }

    private static void runGpuDmaUntilIdle(DmaController dma, Gpu gpu) {
        for (int step = 0; step < 100_000; step++) {
            dma.tick(64);
            gpu.tick(64);
            boolean dmaIdle = (dma.read32(0x1F80_10A8) & (1 << 24)) == 0;
            boolean gpuIdle = (gpu.status() & (1 << 26)) != 0;
            if (dmaIdle && gpuIdle) {
                return;
            }
        }
        throw new AssertionError("GPU DMA did not drain");
    }

    private static final class LoggingPort implements DmaPort {
        private final int id;
        private final List<Integer> order;

        private LoggingPort(int id, List<Integer> order) {
            this.id = id;
            this.order = order;
        }

        @Override
        public int read() {
            return 0;
        }

        @Override
        public void write(int value) {
            order.add(id);
        }
    }

    private static final class RequestingLoggingPort implements DmaPort {
        private final List<Integer> writes = new ArrayList<>();

        @Override
        public int read() {
            return 0;
        }

        @Override
        public void write(int value) {
            writes.add(value);
        }

        @Override
        public boolean dmaRequest() {
            return true;
        }
    }

    private static final class MidBlockRequestPort implements DmaPort {
        private final int readsBeforeDrop;
        private int reads;
        private boolean request = true;
        private boolean dropped;

        private MidBlockRequestPort(int readsBeforeDrop) {
            this.readsBeforeDrop = readsBeforeDrop;
        }

        @Override
        public int read() {
            int value = 0x1111_0000 | reads++;
            if (!dropped && reads >= readsBeforeDrop) {
                request = false;
                dropped = true;
            }
            return value;
        }

        @Override
        public void write(int value) {
        }

        @Override
        public boolean dmaRequest(boolean fromRam) {
            return !fromRam && request;
        }
    }

    private record StableRequestPort(boolean request, boolean stable) implements DmaPort {
        @Override
        public int read() {
            return 0;
        }

        @Override
        public void write(int value) {
        }

        @Override
        public boolean dmaRequest(boolean fromRam) {
            return request;
        }

        @Override
        public boolean dmaRequestStableFor(boolean fromRam, int cycles) {
            return stable;
        }
    }
}
