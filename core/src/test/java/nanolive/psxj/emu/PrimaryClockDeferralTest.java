package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.devices.InterruptController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrimaryClockDeferralTest {

    @Test
    void mappedIoSynchronizesTheDeferredClockBeforeObservation() throws Exception {
        PsxEmulator deferred = new PsxEmulator(null, 100);
        PsxEmulator exact = new PsxEmulator(null, 100);
        Bus deferredBus = field(deferred, "bus", Bus.class);
        Bus exactBus = field(exact, "bus", Bus.class);
        R3000Cpu deferredCpu = field(deferred, "cpu", R3000Cpu.class);
        R3000Cpu exactCpu = field(exact, "cpu", R3000Cpu.class);
        installIntegerLoop(deferredBus, deferredCpu);
        installIntegerLoop(exactBus, exactCpu);

        for (int i = 0; i < 17; i++) {
            deferredCpu.step();
            exactCpu.step();
            exact.flushDeferredPrimaryCycles();
        }
        assertTrue(field(deferred, "deferredPrimaryCycles", Integer.class) > 0);

        assertEquals(exactBus.read32(0x1F80_1814), deferredBus.read32(0x1F80_1814));
        assertEquals(0, (int) field(deferred, "deferredPrimaryCycles", Integer.class));
        assertEquals(exact.saveStateJson(), deferred.saveStateJson());
    }

    @Test
    void maskedIdleRunAheadMatchesExactPerInstructionDeviceStepping() throws Exception {
        PsxEmulator deferred = new PsxEmulator(null, 100);
        PsxEmulator exact = new PsxEmulator(null, 100);
        Bus deferredBus = field(deferred, "bus", Bus.class);
        Bus exactBus = field(exact, "bus", Bus.class);
        R3000Cpu deferredCpu = field(deferred, "cpu", R3000Cpu.class);
        R3000Cpu exactCpu = field(exact, "cpu", R3000Cpu.class);
        InterruptController deferredInterrupts =
            field(deferred, "interrupts", InterruptController.class);
        InterruptController exactInterrupts =
            field(exact, "interrupts", InterruptController.class);
        installIntegerLoop(deferredBus, deferredCpu);
        installIntegerLoop(exactBus, exactCpu);

        deferredInterrupts.writeMask(0x03FF);
        exactInterrupts.writeMask(0x03FF);
        for (int i = 0; i < 150_017; i++) {
            deferredCpu.step();
            exactCpu.step();
            exact.flushDeferredPrimaryCycles();
        }

        assertTrue(field(deferred, "deferredPrimaryCycles", Integer.class) > 0);
        assertEquals(exact.saveStateJson(), deferred.saveStateJson());
        assertEquals(0, (int) field(deferred, "deferredPrimaryCycles", Integer.class));
    }

    @Test
    void gpuLinkedListDefersOnlyIndependentDomainsAndMatchesExactStepping() throws Exception {
        PsxEmulator deferred = new PsxEmulator(null, 100);
        PsxEmulator exact = new PsxEmulator(null, 100);
        Bus deferredBus = field(deferred, "bus", Bus.class);
        Bus exactBus = field(exact, "bus", Bus.class);
        R3000Cpu deferredCpu = field(deferred, "cpu", R3000Cpu.class);
        R3000Cpu exactCpu = field(exact, "cpu", R3000Cpu.class);
        installIntegerLoop(deferredBus, deferredCpu);
        installIntegerLoop(exactBus, exactCpu);
        installGpuLinkedList(deferredBus);
        installGpuLinkedList(exactBus);

        for (int i = 0; i < 64; i++) {
            deferredCpu.step();
            exactCpu.step();
            exact.flushDeferredPrimaryCycles();
        }

        assertTrue(field(deferred, "deferredGpuDmaCycles", Integer.class) > 0,
            "cached CPU work should run ahead of an interrupt-stable GPU DMA window");
        assertEquals(exact.saveStateJson(), deferred.saveStateJson());
    }

    @Test
    void queuedStoresPreserveTheirIssueBoundaryDuringGpuDmaRunAhead() throws Exception {
        PsxEmulator deferred = new PsxEmulator(null, 100);
        PsxEmulator exact = new PsxEmulator(null, 100);
        Bus deferredBus = field(deferred, "bus", Bus.class);
        Bus exactBus = field(exact, "bus", Bus.class);
        R3000Cpu deferredCpu = field(deferred, "cpu", R3000Cpu.class);
        R3000Cpu exactCpu = field(exact, "cpu", R3000Cpu.class);
        installStoreLoop(deferredBus, deferredCpu);
        installStoreLoop(exactBus, exactCpu);
        installGpuLinkedList(deferredBus);
        installGpuLinkedList(exactBus);

        for (int i = 0; i < 96; i++) {
            deferredCpu.step();
            exactCpu.step();
            exact.flushDeferredPrimaryCycles();
        }

        assertEquals(exact.saveStateJson(), deferred.saveStateJson());
    }

    private static void installIntegerLoop(Bus bus, R3000Cpu cpu) {
        bus.write32(0x0000, 0x2508_0001);
        bus.write32(0x0004, 0x0108_4821);
        bus.write32(0x0008, 0x0128_5026);
        bus.write32(0x000C, 0x1000_FFFC);
        bus.write32(0x0010, 0x0109_5821);
        cpu.reset(0x8000_0000);
    }

    private static void installStoreLoop(Bus bus, R3000Cpu cpu) {
        bus.write32(0x0000, 0x2508_0001); // addiu t0,t0,1
        bus.write32(0x0004, 0xAC08_2000); // sw t0,2000h(zero)
        bus.write32(0x0008, 0x1000_FFFD); // b 0000h
        bus.write32(0x000C, 0x0000_0000);
        cpu.reset(0x8000_0000);
    }

    private static void installGpuLinkedList(Bus bus) {
        int headerAddress = 0x1000;
        bus.write32(headerAddress, (255 << 24) | 0x00FF_FFFF);
        bus.write32(headerAddress + 4, 0x20FF_FFFF);
        bus.write32(headerAddress + 8, 0x0000_0000);
        bus.write32(headerAddress + 12, 0x0000_00FF);
        bus.write32(headerAddress + 16, 0x00EF_0000);
        for (int word = 4; word < 255; word++) {
            bus.write32(headerAddress + 4 + word * 4, 0);
        }
        bus.write32(0x1F80_1814, 0x0400_0002);
        int dpcr = bus.read32(0x1F80_10F0);
        bus.write32(0x1F80_10F0, dpcr | (1 << 11));
        bus.write32(0x1F80_10A0, headerAddress);
        bus.write32(0x1F80_10A8, 0x0100_0401);
    }

    private static <T> T field(Object owner, String name, Class<T> type)
        throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }
}
