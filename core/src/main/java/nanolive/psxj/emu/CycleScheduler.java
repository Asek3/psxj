package nanolive.psxj.emu;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

public final class CycleScheduler {

    private static final Phase[] PHASES = Phase.values();

    /** Device order at a shared clock boundary. */
    public enum Phase {
        DEVICE_CLOCK,
        AUDIO_CLOCK,
        BUS_ARBITRATION,
        COUNTER_LATCH,
        INTERRUPT_LATCH,
        CPU_OBSERVE
    }

    @FunctionalInterface
    public interface TickTarget {
        void tick(int cycles);
    }

    private final TickTarget[][] targetsByPhase = new TickTarget[PHASES.length][0];
    private final PriorityQueue<ScheduledEvent> events = new PriorityQueue<>(
        Comparator.comparingLong(ScheduledEvent::timestamp)
            .thenComparingInt(event -> event.phase().ordinal())
            .thenComparingLong(ScheduledEvent::sequence));
    private int overclockPercent = 100;
    private int maxSystemCycleQuantum = Integer.MAX_VALUE;
    private long scaleCarry;
    private long cpuCycles;
    private long systemCycles;
    private long nextEventSequence;
    private Phase activePhase;
    private TickTarget deviceClockTarget;
    private TickTarget audioClockTarget;
    private TickTarget busArbitrationTarget;
    private TickTarget counterLatchTarget;
    private TickTarget interruptLatchTarget;
    private TickTarget cpuObserveTarget;
    private TickTarget combinedPrimaryTarget;
    private boolean hasAdditionalTargets;

    public void addTarget(TickTarget target) {
        addTarget(Phase.DEVICE_CLOCK, target);
    }

    public void addTarget(Phase phase, TickTarget target) {
        if (target != null) {
            int phaseIndex = Objects.requireNonNull(phase).ordinal();
            TickTarget[] oldTargets = targetsByPhase[phaseIndex];
            TickTarget[] newTargets = Arrays.copyOf(oldTargets, oldTargets.length + 1);
            newTargets[oldTargets.length] = target;
            targetsByPhase[phaseIndex] = newTargets;
            hasAdditionalTargets = true;
        }
    }

    public void setPrimaryTargets(
        TickTarget deviceClockTarget,
        TickTarget audioClockTarget,
        TickTarget busArbitrationTarget,
        TickTarget counterLatchTarget,
        TickTarget interruptLatchTarget,
        TickTarget cpuObserveTarget
    ) {
        this.deviceClockTarget = deviceClockTarget;
        this.audioClockTarget = audioClockTarget;
        this.busArbitrationTarget = busArbitrationTarget;
        this.counterLatchTarget = counterLatchTarget;
        this.interruptLatchTarget = interruptLatchTarget;
        this.cpuObserveTarget = cpuObserveTarget;
    }

    // Fast path used when no scheduled event splits the interval.
    public void setCombinedPrimaryTarget(TickTarget combinedPrimaryTarget) {
        this.combinedPrimaryTarget = combinedPrimaryTarget;
    }

    public EventHandle schedule(long timestamp, Phase phase, Runnable action) {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(action);
        if (timestamp < systemCycles) {
            throw new IllegalArgumentException("Cannot schedule an event in the past");
        }
        if (timestamp == systemCycles && activePhase != null
            && phase.ordinal() < activePhase.ordinal()) {
            throw new IllegalArgumentException("The requested phase has already elapsed");
        }
        ScheduledEvent event = new ScheduledEvent(
            timestamp, phase, nextEventSequence++, action);
        events.add(event);
        return event;
    }

    public EventHandle scheduleAfter(long systemCycleDelay, Phase phase, Runnable action) {
        if (systemCycleDelay < 0) {
            throw new IllegalArgumentException("Negative event delay");
        }
        return schedule(Math.addExact(systemCycles, systemCycleDelay), phase, action);
    }

    public void setOverclockPercent(int overclockPercent) {
        this.overclockPercent = Math.max(1, overclockPercent);
        scaleCarry = Math.min(scaleCarry, this.overclockPercent - 1L);
    }

    public int overclockPercent() {
        return overclockPercent;
    }

    public void setMaxSystemCycleQuantum(int maxSystemCycleQuantum) {
        this.maxSystemCycleQuantum = maxSystemCycleQuantum <= 0
            ? Integer.MAX_VALUE
            : maxSystemCycleQuantum;
    }

    public int maxSystemCycleQuantum() {
        return maxSystemCycleQuantum;
    }

    public long cpuCycles() {
        return cpuCycles;
    }

    public long systemCycles() {
        return systemCycles;
    }

    public int advanceCpuCycles(int cycles) {
        // Events at the current timestamp fire even for a zero-cycle call.
        boolean noScheduledEvents = events.isEmpty();
        if (!noScheduledEvents) {
            dispatchCurrentTimestampEvents();
        }
        if (cycles <= 0) {
            return 0;
        }
        // Common 1:1 path: no scaling or event boundary to calculate.
        if (overclockPercent == 100
            && !hasAdditionalTargets
            && noScheduledEvents
            && cycles <= maxSystemCycleQuantum) {
            cpuCycles += cycles;
            systemCycles += cycles;
            if (combinedPrimaryTarget != null) {
                combinedPrimaryTarget.tick(cycles);
            } else {
                tickPrimaryTargets(cycles);
            }
            return cycles;
        }
        cpuCycles += cycles;
        int deviceCycles = scaleCpuToSystemCycles(cycles);
        if (deviceCycles == 0) {
            return 0;
        }
        int remaining = deviceCycles;
        int quantum = Math.max(1, maxSystemCycleQuantum);
        while (remaining > 0) {
            int step = nextStep(remaining, quantum);
            systemCycles += step;
            tickBoundary(step);
            remaining -= step;
        }
        return deviceCycles;
    }

    private int nextStep(int remaining, int quantum) {
        int step = Math.min(remaining, quantum);
        ScheduledEvent next = events.peek();
        if (next != null && next.timestamp > systemCycles) {
            long distance = next.timestamp - systemCycles;
            if (distance < step) {
                step = (int) distance;
            }
        }
        return Math.max(1, step);
    }

    private void tickBoundary(int cycles) {
        // Fixed production targets cannot re-enter the scheduler.
        if (!hasAdditionalTargets && events.isEmpty()) {
            if (combinedPrimaryTarget != null) {
                combinedPrimaryTarget.tick(cycles);
            } else {
                tickPrimaryTargets(cycles);
            }
            return;
        }

        activePhase = Phase.DEVICE_CLOCK;
        if (deviceClockTarget != null) {
            deviceClockTarget.tick(cycles);
        }
        if (hasAdditionalTargets) {
            tickAdditionalTargets(Phase.DEVICE_CLOCK, cycles);
        }
        dispatchDueEvents(Phase.DEVICE_CLOCK);

        activePhase = Phase.AUDIO_CLOCK;
        if (audioClockTarget != null) {
            audioClockTarget.tick(cycles);
        }
        if (hasAdditionalTargets) {
            tickAdditionalTargets(Phase.AUDIO_CLOCK, cycles);
        }
        dispatchDueEvents(Phase.AUDIO_CLOCK);

        activePhase = Phase.BUS_ARBITRATION;
        if (busArbitrationTarget != null) {
            busArbitrationTarget.tick(cycles);
        }
        if (hasAdditionalTargets) {
            tickAdditionalTargets(Phase.BUS_ARBITRATION, cycles);
        }
        dispatchDueEvents(Phase.BUS_ARBITRATION);

        activePhase = Phase.COUNTER_LATCH;
        if (counterLatchTarget != null) {
            counterLatchTarget.tick(cycles);
        }
        if (hasAdditionalTargets) {
            tickAdditionalTargets(Phase.COUNTER_LATCH, cycles);
        }
        dispatchDueEvents(Phase.COUNTER_LATCH);

        activePhase = Phase.INTERRUPT_LATCH;
        if (interruptLatchTarget != null) {
            interruptLatchTarget.tick(cycles);
        }
        if (hasAdditionalTargets) {
            tickAdditionalTargets(Phase.INTERRUPT_LATCH, cycles);
        }
        dispatchDueEvents(Phase.INTERRUPT_LATCH);

        activePhase = Phase.CPU_OBSERVE;
        if (cpuObserveTarget != null) {
            cpuObserveTarget.tick(cycles);
        }
        if (hasAdditionalTargets) {
            tickAdditionalTargets(Phase.CPU_OBSERVE, cycles);
        }
        dispatchDueEvents(Phase.CPU_OBSERVE);
        activePhase = null;
    }

    private void tickPrimaryTargets(int cycles) {
        if (deviceClockTarget != null) {
            deviceClockTarget.tick(cycles);
        }
        if (audioClockTarget != null) {
            audioClockTarget.tick(cycles);
        }
        if (busArbitrationTarget != null) {
            busArbitrationTarget.tick(cycles);
        }
        if (counterLatchTarget != null) {
            counterLatchTarget.tick(cycles);
        }
        if (interruptLatchTarget != null) {
            interruptLatchTarget.tick(cycles);
        }
        if (cpuObserveTarget != null) {
            cpuObserveTarget.tick(cycles);
        }
    }

    private void tickAdditionalTargets(Phase phase, int cycles) {
        TickTarget[] phaseTargets = targetsByPhase[phase.ordinal()];
        for (int i = 0; i < phaseTargets.length; i++) {
            phaseTargets[i].tick(cycles);
        }
    }

    private void dispatchDueEvents(Phase phase) {
        ScheduledEvent next = events.peek();
        if (next != null && next.timestamp <= systemCycles) {
            dispatchEvents(phase);
        }
    }

    private void dispatchEvents(Phase phase) {
        int dispatched = 0;
        while (true) {
            ScheduledEvent next = events.peek();
            if (next == null || next.timestamp > systemCycles || next.phase != phase) {
                return;
            }
            events.remove();
            if (!next.cancelled) {
                next.action.run();
            }
            if (++dispatched > 1_000_000) {
                throw new IllegalStateException("Non-converging same-timestamp event chain");
            }
        }
    }

    private void dispatchCurrentTimestampEvents() {
        if (activePhase != null || events.isEmpty()
            || events.peek().timestamp > systemCycles) {
            return;
        }
        for (Phase phase : PHASES) {
            activePhase = phase;
            dispatchEvents(phase);
        }
        activePhase = null;
    }

    private int scaleCpuToSystemCycles(int cycles) {
        if (overclockPercent == 100) {
            scaleCarry = 0;
            return cycles;
        }
        long numerator = scaleCarry + ((long) cycles * 100L);
        long scaled = numerator / overclockPercent;
        scaleCarry = numerator % overclockPercent;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    public State copyState() {
        State state = new State();
        state.overclockPercent = overclockPercent;
        state.maxSystemCycleQuantum = maxSystemCycleQuantum;
        state.scaleCarry = scaleCarry;
        state.cpuCycles = cpuCycles;
        state.systemCycles = systemCycles;
        state.nextEventSequence = nextEventSequence;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        overclockPercent = Math.max(1, state.overclockPercent);
        // Batch size is a host setting, not part of the console state.
        scaleCarry = Math.floorMod(state.scaleCarry, overclockPercent);
        cpuCycles = state.cpuCycles;
        systemCycles = state.systemCycles;
        nextEventSequence = Math.max(0, state.nextEventSequence);
        events.clear();
        activePhase = null;
    }

    public interface EventHandle {
        void cancel();
    }

    private static final class ScheduledEvent implements EventHandle {
        private final long timestamp;
        private final Phase phase;
        private final long sequence;
        private final Runnable action;
        private boolean cancelled;

        private ScheduledEvent(long timestamp, Phase phase, long sequence, Runnable action) {
            this.timestamp = timestamp;
            this.phase = phase;
            this.sequence = sequence;
            this.action = action;
        }

        private long timestamp() {
            return timestamp;
        }

        private Phase phase() {
            return phase;
        }

        private long sequence() {
            return sequence;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    public static final class State {
        int overclockPercent;
        int maxSystemCycleQuantum;
        long scaleCarry;
        long cpuCycles;
        long systemCycles;
        long nextEventSequence;
    }
}
