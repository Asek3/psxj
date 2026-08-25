package nanolive.psxj.emu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import nanolive.psxj.emu.cop0.Cop0;
import nanolive.psxj.emu.core.BiosImage;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.devices.CdRomController;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.Mdec;
import nanolive.psxj.emu.devices.Sio1Controller;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.devices.Spu;
import nanolive.psxj.emu.devices.TimerController;
import nanolive.psxj.emu.dma.DmaPort;
import nanolive.psxj.emu.gte.Gte;
import nanolive.psxj.emu.hardware.HardwareProfile;
import nanolive.psxj.emu.video.GpuFrame;
import nanolive.psxj.emu.sio.ControllerDevice;
import nanolive.psxj.emu.sio.Sio1LinkEndpoint;
import nanolive.psxj.emu.api.AudioBackend;
import nanolive.psxj.emu.api.GamepadBackend;
import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.util.Log;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

public final class PsxEmulator implements AutoCloseable {

    public static final int ACHIEVEMENT_MEMORY_SIZE = Bus.RAM_SIZE + Bus.SCRATCHPAD_SIZE;
    private static final Gson STATE_GSON = createStateGson();
    private static final int SAVE_STATE_VERSION = 1;
    private static final int MAX_SYSTEM_CYCLE_QUANTUM = 256;
    private static final int STALL_PROGRESS_MASK = 0x0FFF;

    private final Path biosPath;
    private final HardwareProfile hardwareProfile;
    private final Bus bus;
    private final Cop0 cop0;
    private final Gte gte;
    private final R3000Cpu cpu;
    private final Gpu gpu;
    private final Spu spu;
    private final CdRomController cdrom;
    private final DmaController dma;
    private final TimerController timers;
    private final InterruptController interrupts;
    private final SioController sio;
    private final Sio1Controller sio1;
    private final Mdec mdec;
    private final CycleScheduler scheduler;
    private final Object machineLock = new Object();

    private volatile EmulationState state = EmulationState.STOPPED;
    private volatile String loadedGameTitle;
    private volatile PsxExecutable directExecutable;
    private volatile RenderBackend renderBackend;
    private volatile AudioBackend audioBackend;
    private volatile GamepadBackend gamepadBackend;
    private volatile Thread emulationThread;
    private volatile StallWatchdog stallWatchdog;
    private volatile long stallHeartbeatNanos;
    private volatile long stallHeartbeatSteps;
    private volatile long stallHeartbeatSystemCycles;
    private volatile int stallHeartbeatPc;
    private long stallStepCounter;
    private volatile GpuFrame diagnosticFrameSink;
    private volatile CompletableFuture<Void> terminationFuture =
        CompletableFuture.completedFuture(null);
    private long sliceCounter;
    private Runnable stopListener;
    private volatile Runnable frameListener;
    private volatile Runnable idleListener;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private boolean biosHandoffLogged;
    private int lastPc;
    private int deferredPrimaryCycles;
    private int deferredGpuDmaCycles;
    private int deferredGpuDmaPeripheralCycles;
    private int deferredGpuDmaDotClockTicks;
    private int deferredGpuDmaHblankRises;
    private final java.util.concurrent.atomic.AtomicInteger keyboardPadMask =
        new java.util.concurrent.atomic.AtomicInteger();
    private int gamepadPadMask;
    private int gamepadLeftX = 0x80;
    private int gamepadLeftY = 0x80;
    private int gamepadRightX = 0x80;
    private int gamepadRightY = 0x80;
    public PsxEmulator(Path biosPath, int overclockPercent) {
        this.biosPath = biosPath;
        this.hardwareProfile = HardwareProfile.detect(biosPath);
        Log.info("Hardware profile: " + hardwareProfile.model() + " "
            + hardwareProfile.mainboardRevision() + " " + hardwareProfile.region());
        this.bus = new Bus();
        this.interrupts = new InterruptController();
        this.cop0 = new Cop0();
        this.gte = new Gte();
        this.dma = new DmaController(interrupts);
        this.gpu = new Gpu(interrupts, hardwareProfile);
        this.spu = new Spu(interrupts);
        this.cdrom = new CdRomController(interrupts, hardwareProfile);
        this.timers = new TimerController(interrupts);
        this.sio = new SioController(interrupts);
        this.sio1 = new Sio1Controller(interrupts);
        this.mdec = new Mdec();
        this.scheduler = new CycleScheduler();
        this.scheduler.setOverclockPercent(overclockPercent);
        // Check device events before batching clocks.
        this.scheduler.setMaxSystemCycleQuantum(MAX_SYSTEM_CYCLE_QUANTUM);
        this.cpu = new R3000Cpu(bus, cop0, gte);
        wireDevices();
        wireScheduler();
        this.cpu.setCycleAdvancer(scheduler::advanceCpuCycles);
        this.sio.setRumbleHandler((largeMotor, smallMotor) -> {
            GamepadBackend backend = gamepadBackend;
            if (backend != null) {
                backend.rumble(largeMotor, smallMotor);
            }
        });
    }

    private void wireDevices() {
        bus.setInterruptController(interrupts);
        bus.setGpu(gpu);
        bus.setSpu(spu);
        bus.setDma(dma);
        bus.setTimerController(timers);
        bus.setCdRomController(cdrom);
        cdrom.setQueuedAudioFramesSupplier(spu::queuedCdAudioFrames);
        bus.setSioController(sio);
        bus.setSio1Controller(sio1);
        bus.setMdec(mdec);
        bus.setDeviceSynchronizer(this::flushDeferredPrimaryCycles);
        dma.setGpu(gpu);
        timers.setGpu(gpu);
        sio.setBeamPositionSource(() -> new ControllerDevice.BeamPosition(
            gpu.beamField(),
            gpu.beamScanline(),
            gpu.beamCrtcTick()
        ));

        dma.attachPort(0, mdec.inputDmaPort());
        dma.attachPort(1, mdec);
        dma.attachPort(2, gpu);
        dma.attachPort(3, cdrom);
        dma.attachPort(4, new DmaPort() {
            @Override
            public int read() {
                int lo = spu.dmaRead();
                int hi = spu.dmaRead();
                return lo | (hi << 16);
            }

            @Override
            public void write(int value) {
                spu.dmaWrite(value & 0xFFFF);
                spu.dmaWrite((value >>> 16) & 0xFFFF);
            }

            @Override
            public boolean dmaRequest(boolean fromRam) {
                return fromRam ? spu.dmaWriteRequest() : spu.dmaReadRequest();
            }
        });
    }

    private void wireScheduler() {
        scheduler.setPrimaryTargets(
            this::tickDeviceClockDomain,
            this::tickAudioDomain,
            dma::tick,
            timers::tick,
            null,
            null
        );
        scheduler.setCombinedPrimaryTarget(this::tickPrimaryClockDomains);
    }

    private void tickPrimaryClockDomains(int cycles) {
        if (deferredGpuDmaCycles > 0) {
            int combinedCycles = deferredGpuDmaCycles + cycles;
            if (cycles > 0 && combinedCycles <= MAX_SYSTEM_CYCLE_QUANTUM) {
                deferredGpuDmaCycles = combinedCycles;
                bus.setCpuCycleAdvanceDeferred(true);
                return;
            }
            flushDeferredGpuDmaCycles();
        }
        if (deferredGpuDmaPeripheralCycles > 0) {
            tickGpuDmaIntervalDeferringPeripherals(cycles);
            return;
        }
        int combinedCycles = deferredPrimaryCycles + cycles;
        if (cycles > 0 && deferredPrimaryCycles > 0
            && combinedCycles <= MAX_SYSTEM_CYCLE_QUANTUM) {
            deferredPrimaryCycles = combinedCycles;
            if (combinedCycles < MAX_SYSTEM_CYCLE_QUANTUM) {
                return;
            }
            deferredPrimaryCycles = 0;
            tickPrimaryClockDomainsNow(combinedCycles);
            return;
        }
        flushDeferredPrimaryCycles();
        boolean soleGpuLinkedList = cycles > 0 && dma.soleGpuLinkedListTransferConfigured();
        boolean gpuDmaBaseStable = soleGpuLinkedList
            && gpuDmaPeripheralBaseStable(MAX_SYSTEM_CYCLE_QUANTUM);
        boolean timersStable = gpuDmaBaseStable
            && timers.permitsBatchedGpuInterval(MAX_SYSTEM_CYCLE_QUANTUM);
        boolean gpuDmaPeripheralsStable = gpuDmaBaseStable && timersStable
            && gpuDmaPeripheralInterruptStable(MAX_SYSTEM_CYCLE_QUANTUM);
        if (cycles > 0
            && cycles <= MAX_SYSTEM_CYCLE_QUANTUM
            && gpuDmaPeripheralsStable
            && gpuDmaRunAheadInterruptStable(MAX_SYSTEM_CYCLE_QUANTUM, timersStable)) {
            deferredGpuDmaCycles = cycles;
            bus.setCpuCycleAdvanceDeferred(true);
            return;
        }
        if (cycles > 0
            && gpuDmaPeripheralsStable) {
            tickGpuDmaIntervalDeferringPeripherals(cycles);
            return;
        }
        // MMIO flushes this window before DMA or I_MASK changes become visible.
        if (cycles > 0
            && cycles <= MAX_SYSTEM_CYCLE_QUANTUM
            && dma.arbitrationIdleFor(MAX_SYSTEM_CYCLE_QUANTUM)) {
            boolean idleTimersStable = gpuDmaBaseStable
                ? timersStable
                : timers.permitsBatchedGpuInterval(MAX_SYSTEM_CYCLE_QUANTUM);
            if (interruptWindowStable(MAX_SYSTEM_CYCLE_QUANTUM, idleTimersStable)) {
                deferredPrimaryCycles = cycles;
                if (cycles < MAX_SYSTEM_CYCLE_QUANTUM) {
                    return;
                }
                deferredPrimaryCycles = 0;
            }
        }
        tickPrimaryClockDomainsNow(cycles);
    }

    private void tickGpuDmaIntervalDeferringPeripherals(int cycles) {
        if (cycles == 1) {
            gpu.tick(1);
            deferredGpuDmaDotClockTicks += gpu.dotClockTicksLastTick();
            deferredGpuDmaHblankRises += gpu.hblankRisesLastTick();
            boolean dmaStillActive = dma.tickSoleGpuLinkedListClockAndReportActive();
            deferredGpuDmaPeripheralCycles++;
            if (!dmaStillActive
                || deferredGpuDmaPeripheralCycles == MAX_SYSTEM_CYCLE_QUANTUM) {
                flushDeferredGpuDmaPeripherals();
            }
            return;
        }
        int remaining = Math.max(0, cycles);
        while (remaining > 0) {
            int windowCapacity = MAX_SYSTEM_CYCLE_QUANTUM
                - deferredGpuDmaPeripheralCycles;
            int interval = Math.min(remaining, windowCapacity);
            int intervalRemaining = interval;
            while (intervalRemaining > 0) {
                int idleClocks = intervalRemaining == 1
                    ? 0
                    : dma.soleGpuLinkedListIdleClocks(intervalRemaining);
                int activeBatchClocks = idleClocks == 0 && intervalRemaining > 1
                    ? dma.soleGpuLinkedListActiveBatchClocks(intervalRemaining)
                    : 1;
                int quantum;
                if (idleClocks > 0) {
                    quantum = idleClocks;
                } else if (activeBatchClocks > 1) {
                    quantum = activeBatchClocks;
                } else {
                    quantum = intervalRemaining == 1
                        ? 1
                        : dma.cyclesUntilNextArbitrationBoundary(intervalRemaining);
                }
                gpu.tick(quantum);
                deferredGpuDmaDotClockTicks += gpu.dotClockTicksLastTick();
                deferredGpuDmaHblankRises += gpu.hblankRisesLastTick();
                boolean dmaStillActive;
                if (quantum == 1 || idleClocks > 0) {
                    dmaStillActive = dma.tickSoleGpuLinkedListClockAndReportActive();
                } else {
                    dma.tick(quantum);
                    dmaStillActive = dma.soleGpuLinkedListTransferConfigured();
                }
                deferredGpuDmaPeripheralCycles += quantum;
                intervalRemaining -= quantum;
                remaining -= quantum;
                if (!dmaStillActive) {
                    flushDeferredGpuDmaPeripherals();
                    if (remaining > 0) {
                        tickPrimaryClockDomainsNow(remaining);
                    }
                    return;
                }
            }
            if (deferredGpuDmaPeripheralCycles == MAX_SYSTEM_CYCLE_QUANTUM) {
                flushDeferredGpuDmaPeripherals();
                boolean baseStable = remaining > 0
                    && gpuDmaPeripheralBaseStable(MAX_SYSTEM_CYCLE_QUANTUM);
                boolean windowStable = baseStable
                    && timers.permitsBatchedGpuInterval(MAX_SYSTEM_CYCLE_QUANTUM)
                    && gpuDmaPeripheralInterruptStable(MAX_SYSTEM_CYCLE_QUANTUM);
                if (remaining > 0 && !windowStable) {
                    tickPrimaryClockDomainsNow(remaining);
                    return;
                }
            }
        }
    }

    private boolean gpuDmaPeripheralBaseStable(int cycles) {
        if (mdec.clockActive()
            || !sio.gpuTimingIndependent()
            || !cdrom.audioInputStableFor(cycles)) {
            return false;
        }
        return true;
    }

    private boolean gpuDmaPeripheralInterruptStable(int cycles) {
        int mask = interrupts.mask();
        if ((interrupts.status() & mask) != 0) {
            return true;
        }
        // GPU/VBLANK and DMA remain exact-clock domains in this path.
        int deferredMask = mask & ~((1 << 0) | (1 << 1) | (1 << 3));
        if ((deferredMask & ~0x07F4) != 0) {
            return false;
        }
        if ((deferredMask & (1 << 2)) != 0 && !cdrom.interruptStableFor(cycles)) {
            return false;
        }
        if ((deferredMask & (1 << 7)) != 0 && !sio.interruptStableFor(cycles)) {
            return false;
        }
        if ((deferredMask & (1 << 8)) != 0 && !sio1.interruptStableFor(cycles)) {
            return false;
        }
        return (deferredMask & (1 << 9)) == 0 || spu.interruptStableFor(cycles);
    }

    private boolean gpuDmaRunAheadInterruptStable(int cycles, boolean timersStable) {
        int mask = interrupts.mask();
        if ((interrupts.status() & mask) != 0) {
            return true;
        }
        if ((mask & (1 << 3)) != 0 && !dma.soleGpuLinkedListInterruptStable()) {
            return false;
        }
        return interruptWindowStable(cycles, timersStable);
    }

    private void flushDeferredGpuDmaCycles() {
        int cycles = deferredGpuDmaCycles;
        if (cycles <= 0) {
            return;
        }
        deferredGpuDmaCycles = 0;
        bus.setCpuCycleAdvanceDeferred(false);
        tickGpuDmaIntervalDeferringPeripherals(cycles);
        flushDeferredGpuDmaPeripherals();
        bus.flushDeferredCpuCycles();
    }

    private void flushDeferredGpuDmaPeripherals() {
        int cycles = deferredGpuDmaPeripheralCycles;
        if (cycles <= 0) {
            return;
        }
        int dotClockTicks = deferredGpuDmaDotClockTicks;
        int hblankRises = deferredGpuDmaHblankRises;
        deferredGpuDmaPeripheralCycles = 0;
        deferredGpuDmaDotClockTicks = 0;
        deferredGpuDmaHblankRises = 0;
        cdrom.tick(cycles);
        sio.tick(cycles);
        sio1.tick(cycles);
        if (cdrom.consumeAudioResetRequest()) {
            spu.clearCdAudio();
        }
        short[] xaPcm = cdrom.drainXaPcm();
        if (xaPcm.length > 0) {
            spu.submitCdAudio(xaPcm);
        }
        spu.tick(cycles);
        timers.tickBatchedGpuInterval(cycles, dotClockTicks, hblankRises);
    }

    private boolean interruptWindowStable(int cycles, boolean timersStable) {
        int mask = interrupts.mask();
        if (mask == 0 || (interrupts.status() & mask) != 0) {
            return true;
        }
        if ((mask & ~0x07FF) != 0) {
            return false;
        }
        if (!gpu.interruptStableFor(mask, cycles)) {
            return false;
        }
        if ((mask & (1 << 2)) != 0 && !cdrom.interruptStableFor(cycles)) {
            return false;
        }
        if ((mask & 0x70) != 0 && !timersStable) {
            return false;
        }
        if ((mask & (1 << 7)) != 0 && !sio.interruptStableFor(cycles)) {
            return false;
        }
        if ((mask & (1 << 8)) != 0 && !sio1.interruptStableFor(cycles)) {
            return false;
        }
        if ((mask & (1 << 9)) != 0 && !spu.interruptStableFor(cycles)) {
            return false;
        }
        return (mask & (1 << 10)) == 0 || sio.gpuTimingIndependent();
    }

    private void tickPrimaryClockDomainsNow(int cycles) {
        // Keep this order in sync with CycleScheduler.Phase.
        boolean timersStable = cycles > 1 && timers.permitsBatchedGpuInterval(cycles);
        if (cycles > 1
            && sio.gpuTimingIndependent()
            && timersStable) {
            if (dma.arbitrationIdleFor(cycles)) {
                tickIndependentPrimaryInterval(cycles);
                return;
            }
            tickDmaCoupledPrimaryInterval(cycles);
            return;
        }
        if (cycles > 1) {
            // Slow path for an interval that cannot be safely batched.
            for (int i = 0; i < cycles; i++) {
                tickOnePrimaryClock();
            }
            return;
        }
        tickOnePrimaryClock();
    }

    private void tickDmaCoupledPrimaryInterval(int cycles) {
        int dotClockTicks = 0;
        int hblankRises = 0;
        // Keep DMA devices in step at arbitration points.
        int remaining = cycles;
        while (remaining > 0) {
            int quantum = dma.cyclesUntilNextArbitrationBoundary(remaining);
            // Do not batch across a CD-to-SPU sample boundary.
            if (quantum > 1 && !cdrom.audioInputStableFor(quantum)) {
                quantum = 1;
            }
            gpu.tick(quantum);
            dotClockTicks += gpu.dotClockTicksLastTick();
            hblankRises += gpu.hblankRisesLastTick();
            cdrom.tick(quantum);
            if (mdec.clockActive()) {
                mdec.tick(quantum);
            }
            if (cdrom.consumeAudioResetRequest()) {
                spu.clearCdAudio();
            }
            short[] xaPcm = cdrom.drainXaPcm();
            if (xaPcm.length > 0) {
                spu.submitCdAudio(xaPcm);
            }
            spu.tick(quantum);
            dma.tick(quantum);
            remaining -= quantum;
        }
        sio.tick(cycles);
        sio1.tick(cycles);
        timers.tickBatchedGpuInterval(cycles, dotClockTicks, hblankRises);
    }

    private void tickIndependentPrimaryInterval(int cycles) {
        gpu.tick(cycles);
        sio.tick(cycles);
        sio1.tick(cycles);
        if (mdec.clockActive()) {
            mdec.tick(cycles);
        }
        if (cdrom.audioInputStableFor(cycles)) {
            // No CD-to-SPU edge lies inside this interval.
            cdrom.tick(cycles);
            spu.tick(cycles);
            timers.tick(cycles);
            return;
        }
        // CD audio needs single-clock ordering against the SPU here.
        for (int i = 0; i < cycles; i++) {
            cdrom.tick(1);
            if (cdrom.consumeAudioResetRequest()) {
                spu.clearCdAudio();
            }
            short[] xaPcm = cdrom.drainXaPcm();
            if (xaPcm.length > 0) {
                spu.submitCdAudio(xaPcm);
            }
            spu.tick(1);
        }
        timers.tick(cycles);
    }

    private void tickOnePrimaryClock() {
        gpu.tick(1);
        cdrom.tick(1);
        sio.tick(1);
        sio1.tick(1);
        if (mdec.clockActive()) {
            mdec.tick(1);
        }
        if (cdrom.consumeAudioResetRequest()) {
            spu.clearCdAudio();
        }
        short[] xaPcm = cdrom.drainXaPcm();
        if (xaPcm.length > 0) {
            spu.submitCdAudio(xaPcm);
        }
        spu.tick(1);
        dma.tick(1);
        timers.tick(1);
    }

    private void tickDeviceClockDomain(int cycles) {
        flushDeferredPrimaryCycles();
        gpu.tick(cycles);
        cdrom.tick(cycles);
        sio.tick(cycles);
        sio1.tick(cycles);
        if (mdec.clockActive()) {
            mdec.tick(cycles);
        }
    }

    private void tickAudioDomain(int cycles) {
        if (cdrom.consumeAudioResetRequest()) {
            spu.clearCdAudio();
        }
        short[] xaPcm = cdrom.drainXaPcm();
        if (xaPcm.length > 0) {
            spu.submitCdAudio(xaPcm);
        }
        // CD audio is a continuous 44.1 kHz input to the SPU.
        spu.tick(cycles);
    }

    void flushDeferredPrimaryCycles() {
        flushDeferredGpuDmaCycles();
        flushDeferredGpuDmaPeripherals();
        int cycles = deferredPrimaryCycles;
        if (cycles <= 0) {
            return;
        }
        deferredPrimaryCycles = 0;
        tickPrimaryClockDomainsNow(cycles);
    }

    public void loadBios() throws IOException {
        if (biosPath == null) {
            throw new IOException("No BIOS selected.");
        }
        bus.setBios(BiosImage.load(biosPath));
        cpu.reset(bus.resetVector());
    }

    public HardwareProfile hardwareProfile() {
        return hardwareProfile;
    }

    public void setBackends(RenderBackend renderBackend, AudioBackend audioBackend) {
        setBackends(renderBackend, audioBackend, null);
    }

    public void setBackends(RenderBackend renderBackend, AudioBackend audioBackend,
                            GamepadBackend gamepadBackend) {
        this.renderBackend = renderBackend;
        this.audioBackend = audioBackend;
        this.gamepadBackend = gamepadBackend;
        if (this.renderBackend != null) {
            this.renderBackend.setCloseRequestHandler(() -> {
                Log.info("Render window closed by user; stopping emulation.");
                stop();
                return true;
            });
        }
    }

    public void setStopListener(Runnable stopListener) {
        this.stopListener = stopListener;
    }

    public void setFrameListener(Runnable frameListener) {
        this.frameListener = frameListener;
    }

    public void setIdleListener(Runnable idleListener) {
        this.idleListener = idleListener;
    }

    public void setOverclockPercent(int percent) {
        synchronized (machineLock) {
            scheduler.setOverclockPercent(percent);
        }
        Log.info("CPU overclock changed to " + Math.max(1, percent) + "%");
    }

    public int readAchievementMemory(int address, byte[] destination, int offset, int length) {
        if (address < 0 || length < 0) {
            return 0;
        }
        Objects.checkFromIndexSize(offset, length, destination.length);
        synchronized (machineLock) {
            for (int i = 0; i < length; i++) {
                long current = Integer.toUnsignedLong(address) + i;
                int physical;
                if (current <= 0x1F_FFFFL) {
                    physical = (int) current;
                } else if (current >= 0x20_0000L && current <= 0x20_03FFL) {
                    physical = 0x1F80_0000 + (int) (current - 0x20_0000L);
                } else {
                    return i;
                }
                int value = bus.peekRam8(physical);
                if (value < 0) {
                    return i;
                }
                destination[offset + i] = (byte) value;
            }
            return length;
        }
    }

    public void copyAchievementMemory(byte[] destination) {
        Objects.requireNonNull(destination, "destination");
        synchronized (machineLock) {
            bus.copyAchievementMemory(destination);
        }
    }

    public void loadGame(Path path, String title) {
        Objects.requireNonNull(path, "path");
        this.loadedGameTitle = title;
        Log.info("Loading game: title=" + title + ", path=" + path);
        directExecutable = null;
        try {
            PsxExecutable executable = PsxExecutable.tryLoad(path);
            if (executable != null) {
                directExecutable = executable;
                cdrom.eject();
                Log.info("Prepared direct PS-X EXE boot: path=" + executable.path()
                    + ", pc=0x" + Integer.toHexString(executable.initialPc())
                    + ", gp=0x" + Integer.toHexString(executable.initialGp())
                    + ", load=0x" + Integer.toHexString(executable.loadAddress())
                    + ", size=0x" + Integer.toHexString(executable.fileSize()));
            } else {
                cdrom.mount(path);
            }
        } catch (IOException ex) {
            Log.error("Failed to inspect PS-X executable " + path, ex);
            cdrom.mount(path);
        }
    }

    public void prepareBiosBoot(Path card1, Path card2) {
        loadedGameTitle = null;
        directExecutable = null;
        cdrom.eject();
        attachMemoryCards(card1, card2);
    }

    public void attachMemoryCards(Path slot1, Path slot2) {
        try {
            sio.attachMemoryCards(slot1, slot2);
        } catch (IOException ex) {
            Log.error("Failed to attach memory cards", ex);
        }
    }

    public void setMemoryCardWriteListener(IntConsumer listener) {
        sio.setMemoryCardWriteListener(listener);
    }

    public void setBiosTtyCharacterSink(IntConsumer sink) {
        cpu.setBiosTtyCharacterSink(sink);
    }

    public void start() {
        Thread previousThread = emulationThread;
        if (state == EmulationState.RUNNING
            || previousThread != null && previousThread.isAlive()) {
            return;
        }
        stopRequested.set(false);
        terminationFuture = new CompletableFuture<>();
        state = EmulationState.RUNNING;
        sliceCounter = 0;
        biosHandoffLogged = false;
        lastPc = 0;
        Log.info("Starting emulation thread");
        emulationThread = Thread.ofPlatform().name("psxj-emulation").start(this::runLoop);
    }

    public void pause() {
        if (state == EmulationState.RUNNING) {
            state = EmulationState.PAUSED;
            AudioBackend currentAudio = audioBackend;
            if (currentAudio != null) {
                currentAudio.pause();
            }
        }
    }

    public void resume() {
        if (state == EmulationState.PAUSED) {
            AudioBackend currentAudio = audioBackend;
            if (currentAudio != null) {
                currentAudio.resume();
            }
            state = EmulationState.RUNNING;
        }
    }

    public void stop() {
        requestStop();
        Thread thread = emulationThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void requestStop() {
        Log.info("Stopping emulation");
        stopRequested.set(true);
        state = EmulationState.STOPPED;
        Thread thread = emulationThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public EmulationState state() {
        return state;
    }

    public CompletableFuture<Void> terminationFuture() {
        return terminationFuture;
    }

    public AutoCloseable startHeadlessStallDiagnostics() {
        return startStallWatchdog(Thread.currentThread(), false);
    }

    public PerformanceSnapshot performanceSnapshot() {
        synchronized (machineLock) {
            flushDeferredPrimaryCycles();
            return new PerformanceSnapshot(scheduler.systemCycles(), gpu.frameCounter());
        }
    }

    public record PerformanceSnapshot(long systemCycles, int gpuFrames) {
    }

    public MachineDiagnostic machineDiagnostic() {
        synchronized (machineLock) {
            flushDeferredPrimaryCycles();
            return new MachineDiagnostic(
                cpu.pc(), gpu.frameCounter(), gpu.completedFrameHash(),
                gpu.status(), interrupts.status(), interrupts.mask(),
                timers.read16(0x1F80_1100), timers.read16(0x1F80_1110),
                timers.read16(0x1F80_1120), gpu.diagnostic(), cdrom.diagnostic(),
                dma.diagnostic(), spu.diagnostic()
            );
        }
    }

    public record MachineDiagnostic(
        int pc, int gpuFrame, int frameHash, int gpuStatus,
        int irqStatus, int irqMask, int timer0, int timer1, int timer2,
        Gpu.Diagnostic gpu, CdRomController.Diagnostic cdrom,
        DmaController.Diagnostic dma, Spu.Diagnostic spu
    ) {
    }

    public PerformanceSnapshot runHeadlessCycles(long requestedSystemCycles) {
        synchronized (machineLock) {
            long start = scheduler.systemCycles();
            long target = start + Math.max(0L, requestedSystemCycles);
            while (scheduler.systemCycles() < target) {
                stepMachine();
            }
            flushDeferredPrimaryCycles();
            return new PerformanceSnapshot(scheduler.systemCycles(), gpu.frameCounter());
        }
    }

    public PerformanceSnapshot runHeadlessCyclesExactly(long requestedSystemCycles) {
        synchronized (machineLock) {
            long start = scheduler.systemCycles();
            long target = start + Math.max(0L, requestedSystemCycles);
            while (scheduler.systemCycles() < target) {
                stepMachine();
                flushDeferredPrimaryCycles();
            }
            return new PerformanceSnapshot(scheduler.systemCycles(), gpu.frameCounter());
        }
    }

    public PerformanceSnapshot runHeadlessCyclesWithFrameCapture(long requestedSystemCycles) {
        synchronized (machineLock) {
            long start = scheduler.systemCycles();
            long target = start + Math.max(0L, requestedSystemCycles);
            int lastFrame = gpu.frameCounter();
            while (scheduler.systemCycles() < target) {
                stepMachine();
                int frame = gpu.frameCounter();
                if (frame != lastFrame) {
                    diagnosticFrameSink = gpu.captureFrame();
                    lastFrame = frame;
                }
            }
            flushDeferredPrimaryCycles();
            return new PerformanceSnapshot(scheduler.systemCycles(), gpu.frameCounter());
        }
    }

    public void setPadButtonState(int mask, boolean pressed) {
        keyboardPadMask.updateAndGet(current ->
            pressed ? current | mask : current & ~mask);
    }

    public void setControllerPortConnected(int port, boolean connected) {
        synchronized (machineLock) {
            sio.setControllerConnected(port, connected);
        }
    }

    public void setControllerPortState(
        int port,
        int pressedMask,
        int leftX,
        int leftY,
        int rightX,
        int rightY
    ) {
        synchronized (machineLock) {
            sio.setControllerState(port, pressedMask, leftX, leftY, rightX, rightY);
        }
    }

    public void setControllerDevice(int port, ControllerDevice device) {
        synchronized (machineLock) {
            sio.setControllerDevice(port, device);
        }
    }

    public void setMultitapControllerDevice(int port, int slot, ControllerDevice device) {
        synchronized (machineLock) {
            sio.setMultitapController(port, slot, device);
        }
    }

    public void setSio1LinkEndpoint(Sio1LinkEndpoint endpoint) {
        synchronized (machineLock) {
            sio1.setLinkEndpoint(endpoint);
        }
    }

    public String saveStateJson() {
        synchronized (machineLock) {
            flushDeferredPrimaryCycles();
            SaveStateDto dto = new SaveStateDto();
            dto.version = SAVE_STATE_VERSION;
            dto.cpu = cpu.copyState();
            dto.cop0 = copyCop0();
            dto.gteData = copyGteData();
            dto.gteControl = copyGteControl();
            dto.ram = Base64.getEncoder().encodeToString(bus.copyRam());
            dto.scratchpad = Base64.getEncoder().encodeToString(bus.copyScratchpad());
            dto.bus = bus.copyState();
            dto.vram = encodeShortArray(gpu.copyVram());
            dto.gpu = gpu.copyState();
            dto.spuRam = encodeShortArray(spu.copyRam());
            dto.spu = spu.copyState();
            dto.interrupts = interrupts.copyState();
            dto.dma = dma.copyState();
            dto.timers = timers.copyState();
            dto.sio = sio.copyState();
            dto.sio1 = sio1.copyState();
            dto.mdec = mdec.copyState();
            dto.cdrom = cdrom.copyState();
            dto.scheduler = scheduler.copyState();
            return STATE_GSON.toJson(dto);
        }
    }

    public void loadStateJson(String json) {
        SaveStateDto dto = STATE_GSON.fromJson(json, SaveStateDto.class);
        if (dto == null) {
            throw new IllegalArgumentException("Save state is empty.");
        }
        if (dto.version != SAVE_STATE_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported save-state version " + dto.version
                    + "; expected " + SAVE_STATE_VERSION + ".");
        }
        synchronized (machineLock) {
            deferredPrimaryCycles = 0;
            deferredGpuDmaCycles = 0;
            deferredGpuDmaPeripheralCycles = 0;
            deferredGpuDmaDotClockTicks = 0;
            deferredGpuDmaHblankRises = 0;
            loadCop0(dto.cop0);
            gte.loadRawState(dto.gteData, dto.gteControl);
            cpu.loadState(dto.cpu);
            if (dto.ram != null) {
                bus.loadRam(Base64.getDecoder().decode(dto.ram));
            }
            if (dto.scratchpad != null) {
                bus.loadScratchpad(Base64.getDecoder().decode(dto.scratchpad));
            }
            bus.loadState(dto.bus);
            if (dto.vram != null) {
                gpu.loadVram(decodeShortArray(dto.vram));
            }
            gpu.loadState(dto.gpu);
            if (dto.spuRam != null) {
                spu.loadRam(decodeShortArray(dto.spuRam));
            }
            spu.loadState(dto.spu);
            dma.loadState(dto.dma);
            timers.loadState(dto.timers);
            mdec.loadState(dto.mdec);
            cdrom.loadState(dto.cdrom);
            scheduler.loadState(dto.scheduler);
            sio.loadState(dto.sio);
            sio1.loadState(dto.sio1);
            interrupts.loadState(dto.interrupts);
        }
    }

    private static Gson createStateGson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        gson.getAdapter(SaveStateDto.class);
        return gson;
    }

    private int[] copyCop0() {
        return cop0.copyRawRegisters();
    }

    private void loadCop0(int[] values) {
        cop0.loadRawRegisters(values);
    }

    private int[] copyGteData() {
        return gte.copyRawDataRegisters();
    }

    private int[] copyGteControl() {
        return gte.copyRawControlRegisters();
    }

    private static String encodeShortArray(short[] values) {
        byte[] bytes = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            bytes[i * 2] = (byte) values[i];
            bytes[i * 2 + 1] = (byte) (values[i] >>> 8);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static short[] decodeShortArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        short[] values = new short[bytes.length / 2];
        for (int i = 0; i < values.length; i++) {
            values[i] = (short) ((bytes[i * 2] & 0xFF) | ((bytes[i * 2 + 1] & 0xFF) << 8));
        }
        return values;
    }


    private void submitAudio() {
        short[] interleaved = spu.drainMixedSamples();
        if (interleaved.length == 0) {
            return;
        }
        audioBackend.submitSamples(interleaved);
    }

    private void runLoop() {
        StallWatchdog activeWatchdog = null;
        try {
            synchronized (machineLock) {
                loadBios();
                if (renderBackend != null) {
                    renderBackend.open();
                    renderBackend.requestAttention();
                }
                if (audioBackend != null) {
                    audioBackend.open();
                }
                if (gamepadBackend != null) {
                    gamepadBackend.open();
                }
                bootDirectExecutableIfNeeded();
            }
            publishStallProgress();
            activeWatchdog = startStallWatchdog(Thread.currentThread(), true);
            // Keep batches shorter than a video field.
            final int cyclesPerSlice = 120_000;
            int lastPresentedFrame = -1;
            int lastObservedFrame = gpu.frameCounter();
            while (!stopRequested.get()) {
                if (renderBackend != null) {
                    renderBackend.processEvents();
                }
                if (state == EmulationState.PAUSED) {
                    runObserver(idleListener, "Idle observer");
                    Thread.sleep(5);
                    continue;
                }
                int frameNotifications = 0;
                synchronized (machineLock) {
                    pollGamepad();
                    int elapsedSystemCycles = 0;
                    while (elapsedSystemCycles < cyclesPerSlice) {
                        int systemCycles = stepMachine();
                        elapsedSystemCycles += systemCycles;
                        int observedFrame = gpu.frameCounter();
                        if (observedFrame != lastObservedFrame) {
                            lastObservedFrame = observedFrame;
                            frameNotifications++;
                        }
                        if (stopRequested.get()) {
                            break;
                        }
                    }
                    flushDeferredPrimaryCycles();
                    if (!biosHandoffLogged && cpu.pc() < 0xBFC0_0000) {
                        biosHandoffLogged = true;
                        Log.info("BIOS handoff detected: pc=0x" + Integer.toHexString(cpu.pc())
                            + ", game=" + (loadedGameTitle == null ? "<none>" : loadedGameTitle));
                    }
                    if (Log.isDebugEnabled()
                        && (lastPc ^ cpu.pc()) != 0
                        && (cpu.pc() == 0 || cpu.pc() == 0xA0 || cpu.pc() == 0xB0 || cpu.pc() == 0xC0)) {
                        Log.debug("CPU entered BIOS call table region pc=0x" + Integer.toHexString(cpu.pc())
                            + " fn=0x" + Integer.toHexString(cpu.register(9))
                            + " a0=0x" + Integer.toHexString(cpu.register(4))
                            + " a1=0x" + Integer.toHexString(cpu.register(5))
                            + " a2=0x" + Integer.toHexString(cpu.register(6))
                            + " v0=0x" + Integer.toHexString(cpu.register(2)));
                    }
                    lastPc = cpu.pc();
                    if (renderBackend != null) {
                        if (renderBackend.isRenderSurfaceAvailable()) {
                            int currentFrame = gpu.frameCounter();
                            if (currentFrame != lastPresentedFrame) {
                                renderBackend.presentFrame(gpu.captureFrame());
                                lastPresentedFrame = currentFrame;
                            }
                        }
                    }
                    // Audio submission may wait for a backend buffer.
                    if (audioBackend != null) {
                        submitAudio();
                    } else {
                        spu.discardMixedSamples();
                    }
                    if ((++sliceCounter % 2400) == 0 && Log.isDebugEnabled()) {
                        Log.debug("Emu heartbeat: pc=0x" + Integer.toHexString(cpu.pc())
                            + ", gpuFrame=" + gpu.frameCounter()
                            + ", gpuStatus=0x" + Integer.toHexString(gpu.status())
                            + ", gpuCmd=0x" + Integer.toHexString(gpu.command())
                            + ", dma2MADR=0x" + Integer.toHexString(dma.read32(0x1F8010A0))
                            + ", dma2BCR=0x" + Integer.toHexString(dma.read32(0x1F8010A4))
                            + ", dma2CHCR=0x" + Integer.toHexString(dma.read32(0x1F8010A8))
                            + ", dma3MADR=0x" + Integer.toHexString(dma.read32(0x1F8010B0))
                            + ", dma3BCR=0x" + Integer.toHexString(dma.read32(0x1F8010B4))
                            + ", dma3CHCR=0x" + Integer.toHexString(dma.read32(0x1F8010B8))
                            + ", dmaDPCR=0x" + Integer.toHexString(dma.read32(0x1F8010F0))
                            + ", cdStatus=0x" + Integer.toHexString(cdrom.read8(0x1F801800))
                            + ", mountedImage=" + cdrom.mountedImage()
                            + ", biosHandoff=" + biosHandoffLogged);
                    }
                }
                // Callbacks may read memory again.
                while (frameNotifications-- > 0) {
                    runObserver(frameListener, "Frame observer");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            Log.error("Emulation loop crashed", ex);
        } finally {
            boolean restoreInterrupt = Thread.interrupted();
            try {
                if (activeWatchdog != null) {
                    activeWatchdog.close();
                }
                try {
                    sio.flush();
                } catch (IOException ex) {
                    Log.error("Failed to flush memory cards", ex);
                }
                closeBackend(audioBackend, "audio backend");
                closeBackend(gamepadBackend, "gamepad backend");
                closeBackend(renderBackend, "render backend");
            } finally {
                state = EmulationState.STOPPED;
                emulationThread = null;
                terminationFuture.complete(null);
                Runnable listener = stopListener;
                if (listener != null) {
                    try {
                        listener.run();
                    } catch (RuntimeException ex) {
                        Log.error("Emulation stop listener failed", ex);
                    }
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void closeBackend(AutoCloseable backend, String name) {
        if (backend == null) {
            return;
        }
        try {
            backend.close();
        } catch (Throwable failure) {
            Log.error("Failed to close " + name, failure);
        }
    }

    private void runObserver(Runnable observer, String name) {
        if (observer == null) {
            return;
        }
        try {
            observer.run();
        } catch (RuntimeException ex) {
            if (frameListener == observer) {
                frameListener = null;
            }
            if (idleListener == observer) {
                idleListener = null;
            }
            Log.warn(name + " disabled after failure: " + ex.getMessage());
        }
    }

    private void pollGamepad() {
        if (gamepadBackend != null) {
            gamepadBackend.poll((pressedMask, leftX, leftY, rightX, rightY) -> {
                gamepadPadMask = pressedMask;
                gamepadLeftX = leftX;
                gamepadLeftY = leftY;
                gamepadRightX = rightX;
                gamepadRightY = rightY;
            });
        }
        sio.setControllerState(
            keyboardPadMask.get() | gamepadPadMask,
            gamepadLeftX,
            gamepadLeftY,
            gamepadRightX,
            gamepadRightY
        );
    }

    private void bootDirectExecutableIfNeeded() {
        PsxExecutable executable = directExecutable;
        if (executable == null) {
            return;
        }

        int warmupCycles = warmupBiosForExecutableBoot();

        bus.completeCpuWritesBeforeHostMemoryReplacement();
        loadExecutableBody(executable.loadAddress(), executable.body());
        zeroExecutableRegion(executable.memfillAddress(), executable.memfillSize());

        int[] registers = cpu.copyRegisters();
        registers[4] = 1;
        registers[5] = 0;
        registers[28] = executable.initialGp();
        if (executable.hasStack()) {
            int stack = executable.initialSp();
            registers[29] = stack;
            registers[30] = stack;
        }
        cpu.loadRegisters(registers);
        cpu.setHi(0);
        cpu.setLo(0);
        cpu.setPcState(executable.initialPc(), executable.initialPc() + 4, false);
        biosHandoffLogged = cpu.pc() < 0xBFC0_0000;

        Log.info("Direct PS-X EXE boot complete: path=" + executable.path()
            + ", entry=0x" + Integer.toHexString(executable.initialPc())
            + ", load=0x" + Integer.toHexString(executable.loadAddress())
            + ", bytes=0x" + Integer.toHexString(executable.fileSize())
            + ", stack=" + (executable.hasStack() ? ("0x" + Integer.toHexString(executable.initialSp())) : "<unchanged>")
            + ", biosWarmupCycles=" + warmupCycles);
    }

    private void loadExecutableBody(int address, byte[] body) {
        int uncachedAddress = 0xA000_0000 | (address & 0x1FFF_FFFF);
        for (int i = 0; i < body.length; i++) {
            bus.write8(uncachedAddress + i, body[i] & 0xFF);
        }
        bus.invalidateInstructionCacheRange(address, body.length);
    }

    private void zeroExecutableRegion(int address, int size) {
        int uncachedAddress = 0xA000_0000 | (address & 0x1FFF_FFFF);
        for (int i = 0; i < size; i++) {
            bus.write8(uncachedAddress + i, 0);
        }
        bus.invalidateInstructionCacheRange(address, size);
    }

    private int warmupBiosForExecutableBoot() {
        final int maxWarmupCycles = 4_000_000;
        int elapsed = 0;
        // The BIOS temporarily jumps into uncached RAM while it initializes caches.
        while (elapsed < maxWarmupCycles && !stopRequested.get()) {
            elapsed += stepMachine();
        }
        flushDeferredPrimaryCycles();
        Log.info("Direct PS-X EXE BIOS warmup finished: cycles=" + elapsed
            + ", pc=0x" + Integer.toHexString(cpu.pc())
            + ", gpuStatus=0x" + Integer.toHexString(gpu.status())
            + ", cdMounted=" + cdrom.mountedImage());
        return elapsed;
    }

    private int stepMachine() {
        cpu.step();
        if ((++stallStepCounter & STALL_PROGRESS_MASK) == 0) {
            publishStallProgress();
        }
        return cpu.lastStepSystemCycles();
    }

    private void publishStallProgress() {
        stallHeartbeatPc = cpu.pc();
        stallHeartbeatSystemCycles = scheduler.systemCycles();
        stallHeartbeatSteps = stallStepCounter;
        stallHeartbeatNanos = System.nanoTime();
    }

    private synchronized StallWatchdog startStallWatchdog(Thread target, boolean requireRunning) {
        if (requireRunning && !Boolean.parseBoolean(
            System.getProperty("psxj.stallDiagnostics", "true"))) {
            return null;
        }
        StallWatchdog previous = stallWatchdog;
        if (previous != null) {
            previous.close();
        }
        publishStallProgress();
        StallWatchdog watchdog = new StallWatchdog(this, target, requireRunning);
        stallWatchdog = watchdog;
        watchdog.start();
        return watchdog;
    }

    private String stallMachineSnapshot() {
        return "irq=0x" + Integer.toHexString(interrupts.status())
            + "/0x" + Integer.toHexString(interrupts.mask())
            + " pending=" + interrupts.describePending()
            + " gpu=0x" + Integer.toHexString(gpu.status())
            + " frame=" + gpu.frameCounter()
            + " dma=0x" + Integer.toHexString(dma.read32(0x1F80_10F0))
            + "/0x" + Integer.toHexString(dma.read32(0x1F80_10F4))
            + " cdAudio=" + cdrom.audioClockCoupled()
            + " cdda=" + cdrom.cddaPlaying()
            + " spuCdFrames=" + spu.queuedCdAudioFrames()
            + " mdec=0x" + Integer.toHexString(mdec.status());
    }

    @Override
    public void close() {
        stop();
        synchronized (machineLock) {
            cdrom.close();
        }
    }

    private enum StallArea {
        CPU, GTE, GPU, CD, SPU, MDEC, DMA, BUS, SCHEDULER, AUDIO, VIDEO, IO, LOCK, OTHER
    }

    private static final class StallWatchdog implements AutoCloseable, Runnable {

        private static final long POLL_NANOS = 10_000_000L;

        private final PsxEmulator emulator;
        private final Thread target;
        private final boolean requireRunning;
        private final long thresholdNanos;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread thread;
        private final ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        private final EnumMap<StallArea, Integer> samples = new EnumMap<>(StallArea.class);

        private boolean stalled;
        private long stallStartedNanos;
        private long stallStartedCpuNanos;
        private long stallStartedSteps;
        private long stallStartedCycles;
        private int stallStartedPc;
        private long stallGcCount;
        private long stallGcMillis;
        private int totalSamples;
        private String topFrame = "<unavailable>";
        private Thread.State sampledThreadState = Thread.State.NEW;

        private StallWatchdog(PsxEmulator emulator, Thread target, boolean requireRunning) {
            this.emulator = emulator;
            this.target = target;
            this.requireRunning = requireRunning;
            long thresholdMillis = Long.getLong("psxj.stallThresholdMs", 50L);
            thresholdNanos = Math.max(10L, thresholdMillis) * 1_000_000L;
            thread = Thread.ofPlatform().daemon().name("psxj-stall-watchdog").unstarted(this);
        }

        private void start() {
            thread.start();
            Log.info("Stall diagnostics enabled: thresholdMs=" + thresholdNanos / 1_000_000L);
        }

        @Override
        public void run() {
            long lastHeartbeat = emulator.stallHeartbeatNanos;
            while (!closed.get() && target.isAlive()) {
                LockSupport.parkNanos(POLL_NANOS);
                if (closed.get()) {
                    break;
                }
                if (requireRunning && emulator.state != EmulationState.RUNNING) {
                    reset();
                    lastHeartbeat = emulator.stallHeartbeatNanos;
                    continue;
                }
                long now = System.nanoTime();
                long heartbeat = emulator.stallHeartbeatNanos;
                if (heartbeat != lastHeartbeat) {
                    if (stalled) {
                        report(now);
                    }
                    lastHeartbeat = heartbeat;
                }
                if (now - heartbeat >= thresholdNanos) {
                    if (!stalled) {
                        beginStall(heartbeat);
                    }
                    sampleTarget();
                }
            }
            if (stalled) {
                report(System.nanoTime());
            }
        }

        private void beginStall(long heartbeat) {
            stalled = true;
            stallStartedNanos = heartbeat;
            stallStartedSteps = emulator.stallHeartbeatSteps;
            stallStartedCycles = emulator.stallHeartbeatSystemCycles;
            stallStartedPc = emulator.stallHeartbeatPc;
            stallStartedCpuNanos = cpuTime();
            stallGcCount = gcCount();
            stallGcMillis = gcMillis();
            samples.clear();
            totalSamples = 0;
            topFrame = "<unavailable>";
        }

        private void sampleTarget() {
            sampledThreadState = target.getState();
            StackTraceElement[] trace = target.getStackTrace();
            if (trace.length == 0) {
                samples.merge(sampledThreadState == Thread.State.BLOCKED
                    ? StallArea.LOCK : StallArea.OTHER, 1, Integer::sum);
                totalSamples++;
                return;
            }
            topFrame = trace[0].toString();
            samples.merge(classify(trace, sampledThreadState), 1, Integer::sum);
            totalSamples++;
        }

        private void report(long now) {
            long endSteps = emulator.stallHeartbeatSteps;
            long endCycles = emulator.stallHeartbeatSystemCycles;
            int endPc = emulator.stallHeartbeatPc;
            long durationMillis = Math.max(0L, now - stallStartedNanos) / 1_000_000L;
            long cpuNanos = cpuTime();
            long cpuMillis = stallStartedCpuNanos < 0 || cpuNanos < 0
                ? -1L : Math.max(0L, cpuNanos - stallStartedCpuNanos) / 1_000_000L;
            long gcCount = Math.max(0L, gcCount() - stallGcCount);
            long gcMillis = Math.max(0L, gcMillis() - stallGcMillis);
            String message = "Emulation stall: wallMs=" + durationMillis
                + " sampledCpuMs=" + cpuMillis
                + " threadState=" + sampledThreadState
                + " pc=0x" + Integer.toHexString(stallStartedPc)
                + "->0x" + Integer.toHexString(endPc)
                + " samePc=" + (stallStartedPc == endPc)
                + " steps=" + Math.max(0L, endSteps - stallStartedSteps)
                + " cycles=" + Math.max(0L, endCycles - stallStartedCycles)
                + " samples={" + formatSamples() + "}"
                + " gc=" + gcCount + "/" + gcMillis + "ms"
                + " top=" + topFrame
                + " " + emulator.stallMachineSnapshot();
            if (requireRunning) {
                Log.warn(message);
            } else {
                System.err.println(message);
            }
            reset();
        }

        private String formatSamples() {
            StringBuilder result = new StringBuilder();
            for (StallArea area : StallArea.values()) {
                int count = samples.getOrDefault(area, 0);
                if (!result.isEmpty()) {
                    result.append(',');
                }
                double percent = totalSamples == 0 ? 0.0 : count * 100.0 / totalSamples;
                result.append(area).append('=').append(String.format(Locale.ROOT, "%.0f%%", percent));
            }
            return result.toString();
        }

        private long cpuTime() {
            return threadMxBean.isThreadCpuTimeSupported()
                ? threadMxBean.getThreadCpuTime(target.threadId()) : -1L;
        }

        private static long gcCount() {
            long total = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                total += Math.max(0L, bean.getCollectionCount());
            }
            return total;
        }

        private static long gcMillis() {
            long total = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                total += Math.max(0L, bean.getCollectionTime());
            }
            return total;
        }

        private static StallArea classify(StackTraceElement[] trace, Thread.State state) {
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING) {
                return StallArea.LOCK;
            }
            for (StackTraceElement frame : trace) {
                String name = frame.getClassName();
                if (name.contains(".devices.Gpu")) return StallArea.GPU;
                if (name.contains("CdRom") || name.contains("XaAdpcm")) return StallArea.CD;
                if (name.contains(".devices.Spu")) return StallArea.SPU;
                if (name.contains(".devices.Mdec")) return StallArea.MDEC;
                if (name.contains("DmaController") || name.contains("DmaChannel")) return StallArea.DMA;
                if (name.contains(".gte.") || name.endsWith(".Gte")) return StallArea.GTE;
                if (name.contains("R3000Cpu")) return StallArea.CPU;
                if (name.endsWith(".Bus")) return StallArea.BUS;
                if (name.contains("CycleScheduler")) return StallArea.SCHEDULER;
                if (name.contains("audio") || name.contains("OpenAL")) return StallArea.AUDIO;
                if (name.contains("render") || name.contains("lwjgl") || name.contains("opengl")) {
                    return StallArea.VIDEO;
                }
                if (name.startsWith("java.io.") || name.startsWith("java.nio.")) return StallArea.IO;
            }
            return StallArea.OTHER;
        }

        private void reset() {
            stalled = false;
            samples.clear();
            totalSamples = 0;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            thread.interrupt();
            if (emulator.stallWatchdog == this) {
                emulator.stallWatchdog = null;
            }
        }
    }

}
