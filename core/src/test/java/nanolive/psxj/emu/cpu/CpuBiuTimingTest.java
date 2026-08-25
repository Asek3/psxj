package nanolive.psxj.emu.cpu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.TimerController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CpuBiuTimingTest {

    @Test
    void pu18DataReadBaselinesUseMeasuredRamAndOnDieLatencies() {
        assertEquals(0, new Bus().cpuAccessCycles(0x1F80_0000, false, 4));
        assertEquals(6, new Bus().cpuAccessCycles(0x0000_0100, false, 4));
        assertEquals(6, new Bus().cpuAccessCycles(0xA000_0100, false, 4));

        int[] onDieAddresses = {0x1F80_1070, 0x1F80_10A0, 0x1F80_1100, 0x1F80_1120};
        for (int address : onDieAddresses) {
            assertEquals(4, new Bus().cpuAccessCycles(address, false, 1));
            assertEquals(4, new Bus().cpuAccessCycles(address, false, 2));
            assertEquals(4, new Bus().cpuAccessCycles(address, false, 4));
        }
    }

    @Test
    void ramLoadBlocksForBusLatencyAndKeepsOneArchitecturalDelaySlot() {
        Fixture fixture = fixture(
            0x8C08_0100, // lw t0, 0x100(zero)
            0x2409_0001, // addiu t1, zero, 1 -- architectural load-delay slot
            0x240A_0002, // addiu t2, zero, 2
            0x240B_0003, // addiu t3, zero, 3
            0x240C_0004, // addiu t4, zero, 4
            0x240D_0005, // addiu t5, zero, 5
            0x240E_0006  // addiu t6, zero, 6
        );
        fixture.bus.write32(0x100, 0x1234_5678);

        assertEquals(7, fixture.cpu.step());
        assertFalse(fixture.cpu.copyState().pendingDataReadValid);
        assertEquals(1, fixture.cpu.step());

        assertEquals(0x1234_5678, fixture.cpu.register(8));
        assertEquals(1, fixture.cpu.register(9));
    }

    @Test
    void immediateConsumerSeesOldValueAndFollowingInstructionSeesLoadedValue() {
        Fixture fixture = fixture(
            0x8C08_0100, // lw t0, 0x100(zero)
            0x0100_4821, // addu t1, t0, zero -- sees old value
            0x0100_5021  // addu t2, t0, zero -- interlocks until data is ready
        );
        fixture.bus.write32(0x100, 0x1357_9BDF);

        assertEquals(7, fixture.cpu.step());
        assertEquals(1, fixture.cpu.step());
        assertEquals(0, fixture.cpu.register(9));
        assertEquals(1, fixture.cpu.step());

        assertEquals(0x1357_9BDF, fixture.cpu.register(8));
        assertEquals(0x1357_9BDF, fixture.cpu.register(10));
    }

    @Test
    void adjacentDataLoadsEachPayTheirBusLatency() {
        Fixture fixture = fixture(
            0x8C08_0100, // lw t0, 0x100(zero)
            0x8C09_0104, // lw t1, 0x104(zero)
            0x0000_0000
        );
        fixture.bus.write32(0x100, 0x1111_1111);
        fixture.bus.write32(0x104, 0x2222_2222);

        assertEquals(7, fixture.cpu.step());
        assertEquals(7, fixture.cpu.step());

        assertEquals(0x1111_1111, fixture.cpu.register(8));
        assertFalse(fixture.cpu.copyState().pendingDataReadValid);
    }

    @Test
    void ldschDoesNotTurnSystemBusLoadIntoCoreRunAhead() {
        Fixture fixture = fixture(
            0x8C08_0100, // lw t0, 0x100(zero)
            0x0100_4821, // addu t1, t0, zero -- still sees old value
            0x0000_0000
        );
        fixture.bus.write32(0x100, 0x7654_3210);
        assertTrue(fixture.bus.loadSchedulingEnabled());
        assertEquals(7, fixture.cpu.step());
        assertFalse(fixture.cpu.copyState().pendingDataReadValid);
        assertEquals(1, fixture.cpu.step());

        assertEquals(0, fixture.cpu.register(9));
        assertEquals(0x7654_3210, fixture.cpu.register(8));
    }

    @Test
    void adjacentLwlLwrForwardCompletedReadBufferValue() {
        Fixture fixture = fixture(
            0x3C08_AABB, // lui t0, 0xaabb
            0x3508_CCDD, // ori t0, t0, 0xccdd
            0x2409_0100, // addiu t1, zero, 0x100
            0x8928_0001, // lwl t0, 1(t1)
            0x9928_0002, // lwr t0, 2(t1)
            0x0000_0000, // load-delay slot
            0x0100_5021  // addu t2, t0, zero
        );
        fixture.bus.write32(0x100, 0x4433_2211);

        for (int i = 0; i < 7; i++) {
            fixture.cpu.step();
        }

        assertEquals(0x2211_4433, fixture.cpu.register(8));
        assertEquals(0x2211_4433, fixture.cpu.register(10));
    }

    @Test
    void architecturalLoadDelaySurvivesCpuSaveStateRoundTrip() {
        int[] program = {
            0x8C08_0100, // lw t0, 0x100(zero)
            0x2409_0001,
            0x240A_0002,
            0x240B_0003,
            0x240C_0004,
            0x240D_0005,
            0x240E_0006
        };
        Fixture original = fixture(program);
        original.bus.write32(0x100, 0xCAFE_BABE);
        original.cpu.step();
        R3000Cpu.State saved = original.cpu.copyState();
        assertTrue(saved.pendingLoadValid);
        assertFalse(saved.pendingDataReadValid);

        Fixture restored = fixture(program);
        restored.bus.write32(0x100, 0xCAFE_BABE);
        restored.cpu.loadState(saved);

        assertEquals(original.cpu.step(), restored.cpu.step());
        assertEquals(original.cpu.register(8), restored.cpu.register(8));
        assertEquals(0xCAFE_BABE, restored.cpu.register(8));
        assertFalse(restored.cpu.copyState().pendingDataReadValid);
    }

    @Test
    void mmioResponderIsSampledAtReadCompletionNotIssue() {
        Bus bus = new Bus();
        TimerController timers = new TimerController(new InterruptController());
        bus.setTimerController(timers);
        Fixture fixture = fixture(bus,
            0x3C08_1F80, // lui t0, 0x1f80
            0x8509_1120, // lh t1, 0x1120(t0)
            0x240A_0001,
            0x240B_0002,
            0x240C_0003,
            0x240D_0004
        );
        fixture.cpu.setCycleAdvancer(cycles -> {
            timers.tick(cycles);
            return cycles;
        });

        assertEquals(1, fixture.cpu.step());
        assertEquals(5, fixture.cpu.step());
        assertEquals(0, fixture.cpu.register(9));
        assertEquals(1, fixture.cpu.step());

        assertEquals(6, fixture.cpu.register(9));
    }

    private static Fixture fixture(int... instructions) {
        return fixture(new Bus(), instructions);
    }

    private static Fixture fixture(Bus bus, int... instructions) {
        for (int i = 0; i < instructions.length; i++) {
            bus.write32(i * 4, instructions[i]);
        }
        for (int address = 0; address < instructions.length * 4; address += 16) {
            bus.fetchInstruction(address, false);
        }
        R3000Cpu cpu = new R3000Cpu(bus);
        cpu.reset(0x0000_0000);
        cpu.cop0().writeRegister(12, cpu.cop0().status() & ~(1 << 22));
        return new Fixture(bus, cpu);
    }

    private record Fixture(Bus bus, R3000Cpu cpu) {
    }
}
