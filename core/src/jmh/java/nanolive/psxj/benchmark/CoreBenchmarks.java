package nanolive.psxj.benchmark;

import nanolive.psxj.emu.CycleScheduler;
import nanolive.psxj.emu.PsxEmulator;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.CdRomController;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.Sio1Controller;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.devices.Spu;
import nanolive.psxj.emu.devices.TimerController;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;

/**
 * Stable, BIOS-free probes for the emulator's principal hot paths.
 *
 * <p>The CPU benchmark executes a representative cached-RAM integer loop.
 * Device clock benchmarks report emulated system clocks per host second, so
 * their result can be compared directly with the 33,868,800 Hz target.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(2)
public class CoreBenchmarks {

    private static final int CPU_BATCH = 4_096;
    private static final int CLOCK_BATCH = 120_000;

    @State(Scope.Thread)
    public static class CpuState {
        private R3000Cpu cpu;

        @Setup
        public void setup() {
            Bus bus = new Bus();
            bus.write32(0x0000, 0x2508_0001);
            bus.write32(0x0004, 0x0108_4821);
            bus.write32(0x0008, 0x0128_5026);
            bus.write32(0x000C, 0x1000_FFFC);
            bus.write32(0x0010, 0x0109_5821);
            cpu = new R3000Cpu(bus);
            cpu.reset(0x8000_0000);
            for (int i = 0; i < 64; i++) {
                cpu.step();
            }
        }
    }

    @State(Scope.Thread)
    public static class FullMachineCpuState {
        private PsxEmulator emulator;
        private R3000Cpu cpu;
        private Spu spu;

        @Param({"0", "1023"})
        private int interruptMask;

        @Setup
        public void setup() throws ReflectiveOperationException {
            emulator = new PsxEmulator(null, 100);
            Bus bus = field(emulator, "bus", Bus.class);
            cpu = field(emulator, "cpu", R3000Cpu.class);
            spu = field(emulator, "spu", Spu.class);
            field(emulator, "interrupts", InterruptController.class)
                .writeMask(interruptMask);
            installIntegerLoop(bus, cpu);
        }
    }

    @State(Scope.Thread)
    public static class BusState {
        private final Bus bus = new Bus();
        private int address;

        @Setup
        public void setup() {
            for (int i = 0; i < 16 * 1024; i += Integer.BYTES) {
                bus.write32(i, i * 0x9E37_79B9);
            }
        }
    }

    @State(Scope.Thread)
    public static class SchedulerState {
        private final CycleScheduler scheduler = new CycleScheduler();

        @Setup
        public void setup() {
            scheduler.setMaxSystemCycleQuantum(32);
            scheduler.setPrimaryTargets(
                cycles -> { }, cycles -> { }, cycles -> { }, cycles -> { }, null, null);
            scheduler.setCombinedPrimaryTarget(cycles -> { });
        }
    }

    @State(Scope.Thread)
    public static class GpuState {
        private final Gpu gpu = new Gpu(new InterruptController());
        private int origin;

        @Setup
        public void setup() {
            gpu.gp1(0x0000_0000);
            gpu.gp0(0xE300_0000);
            gpu.gp0(0xE407_FFFF);
            gpu.gp0(0x0200_FF00);
            gpu.gp0(512);
            gpu.gp0((128 << 16) | 128);
            gpu.tick(CLOCK_BATCH);
        }

        private void drawTriangle() {
            int x = origin++ & 0x7F;
            int y = (origin >>> 7) & 0x7F;
            gpu.gp0(0x3000_00FF);
            gpu.gp0((y << 16) | x);
            gpu.gp0(0x0000_FF00);
            gpu.gp0(((y + 96) << 16) | x);
            gpu.gp0(0x00FF_0000);
            gpu.gp0(((y + 32) << 16) | (x + 96));
            gpu.tick(CLOCK_BATCH);
        }

        private void drawTexturedTriangle() {
            int x = origin++ & 0x7F;
            int y = (origin >>> 7) & 0x7F;
            gpu.gp0(0x2500_8080);
            gpu.gp0((y << 16) | x);
            gpu.gp0(0);
            gpu.gp0(((y + 96) << 16) | x);
            gpu.gp0((8 << 16) | (96 << 8));
            gpu.gp0(((y + 32) << 16) | (x + 96));
            gpu.gp0((32 << 8) | 96);
            gpu.tick(CLOCK_BATCH);
        }
    }

    @State(Scope.Thread)
    public static class SpuState {
        private final Spu spu = new Spu(new InterruptController());
    }

    @State(Scope.Thread)
    public static class MachineClockState {
        private final InterruptController interrupts = new InterruptController();
        private final Gpu gpu = new Gpu(interrupts);
        private final Spu spu = new Spu(interrupts);
        private final CdRomController cdrom = new CdRomController(interrupts);
        private final DmaController dma = new DmaController(interrupts);
        private final TimerController timers = new TimerController(interrupts);
        private final SioController sio = new SioController(interrupts);
        private final Sio1Controller sio1 = new Sio1Controller(interrupts);

        @Param({"1", "4", "8", "16", "32"})
        private int quantum;

        @Setup
        public void setup() {
            timers.setGpu(gpu);
        }
    }

    @Benchmark
    @OperationsPerInvocation(CPU_BATCH)
    public int cpuCachedIntegerLoop(CpuState state) {
        for (int i = 0; i < CPU_BATCH; i++) {
            state.cpu.step();
        }
        return state.cpu.register(8);
    }

    @Benchmark
    @OperationsPerInvocation(CPU_BATCH)
    public int cpuWithIdleMachineClocks(FullMachineCpuState state) {
        for (int i = 0; i < CPU_BATCH; i++) {
            state.cpu.step();
        }
        state.spu.discardMixedSamples();
        return state.cpu.register(8);
    }

    @Benchmark
    @OperationsPerInvocation(CPU_BATCH)
    public void ramReadWrite(BusState state, Blackhole blackhole) {
        int address = state.address;
        for (int i = 0; i < CPU_BATCH; i++) {
            address = (address + 4) & 0x3FFC;
            int value = state.bus.read32(address);
            state.bus.write32(address, value + i);
            blackhole.consume(value);
        }
        state.address = address;
    }

    @Benchmark
    @OperationsPerInvocation(CPU_BATCH)
    public long schedulerFastPath(SchedulerState state) {
        for (int i = 0; i < CPU_BATCH; i++) {
            state.scheduler.advanceCpuCycles(1);
        }
        return state.scheduler.systemCycles();
    }

    @Benchmark
    @OperationsPerInvocation(CLOCK_BATCH)
    public int gpuCrtcClocks(GpuState state) {
        state.gpu.tick(CLOCK_BATCH);
        return state.gpu.frameCounter();
    }

    @Benchmark
    public int gpuShadedTriangle(GpuState state) {
        state.drawTriangle();
        return state.gpu.status();
    }

    @Benchmark
    public int gpuTexturedTriangle(GpuState state) {
        state.drawTexturedTriangle();
        return state.gpu.status();
    }

    @Benchmark
    @OperationsPerInvocation(CLOCK_BATCH)
    public int spuSilentClocks(SpuState state) {
        state.spu.tick(CLOCK_BATCH);
        state.spu.discardMixedSamples();
        return state.spu.queuedCdAudioFrames();
    }

    @Benchmark
    @OperationsPerInvocation(CLOCK_BATCH)
    public int idleMachineClocks(MachineClockState state) {
        int remaining = CLOCK_BATCH;
        while (remaining > 0) {
            int step = Math.min(remaining, state.quantum);
            state.gpu.tick(step);
            state.cdrom.tick(step);
            state.sio.tick(step);
            state.sio1.tick(step);
            state.spu.tick(step);
            state.dma.tick(step);
            state.timers.tick(step);
            remaining -= step;
        }
        state.spu.discardMixedSamples();
        return state.gpu.frameCounter();
    }

    private static void installIntegerLoop(Bus bus, R3000Cpu cpu) {
        bus.write32(0x0000, 0x2508_0001);
        bus.write32(0x0004, 0x0108_4821);
        bus.write32(0x0008, 0x0128_5026);
        bus.write32(0x000C, 0x1000_FFFC);
        bus.write32(0x0010, 0x0109_5821);
        cpu.reset(0x8000_0000);
        for (int i = 0; i < 64; i++) {
            cpu.step();
        }
    }

    private static <T> T field(Object owner, String name, Class<T> type)
        throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }
}
