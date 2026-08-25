package nanolive.psxj.emu.devices;

public final class TimerController {
    private static final int TIMER_BASE = 0x1F80_1100;
    private static final int[] IRQ_BITS = {4, 5, 6};
    private static final int MODE_SYNC_ENABLE = 1 << 0;
    private static final int MODE_RESET_ON_TARGET = 1 << 3;
    private static final int MODE_IRQ_ON_TARGET = 1 << 4;
    private static final int MODE_IRQ_ON_OVERFLOW = 1 << 5;
    private static final int MODE_IRQ_REPEAT = 1 << 6;
    private static final int MODE_IRQ_TOGGLE = 1 << 7;
    private static final int MODE_NO_IRQ_REQUEST = 1 << 10;
    private static final int MODE_REACHED_TARGET = 1 << 11;
    private static final int MODE_REACHED_OVERFLOW = 1 << 12;
    private static final int NTSC_VIDEO_CLOCK_NUMERATOR = 715_909;
    private static final int VIDEO_CLOCK_DENOMINATOR = 451_584;
    private final InterruptController interruptController;
    private final Counter[] counters = new Counter[] {new Counter(), new Counter(), new Counter()};
    private Gpu gpu;
    private long dotClockAccumulator0;
    private long dotClockAccumulator2;
    private boolean lastHblank;
    private boolean lastVblank;
    private int transientCounterMask;
    private int resetAppliedCounterMask;
    private boolean fastConfiguration;
    private final int[] ticksUntilCounterEvent = new int[3];
    private final int[] clockSources = new int[3];
    private int synchronizedCounterMask;

    public TimerController(InterruptController interruptController) {
        this.interruptController = interruptController;
        this.recomputeFastPath();
    }

    public void setGpu(Gpu gpu) {
        this.gpu = gpu;
        this.lastHblank = gpu != null && gpu.inHblank();
        this.lastVblank = gpu != null && gpu.inVblank();
    }

    public int read16(int address) {
        int index = (address - TIMER_BASE) / 0x10;
        int reg = (address - TIMER_BASE) & 0xF;
        if (index < 0 || index >= counters.length) {
            return 0;
        }
        Counter counter = counters[index];
        int value = switch (reg) {
            case 0x0 -> counter.current & 0xFFFF;
            case 0x4 -> {
                int mode = composeMode(counter);
                counter.targetReached = false;
                counter.overflowReached = false;
                yield mode;
            }
            case 0x8 -> counter.target & 0xFFFF;
            default -> 0;
        };
        return value;
    }

    public void write16(int address, int value) {
        int index = (address - TIMER_BASE) / 0x10;
        int reg = (address - TIMER_BASE) & 0xF;
        if (index < 0 || index >= counters.length) {
            return;
        }
        Counter counter = counters[index];
        int v = value & 0xFFFF;
        switch (reg) {
            case 0x0 -> {
                counter.current = v;
                counter.holdCycles = 2;
                counter.resetDelayCycles = 0;
                counter.postResetHoldCycles = 0;
                counter.resetAppliedThisCycle = false;
            }
            case 0x4 -> {
                counter.mode = v & 0x03FF;
                counter.current = 0;
                counter.targetReached = false;
                counter.overflowReached = false;
                counter.irqLatched = false;
                counter.irqRequestActive = false;
                counter.irqPulseCycles = 0;
                counter.pausedByGate = false;
                counter.syncStarted = false;
                counter.holdCycles = 2;
                counter.resetDelayCycles = 0;
                counter.postResetHoldCycles = 0;
                counter.resetAppliedThisCycle = false;
                interruptController.clear(IRQ_BITS[index]);
            }
            case 0x8 -> counter.target = v;
            default -> {
            }
        }
        refreshTransientMasks(index);
        recomputeFastPath();
    }

    public void tick(int cycles) {
        if (cycles <= 0) {
            return;
        }
        if (canFastTick(cycles)) {
            fastTick(cycles);
            lastHblank = gpu != null && gpu.inHblank();
            lastVblank = gpu != null && gpu.inVblank();
            return;
        }
        for (int i = 0; i < cycles; i++) {
            tickOneSystemCycle();
        }
    }

    // Check timer events before batching clocks.
    public boolean permitsBatchedGpuInterval(int cycles) {
        if (cycles <= 1
                || !this.fastConfiguration
                || (this.transientCounterMask | this.resetAppliedCounterMask) != 0
                || this.counters[0].resetAppliedThisCycle
                || this.counters[1].resetAppliedThisCycle
                || this.counters[2].resetAppliedThisCycle) {
            return false;
        }
        int maximumCounter0Ticks =
                this.clockSources[0] == 0 || this.clockSources[0] == 2 ? cycles : cycles * 2;
        int maximumCounter1Ticks =
                this.clockSources[1] == 0 || this.clockSources[1] == 2
                        ? cycles
                        : (this.gpu == null ? 0 : this.gpu.hblankRisesWithinSystemClocks(cycles));
        int maximumCounter2Ticks =
                this.clockSources[2] == 0 || this.clockSources[2] == 1 ? cycles : (cycles + 7) / 8;
        return ticksAfterHold(0, maximumCounter0Ticks) < this.ticksUntilCounterEvent[0]
                && ticksAfterHold(1, maximumCounter1Ticks) < this.ticksUntilCounterEvent[1]
                && ticksAfterHold(2, maximumCounter2Ticks) < this.ticksUntilCounterEvent[2];
    }

    public void tickBatchedGpuInterval(int cycles, int dotClockTicks, int hblankRises) {
        if (cycles <= 0) {
            return;
        }
        if (!fastConfiguration || (transientCounterMask | resetAppliedCounterMask) != 0) {
            throw new IllegalStateException("Timer interval was not preflighted for batching");
        }
        int ticks0 = clockSources[0] == 0 || clockSources[0] == 2
            ? cycles : Math.max(0, dotClockTicks);
        int ticks1 = clockSources[1] == 0 || clockSources[1] == 2
            ? cycles : Math.max(0, hblankRises);
        int ticks2;
        if (clockSources[2] == 0 || clockSources[2] == 1) {
            ticks2 = cycles;
        } else {
            long accumulated = (long) Math.floorMod(dotClockAccumulator2, 8) + cycles;
            ticks2 = (int) (accumulated / 8L);
            dotClockAccumulator2 = accumulated % 8L;
        }
        fastAdvanceCounter(0, counters[0], ticks0);
        fastAdvanceCounter(1, counters[1], ticks1);
        fastAdvanceCounter(2, counters[2], ticks2);
        lastHblank = gpu != null && gpu.inHblank();
        lastVblank = gpu != null && gpu.inVblank();
    }

    private boolean canFastTick(int cycles) {
        if (this.fastConfiguration
                && (this.transientCounterMask | this.resetAppliedCounterMask) == 0
                && !this.counters[0].resetAppliedThisCycle
                && !this.counters[1].resetAppliedThisCycle
                && !this.counters[2].resetAppliedThisCycle) {
            int source0 = this.clockSource(0);
            int ticks0 = source0 != 0 && source0 != 2 ? this.dotClockTicks(cycles, false) : cycles;
            int source1 = this.clockSource(1);
            int ticks1 =
                    source1 != 0 && source1 != 2
                            ? (this.gpu == null ? 0 : this.gpu.hblankRisesLastTick())
                            : cycles;
            int source2 = this.clockSource(2);
            int ticks2 =
                    source2 != 0 && source2 != 1
                            ? (Math.floorMod(this.dotClockAccumulator2, 8) + cycles) / 8
                            : cycles;
            return ticksAfterHold(0, ticks0) < this.ticksUntilCounterEvent[0]
                    && ticksAfterHold(1, ticks1) < this.ticksUntilCounterEvent[1]
                    && ticksAfterHold(2, ticks2) < this.ticksUntilCounterEvent[2];
        } else {
            return false;
        }
    }

    private void fastTick(int cycles) {
        Counter counter0 = this.counters[0];
        int source0 = this.clockSource(0);
        int ticks0;
        if (source0 != 0 && source0 != 2) {
            ticks0 = this.dotClockTicks(cycles, true);
        } else {
            ticks0 = cycles;
        }

        fastAdvanceCounter(0, counter0, ticks0);
        Counter counter1 = this.counters[1];
        int source1 = this.clockSource(1);
        int ticks1 =
                source1 != 0 && source1 != 2
                        ? (this.gpu == null ? 0 : this.gpu.hblankRisesLastTick())
                        : cycles;
        fastAdvanceCounter(1, counter1, ticks1);
        Counter counter2 = this.counters[2];
        int source2 = this.clockSource(2);
        int ticks2;
        if (source2 != 0 && source2 != 1) {
            long accumulated = (long) (Math.floorMod(this.dotClockAccumulator2, 8) + cycles);
            ticks2 = (int) (accumulated / 8L);
            this.dotClockAccumulator2 = accumulated % 8L;
        } else {
            ticks2 = cycles;
        }

        fastAdvanceCounter(2, counter2, ticks2);
    }

    private int ticksAfterHold(int index, int sourceTicks) {
        return Math.max(0, sourceTicks - this.counters[index].holdCycles);
    }

    private void fastAdvanceCounter(int index, Counter counter, int sourceTicks) {
        int heldTicks = Math.min(sourceTicks, counter.holdCycles);
        counter.holdCycles -= heldTicks;
        int counterTicks = sourceTicks - heldTicks;
        counter.current = (counter.current + counterTicks) & 0xFFFF;
        this.ticksUntilCounterEvent[index] -= counterTicks;
    }

    private void recomputeFastPath() {
        this.fastConfiguration = true;
        this.synchronizedCounterMask = 0;

        for (int i = 0; i < this.counters.length; i++) {
            Counter counter = this.counters[i];
            this.clockSources[i] = (counter.mode >>> 8) & 0x3;
            if (requiresGateService(i, counter)) {
                this.synchronizedCounterMask |= 1 << i;
                this.fastConfiguration = false;
            }
            this.recomputeCounterDistance(i);
        }
    }

    private static boolean requiresGateService(int index, Counter counter) {
        if ((counter.mode & MODE_SYNC_ENABLE) == 0) {
            return false;
        }
        int syncMode = (counter.mode >>> 1) & 0x3;
        return index == 2 || syncMode != 3 || !counter.syncStarted;
    }

    private void recomputeCounterDistance(int index) {
        Counter counter = this.counters[index];
        this.ticksUntilCounterEvent[index] =
                Math.min(
                        distanceToValue(counter.current, counter.target & 0xFFFF),
                        distanceToValue(counter.current, 0xFFFF));
    }

    private static int distanceToValue(int current, int value) {
        int distance = (value - current) & 0xFFFF;
        return distance == 0 ? 0x1_0000 : distance;
    }

    private void tickOneSystemCycle() {
        boolean hblank = this.gpu != null && this.gpu.inHblank();
        boolean vblank = this.gpu != null && this.gpu.inVblank();
        boolean hblankRise = hblank && !this.lastHblank;
        boolean vblankRise = vblank && !this.lastVblank;
        boolean hblankFall = !hblank && this.lastHblank;
        boolean vblankFall = !vblank && this.lastVblank;
        this.lastHblank = hblank;
        this.lastVblank = vblank;
        if (this.synchronizedCounterMask == 0
                && (this.transientCounterMask | this.resetAppliedCounterMask) == 0
                && this.counters[0].holdCycles == 0
                && this.counters[1].holdCycles == 0
                && this.counters[2].holdCycles == 0) {
            int source0 = this.clockSources[0];
            boolean systemClock0 = source0 == 0 || source0 == 2;
            boolean dotClockEdge = !systemClock0 && this.advanceDotClock();
            this.tickUngatedCounter(0, systemClock0 || dotClockEdge);
            this.tickUngatedCounter(
                    1, this.clockSources[1] == 0 || this.clockSources[1] == 2 || hblankRise);
            this.tickUngatedCounter(2, this.shouldClockCounter2());
        } else {
            if ((this.transientCounterMask | this.resetAppliedCounterMask) != 0) {
                this.advanceTransientState();
            }

            int source0 = this.clockSources[0];
            boolean systemClock0 = source0 == 0 || source0 == 2;
            boolean dotClockEdge;
            if (systemClock0) {
                dotClockEdge = false;
            } else {
                dotClockEdge = this.advanceDotClock();
            }

            this.tickCounter(
                    0,
                    systemClock0 || dotClockEdge,
                    hblankRise,
                    hblankFall,
                    vblankRise,
                    vblankFall,
                    hblank,
                    vblank);
            this.tickCounter(
                    1,
                    this.shouldClockCounter1(hblankRise),
                    hblankRise,
                    hblankFall,
                    vblankRise,
                    vblankFall,
                    hblank,
                    vblank);
            this.tickCounter(
                    2,
                    this.shouldClockCounter2(),
                    hblankRise,
                    hblankFall,
                    vblankRise,
                    vblankFall,
                    hblank,
                    vblank);
        }
    }

    private void tickUngatedCounter(int index, boolean clockEdge) {
        if (!clockEdge) {
            return;
        }
        Counter counter = counters[index];
        int distance = ticksUntilCounterEvent[index];
        if (distance > 1) {
            counter.current = (counter.current + 1) & 0xFFFF;
            ticksUntilCounterEvent[index] = distance - 1;
            return;
        }

        counter.current = (counter.current + 1) & 0xFFFF;
        boolean hitTarget = counter.current == (counter.target & 0xFFFF);
        boolean overflow = counter.current == 0xFFFF;
        if (hitTarget) {
            counter.targetReached = true;
            if ((counter.mode & MODE_RESET_ON_TARGET) != 0) {
                scheduleReset(index, counter, 1);
            }
        }
        if (overflow) {
            counter.overflowReached = true;
            if ((counter.mode & MODE_RESET_ON_TARGET) == 0 || !hitTarget) {
                scheduleReset(index, counter, 0);
            }
        }
        if ((hitTarget && (counter.mode & MODE_IRQ_ON_TARGET) != 0)
                || (overflow && (counter.mode & MODE_IRQ_ON_OVERFLOW) != 0)) {
            raiseIrq(index, counter);
        }
        recomputeCounterDistance(index);
    }

    private void advanceTransientState() {
        int resetMask = resetAppliedCounterMask;
        while (resetMask != 0) {
            int index = Integer.numberOfTrailingZeros(resetMask);
            counters[index].resetAppliedThisCycle = false;
            resetMask &= resetMask - 1;
        }
        resetAppliedCounterMask = 0;

        int activeMask = transientCounterMask;
        if (activeMask == 0) {
            return;
        }
        int nextMask = 0;
        for (int i = 0; i < counters.length; i++) {
            if ((activeMask & (1 << i)) == 0) {
                continue;
            }
            Counter counter = counters[i];
            if (counter.resetDelayCycles > 0) {
                counter.resetDelayCycles--;
                if (counter.resetDelayCycles == 0) {
                    counter.current = 0;
                    counter.holdCycles = Math.max(counter.holdCycles, counter.postResetHoldCycles);
                    counter.postResetHoldCycles = 0;
                    counter.resetAppliedThisCycle = true;
                    resetAppliedCounterMask |= 1 << i;
                    recomputeCounterDistance(i);
                }
            }
            if ((counter.mode & MODE_IRQ_TOGGLE) == 0 && counter.irqPulseCycles > 0) {
                counter.irqPulseCycles--;
                if (counter.irqPulseCycles == 0 && counter.irqRequestActive) {
                    counter.irqRequestActive = false;
                    interruptController.clear(IRQ_BITS[i]);
                }
            }
            if (counter.resetDelayCycles > 0 || counter.irqPulseCycles > 0) {
                nextMask |= 1 << i;
            }
        }
        transientCounterMask = nextMask;
    }

    private boolean advanceDotClock() {
        return this.dotClockTicks(1, true) != 0;
    }

    private int dotClockTicks(int systemCycles, boolean updateAccumulator) {
        if (gpu != null) {
            return gpu.dotClockTicksLastTick();
        }
        long numerator = videoClockNumerator();
        long denominator = (long) (gpu == null ? VIDEO_CLOCK_DENOMINATOR : gpu.crtcClockDenominator())
                * dotClockDivider();
        long accumulator = Math.floorMod(dotClockAccumulator0, denominator)
                + (long) systemCycles * numerator;
        int ticks = (int) (accumulator / denominator);
        if (updateAccumulator) {
            dotClockAccumulator0 = accumulator % denominator;
        }
        return ticks;
    }

    private long videoClockNumerator() {
        return gpu == null ? NTSC_VIDEO_CLOCK_NUMERATOR : gpu.crtcClockNumerator();
    }

    private int dotClockDivider() {
        int displayMode = gpu == null ? 0 : gpu.displayMode();
        int horizontalMode = (displayMode & 0x3) | (((displayMode >>> 6) & 0x1) << 2);
        return switch (horizontalMode) {
            case 0 -> 10;
            case 1 -> 8;
            case 2 -> 5;
            case 3 -> 4;
            case 4, 5, 6, 7 -> 7;
            default -> throw new AssertionError();
        };
    }

    private boolean shouldClockCounter1(boolean hblankRise) {
        int source = this.clockSources[1];
        return source == 0 || source == 2 || hblankRise;
    }

    private boolean shouldClockCounter2() {
        int source = this.clockSources[2];
        if (source == 0 || source == 1) {
            return true;
        }
        dotClockAccumulator2++;
        return (dotClockAccumulator2 & 7) == 0;
    }

    private void tickCounter(
            int index,
            boolean clockEdge,
            boolean hblankRise,
            boolean hblankFall,
            boolean vblankRise,
            boolean vblankFall,
            boolean hblank,
            boolean vblank) {
        Counter counter = counters[index];
        handleGate(index, counter, hblankRise, hblankFall, vblankRise, vblankFall, hblank, vblank);
        if (!clockEdge || counter.pausedByGate || counter.resetAppliedThisCycle) {
            return;
        }
        if (counter.holdCycles > 0) {
            counter.holdCycles--;
            return;
        }

        counter.current = (counter.current + 1) & 0xFFFF;
        boolean hitTarget = counter.current == (counter.target & 0xFFFF);
        boolean overflow = counter.current == 0xFFFF;
        if (hitTarget) {
            counter.targetReached = true;
            if ((counter.mode & MODE_RESET_ON_TARGET) != 0) {
                scheduleReset(index, counter, 1);
            }
        }
        if (overflow) {
            counter.overflowReached = true;
            if ((counter.mode & MODE_RESET_ON_TARGET) == 0 || !hitTarget) {
                scheduleReset(index, counter, 0);
            }
        }
        boolean targetIrq = hitTarget && (counter.mode & MODE_IRQ_ON_TARGET) != 0;
        boolean overflowIrq = overflow && (counter.mode & MODE_IRQ_ON_OVERFLOW) != 0;
        if (targetIrq || overflowIrq) {
            raiseIrq(index, counter);
        }
        if (fastConfiguration) {
            recomputeCounterDistance(index);
        }
    }

    private void scheduleReset(int index, Counter counter, int postResetHoldCycles) {
        counter.resetDelayCycles = 1;
        counter.postResetHoldCycles = Math.max(counter.postResetHoldCycles, postResetHoldCycles);
        this.transientCounterMask |= 1 << index;
    }

    private void handleGate(
            int index,
            Counter counter,
            boolean hblankRise,
            boolean hblankFall,
            boolean vblankRise,
            boolean vblankFall,
            boolean hblank,
            boolean vblank) {
        if ((counter.mode & MODE_SYNC_ENABLE) == 0) {
            counter.pausedByGate = false;
            return;
        }
        int syncMode = (counter.mode >>> 1) & 0x3;
        boolean signal = index == 0 ? hblank : (index == 1 ? vblank : hblank);
        boolean rise = index == 0 ? hblankRise : (index == 1 ? vblankRise : hblankRise);
        if (index == 2) {
            counter.pausedByGate = syncMode == 0 || syncMode == 3;
            return;
        }
        switch (syncMode) {
            case 0 -> counter.pausedByGate = signal;
            case 1 -> {
                if (rise) {
                    resetFromGate(counter);
                }
                counter.pausedByGate = false;
            }
            case 2 -> {
                if (rise) {
                    resetFromGate(counter);
                }
                counter.pausedByGate = !signal;
            }
            case 3 -> {
                if (!counter.syncStarted) {
                    counter.pausedByGate = true;
                    if (rise) {
                        counter.syncStarted = true;
                        counter.pausedByGate = false;
                        recomputeFastPath();
                    }
                } else {
                    counter.pausedByGate = false;
                }
            }
            default -> counter.pausedByGate = false;
        }
    }

    private void resetFromGate(Counter counter) {
        counter.current = 0;
        counter.resetDelayCycles = 0;
        counter.postResetHoldCycles = 0;
        counter.holdCycles = 0;
    }

    private void raiseIrq(int index, Counter counter) {
        boolean repeat = (counter.mode & MODE_IRQ_REPEAT) != 0;
        if (!repeat && counter.irqLatched) {
            return;
        }
        if ((counter.mode & MODE_IRQ_TOGGLE) != 0) {
            counter.irqRequestActive = !counter.irqRequestActive;
            if (counter.irqRequestActive) {
                interruptController.raise(IRQ_BITS[index]);
            } else {
                interruptController.clear(IRQ_BITS[index]);
            }
        } else {
            counter.irqRequestActive = true;
            counter.irqPulseCycles = 3;
            transientCounterMask |= 1 << index;
            interruptController.raise(IRQ_BITS[index]);
        }
        counter.irqLatched = true;
    }

    private void refreshTransientMasks(int index) {
        Counter counter = this.counters[index];
        int bit = 1 << index;
        if (counter.resetDelayCycles <= 0 && counter.irqPulseCycles <= 0) {
            this.transientCounterMask &= ~bit;
        } else {
            this.transientCounterMask |= bit;
        }

        if (counter.resetAppliedThisCycle) {
            this.resetAppliedCounterMask |= bit;
        } else {
            this.resetAppliedCounterMask &= ~bit;
        }
    }

    private int clockSource(int index) {
        return this.clockSources[index];
    }

    private int composeMode(Counter counter) {
        int mode = counter.mode & 0x03FF;
        if (!counter.irqRequestActive) {
            mode |= MODE_NO_IRQ_REQUEST;
        }

        if (counter.targetReached) {
            mode |= MODE_REACHED_TARGET;
        }

        if (counter.overflowReached) {
            mode |= MODE_REACHED_OVERFLOW;
        }

        return mode;
    }

    public State copyState() {
        State state = new State();
        state.dotClockAccumulator0 = this.dotClockAccumulator0;
        state.dotClockAccumulator2 = this.dotClockAccumulator2;
        state.lastHblank = this.lastHblank;
        state.lastVblank = this.lastVblank;
        state.counters = new CounterState[this.counters.length];

        for (int i = 0; i < this.counters.length; ++i) {
            state.counters[i] = this.counters[i].copyState();
        }

        return state;
    }

    public void loadState(State state) {
        if (state != null) {
            this.dotClockAccumulator0 = state.dotClockAccumulator0;
            this.dotClockAccumulator2 = state.dotClockAccumulator2;
            this.lastHblank = state.lastHblank;
            this.lastVblank = state.lastVblank;
            if (state.counters != null) {
                for (int i = 0; i < Math.min(this.counters.length, state.counters.length); ++i) {
                    this.counters[i].loadState(state.counters[i]);
                }
            }

            this.transientCounterMask = 0;
            this.resetAppliedCounterMask = 0;

            for (int i = 0; i < this.counters.length; ++i) {
                this.refreshTransientMasks(i);
            }

            this.recomputeFastPath();
        }
    }

    private static final class Counter {
        int current;
        int mode;
        int target;
        boolean targetReached;
        boolean overflowReached;
        boolean irqLatched;
        boolean irqRequestActive;
        int irqPulseCycles;
        boolean pausedByGate;
        boolean syncStarted;
        int holdCycles;
        int resetDelayCycles;
        int postResetHoldCycles;
        boolean resetAppliedThisCycle;

        CounterState copyState() {
            CounterState state = new CounterState();
            state.current = this.current;
            state.mode = this.mode;
            state.target = this.target;
            state.targetReached = this.targetReached;
            state.overflowReached = this.overflowReached;
            state.irqLatched = this.irqLatched;
            state.irqRequestActive = this.irqRequestActive;
            state.irqPulseCycles = this.irqPulseCycles;
            state.pausedByGate = this.pausedByGate;
            state.syncStarted = this.syncStarted;
            state.holdCycles = this.holdCycles;
            state.resetDelayCycles = this.resetDelayCycles;
            state.postResetHoldCycles = this.postResetHoldCycles;
            state.resetAppliedThisCycle = this.resetAppliedThisCycle;
            return state;
        }

        void loadState(CounterState state) {
            if (state != null) {
                this.current = state.current;
                this.mode = state.mode;
                this.target = state.target;
                this.targetReached = state.targetReached;
                this.overflowReached = state.overflowReached;
                this.irqLatched = state.irqLatched;
                this.irqRequestActive = state.irqRequestActive;
                this.irqPulseCycles = state.irqPulseCycles;
                this.pausedByGate = state.pausedByGate;
                this.syncStarted = state.syncStarted;
                this.holdCycles = state.holdCycles;
                this.resetDelayCycles = state.resetDelayCycles;
                this.postResetHoldCycles = state.postResetHoldCycles;
                this.resetAppliedThisCycle = state.resetAppliedThisCycle;
            }
        }
    }

    public static final class State {
        long dotClockAccumulator0;
        long dotClockAccumulator2;
        boolean lastHblank;
        boolean lastVblank;
        CounterState[] counters;
    }

    public static final class CounterState {
        int current;
        int mode;
        int target;
        boolean targetReached;
        boolean overflowReached;
        boolean irqLatched;
        boolean irqRequestActive;
        int irqPulseCycles;
        boolean pausedByGate;
        boolean syncStarted;
        int holdCycles;
        int resetDelayCycles;
        int postResetHoldCycles;
        boolean resetAppliedThisCycle;
    }
}
