package nanolive.psxj.emu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CycleSchedulerTest {

    @Test
    void defaultClockAdvancesDevicesOneToOneWithCpuCycles() {
        CycleScheduler scheduler = new CycleScheduler();
        List<Integer> ticks = new ArrayList<>();
        scheduler.addTarget(ticks::add);

        assertEquals(7, scheduler.advanceCpuCycles(7));

        assertEquals(List.of(7), ticks);
        assertEquals(7, scheduler.cpuCycles());
        assertEquals(7, scheduler.systemCycles());
    }

    @Test
    void maxSystemCycleQuantumInterleavesLongDeviceAdvances() {
        CycleScheduler scheduler = new CycleScheduler();
        List<Integer> ticks = new ArrayList<>();
        scheduler.setMaxSystemCycleQuantum(3);
        scheduler.addTarget(ticks::add);

        assertEquals(8, scheduler.advanceCpuCycles(8));

        assertEquals(List.of(3, 3, 2), ticks);
        assertEquals(8, scheduler.cpuCycles());
        assertEquals(8, scheduler.systemCycles());
    }

    @Test
    void overclockScalesCpuCyclesDownToSystemCyclesWithCarry() {
        CycleScheduler scheduler = new CycleScheduler();
        List<Integer> ticks = new ArrayList<>();
        scheduler.setOverclockPercent(200);
        scheduler.addTarget(ticks::add);

        assertEquals(0, scheduler.advanceCpuCycles(1));
        assertEquals(1, scheduler.advanceCpuCycles(1));
        assertEquals(0, scheduler.advanceCpuCycles(1));
        assertEquals(1, scheduler.advanceCpuCycles(1));

        assertEquals(List.of(1, 1), ticks);
        assertEquals(4, scheduler.cpuCycles());
        assertEquals(2, scheduler.systemCycles());
    }

    @Test
    void underclockScalesCpuCyclesUpToSystemCycles() {
        CycleScheduler scheduler = new CycleScheduler();
        scheduler.setOverclockPercent(50);

        assertEquals(6, scheduler.advanceCpuCycles(3));

        assertEquals(3, scheduler.cpuCycles());
        assertEquals(6, scheduler.systemCycles());
    }

    @Test
    void stateRestorePreservesFractionalCarry() {
        CycleScheduler scheduler = new CycleScheduler();
        scheduler.setOverclockPercent(200);
        assertEquals(0, scheduler.advanceCpuCycles(1));

        CycleScheduler.State state = scheduler.copyState();
        CycleScheduler restored = new CycleScheduler();
        restored.loadState(state);

        assertEquals(1, restored.advanceCpuCycles(1));
        assertEquals(2, restored.cpuCycles());
        assertEquals(1, restored.systemCycles());
    }

    @Test
    void stateRestoreKeepsTheCurrentHostBatchingPolicy() {
        CycleScheduler saved = new CycleScheduler();
        saved.setMaxSystemCycleQuantum(1);

        CycleScheduler restored = new CycleScheduler();
        restored.setMaxSystemCycleQuantum(32);
        restored.loadState(saved.copyState());

        assertEquals(32, restored.maxSystemCycleQuantum());
    }

    @Test
    void primaryTargetsRetainHardwarePhaseOrderAroundAnExactEvent() {
        CycleScheduler scheduler = new CycleScheduler();
        List<String> trace = new ArrayList<>();
        scheduler.setPrimaryTargets(
            cycles -> trace.add("device"),
            cycles -> trace.add("audio"),
            cycles -> trace.add("bus"),
            cycles -> trace.add("counter"),
            cycles -> trace.add("interrupt"),
            cycles -> trace.add("cpu")
        );
        scheduler.schedule(1, CycleScheduler.Phase.BUS_ARBITRATION,
            () -> trace.add("event"));

        scheduler.advanceCpuCycles(1);

        assertEquals(List.of(
            "device", "audio", "bus", "event", "counter", "interrupt", "cpu"
        ), trace);
    }

    @Test
    void exactEventSplitsADeviceIntervalAtItsTimestamp() {
        CycleScheduler scheduler = new CycleScheduler();
        List<String> trace = new ArrayList<>();
        scheduler.addTarget(CycleScheduler.Phase.DEVICE_CLOCK,
            cycles -> trace.add("tick:" + cycles + "@" + scheduler.systemCycles()));
        scheduler.schedule(3, CycleScheduler.Phase.BUS_ARBITRATION,
            () -> trace.add("event@" + scheduler.systemCycles()));

        scheduler.advanceCpuCycles(5);

        assertEquals(List.of("tick:3@3", "event@3", "tick:2@5"), trace);
    }

    @Test
    void sameTimestampUsesHardwarePhaseThenInsertionOrder() {
        CycleScheduler scheduler = new CycleScheduler();
        List<String> trace = new ArrayList<>();
        scheduler.addTarget(CycleScheduler.Phase.DEVICE_CLOCK, cycles -> trace.add("device"));
        scheduler.addTarget(CycleScheduler.Phase.COUNTER_LATCH, cycles -> trace.add("counter"));
        scheduler.schedule(1, CycleScheduler.Phase.BUS_ARBITRATION, () -> trace.add("bus-a"));
        scheduler.schedule(1, CycleScheduler.Phase.BUS_ARBITRATION, () -> trace.add("bus-b"));

        scheduler.advanceCpuCycles(1);

        assertEquals(List.of("device", "bus-a", "bus-b", "counter"), trace);
    }

    @Test
    void currentTimestampEventsReachFixedPointBeforeTimeAdvances() {
        CycleScheduler scheduler = new CycleScheduler();
        List<String> trace = new ArrayList<>();
        scheduler.schedule(0, CycleScheduler.Phase.DEVICE_CLOCK, () -> {
            trace.add("first@" + scheduler.systemCycles());
            scheduler.schedule(0, CycleScheduler.Phase.DEVICE_CLOCK,
                () -> trace.add("second@" + scheduler.systemCycles()));
        });
        scheduler.addTarget(cycles -> trace.add("tick@" + scheduler.systemCycles()));

        scheduler.advanceCpuCycles(1);

        assertEquals(List.of("first@0", "second@0", "tick@1"), trace);
    }

    @Test
    void pastEventsAreRejected() {
        CycleScheduler scheduler = new CycleScheduler();
        scheduler.advanceCpuCycles(2);

        assertThrows(IllegalArgumentException.class,
            () -> scheduler.schedule(1, CycleScheduler.Phase.DEVICE_CLOCK, () -> { }));
    }
}
