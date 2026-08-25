package nanolive.psxj.emu.cpu;

import nanolive.psxj.emu.cop0.Cop0;
import nanolive.psxj.emu.cop0.CpuException;
import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.gte.Gte;
import nanolive.psxj.util.Log;

import java.util.Arrays;
import java.util.function.IntConsumer;

public final class R3000Cpu {
    private static final int TRACE_LENGTH = 32;
    private static final int STACK_WRITE_TRACE_LENGTH = 16;
    // Enough room for writes issued during the longest (44-clock) GTE command.
    private static final int GTE_WRITE_QUEUE_LENGTH = 256;
    private static final int STATUS_EFFECT_QUEUE_LENGTH = 8;
    private static final int GTE_REGISTER_WRITE_DELAY_CYCLES = 2;
    private static final int GTE_IRGB_RED_GREEN_WRITE_DELAY_CYCLES = 3;
    private static final int DATA_READ_SIGNED_BYTE = 1;
    private static final int DATA_READ_SIGNED_HALF = 2;
    private static final int DATA_READ_WORD = 3;
    private static final int DATA_READ_UNSIGNED_BYTE = 4;
    private static final int DATA_READ_UNSIGNED_HALF = 5;
    private static final int DATA_READ_WORD_LEFT = 6;
    private static final int DATA_READ_WORD_RIGHT = 7;
    // Core cost left after cached instructions overlap a slow BIU read.
    private static final int LOAD_SCHEDULE_CORE_EXTRA_CYCLES = 2;
    private static final boolean DIAGNOSTIC_TRACE_ENABLED = Log.isDebugEnabled();
    private static final String[] GPR_NAMES = {
        "zr", "at", "v0", "v1", "a0", "a1", "a2", "a3",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"
    };

    @FunctionalInterface
    public interface CpuCycleAdvancer {
        int advanceCpuCycles(int cycles);
    }

    private final Bus bus;
    private final Cop0 cop0;
    private final Gte gte;
    private final int[] gpr = new int[32];
    private final boolean[] memoryLoadOrigin = new boolean[32];
    private final int[][] coprocessorDataLatches = new int[4][32];
    private final int[][] coprocessorControlLatches = new int[4][32];
    private final int[] recentPcs = new int[TRACE_LENGTH];
    private final int[] recentOpcodes = new int[TRACE_LENGTH];
    private final int[] recentStackWritePc = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteOpcode = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteAddress = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteValue = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteSp = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteBaseReg = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteBaseValue = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteSourceReg = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteSourceValue = new int[STACK_WRITE_TRACE_LENGTH];
    private final boolean[] recentStackWriteSourceCop2 = new boolean[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteA0 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteA1 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteA2 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteA3 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteT0 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteT1 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteT2 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteT3 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteS0 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteS1 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteS2 = new int[STACK_WRITE_TRACE_LENGTH];
    private final int[] recentStackWriteS3 = new int[STACK_WRITE_TRACE_LENGTH];
    private int pc;
    private int nextPc;
    private int hi;
    private int lo;
    private boolean inDelaySlot;
    private boolean pendingBranchTaken;
    private int pendingBranchTarget;
    private boolean pendingLoadValid;
    private int pendingLoadRegister;
    private int pendingLoadValue;
    private boolean queuedLoadValid;
    private int queuedLoadRegister;
    private int queuedLoadValue;
    // The BIU has one read buffer; cached work may overlap it with LDSCH set.
    private boolean pendingDataReadValid;
    private long pendingDataReadId;
    private long nextDataReadId;
    private int pendingDataReadAddress;
    private int pendingDataReadWidth;
    private int pendingDataReadKind;
    private int pendingDataReadRegister;
    private int pendingDataReadMergeValue;
    private int pendingDataReadOriginalAddress;
    private long pendingDataReadReadyCycle;
    private boolean pendingDataReadReady;
    private int pendingDataReadValue;
    private boolean pendingDataReadWritebackCancelled;
    private boolean pendingDataReadShadowPending;
    private long currentInstructionDataReadShadowId;
    private int currentInstructionPc;
    private boolean currentInstructionWasDelaySlot;
    private boolean currentInstructionStartedWithInterruptPending;
    private boolean currentInstructionWasGteCommand;
    private boolean currentInstructionDataBreakpointTrap;
    private int currentInstructionOpcode;
    private int currentInstructionCycles;
    private int advancedInstructionCycles;
    private int lastStepSystemCycles;
    private long totalCycles;
    private long mulDivReadyCycle;
    private boolean mulDivPending;
    private int pendingHi;
    private int pendingLo;
    private long gteBusyUntilCycle;
    private long gteCommandStartCycle;
    private int pendingGteCommand;
    private boolean gteCommandPending;
    private final Gte pendingGte = new Gte();
    // Timed CPU-to-GTE writes; later CPU writes must survive command commit.
    private final long[] pendingGteWriteReadyCycle = new long[GTE_WRITE_QUEUE_LENGTH];
    private final int[] pendingGteWriteIndex = new int[GTE_WRITE_QUEUE_LENGTH];
    private final int[] pendingGteWriteValue = new int[GTE_WRITE_QUEUE_LENGTH];
    private final boolean[] pendingGteWriteControl = new boolean[GTE_WRITE_QUEUE_LENGTH];
    private int pendingGteWriteCount;
    // COP0 control changes take effect two slots later.
    private int effectiveCop0Status;
    private int observedCop0Status;
    private long retiredInstructionCount;
    private final long[] pendingStatusEffectReadyInstruction = new long[STATUS_EFFECT_QUEUE_LENGTH];
    private final int[] pendingStatusEffectValue = new int[STATUS_EFFECT_QUEUE_LENGTH];
    private int pendingStatusEffectCount;
    private int cop0ReadLatch;
    private long cop0ReadLatchCycle;
    private boolean cop0ReadLatchValid;
    private boolean cpuWedged;
    private long cop2EnableVisibleCycle;
    private boolean lastCompletedInstructionWasGteCommand;
    private boolean lastCompletedInstructionWasDelaySlot;
    private boolean lastCompletedInstructionStartedWithInterruptPending;
    private int lastCompletedInstructionPc;
    private int lastCompletedInstructionOpcode;
    private int recentTraceIndex;
    private int recentStackWriteIndex;
    private CpuCycleAdvancer cycleAdvancer;
    private IntConsumer biosTtyCharacterSink;

    private static final class CpuTrap extends RuntimeException {
        private static final CpuTrap INSTANCE = new CpuTrap();
        private CpuTrap() { super(null, null, false, false); }
    }

    public R3000Cpu(Bus bus, Cop0 cop0, Gte gte) {
        this.bus = bus;
        this.cop0 = cop0;
        this.gte = gte;
        reset(bus.resetVector());
    }

    public R3000Cpu(Bus bus) {
        this(bus, new Cop0(), new Gte());
    }

    public void reset(int resetPc) {
        pc = resetPc;
        nextPc = resetPc + 4;
        hi = 0;
        lo = 0;
        inDelaySlot = false;
        pendingBranchTaken = false;
        pendingBranchTarget = 0;
        pendingLoadValid = false;
        pendingLoadRegister = 0;
        pendingLoadValue = 0;
        queuedLoadValid = false;
        queuedLoadRegister = 0;
        queuedLoadValue = 0;
        clearPendingDataRead();
        nextDataReadId = 1;
        currentInstructionDataReadShadowId = 0;
        currentInstructionPc = resetPc;
        currentInstructionWasDelaySlot = false;
        currentInstructionStartedWithInterruptPending = false;
        currentInstructionWasGteCommand = false;
        currentInstructionDataBreakpointTrap = false;
        currentInstructionOpcode = 0;
        currentInstructionCycles = 1;
        totalCycles = 0;
        mulDivReadyCycle = 0;
        mulDivPending = false;
        pendingHi = 0;
        pendingLo = 0;
        gteBusyUntilCycle = 0;
        gteCommandStartCycle = 0;
        pendingGteCommand = 0;
        gteCommandPending = false;
        pendingGteWriteCount = 0;
        effectiveCop0Status = 0;
        observedCop0Status = 0;
        retiredInstructionCount = 0;
        pendingStatusEffectCount = 0;
        cop0ReadLatch = 0x20;
        cop0ReadLatchCycle = 0;
        cop0ReadLatchValid = false;
        cpuWedged = false;
        cop2EnableVisibleCycle = 0;
        lastCompletedInstructionWasGteCommand = false;
        lastCompletedInstructionWasDelaySlot = false;
        lastCompletedInstructionStartedWithInterruptPending = false;
        lastCompletedInstructionPc = resetPc;
        lastCompletedInstructionOpcode = 0;
        recentTraceIndex = 0;
        for (int i = 0; i < TRACE_LENGTH; i++) {
            recentPcs[i] = 0;
            recentOpcodes[i] = 0;
        }
        clearRecentStackWrites();
        cop0.reset();
        effectiveCop0Status = cop0.status();
        observedCop0Status = effectiveCop0Status;
        Arrays.fill(gpr, 0);
        Arrays.fill(memoryLoadOrigin, false);
        for (int i = 0; i < coprocessorDataLatches.length; i++) {
            Arrays.fill(coprocessorDataLatches[i], 0);
            Arrays.fill(coprocessorControlLatches[i], 0);
        }
    }

    public void run(int cycles) {
        for (int i = 0; i < cycles; i++) {
            step();
        }
    }

    public void setCycleAdvancer(CpuCycleAdvancer cycleAdvancer) {
        this.cycleAdvancer = cycleAdvancer;
    }

    public void setBiosTtyCharacterSink(IntConsumer biosTtyCharacterSink) {
        this.biosTtyCharacterSink = biosTtyCharacterSink;
    }

    public int lastStepSystemCycles() {
        return lastStepSystemCycles;
    }

    public int step() {
        currentInstructionCycles = 1;
        advancedInstructionCycles = 0;
        lastStepSystemCycles = 0;
        queuedLoadValid = false;
        queuedLoadRegister = 0;
        queuedLoadValue = 0;
        currentInstructionWasGteCommand = false;
        currentInstructionDataBreakpointTrap = false;
        currentInstructionStartedWithInterruptPending = false;
        if (cpuWedged) {
            return advanceWedgedClock();
        }
        synchronizeExternalCop0StatusWrite();
        serviceDeferredUnits(totalCycles);
        if (pendingDataReadValid && !pendingDataReadReady) {
            completePendingDataReadIfDue(totalCycles);
        }
        currentInstructionDataReadShadowId = pendingDataReadValid && pendingDataReadShadowPending
            ? pendingDataReadId
            : 0;
        try {
            bus.setCacheIsolated((effectiveCop0Status & (1 << 16)) != 0);
            cop0.setHardwareInterruptLine(bus.interruptPending());
            boolean interruptPending = cop0.shouldTakeInterrupt(effectiveCop0Status);
            currentInstructionStartedWithInterruptPending = interruptPending;
            if (interruptPending) {
                if (shouldUseGteInterruptEpcQuirk()) {
                    currentInstructionOpcode = lastCompletedInstructionOpcode;
                    raiseException(CpuException.INTERRUPT, lastCompletedInstructionPc, false, 0, lastCompletedInstructionOpcode);
                }
                currentInstructionPc = pc;
                currentInstructionWasDelaySlot = inDelaySlot;
                currentInstructionOpcode = 0;
                raiseException(CpuException.INTERRUPT, pc, inDelaySlot, 0);
                return currentInstructionCycles;
            }

            final int currentPc = pc;
            currentInstructionPc = currentPc;
            bus.setCurrentCpuPc(currentPc);
            final boolean branchDelay = inDelaySlot;
            currentInstructionWasDelaySlot = branchDelay;
            boolean instructionBreakpointChecks =
                cop0.instructionBreakpointChecksEnabled(effectiveCop0Status);
            boolean programBreakpointTrap = instructionBreakpointChecks
                && cop0.testProgramBreakpoint(currentPc, effectiveCop0Status);
            if ((currentPc & 3) != 0) {
                currentInstructionOpcode = 0;
                cop0.setBadVaddr(currentPc);
                raiseException(CpuException.ADDRESS_ERROR_LOAD, currentPc, branchDelay, 0);
                return currentInstructionCycles;
            }
            if (isUserModeSegmentViolation(currentPc)) {
                currentInstructionOpcode = 0;
                cop0.setBadVaddr(currentPc);
                raiseException(CpuException.ADDRESS_ERROR_LOAD, currentPc, branchDelay, 0);
                return currentInstructionCycles;
            }
            if (!bus.canFetchInstruction(currentPc)) {
                if (programBreakpointTrap) {
                    currentInstructionOpcode = 0;
                    raiseException(CpuException.BREAKPOINT, currentPc, branchDelay, 0, 0, true);
                }
                currentInstructionOpcode = 0;
                raiseException(CpuException.BUS_ERROR_FETCH, currentPc, branchDelay, 0);
                return currentInstructionCycles;
            }
            if (pendingDataReadValid && bus.instructionFetchUsesSystemBus(currentPc)) {
                synchronizePendingDataReadForBusAccess();
            }
            boolean cacheIsolated = (effectiveCop0Status & (1 << 16)) != 0;
            int instruction = bus.fetchInstruction(currentPc, cacheIsolated);
            int fetchPenaltyCycles = bus.lastInstructionFetchExtraCycles();
            currentInstructionCycles += fetchPenaltyCycles;
            if (fetchPenaltyCycles > 0) {
                retireCyclesToCurrentInstructionCycle();
            }
            currentInstructionOpcode = instruction;
            recordTrace(currentPc, instruction);
            captureBiosTtyCharacter(currentPc);
            if (instructionBreakpointChecks) {
                if (programBreakpointTrap) {
                    raiseException(CpuException.BREAKPOINT, currentPc, branchDelay, 0,
                        instruction, true);
                }
                if (cop0.traceBreakpointCheckEnabled()
                    && cop0.testTraceBreakpoint(
                        isControlTransferInstruction(instruction), effectiveCop0Status, currentPc)) {
                    raiseException(CpuException.BREAKPOINT, currentPc, branchDelay, 0,
                        instruction, true);
                }
            }
            pc = nextPc;
            nextPc += 4;
            inDelaySlot = false;

            try {
                int op = instruction >>> 26;
                switch (op) {
                    case 0x00 -> decodeSpecial(instruction, currentPc, branchDelay);
                    case 0x01 -> decodeRegImm(instruction, currentPc, branchDelay);
                    case 0x02 -> jump((pc & 0xF000_0000) | ((instruction & 0x03FF_FFFF) << 2));
                    case 0x03 -> {
                        setRegister(31, nextPc);
                        jump((pc & 0xF000_0000) | ((instruction & 0x03FF_FFFF) << 2));
                    }
                    case 0x04 -> branch(getRegister(rs(instruction)) == getRegister(rt(instruction)), instruction);
                    case 0x05 -> branch(getRegister(rs(instruction)) != getRegister(rt(instruction)), instruction);
                    case 0x06 -> branch(getRegister(rs(instruction)) <= 0, instruction);
                    case 0x07 -> branch(getRegister(rs(instruction)) > 0, instruction);
                    case 0x08 -> addImmediate(instruction, true);
                    case 0x09 -> addImmediate(instruction, false);
                    case 0x0A -> setRegister(rt(instruction), getRegister(rs(instruction)) < imm16s(instruction) ? 1 : 0);
                    case 0x0B ->
                        setRegister(rt(instruction), Integer.compareUnsigned(getRegister(rs(instruction)), imm16s(instruction)) < 0 ? 1 : 0);
                    case 0x0C -> setRegister(rt(instruction), getRegister(rs(instruction)) & imm16u(instruction));
                    case 0x0D -> setRegister(rt(instruction), getRegister(rs(instruction)) | imm16u(instruction));
                    case 0x0E -> setRegister(rt(instruction), getRegister(rs(instruction)) ^ imm16u(instruction));
                    case 0x0F -> setRegister(rt(instruction), imm16u(instruction) << 16);
                    case 0x10 -> decodeCop0(instruction, currentPc, branchDelay);
                    case 0x11 -> decodeMissingCoprocessor(instruction, currentPc, branchDelay, 1);
                    case 0x12 -> decodeCop2(instruction, currentPc, branchDelay);
                    case 0x13 -> decodeMissingCoprocessor(instruction, currentPc, branchDelay, 3);
                    case 0x20 -> issueGprDataLoad(instruction, DATA_READ_SIGNED_BYTE);
                    case 0x21 -> issueGprDataLoad(instruction, DATA_READ_SIGNED_HALF);
                    case 0x22 -> issueGprDataLoad(instruction, DATA_READ_WORD_LEFT);
                    case 0x23 -> issueGprDataLoad(instruction, DATA_READ_WORD);
                    case 0x24 -> issueGprDataLoad(instruction, DATA_READ_UNSIGNED_BYTE);
                    case 0x25 -> issueGprDataLoad(instruction, DATA_READ_UNSIGNED_HALF);
                    case 0x26 -> issueGprDataLoad(instruction, DATA_READ_WORD_RIGHT);
                    case 0x28 ->
                        write8(getRegister(rs(instruction)) + imm16s(instruction), getRegister(rt(instruction)));
                    case 0x29 ->
                        write16Aligned(getRegister(rs(instruction)) + imm16s(instruction), getRegister(rt(instruction)));
                    case 0x2A ->
                        storeWordLeft(getRegister(rs(instruction)) + imm16s(instruction), getRegister(rt(instruction)));
                    case 0x2B ->
                        write32Aligned(getRegister(rs(instruction)) + imm16s(instruction), getRegister(rt(instruction)));
                    case 0x2E ->
                        storeWordRight(getRegister(rs(instruction)) + imm16s(instruction), getRegister(rt(instruction)));
                    case 0x30 -> loadMissingCoprocessor(instruction, currentPc, branchDelay, 0);
                    case 0x31 -> loadMissingCoprocessor(instruction, currentPc, branchDelay, 1);
                    case 0x32 -> {
                        ensureCop2Usable(currentPc, branchDelay);
                        int target = rt(instruction);
                        writeGteData(target, read32Aligned(getRegister(rs(instruction)) + imm16s(instruction)));
                    }
                    case 0x33 -> loadMissingCoprocessor(instruction, currentPc, branchDelay, 3);
                    case 0x38 -> storeMissingCoprocessor(instruction, currentPc, branchDelay, 0);
                    case 0x39 -> storeMissingCoprocessor(instruction, currentPc, branchDelay, 1);
                    case 0x3A -> {
                        ensureCop2Usable(currentPc, branchDelay);
                        synchronizeGteReadAccess();
                        write32Aligned(getRegister(rs(instruction)) + imm16s(instruction), gte.readData(rt(instruction)));
                    }
                    case 0x3B -> storeMissingCoprocessor(instruction, currentPc, branchDelay, 3);
                    default -> raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                }
                recordCompletedInstruction(currentPc, instruction, branchDelay);
            } catch (ArithmeticException ex) {
                raiseException(CpuException.OVERFLOW, currentPc, branchDelay, 0, instruction);
            }
        } catch (CpuTrap ignored) {
        } finally {
            retireCyclesToCurrentInstructionCycle();
            finishCurrentDataReadShadow();
            commitPendingLoad();
            promoteQueuedLoad();
            totalCycles += currentInstructionCycles;
            serviceDeferredUnits(totalCycles);
            if (pendingDataReadValid && !pendingDataReadReady) {
                completePendingDataReadIfDue(totalCycles);
            }
            currentInstructionDataReadShadowId = 0;
        }

        gpr[0] = 0;
        if (currentInstructionWasDelaySlot && !inDelaySlot) {
            pendingBranchTaken = false;
            pendingBranchTarget = 0;
        }
        return currentInstructionCycles;
    }

    private void decodeSpecial(int instruction, int currentPc, boolean branchDelay) {
        int funct = instruction & 0x3F;
        int s = rs(instruction);
        int t = rt(instruction);
        int d = rd(instruction);
        switch (funct) {
            case 0x00 -> setRegister(d, getRegister(t) << sa(instruction));
            case 0x02 -> setRegister(d, getRegister(t) >>> sa(instruction));
            case 0x03 -> setRegister(d, getRegister(t) >> sa(instruction));
            case 0x04 -> setRegister(d, getRegister(t) << (getRegister(s) & 0x1F));
            case 0x06 -> setRegister(d, getRegister(t) >>> (getRegister(s) & 0x1F));
            case 0x07 -> setRegister(d, getRegister(t) >> (getRegister(s) & 0x1F));
            case 0x08 -> jump(indirectJumpTarget(s));
            case 0x09 -> {
                int target = indirectJumpTarget(s, getRegister(s));
                setRegister(d, nextPc);
                jump(target);
            }
            case 0x0C -> raiseException(CpuException.SYSCALL, currentPc, branchDelay, 0);
            case 0x0D -> raiseException(CpuException.BREAKPOINT, currentPc, branchDelay, 0);
            case 0x10 -> {
                synchronizeMulDivRead();
                setRegister(d, hi);
            }
            case 0x11 -> hi = getRegister(s);
            case 0x12 -> {
                synchronizeMulDivRead();
                setRegister(d, lo);
            }
            case 0x13 -> lo = getRegister(s);
            case 0x18 -> {
                long result = (long) getRegister(s) * (long) getRegister(t);
                startMulDiv((int) (result >>> 32), (int) result, signedMultiplyLatency(getRegister(s)));
            }
            case 0x19 -> {
                long result = Integer.toUnsignedLong(getRegister(s)) * Integer.toUnsignedLong(getRegister(t));
                startMulDiv((int) (result >>> 32), (int) result, unsignedMultiplyLatency(getRegister(s)));
            }
            case 0x1A -> divideSigned(getRegister(s), getRegister(t));
            case 0x1B -> divideUnsigned(getRegister(s), getRegister(t));
            case 0x20 -> setRegister(d, Math.addExact(getRegister(s), getRegister(t)));
            case 0x21 -> setRegister(d, getRegister(s) + getRegister(t));
            case 0x22 -> setRegister(d, Math.subtractExact(getRegister(s), getRegister(t)));
            case 0x23 -> setRegister(d, getRegister(s) - getRegister(t));
            case 0x24 -> setRegister(d, getRegister(s) & getRegister(t));
            case 0x25 -> setRegister(d, getRegister(s) | getRegister(t));
            case 0x26 -> setRegister(d, getRegister(s) ^ getRegister(t));
            case 0x27 -> setRegister(d, ~(getRegister(s) | getRegister(t)));
            case 0x2A -> setRegister(d, getRegister(s) < getRegister(t) ? 1 : 0);
            case 0x2B -> setRegister(d, Integer.compareUnsigned(getRegister(s), getRegister(t)) < 0 ? 1 : 0);
            default -> raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
        }
    }

    private void decodeRegImm(int instruction, int currentPc, boolean branchDelay) {
        int value = getRegister(rs(instruction));
        int rt = rt(instruction);
        // On the R3000A every BCOND/REGIMM rt encoding is valid.
        if (rt == 0x10 || rt == 0x11) {
            setRegister(31, nextPc);
        }
        branch(((rt & 1) == 0) == (value < 0), instruction);
    }

    private void decodeCop0(int instruction, int currentPc, boolean branchDelay) {
        int rs = rs(instruction);
        int rt = rt(instruction);
        int rd = rd(instruction);
        switch (rs) {
            case 0x00 -> queueLoad(rt, readCop0DataRegister(rd, currentPc, branchDelay));
            case 0x04 -> writeCop0DataRegister(rd, getRegister(rt), currentPc, branchDelay);
            case 0x08 -> {
                requireCop0Usable(currentPc, branchDelay);
                if ((rt & 0x1E) != 0) {
                    raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                }
                branch((rt & 1) == 0, instruction);
            }
            case 0x02, 0x06 -> raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
            default -> {
                if ((instruction & (1 << 25)) == 0) {
                    raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                }
                requireCop0Usable(currentPc, branchDelay);
                switch (instruction & 0x3F) {
                    case 0x01, 0x02, 0x06, 0x08 ->
                        // The PSX R3000A has no TLB.
                        raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                    case 0x10 -> {
                        cop0.returnFromException();
                        commitCop0StatusImmediately();
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private void decodeMissingCoprocessor(int instruction, int currentPc, boolean branchDelay, int coprocessorId) {
        ensureMissingCoprocessorUsable(coprocessorId, currentPc, branchDelay);
        int rs = rs(instruction);
        int target = rt(instruction);
        int register = rd(instruction);
        switch (rs) {
            case 0x00 -> queueLoad(target, coprocessorDataLatches[coprocessorId][register]);
            case 0x02 -> queueLoad(target, coprocessorControlLatches[coprocessorId][register]);
            case 0x04 -> coprocessorDataLatches[coprocessorId][register] = getRegister(target);
            case 0x06 -> coprocessorControlLatches[coprocessorId][register] = getRegister(target);
            case 0x08 -> {
                if ((target & 0x1E) != 0) {
                    raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, coprocessorId);
                }
                branch((target & 1) == 0, instruction);
            }
            default -> {
                if ((instruction & (1 << 25)) == 0) {
                    raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, coprocessorId);
                }
            }
        }
    }

    private void decodeCop2(int instruction, int currentPc, boolean branchDelay) {
        ensureCop2Usable(currentPc, branchDelay);
        int rs = rs(instruction);
        int rt = rt(instruction);
        int rd = rd(instruction);
        switch (rs) {
            case 0x00 -> {
                synchronizeGteReadAccess();
                queueLoad(rt, gte.readData(rd));
            }
            case 0x02 -> {
                synchronizeGteReadAccess();
                queueLoad(rt, gte.readControl(rd));
            }
            case 0x04 -> writeGteData(rd, getRegister(rt));
            case 0x06 -> writeGteControl(rd, getRegister(rt));
            case 0x08 -> {
                if ((rt & 0x1E) != 0) {
                    raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                }
                branch((rt & 1) == 0, instruction);
            }
            default -> {
                if ((instruction & (1 << 25)) != 0) {
                    synchronizeGteCommandAccess();
                    pendingGte.copyRawStateFrom(gte);
                    gte.writeControl(31, 0);
                    int gteCycles = Gte.commandCycles(instruction);
                    gteCommandStartCycle = currentCpuCycle();
                    gteBusyUntilCycle = currentCpuCycle() + Math.max(0, gteCycles - 1);
                    pendingGteCommand = instruction;
                    gteCommandPending = true;
                    currentInstructionWasGteCommand = true;
                } else {
                    raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                }
            }
        }
    }

    private void loadMissingCoprocessor(int instruction, int currentPc, boolean branchDelay, int coprocessorId) {
        int address = getRegister(rs(instruction)) + imm16s(instruction);
        read32Aligned(address);
    }

    private void storeMissingCoprocessor(int instruction, int currentPc, boolean branchDelay, int coprocessorId) {
        int address = getRegister(rs(instruction)) + imm16s(instruction);
        // The unconnected output bus is pulled low on retail hardware.
        write32Aligned(address, 0);
    }

    private void addImmediate(int instruction, boolean trapOnOverflow) {
        int result = trapOnOverflow
            ? Math.addExact(getRegister(rs(instruction)), imm16s(instruction))
            : getRegister(rs(instruction)) + imm16s(instruction);
        setRegister(rt(instruction), result);
    }

    private void divideSigned(int dividend, int divisor) {
        if (divisor == 0) {
            startMulDiv(dividend, dividend >= 0 ? -1 : 1, 36);
            return;
        }
        if (dividend == Integer.MIN_VALUE && divisor == -1) {
            startMulDiv(0, Integer.MIN_VALUE, 36);
            return;
        }
        startMulDiv(dividend % divisor, dividend / divisor, 36);
    }

    private void divideUnsigned(int dividend, int divisor) {
        if (divisor == 0) {
            startMulDiv(dividend, 0xFFFF_FFFF, 36);
            return;
        }
        startMulDiv(Integer.remainderUnsigned(dividend, divisor), Integer.divideUnsigned(dividend, divisor), 36);
    }

    private void jump(int target) {
        nextPc = target;
        pendingBranchTaken = true;
        pendingBranchTarget = target;
        inDelaySlot = true;
    }

    private int indirectJumpTarget(int sourceRegister) {
        return indirectJumpTarget(sourceRegister, getRegister(sourceRegister));
    }

    private int indirectJumpTarget(int sourceRegister, int architecturalTarget) {
        return cop0.jumpRedirectionEnabled() && memoryLoadOrigin[sourceRegister & 31]
            ? 0
            : architecturalTarget;
    }

    private void markMemoryLoadOrigin(int register) {
        if (register != 0) {
            memoryLoadOrigin[register & 31] = true;
        }
    }

    private void branch(boolean taken, int instruction) {
        if (taken) {
            pendingBranchTarget = pc + (imm16s(instruction) << 2);
            nextPc = pendingBranchTarget;
        } else {
            pendingBranchTarget = pc + 4;
        }
        pendingBranchTaken = taken;
        inDelaySlot = true;
    }

    private void issueGprDataLoad(int instruction, int kind) {
        int target = rt(instruction);
        int address = getRegister(rs(instruction)) + imm16s(instruction);
        int busAddress = address;
        int width = switch (kind) {
            case DATA_READ_SIGNED_BYTE, DATA_READ_UNSIGNED_BYTE -> 1;
            case DATA_READ_SIGNED_HALF, DATA_READ_UNSIGNED_HALF -> 2;
            case DATA_READ_WORD -> 4;
            case DATA_READ_WORD_LEFT -> {
                busAddress = address & ~3;
                yield (address & 3) + 1;
            }
            case DATA_READ_WORD_RIGHT -> 4 - (address & 3);
            default -> throw new IllegalArgumentException("Unknown CPU data-read kind: " + kind);
        };

        checkDataBreakpoint(busAddress, false);
        if ((kind == DATA_READ_SIGNED_HALF || kind == DATA_READ_UNSIGNED_HALF) && (address & 1) != 0) {
            cop0.setBadVaddr(address);
            raiseException(CpuException.ADDRESS_ERROR_LOAD,
                currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        if (kind == DATA_READ_WORD && (address & 3) != 0) {
            cop0.setBadVaddr(address);
            raiseException(CpuException.ADDRESS_ERROR_LOAD,
                currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        checkUserModeDataAccess(busAddress, false);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(busAddress);
        if (!bus.canReadData(busAddress, width)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE,
                currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        markMemoryLoadOrigin(target);

        int mergeValue = 0;
        if (kind == DATA_READ_WORD_LEFT || kind == DATA_READ_WORD_RIGHT) {
            mergeValue = resolveLoadMergeValue(target);
        }
        synchronizePendingDataReadForBusAccess();
        int readExtraCycles = bus.beginCpuReadExtraCycles(busAddress, width);

        // There is no usable data cache.
        currentInstructionCycles += readExtraCycles;
        retireCyclesToCurrentInstructionCycle();
        int rawValue = bus.completeCpuRead(busAddress, width);
        updateCacheIsolatedLoadResult();
        queueLoad(target, transformDataReadValue(kind, rawValue, mergeValue, address), true);
    }

    private int transformDataReadValue(int kind, int rawValue, int mergeValue, int originalAddress) {
        return switch (kind) {
            case DATA_READ_SIGNED_BYTE -> (byte) rawValue;
            case DATA_READ_SIGNED_HALF -> (short) rawValue;
            case DATA_READ_WORD -> rawValue;
            case DATA_READ_UNSIGNED_BYTE -> rawValue & 0xFF;
            case DATA_READ_UNSIGNED_HALF -> rawValue & 0xFFFF;
            case DATA_READ_WORD_LEFT -> switch (originalAddress & 3) {
                case 0 -> (mergeValue & 0x00FF_FFFF) | (rawValue << 24);
                case 1 -> (mergeValue & 0x0000_FFFF) | (rawValue << 16);
                case 2 -> (mergeValue & 0x0000_00FF) | (rawValue << 8);
                default -> rawValue;
            };
            case DATA_READ_WORD_RIGHT -> switch (originalAddress & 3) {
                case 0 -> rawValue;
                case 1 -> (mergeValue & 0xFF00_0000) | (rawValue & 0x00FF_FFFF);
                case 2 -> (mergeValue & 0xFFFF_0000) | (rawValue & 0x0000_FFFF);
                default -> (mergeValue & 0xFFFF_FF00) | (rawValue & 0x0000_00FF);
            };
            default -> throw new IllegalArgumentException("Unknown CPU data-read kind: " + kind);
        };
    }

    private int read8Aligned(int address) {
        checkDataBreakpoint(address, false);
        checkUserModeDataAccess(address, false);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canReadData(address)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        currentInstructionCycles += bus.beginCpuReadExtraCycles(address, 1);
        retireCyclesToCurrentInstructionCycle();
        int value = bus.completeCpuRead(address, 1);
        updateCacheIsolatedLoadResult();
        return value;
    }

    private void write8(int address, int value) {
        checkDataBreakpoint(address, true);
        checkUserModeDataAccess(address, true);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canWriteData(address)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        currentInstructionCycles += bus.beginCpuWriteExtraCycles(address, value, 1);
        retireCyclesToCurrentInstructionCycle();
        finishIssuedCpuWriteStall();
        recordStackWrite(address, value & 0xFF);
        bus.completeCpuWrite8(address, value);
    }

    private int read16Aligned(int address) {
        checkDataBreakpoint(address, false);
        if ((address & 1) != 0) {
            cop0.setBadVaddr(address);
            raiseException(CpuException.ADDRESS_ERROR_LOAD, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        checkUserModeDataAccess(address, false);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canReadData(address, 2)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        currentInstructionCycles += bus.beginCpuReadExtraCycles(address, 2);
        retireCyclesToCurrentInstructionCycle();
        int value = bus.completeCpuRead(address, 2);
        updateCacheIsolatedLoadResult();
        return value;
    }

    private int read32Aligned(int address) {
        checkDataBreakpoint(address, false);
        if ((address & 3) != 0) {
            cop0.setBadVaddr(address);
            raiseException(CpuException.ADDRESS_ERROR_LOAD, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        checkUserModeDataAccess(address, false);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canReadData(address, 4)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        currentInstructionCycles += bus.beginCpuReadExtraCycles(address, 4);
        retireCyclesToCurrentInstructionCycle();
        int value = bus.completeCpuRead(address, 4);
        updateCacheIsolatedLoadResult();
        return value;
    }

    private void write16Aligned(int address, int value) {
        checkDataBreakpoint(address, true);
        if ((address & 1) != 0) {
            cop0.setBadVaddr(address);
            raiseException(CpuException.ADDRESS_ERROR_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        checkUserModeDataAccess(address, true);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canWriteData(address, 2)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        int writeExtraCycles = bus.beginCpuWriteExtraCycles(address, value, 2);
        currentInstructionCycles += writeExtraCycles;
        retireCyclesToCurrentInstructionCycle();
        finishIssuedCpuWriteStall();
        recordStackWrite(address, value & 0xFFFF);
        bus.completeCpuWrite16(address, value);
    }

    private void write32Aligned(int address, int value) {
        checkDataBreakpoint(address, true);
        if ((address & 3) != 0) {
            cop0.setBadVaddr(address);
            raiseException(CpuException.ADDRESS_ERROR_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        checkUserModeDataAccess(address, true);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canWriteData(address, 4)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE, currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        int writeExtraCycles = bus.beginCpuWriteExtraCycles(address, value, 4);
        currentInstructionCycles += writeExtraCycles;
        retireCyclesToCurrentInstructionCycle();
        finishIssuedCpuWriteStall();
        recordStackWrite(address, value);
        bus.completeCpuWrite32(address, value);
    }

    private void updateCacheIsolatedLoadResult() {
        int result = bus.consumeIsolatedCacheReadResult();
        if (result >= 0) {
            cop0.setCacheIsolatedLoadResult(result != 0);
            observedCop0Status = cop0.status();
            effectiveCop0Status = (effectiveCop0Status & ~(1 << 19))
                | (observedCop0Status & (1 << 19));
        }
    }

    private void startMulDiv(int nextHi, int nextLo, int latencyCycles) {
        pendingHi = nextHi;
        pendingLo = nextLo;
        mulDivPending = true;
        mulDivReadyCycle = totalCycles + currentInstructionCycles + latencyCycles;
    }

    private void synchronizeMulDivRead() {
        stallUntilCycle(mulDivReadyCycle);
        serviceDeferredUnits(totalCycles + currentInstructionCycles);
    }


    private int loadWordLeft(int currentValue, int address) {
        int alignedAddress = address & ~3;
        int width = (address & 3) + 1;
        int word = readPartialWord(alignedAddress, width);
        return switch (address & 3) {
            case 0 -> (currentValue & 0x00FF_FFFF) | (word << 24);
            case 1 -> (currentValue & 0x0000_FFFF) | (word << 16);
            case 2 -> (currentValue & 0x0000_00FF) | (word << 8);
            default -> word;
        };
    }

    private int loadWordRight(int currentValue, int address) {
        int offset = address & 3;
        int word = readPartialWord(address, 4 - offset);
        return switch (address & 3) {
            case 0 -> word;
            case 1 -> (currentValue & 0xFF00_0000) | ((word >>> 8) & 0x00FF_FFFF);
            case 2 -> (currentValue & 0xFFFF_0000) | ((word >>> 16) & 0x0000_FFFF);
            default -> (currentValue & 0xFFFF_FF00) | ((word >>> 24) & 0x0000_00FF);
        };
    }

    private void storeWordLeft(int address, int value) {
        int alignedAddress = address & ~3;
        checkDataBreakpoint(alignedAddress, true);
        int width = (address & 3) + 1;
        int shiftedValue = value >>> ((4 - width) * 8);
        writePartialWord(alignedAddress, shiftedValue, width);
    }

    private void storeWordRight(int address, int value) {
        checkDataBreakpoint(address, true);
        int width = 4 - (address & 3);
        writePartialWord(address, value, width);
    }

    private int readPartialWord(int address, int width) {
        checkDataBreakpoint(address, false);
        checkUserModeDataAccess(address, false);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canReadData(address, width)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE,
                currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        currentInstructionCycles += bus.cpuAccessCycles(address, false, width);
        if (width < 4) {
            currentInstructionCycles++;
        }
        retireCyclesToCurrentInstructionCycle();
        int packed = switch (width) {
            case 1 -> bus.read8(address);
            case 2 -> bus.read16(address);
            case 3 -> bus.read16(address) | (bus.read8(address + 2) << 16);
            case 4 -> bus.read32(address);
            default -> throw new IllegalArgumentException("Invalid partial load width: " + width);
        };
        updateCacheIsolatedLoadResult();
        return packed << ((address & 3) * 8);
    }

    private void writePartialWord(int address, int value, int width) {
        checkUserModeDataAccess(address, true);
        dispatchDataBreakpointIfPending();
        checkDataAccessWedge(address);
        if (!bus.canWriteData(address, width)) {
            raiseException(CpuException.BUS_ERROR_LOAD_STORE,
                currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
        synchronizePendingDataReadForBusAccess();
        currentInstructionCycles += bus.beginCpuWriteExtraCycles(address, value, width);
        retireCyclesToCurrentInstructionCycle();
        finishIssuedCpuWriteStall();
        switch (width) {
            case 1 -> bus.completeCpuWrite8(address, value);
            case 2 -> bus.completeCpuWrite16(address, value);
            case 3 -> bus.completeCpuWrite24(address, value);
            case 4 -> bus.completeCpuWrite32(address, value);
            default -> throw new IllegalArgumentException("Invalid partial store width: " + width);
        }
    }

    private void finishIssuedCpuWriteStall() {
        int extraCycles;
        while ((extraCycles = bus.pendingCpuWriteStallCycles()) > 0) {
            currentInstructionCycles += extraCycles;
            retireCyclesToCurrentInstructionCycle();
        }
    }


    private void commitPendingLoad() {
        if (pendingLoadValid && pendingLoadRegister != 0) {
            gpr[pendingLoadRegister] = pendingLoadValue;
        }
        pendingLoadValid = false;
        pendingLoadRegister = 0;
        pendingLoadValue = 0;
    }

    private void queueLoad(int index, int value) {
        queueLoad(index, value, false);
    }

    private void queueLoad(int index, int value, boolean memoryOrigin) {
        if (index == 0) {
            return;
        }
        memoryLoadOrigin[index] = memoryOrigin;
        if (pendingDataReadValid && pendingDataReadRegister == index) {
            pendingDataReadWritebackCancelled = true;
        }
        if (pendingLoadValid && pendingLoadRegister == index) {
            pendingLoadValid = false;
            pendingLoadRegister = 0;
            pendingLoadValue = 0;
        }
        queuedLoadValid = true;
        queuedLoadRegister = index;
        queuedLoadValue = value;
    }

    private int resolveLoadMergeValue(int registerIndex) {
        if (pendingDataReadValid
            && !pendingDataReadWritebackCancelled
            && pendingDataReadRegister == registerIndex) {
            long readId = pendingDataReadId;
            synchronizePendingDataReadCompletion();
            if (pendingDataReadValid && pendingDataReadId == readId && pendingDataReadReady) {
                int value = pendingDataReadValue;
                releaseCompletedDataReadForBusAccess();
                return value;
            }
        }
        if (pendingLoadValid && pendingLoadRegister == registerIndex) {
            return pendingLoadValue;
        }
        return getRegister(registerIndex);
    }

    private void synchronizePendingDataReadForBusAccess() {
        if (!pendingDataReadValid) {
            return;
        }
        synchronizePendingDataReadCompletion();
        releaseCompletedDataReadForBusAccess();
    }

    private void synchronizePendingDataReadCompletion() {
        completePendingDataReadIfDue(elapsedCpuCycleBoundary());
        while (pendingDataReadValid && !pendingDataReadReady) {
            long remaining = pendingDataReadReadyCycle - elapsedCpuCycleBoundary();
            if (remaining <= 0) {
                completePendingDataReadIfDue(elapsedCpuCycleBoundary());
                break;
            }
            int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
            advanceCpuStallCycles(chunk);
        }
    }

    private void releaseCompletedDataReadForBusAccess() {
        if (!pendingDataReadValid || !pendingDataReadReady) {
            return;
        }
        if (pendingDataReadShadowPending
            && currentInstructionDataReadShadowId == pendingDataReadId) {
            if (!pendingDataReadWritebackCancelled && pendingDataReadRegister != 0) {
                pendingLoadValid = true;
                pendingLoadRegister = pendingDataReadRegister;
                pendingLoadValue = pendingDataReadValue;
            }
        } else if (!pendingDataReadWritebackCancelled && pendingDataReadRegister != 0) {
            gpr[pendingDataReadRegister] = pendingDataReadValue;
        }
        clearPendingDataRead();
    }

    private void completePendingDataReadIfDue(long cycleBoundary) {
        if (!pendingDataReadValid || pendingDataReadReady
            || cycleBoundary < pendingDataReadReadyCycle) {
            return;
        }
        int rawValue = bus.completeCpuRead(pendingDataReadAddress, pendingDataReadWidth);
        updateCacheIsolatedLoadResult();
        pendingDataReadValue = transformDataReadValue(
            pendingDataReadKind,
            rawValue,
            pendingDataReadMergeValue,
            pendingDataReadOriginalAddress);
        pendingDataReadReady = true;

        if (pendingDataReadWritebackCancelled) {
            clearPendingDataRead();
        } else if (!pendingDataReadShadowPending) {
            if (pendingDataReadRegister != 0) {
                gpr[pendingDataReadRegister] = pendingDataReadValue;
            }
            clearPendingDataRead();
        }
    }

    private void finishCurrentDataReadShadow() {
        if (!pendingDataReadValid
            || pendingDataReadId != currentInstructionDataReadShadowId) {
            return;
        }
        pendingDataReadShadowPending = false;
        if (pendingDataReadReady) {
            if (!pendingDataReadWritebackCancelled && pendingDataReadRegister != 0) {
                gpr[pendingDataReadRegister] = pendingDataReadValue;
            }
            clearPendingDataRead();
        }
    }

    private void clearPendingDataRead() {
        pendingDataReadValid = false;
        pendingDataReadId = 0;
        pendingDataReadAddress = 0;
        pendingDataReadWidth = 0;
        pendingDataReadKind = 0;
        pendingDataReadRegister = 0;
        pendingDataReadMergeValue = 0;
        pendingDataReadOriginalAddress = 0;
        pendingDataReadReadyCycle = 0;
        pendingDataReadReady = false;
        pendingDataReadValue = 0;
        pendingDataReadWritebackCancelled = false;
        pendingDataReadShadowPending = false;
    }

    private void raiseException(CpuException exception, int currentPc, boolean branchDelay, int coprocessorId) {
        raiseException(exception, currentPc, branchDelay, coprocessorId, currentInstructionOpcode);
    }

    private void raiseException(CpuException exception, int currentPc, boolean branchDelay, int coprocessorId, int opcode) {
        raiseException(exception, currentPc, branchDelay, coprocessorId, opcode, false);
    }

    private void raiseException(CpuException exception, int currentPc, boolean branchDelay,
                                int coprocessorId, int opcode, boolean debugBreakpoint) {
        queuedLoadValid = false;
        queuedLoadRegister = 0;
        queuedLoadValue = 0;
        serviceDeferredUnits(totalCycles + currentInstructionCycles);
        boolean branchTaken = branchDelay && pendingBranchTaken;
        int branchTarget = branchDelay ? pendingBranchTarget : 0;
        int causeCoprocessorId = switch (exception) {
            case BUS_ERROR_FETCH, BUS_ERROR_LOAD_STORE -> 0;
            case COPROCESSOR_UNUSABLE -> coprocessorId;
            default -> (opcode >>> 26) & 0x3;
        };
        cop0.enterException(exception, currentPc, branchDelay, causeCoprocessorId,
            branchTaken, branchTarget, cop0.status());
        commitCop0StatusImmediately();
        pc = cop0.exceptionVector(debugBreakpoint);
        nextPc = pc + 4;
        inDelaySlot = false;
        pendingBranchTaken = false;
        pendingBranchTarget = 0;

        if (isExpectedControlFlowException(exception)) {
            if (Log.isDebugEnabled()) {
                Log.debug(exceptionMessage(exception, currentPc, branchDelay, coprocessorId, opcode));
            }
        } else {
            Log.error(exceptionMessage(exception, currentPc, branchDelay, coprocessorId, opcode));
        }
        if (exception == CpuException.BREAKPOINT) {
            String mmioSummary = bus.recentMmioSummary();
            String ramSummary = bus.recentRamWriteSummary(gpr[29], gpr[29] + 0x40);
            Log.error("BREAK context: "
                + "v0=0x" + Integer.toHexString(gpr[2])
                + ", v1=0x" + Integer.toHexString(gpr[3])
                + ", a0=0x" + Integer.toHexString(gpr[4])
                + ", a1=0x" + Integer.toHexString(gpr[5])
                + ", a2=0x" + Integer.toHexString(gpr[6])
                + ", a3=0x" + Integer.toHexString(gpr[7])
                + ", t0=0x" + Integer.toHexString(gpr[8])
                + ", t1=0x" + Integer.toHexString(gpr[9])
                + ", t2=0x" + Integer.toHexString(gpr[10])
                + ", t3=0x" + Integer.toHexString(gpr[11])
                + ", s0=0x" + Integer.toHexString(gpr[16])
                + ", s1=0x" + Integer.toHexString(gpr[17])
                + ", s2=0x" + Integer.toHexString(gpr[18])
                + ", s3=0x" + Integer.toHexString(gpr[19])
                + ", sp=0x" + Integer.toHexString(gpr[29])
                + ", ra=0x" + Integer.toHexString(gpr[31])
                + ", hi=0x" + Integer.toHexString(hi)
                + ", lo=0x" + Integer.toHexString(lo));
            logInstructionWindow(currentPc);
            logInstructionWindow(gpr[31]);
            logRecentTrace();
            logStackWindow();
            logCallerFrame();
            logPointerWindow("BREAK mem a1", gpr[5]);
            logPointerWindow("BREAK mem a3", gpr[7]);
            logRecentStackWrites();
            if (!mmioSummary.isEmpty()) {
                for (String line : mmioSummary.split("\\n")) {
                    Log.error(line);
                }
            }
            if (!ramSummary.isEmpty()) {
                for (String line : ramSummary.split("\\n")) {
                    Log.error(line);
                }
            }
        } else if (isFaultContextException(exception)) {
            logFaultContext(exception, currentPc, opcode);
        }

        throw CpuTrap.INSTANCE;
    }

    private void checkDataBreakpoint(int address, boolean write) {
        if (cop0.testDataBreakpoint(address, write, effectiveCop0Status)) {
            currentInstructionDataBreakpointTrap = true;
        }
    }

    private void dispatchDataBreakpointIfPending() {
        if (!currentInstructionDataBreakpointTrap) {
            return;
        }
        raiseException(CpuException.BREAKPOINT, currentInstructionPc,
            currentInstructionWasDelaySlot, 0, 0, true);
    }

    private void checkDataAccessWedge(int address) {
        if (!bus.cpuDataAccessDeadlocks(address)) {
            return;
        }
        cpuWedged = true;
        pc = currentInstructionPc;
        nextPc = currentInstructionPc + 4;
        inDelaySlot = currentInstructionWasDelaySlot;
        throw CpuTrap.INSTANCE;
    }

    private int advanceWedgedClock() {
        serviceDeferredUnits(totalCycles);
        completePendingDataReadIfDue(totalCycles);
        advanceCpuClocks(1);
        totalCycles++;
        serviceDeferredUnits(totalCycles);
        completePendingDataReadIfDue(totalCycles);
        return 1;
    }

    private String exceptionMessage(
        CpuException exception,
        int currentPc,
        boolean branchDelay,
        int coprocessorId,
        int opcode
    ) {
        return "CPU exception: type=" + exception
            + ", currentPc=0x" + Integer.toHexString(currentPc)
            + ", opcode=0x" + Integer.toHexString(opcode)
            + ", branchDelay=" + branchDelay
            + ", copId=" + coprocessorId
            + ", newPc=0x" + Integer.toHexString(pc)
            + ", nextPc=0x" + Integer.toHexString(nextPc)
            + ", cause=0x" + Integer.toHexString(cop0.readRegister(13))
            + ", sr=0x" + Integer.toHexString(cop0.readRegister(12))
            + ", epc=0x" + Integer.toHexString(cop0.readRegister(14))
            + ", badVaddr=0x" + Integer.toHexString(cop0.readRegister(8))
            + ", iStat=0x" + Integer.toHexString(bus.read32(0x1F801070))
            + ", iMask=0x" + Integer.toHexString(bus.read32(0x1F801074))
            + ", pendingIrqs=" + interruptSummary();
    }

    private boolean isExpectedControlFlowException(CpuException exception) {
        return exception == CpuException.SYSCALL || exception == CpuException.INTERRUPT;
    }

    private boolean isFaultContextException(CpuException exception) {
        return exception == CpuException.ADDRESS_ERROR_LOAD
            || exception == CpuException.ADDRESS_ERROR_STORE
            || exception == CpuException.BUS_ERROR_FETCH
            || exception == CpuException.BUS_ERROR_LOAD_STORE;
    }

    private void logFaultContext(CpuException exception, int currentPc, int opcode) {
        int op = opcode >>> 26;
        boolean memoryOpcode = switch (op) {
            case 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26,
                 0x28, 0x29, 0x2A, 0x2B, 0x2E, 0x32, 0x3A -> true;
            default -> false;
        };
        if (memoryOpcode) {
            int baseReg = rs(opcode);
            int targetReg = rt(opcode);
            int baseValue = gpr[baseReg];
            int immediate = imm16s(opcode);
            int effectiveAddress = baseValue + immediate;
            Log.error("FAULT context: type=" + exception
                + ", pc=0x" + Integer.toHexString(currentPc)
                + ", op=0x" + Integer.toHexString(opcode)
                + ", mnemonic=" + storeInstructionName(opcode).replace("store?", loadStoreInstructionName(opcode))
                + ", baseReg=" + formatRegisterRef(baseReg, false)
                + ", baseValue=0x" + Integer.toHexString(baseValue)
                + ", targetReg=" + formatRegisterRef(targetReg, false)
                + ", targetValue=0x" + Integer.toHexString(gpr[targetReg])
                + ", imm=" + formatSignedHex(immediate)
                + ", effective=0x" + Integer.toHexString(effectiveAddress)
                + ", a0=0x" + Integer.toHexString(gpr[4])
                + ", a1=0x" + Integer.toHexString(gpr[5])
                + ", a2=0x" + Integer.toHexString(gpr[6])
                + ", a3=0x" + Integer.toHexString(gpr[7])
                + ", t0=0x" + Integer.toHexString(gpr[8])
                + ", t1=0x" + Integer.toHexString(gpr[9])
                + ", t2=0x" + Integer.toHexString(gpr[10])
                + ", t3=0x" + Integer.toHexString(gpr[11])
                + ", s0=0x" + Integer.toHexString(gpr[16])
                + ", s1=0x" + Integer.toHexString(gpr[17])
                + ", s2=0x" + Integer.toHexString(gpr[18])
                + ", s3=0x" + Integer.toHexString(gpr[19])
                + ", sp=0x" + Integer.toHexString(gpr[29])
                + ", ra=0x" + Integer.toHexString(gpr[31]));
        }
        logInstructionWindow(currentPc);
        logRecentTrace();
    }

    private String interruptSummary() {
        if (bus.read32(0x1F801070) == 0 && bus.read32(0x1F801074) == 0) {
            return "<none>";
        }
        return bus.interruptSummary();
    }

    private void logInstructionWindow(int centerPc) {
        if (!bus.canReadData(centerPc)) {
            return;
        }
        for (int offset = -32; offset <= 32; offset += 4) {
            int address = centerPc + offset;
            if (!bus.canReadData(address)) {
                continue;
            }
            Log.error("BREAK code: center=0x" + Integer.toHexString(centerPc)
                + ", pc=0x" + Integer.toHexString(address)
                + ", op=0x" + Integer.toHexString(bus.read32(address)));
        }
    }

    private void logRecentTrace() {
        for (int i = 0; i < TRACE_LENGTH; i++) {
            int index = (recentTraceIndex + i) % TRACE_LENGTH;
            int tracePc = recentPcs[index];
            if (tracePc == 0) {
                continue;
            }
            Log.error("BREAK trace: slot=" + i
                + ", pc=0x" + Integer.toHexString(tracePc)
                + ", op=0x" + Integer.toHexString(recentOpcodes[index]));
        }
    }

    private void logStackWindow() {
        int sp = gpr[29];
        for (int offset = 0; offset <= 0x40; offset += 4) {
            int address = sp + offset;
            if (!isSafeRamLikeAddress(address)) {
                continue;
            }
            Log.error("BREAK stack: addr=0x" + Integer.toHexString(address)
                + ", value=0x" + Integer.toHexString(bus.read32(address)));
        }
    }

    private void logCallerFrame() {
        int sp = gpr[29];
        logSingleWord("BREAK caller", sp + 0x24);
        logSingleWord("BREAK arg", sp + 0x30);
        logSingleWord("BREAK arg", sp + 0x34);
        logSingleWord("BREAK arg", sp + 0x38);
        if (isSafeRamLikeAddress(sp + 0x24)) {
            int callerRa = bus.read32(sp + 0x24);
            if (bus.canReadData(callerRa)) {
                logInstructionWindow(callerRa);
            }
        }
    }

    private void logPointerWindow(String prefix, int address) {
        if (!isSafeRamLikeAddress(address)) {
            return;
        }
        for (int offset = 0; offset < 32; offset += 4) {
            int currentAddress = address + offset;
            if (!isSafeRamLikeAddress(currentAddress)) {
                continue;
            }
            Log.error(prefix + ": addr=0x" + Integer.toHexString(currentAddress)
                + ", value=0x" + Integer.toHexString(bus.read32(currentAddress)));
        }
    }

    private void logSingleWord(String prefix, int address) {
        if (!isSafeRamLikeAddress(address)) {
            return;
        }
        Log.error(prefix + ": addr=0x" + Integer.toHexString(address)
            + ", value=0x" + Integer.toHexString(bus.read32(address)));
    }

    private void logRecentStackWrites() {
        for (int i = 0; i < STACK_WRITE_TRACE_LENGTH; i++) {
            int index = (recentStackWriteIndex + i) % STACK_WRITE_TRACE_LENGTH;
            int address = recentStackWriteAddress[index];
            if (address == 0) {
                continue;
            }
            int opcode = recentStackWriteOpcode[index];
            int writeSp = recentStackWriteSp[index];
            Log.error("BREAK stack-write: pc=0x" + Integer.toHexString(recentStackWritePc[index])
                + ", op=0x" + Integer.toHexString(opcode)
                + ", kind=" + storeInstructionName(opcode)
                + ", addr=0x" + Integer.toHexString(address)
                + ", value=0x" + Integer.toHexString(recentStackWriteValue[index])
                + ", writeSp=0x" + Integer.toHexString(writeSp)
                + ", stackOff=" + formatSignedHex(address - writeSp)
                + ", baseReg=" + formatRegisterRef(recentStackWriteBaseReg[index], false)
                + ", baseValue=0x" + Integer.toHexString(recentStackWriteBaseValue[index])
                + ", imm=" + formatSignedHex(imm16s(opcode))
                + ", srcReg=" + formatRegisterRef(recentStackWriteSourceReg[index], recentStackWriteSourceCop2[index])
                + ", srcValue=0x" + Integer.toHexString(recentStackWriteSourceValue[index])
                + ", a0=0x" + Integer.toHexString(recentStackWriteA0[index])
                + ", a1=0x" + Integer.toHexString(recentStackWriteA1[index])
                + ", a2=0x" + Integer.toHexString(recentStackWriteA2[index])
                + ", a3=0x" + Integer.toHexString(recentStackWriteA3[index])
                + ", t0=0x" + Integer.toHexString(recentStackWriteT0[index])
                + ", t1=0x" + Integer.toHexString(recentStackWriteT1[index])
                + ", t2=0x" + Integer.toHexString(recentStackWriteT2[index])
                + ", t3=0x" + Integer.toHexString(recentStackWriteT3[index])
                + ", s0=0x" + Integer.toHexString(recentStackWriteS0[index])
                + ", s1=0x" + Integer.toHexString(recentStackWriteS1[index])
                + ", s2=0x" + Integer.toHexString(recentStackWriteS2[index])
                + ", s3=0x" + Integer.toHexString(recentStackWriteS3[index]));
            logInstructionWindow(recentStackWritePc[index]);
        }
    }

    private boolean isSafeRamLikeAddress(int address) {
        int masked = address & 0x1FFF_FFFF;
        if ((masked >= 0x0000_0000 && masked <= 0x001F_FFFC)
            || (masked >= 0x1F80_0000 && masked <= 0x1F80_03FC)) {
            return (address & 3) == 0 && bus.canReadData(address) && bus.canReadData(address + 3);
        }
        return false;
    }

    private void recordTrace(int currentPc, int instruction) {
        if (!DIAGNOSTIC_TRACE_ENABLED) {
            return;
        }
        recentPcs[recentTraceIndex] = currentPc;
        recentOpcodes[recentTraceIndex] = instruction;
        recentTraceIndex = (recentTraceIndex + 1) % TRACE_LENGTH;
    }

    private void captureBiosTtyCharacter(int currentPc) {
        IntConsumer sink = biosTtyCharacterSink;
        if (sink == null) {
            return;
        }
        int vector = currentPc & 0x1FFF_FFFF;
        int call = gpr[9];
        boolean putc = (vector == 0xA0 && (call == 0x09 || call == 0x3C))
            || (vector == 0xB0 && (call == 0x3B || call == 0x3D));
        if (putc && gpr[4] != 0) {
            sink.accept(gpr[4] & 0xFF);
        }
    }

    private void recordStackWrite(int address, int value) {
        if (!DIAGNOSTIC_TRACE_ENABLED) {
            return;
        }
        int sp = gpr[29];
        if (Integer.compareUnsigned(address, sp) < 0 || Integer.compareUnsigned(address, sp + 0x80) > 0) {
            return;
        }
        int opcode = currentInstructionOpcode;
        int op = opcode >>> 26;
        int baseReg = rs(opcode);
        int sourceReg = rt(opcode);
        recentStackWritePc[recentStackWriteIndex] = currentInstructionPc;
        recentStackWriteOpcode[recentStackWriteIndex] = opcode;
        recentStackWriteAddress[recentStackWriteIndex] = address;
        recentStackWriteValue[recentStackWriteIndex] = value;
        recentStackWriteSp[recentStackWriteIndex] = sp;
        recentStackWriteBaseReg[recentStackWriteIndex] = baseReg;
        recentStackWriteBaseValue[recentStackWriteIndex] = getRegister(baseReg);
        recentStackWriteSourceReg[recentStackWriteIndex] = sourceReg;
        recentStackWriteSourceCop2[recentStackWriteIndex] = op == 0x3A;
        recentStackWriteSourceValue[recentStackWriteIndex] = op == 0x3A ? value : getRegister(sourceReg);
        recentStackWriteA0[recentStackWriteIndex] = gpr[4];
        recentStackWriteA1[recentStackWriteIndex] = gpr[5];
        recentStackWriteA2[recentStackWriteIndex] = gpr[6];
        recentStackWriteA3[recentStackWriteIndex] = gpr[7];
        recentStackWriteT0[recentStackWriteIndex] = gpr[8];
        recentStackWriteT1[recentStackWriteIndex] = gpr[9];
        recentStackWriteT2[recentStackWriteIndex] = gpr[10];
        recentStackWriteT3[recentStackWriteIndex] = gpr[11];
        recentStackWriteS0[recentStackWriteIndex] = gpr[16];
        recentStackWriteS1[recentStackWriteIndex] = gpr[17];
        recentStackWriteS2[recentStackWriteIndex] = gpr[18];
        recentStackWriteS3[recentStackWriteIndex] = gpr[19];
        recentStackWriteIndex = (recentStackWriteIndex + 1) % STACK_WRITE_TRACE_LENGTH;
    }

    private void clearRecentStackWrites() {
        recentStackWriteIndex = 0;
        for (int i = 0; i < STACK_WRITE_TRACE_LENGTH; i++) {
            recentStackWritePc[i] = 0;
            recentStackWriteOpcode[i] = 0;
            recentStackWriteAddress[i] = 0;
            recentStackWriteValue[i] = 0;
            recentStackWriteSp[i] = 0;
            recentStackWriteBaseReg[i] = 0;
            recentStackWriteBaseValue[i] = 0;
            recentStackWriteSourceReg[i] = 0;
            recentStackWriteSourceValue[i] = 0;
            recentStackWriteSourceCop2[i] = false;
            recentStackWriteA0[i] = 0;
            recentStackWriteA1[i] = 0;
            recentStackWriteA2[i] = 0;
            recentStackWriteA3[i] = 0;
            recentStackWriteT0[i] = 0;
            recentStackWriteT1[i] = 0;
            recentStackWriteT2[i] = 0;
            recentStackWriteT3[i] = 0;
            recentStackWriteS0[i] = 0;
            recentStackWriteS1[i] = 0;
            recentStackWriteS2[i] = 0;
            recentStackWriteS3[i] = 0;
        }
    }

    private String storeInstructionName(int opcode) {
        return switch (opcode >>> 26) {
            case 0x28 -> "sb";
            case 0x29 -> "sh";
            case 0x2A -> "swl";
            case 0x2B -> "sw";
            case 0x2E -> "swr";
            case 0x3A -> "swc2";
            default -> "store?";
        };
    }

    private String loadStoreInstructionName(int opcode) {
        return switch (opcode >>> 26) {
            case 0x20 -> "lb";
            case 0x21 -> "lh";
            case 0x22 -> "lwl";
            case 0x23 -> "lw";
            case 0x24 -> "lbu";
            case 0x25 -> "lhu";
            case 0x26 -> "lwr";
            case 0x28 -> "sb";
            case 0x29 -> "sh";
            case 0x2A -> "swl";
            case 0x2B -> "sw";
            case 0x2E -> "swr";
            case 0x32 -> "lwc2";
            case 0x3A -> "swc2";
            default -> "mem?";
        };
    }

    private String formatRegisterRef(int registerIndex, boolean cop2) {
        if (cop2) {
            return "cop2d" + registerIndex + "(" + registerIndex + ")";
        }
        if (registerIndex < 0 || registerIndex >= GPR_NAMES.length) {
            return "r?" + "(" + registerIndex + ")";
        }
        return GPR_NAMES[registerIndex] + "(" + registerIndex + ")";
    }

    private String formatSignedHex(int value) {
        if (value < 0) {
            return "-0x" + Integer.toHexString(-value);
        }
        return "+0x" + Integer.toHexString(value);
    }

    private int getRegister(int index) {
        if (index != 0
            && pendingDataReadValid
            && !pendingDataReadWritebackCancelled
            && pendingDataReadRegister == index
            && currentInstructionDataReadShadowId != pendingDataReadId) {
            synchronizePendingDataReadCompletion();
        }
        return gpr[index];
    }

    private void setRegister(int index, int value) {
        if (index != 0) {
            memoryLoadOrigin[index] = false;
            if (pendingLoadValid && pendingLoadRegister == index) {
                pendingLoadValid = false;
                pendingLoadRegister = 0;
                pendingLoadValue = 0;
            }
            if (pendingDataReadValid && pendingDataReadRegister == index) {
                pendingDataReadWritebackCancelled = true;
            }
            gpr[index] = value;
        }
    }

    public int pc() {
        return pc;
    }

    public int register(int index) {
        return gpr[index];
    }

    public int[] copyRegisters() {
        return gpr.clone();
    }

    public State copyState() {
        serviceDeferredUnits(totalCycles);
        completePendingDataReadIfDue(totalCycles);
        State state = new State();
        state.gpr = gpr.clone();
        state.memoryLoadOrigin = memoryLoadOrigin.clone();
        state.coprocessorDataLatches = copyCoprocessorLatches(coprocessorDataLatches);
        state.coprocessorControlLatches = copyCoprocessorLatches(coprocessorControlLatches);
        state.pc = pc;
        state.nextPc = nextPc;
        state.hi = hi;
        state.lo = lo;
        state.inDelaySlot = inDelaySlot;
        state.pendingBranchTaken = pendingBranchTaken;
        state.pendingBranchTarget = pendingBranchTarget;
        state.pendingLoadValid = pendingLoadValid;
        state.pendingLoadRegister = pendingLoadRegister;
        state.pendingLoadValue = pendingLoadValue;
        state.queuedLoadValid = queuedLoadValid;
        state.queuedLoadRegister = queuedLoadRegister;
        state.queuedLoadValue = queuedLoadValue;
        state.pendingDataReadValid = pendingDataReadValid;
        state.pendingDataReadId = pendingDataReadId;
        state.nextDataReadId = nextDataReadId;
        state.pendingDataReadAddress = pendingDataReadAddress;
        state.pendingDataReadWidth = pendingDataReadWidth;
        state.pendingDataReadKind = pendingDataReadKind;
        state.pendingDataReadRegister = pendingDataReadRegister;
        state.pendingDataReadMergeValue = pendingDataReadMergeValue;
        state.pendingDataReadOriginalAddress = pendingDataReadOriginalAddress;
        state.pendingDataReadReadyCycle = pendingDataReadReadyCycle;
        state.pendingDataReadReady = pendingDataReadReady;
        state.pendingDataReadValue = pendingDataReadValue;
        state.pendingDataReadWritebackCancelled = pendingDataReadWritebackCancelled;
        state.pendingDataReadShadowPending = pendingDataReadShadowPending;
        state.totalCycles = totalCycles;
        state.mulDivReadyCycle = mulDivReadyCycle;
        state.mulDivPending = mulDivPending;
        state.pendingHi = pendingHi;
        state.pendingLo = pendingLo;
        state.gteBusyUntilCycle = gteBusyUntilCycle;
        state.gteCommandStartCycle = gteCommandStartCycle;
        state.pendingGteCommand = pendingGteCommand;
        state.gteCommandPending = gteCommandPending;
        state.pendingGteData = gteCommandPending ? pendingGte.copyRawDataRegisters() : null;
        state.pendingGteControl = gteCommandPending ? pendingGte.copyRawControlRegisters() : null;
        state.pendingGteWriteReadyCycle = Arrays.copyOf(pendingGteWriteReadyCycle, pendingGteWriteCount);
        state.pendingGteWriteIndex = Arrays.copyOf(pendingGteWriteIndex, pendingGteWriteCount);
        state.pendingGteWriteValue = Arrays.copyOf(pendingGteWriteValue, pendingGteWriteCount);
        state.pendingGteWriteControl = Arrays.copyOf(pendingGteWriteControl, pendingGteWriteCount);
        state.pendingGteWriteCount = pendingGteWriteCount;
        state.statusPipelineStatePresent = true;
        state.effectiveCop0Status = effectiveCop0Status;
        state.observedCop0Status = observedCop0Status;
        state.retiredInstructionCount = retiredInstructionCount;
        state.pendingStatusEffectReadyInstruction = Arrays.copyOf(
            pendingStatusEffectReadyInstruction, pendingStatusEffectCount);
        state.pendingStatusEffectValue = Arrays.copyOf(pendingStatusEffectValue, pendingStatusEffectCount);
        state.pendingStatusEffectCount = pendingStatusEffectCount;
        state.cop0ReadLatch = cop0ReadLatch;
        state.cop0ReadLatchCycle = cop0ReadLatchCycle;
        state.cop0ReadLatchValid = cop0ReadLatchValid;
        state.cpuWedged = cpuWedged;
        state.cop2EnableVisibleCycle = cop2EnableVisibleCycle;
        state.lastCompletedInstructionWasGteCommand = lastCompletedInstructionWasGteCommand;
        state.lastCompletedInstructionWasDelaySlot = lastCompletedInstructionWasDelaySlot;
        state.lastCompletedInstructionStartedWithInterruptPending = lastCompletedInstructionStartedWithInterruptPending;
        state.lastCompletedInstructionPc = lastCompletedInstructionPc;
        state.lastCompletedInstructionOpcode = lastCompletedInstructionOpcode;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        Arrays.fill(gpr, 0);
        if (state.gpr != null) {
            System.arraycopy(state.gpr, 0, gpr, 0, Math.min(state.gpr.length, gpr.length));
        }
        Arrays.fill(memoryLoadOrigin, false);
        if (state.memoryLoadOrigin != null) {
            System.arraycopy(state.memoryLoadOrigin, 0, memoryLoadOrigin, 0,
                Math.min(state.memoryLoadOrigin.length, memoryLoadOrigin.length));
        }
        loadCoprocessorLatches(coprocessorDataLatches, state.coprocessorDataLatches);
        loadCoprocessorLatches(coprocessorControlLatches, state.coprocessorControlLatches);
        gpr[0] = 0;
        pc = state.pc;
        nextPc = state.nextPc;
        hi = state.hi;
        lo = state.lo;
        inDelaySlot = state.inDelaySlot;
        pendingBranchTaken = state.pendingBranchTaken;
        pendingBranchTarget = state.pendingBranchTarget;
        pendingLoadValid = state.pendingLoadValid;
        pendingLoadRegister = state.pendingLoadRegister & 31;
        pendingLoadValue = state.pendingLoadValue;
        queuedLoadValid = state.queuedLoadValid;
        queuedLoadRegister = state.queuedLoadRegister & 31;
        queuedLoadValue = state.queuedLoadValue;
        pendingDataReadValid = state.pendingDataReadValid;
        pendingDataReadId = state.pendingDataReadId;
        nextDataReadId = state.nextDataReadId == 0 ? Math.max(1, pendingDataReadId + 1) : state.nextDataReadId;
        pendingDataReadAddress = state.pendingDataReadAddress;
        pendingDataReadWidth = state.pendingDataReadWidth;
        pendingDataReadKind = state.pendingDataReadKind;
        pendingDataReadRegister = state.pendingDataReadRegister & 31;
        pendingDataReadMergeValue = state.pendingDataReadMergeValue;
        pendingDataReadOriginalAddress = state.pendingDataReadOriginalAddress;
        pendingDataReadReadyCycle = state.pendingDataReadReadyCycle;
        pendingDataReadReady = state.pendingDataReadReady;
        pendingDataReadValue = state.pendingDataReadValue;
        pendingDataReadWritebackCancelled = state.pendingDataReadWritebackCancelled;
        pendingDataReadShadowPending = state.pendingDataReadShadowPending;
        currentInstructionDataReadShadowId = 0;
        currentInstructionPc = pc;
        currentInstructionWasDelaySlot = false;
        currentInstructionStartedWithInterruptPending = false;
        currentInstructionWasGteCommand = false;
        currentInstructionOpcode = 0;
        currentInstructionCycles = 1;
        advancedInstructionCycles = 0;
        lastStepSystemCycles = 0;
        totalCycles = state.totalCycles;
        mulDivReadyCycle = state.mulDivReadyCycle;
        mulDivPending = state.mulDivPending;
        pendingHi = state.pendingHi;
        pendingLo = state.pendingLo;
        gteBusyUntilCycle = state.gteBusyUntilCycle;
        gteCommandStartCycle = state.gteCommandStartCycle;
        pendingGteCommand = state.pendingGteCommand;
        gteCommandPending = state.gteCommandPending
            && state.pendingGteData != null
            && state.pendingGteControl != null;
        if (gteCommandPending) {
            pendingGte.loadRawState(state.pendingGteData, state.pendingGteControl);
        }
        pendingGteWriteCount = Math.clamp(state.pendingGteWriteCount, 0, GTE_WRITE_QUEUE_LENGTH);
        Arrays.fill(pendingGteWriteReadyCycle, 0);
        Arrays.fill(pendingGteWriteIndex, 0);
        Arrays.fill(pendingGteWriteValue, 0);
        Arrays.fill(pendingGteWriteControl, false);
        for (int i = 0; i < pendingGteWriteCount; i++) {
            pendingGteWriteReadyCycle[i] = state.pendingGteWriteReadyCycle == null || i >= state.pendingGteWriteReadyCycle.length
                ? totalCycles
                : state.pendingGteWriteReadyCycle[i];
            pendingGteWriteIndex[i] = state.pendingGteWriteIndex == null || i >= state.pendingGteWriteIndex.length
                ? 0
                : state.pendingGteWriteIndex[i] & 31;
            pendingGteWriteValue[i] = state.pendingGteWriteValue == null || i >= state.pendingGteWriteValue.length
                ? 0
                : state.pendingGteWriteValue[i];
            pendingGteWriteControl[i] = state.pendingGteWriteControl != null
                && i < state.pendingGteWriteControl.length
                && state.pendingGteWriteControl[i];
        }
        if (state.statusPipelineStatePresent) {
            effectiveCop0Status = state.effectiveCop0Status;
            observedCop0Status = state.observedCop0Status;
            retiredInstructionCount = Math.max(0, state.retiredInstructionCount);
            pendingStatusEffectCount = Math.clamp(state.pendingStatusEffectCount, 0, STATUS_EFFECT_QUEUE_LENGTH);
            Arrays.fill(pendingStatusEffectReadyInstruction, 0);
            Arrays.fill(pendingStatusEffectValue, 0);
            for (int i = 0; i < pendingStatusEffectCount; i++) {
                pendingStatusEffectReadyInstruction[i] = state.pendingStatusEffectReadyInstruction == null
                    || i >= state.pendingStatusEffectReadyInstruction.length
                    ? retiredInstructionCount
                    : state.pendingStatusEffectReadyInstruction[i];
                pendingStatusEffectValue[i] = state.pendingStatusEffectValue == null
                    || i >= state.pendingStatusEffectValue.length
                    ? cop0.status()
                    : state.pendingStatusEffectValue[i];
            }
            applyReadyCop0StatusEffects();
            cop0ReadLatch = state.cop0ReadLatch;
            cop0ReadLatchCycle = state.cop0ReadLatchCycle;
            cop0ReadLatchValid = state.cop0ReadLatchValid;
        } else {
            effectiveCop0Status = cop0.status();
            observedCop0Status = effectiveCop0Status;
            retiredInstructionCount = 0;
            pendingStatusEffectCount = 0;
            cop0ReadLatch = 0x20;
            cop0ReadLatchCycle = totalCycles;
            cop0ReadLatchValid = false;
        }
        cpuWedged = state.cpuWedged;
        cop2EnableVisibleCycle = state.cop2EnableVisibleCycle;
        lastCompletedInstructionWasGteCommand = state.lastCompletedInstructionWasGteCommand;
        lastCompletedInstructionWasDelaySlot = state.lastCompletedInstructionWasDelaySlot;
        lastCompletedInstructionStartedWithInterruptPending = state.lastCompletedInstructionStartedWithInterruptPending;
        lastCompletedInstructionPc = state.lastCompletedInstructionPc;
        lastCompletedInstructionOpcode = state.lastCompletedInstructionOpcode;
        recentTraceIndex = 0;
        Arrays.fill(recentPcs, 0);
        Arrays.fill(recentOpcodes, 0);
        clearRecentStackWrites();
        serviceDeferredUnits(totalCycles);
        completePendingDataReadIfDue(totalCycles);
    }

    public void loadRegisters(int[] snapshot) {
        System.arraycopy(snapshot, 0, gpr, 0, Math.min(snapshot.length, gpr.length));
        gpr[0] = 0;
        pendingBranchTaken = false;
        pendingBranchTarget = 0;
        pendingLoadValid = false;
        pendingLoadRegister = 0;
        pendingLoadValue = 0;
        queuedLoadValid = false;
        queuedLoadRegister = 0;
        queuedLoadValue = 0;
        clearPendingDataRead();
        nextDataReadId = 1;
        currentInstructionDataReadShadowId = 0;
        recentTraceIndex = 0;
        totalCycles = 0;
        mulDivReadyCycle = 0;
        mulDivPending = false;
        pendingHi = hi;
        pendingLo = lo;
        gteBusyUntilCycle = 0;
        gteCommandStartCycle = 0;
        pendingGteCommand = 0;
        gteCommandPending = false;
        pendingGteWriteCount = 0;
        effectiveCop0Status = cop0.status();
        observedCop0Status = effectiveCop0Status;
        retiredInstructionCount = 0;
        pendingStatusEffectCount = 0;
        cop0ReadLatch = 0x20;
        cop0ReadLatchCycle = 0;
        cop0ReadLatchValid = false;
        cpuWedged = false;
        cop2EnableVisibleCycle = 0;
        lastCompletedInstructionWasGteCommand = false;
        lastCompletedInstructionWasDelaySlot = false;
        lastCompletedInstructionStartedWithInterruptPending = false;
        lastCompletedInstructionPc = pc;
        lastCompletedInstructionOpcode = 0;
        for (int i = 0; i < TRACE_LENGTH; i++) {
            recentPcs[i] = 0;
            recentOpcodes[i] = 0;
        }
        Arrays.fill(memoryLoadOrigin, false);
        clearRecentStackWrites();
    }

    public int hi() {
        serviceDeferredUnits(totalCycles);
        return hi;
    }

    public void setHi(int hi) {
        this.hi = hi;
    }

    public int lo() {
        serviceDeferredUnits(totalCycles);
        return lo;
    }

    public void setLo(int lo) {
        this.lo = lo;
    }

    public int nextPc() {
        return nextPc;
    }

    public void setPcState(int pc, int nextPc, boolean inDelaySlot) {
        this.pc = pc;
        this.nextPc = nextPc;
        this.inDelaySlot = inDelaySlot;
        this.pendingBranchTaken = false;
        this.pendingBranchTarget = 0;
    }

    private void promoteQueuedLoad() {
        pendingLoadValid = queuedLoadValid;
        pendingLoadRegister = queuedLoadRegister;
        pendingLoadValue = queuedLoadValue;
        queuedLoadValid = false;
        queuedLoadRegister = 0;
        queuedLoadValue = 0;
    }

    public boolean inDelaySlot() {
        return inDelaySlot;
    }

    public Cop0 cop0() {
        return cop0;
    }

    public Gte gte() {
        return gte;
    }

    private void requireCop0Usable(int currentPc, boolean branchDelay) {
        if ((effectiveCop0Status & (1 << 1)) != 0
            && (effectiveCop0Status & (1 << 28)) == 0) {
            raiseException(CpuException.COPROCESSOR_UNUSABLE, currentPc, branchDelay, 0);
        }
    }

    private void ensureMissingCoprocessorUsable(int coprocessorId, int currentPc, boolean branchDelay) {
        if ((effectiveCop0Status & (1 << (28 + coprocessorId))) == 0) {
            raiseException(CpuException.COPROCESSOR_UNUSABLE, currentPc, branchDelay, coprocessorId);
        }
    }

    private void ensureCop2Usable(int currentPc, boolean branchDelay) {
        if (!cop0Coprocessor2Enabled()) {
            raiseException(CpuException.COPROCESSOR_UNUSABLE, currentPc, branchDelay, 2);
        }
    }

    private int readCop0DataRegister(int register, int currentPc, boolean branchDelay) {
        int index = register & 31;
        if (index >= 16) {
            return readCop0GarbageRegister(index);
        }
        switch (index) {
            case 3, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15 -> {
                requireCop0Usable(currentPc, branchDelay);
                int value = cop0.readRegister(index);
                cop0ReadLatch = value;
                cop0ReadLatchCycle = currentCpuCycle();
                cop0ReadLatchValid = true;
                return value;
            }
            default -> {
                raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
                return 0;
            }
        }
    }

    private void writeCop0DataRegister(int register, int value, int currentPc, boolean branchDelay) {
        int index = register & 31;
        if (index >= 16) {
            return;
        }
        requireCop0Usable(currentPc, branchDelay);
        switch (index) {
            case 3, 5, 7, 9, 11, 12, 13 -> {
                cop0.writeRegister(index, value);
                if (index == 12) {
                    observedCop0Status = cop0.status();
                    scheduleCop0StatusEffects(observedCop0Status);
                }
            }
            case 6, 8, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31 -> {
                return;
            }
            default -> raiseException(CpuException.RESERVED_INSTRUCTION, currentPc, branchDelay, 0);
        }
    }

    private boolean isUserModeSegmentViolation(int address) {
        return (effectiveCop0Status & (1 << 1)) != 0 && (address & 0x8000_0000) != 0;
    }

    private void checkUserModeDataAccess(int address, boolean write) {
        if (isUserModeSegmentViolation(address)) {
            cop0.setBadVaddr(address);
            raiseException(write ? CpuException.ADDRESS_ERROR_STORE : CpuException.ADDRESS_ERROR_LOAD,
                currentInstructionPc, currentInstructionWasDelaySlot, 0);
        }
    }

    private boolean cop0Coprocessor2Enabled() {
        return (effectiveCop0Status & (1 << 30)) != 0;
    }

    private int readCop0GarbageRegister(int index) {
        // Unimplemented r16-r31 expose the previous COP0 result briefly, then 20h.
        if (cop0ReadLatchValid && currentCpuCycle() - cop0ReadLatchCycle <= 2) {
            return cop0ReadLatch;
        }
        return 0x20;
    }

    private void scheduleCop0StatusEffects(int value) {
        if (pendingStatusEffectCount >= STATUS_EFFECT_QUEUE_LENGTH) {
            throw new IllegalStateException("COP0 Status effect queue overflow");
        }
        // The adjacent pipeline slot uses the old MTC0 control state.
        pendingStatusEffectReadyInstruction[pendingStatusEffectCount] = retiredInstructionCount + 2;
        pendingStatusEffectValue[pendingStatusEffectCount] = value;
        pendingStatusEffectCount++;
    }

    private void applyReadyCop0StatusEffects() {
        int writeIndex = 0;
        for (int i = 0; i < pendingStatusEffectCount; i++) {
            if (pendingStatusEffectReadyInstruction[i] <= retiredInstructionCount) {
                effectiveCop0Status = pendingStatusEffectValue[i];
            } else {
                pendingStatusEffectReadyInstruction[writeIndex] = pendingStatusEffectReadyInstruction[i];
                pendingStatusEffectValue[writeIndex] = pendingStatusEffectValue[i];
                writeIndex++;
            }
        }
        pendingStatusEffectCount = writeIndex;
    }

    private void synchronizeExternalCop0StatusWrite() {
        int rawStatus = cop0.status();
        if (rawStatus == observedCop0Status) {
            return;
        }
        observedCop0Status = rawStatus;
        effectiveCop0Status = rawStatus;
        pendingStatusEffectCount = 0;
    }

    private void commitCop0StatusImmediately() {
        observedCop0Status = cop0.status();
        effectiveCop0Status = observedCop0Status;
        pendingStatusEffectCount = 0;
    }

    private void synchronizeGteReadAccess() {
        long before = currentCpuCycle();
        stallUntilCycle(gteBusyUntilCycle);
        if (gteInterlockActiveAt(before)) {
            currentInstructionCycles += 2;
        }
        completePendingGteCommand(currentCpuCycle());
        applyReadyGteWrites(currentCpuCycle());
    }

    private void synchronizeGteCommandAccess() {
        long before = currentCpuCycle();
        stallUntilCycle(gteBusyUntilCycle);
        if (gteInterlockActiveAt(before)) {
            currentInstructionCycles += 2;
        }
        completePendingGteCommand(currentCpuCycle());
        applyReadyGteWrites(currentCpuCycle());
    }

    private boolean gteInterlockActiveAt(long cycle) {
        return gteBusyUntilCycle > gteCommandStartCycle && cycle <= gteBusyUntilCycle;
    }

    private void writeGteData(int register, int value) {
        int index = register & 31;
        long issueCycle = currentCpuCycle();
        if (index == 28) {
            // IRGB writes its components over several clocks.
            queueGteDataComponentWrite(11, ((value >>> 10) & 0x1F) << 7,
                issueCycle, GTE_REGISTER_WRITE_DELAY_CYCLES, 0);
            queueGteDataComponentWrite(9, (value & 0x1F) << 7,
                issueCycle, GTE_IRGB_RED_GREEN_WRITE_DELAY_CYCLES, 1);
            queueGteDataComponentWrite(10, ((value >>> 5) & 0x1F) << 7,
                issueCycle, GTE_IRGB_RED_GREEN_WRITE_DELAY_CYCLES, 1);
            return;
        }
        queueGteDataComponentWrite(index, value, issueCycle,
            GTE_REGISTER_WRITE_DELAY_CYCLES, 0);
    }

    private void writeGteControl(int register, int value) {
        int index = register & 31;
        long issueCycle = currentCpuCycle();
        long readyCycle = gteRegisterWriteReadyCycle(issueCycle, GTE_REGISTER_WRITE_DELAY_CYCLES);
        if (gteCommandPending && issueCycle <= gteBusyUntilCycle
            && issueCycle - gteCommandStartCycle <= gteInputLatchBoundary(
                pendingGteCommand, true, index)) {
            pendingGte.writeControl(register, value);
        }
        queueGteRegisterWrite(readyCycle, index, value, true);
    }

    private void queueGteDataComponentWrite(int register, int value, long issueCycle,
                                            int delayCycles, int extraLatchDelay) {
        long readyCycle = gteRegisterWriteReadyCycle(issueCycle, delayCycles);
        int latchBoundary = gteCommandPending
            ? gteInputLatchBoundary(pendingGteCommand, false, register & 31)
            : -1;
        long commandOffset = issueCycle - gteCommandStartCycle + extraLatchDelay;
        if (gteCommandPending && issueCycle <= gteBusyUntilCycle) {
            if (commandOffset <= latchBoundary) {
                pendingGte.writeData(register, value);
            } else if (latchBoundary >= 0) {
                // The command has consumed the older input.
                readyCycle = Math.max(readyCycle, gteBusyUntilCycle);
            }
        }
        queueGteRegisterWrite(readyCycle, register, value, false);
    }

    private static long gteRegisterWriteReadyCycle(long issueCycle, int delayCycles) {
        // The issue clock is the first clock of the COP2 write path.
        return issueCycle + delayCycles;
    }

    private void queueGteRegisterWrite(long readyCycle, int register, int value, boolean control) {
        if (pendingGteWriteCount >= GTE_WRITE_QUEUE_LENGTH) {
            throw new IllegalStateException("GTE register write queue overflow");
        }
        pendingGteWriteReadyCycle[pendingGteWriteCount] = readyCycle;
        pendingGteWriteIndex[pendingGteWriteCount] = register & 31;
        pendingGteWriteValue[pendingGteWriteCount] = value;
        pendingGteWriteControl[pendingGteWriteCount] = control;
        pendingGteWriteCount++;
    }

    private void completePendingGteCommand(long cycle) {
        if (!gteCommandPending || cycle < gteBusyUntilCycle) {
            return;
        }
        pendingGte.execute(pendingGteCommand);
        gte.commitCommandResults(pendingGte, pendingGteCommand);
        gteCommandPending = false;
    }

    private static int gteInputLatchBoundary(int command, boolean control, int register) {
        return switch (command & 0x3F) {
            case 0x01 -> control
                ? switch (register) {
                    case 0, 1, 2, 3, 4, 5, 6, 7, 25 -> 0;
                    case 24, 26 -> 1;
                    case 28 -> 3;
                    case 27 -> 4;
                    default -> -1;
                }
                : switch (register) {
                    case 0, 1 -> 0;
                    default -> -1;
                };
            case 0x30 -> control
                ? switch (register) {
                    case 0 -> 2;
                    case 1, 2, 6, 25 -> 4;
                    case 3, 4 -> 0;
                    case 5, 7 -> 1;
                    case 24, 26 -> 5;
                    case 27 -> 7;
                    case 28 -> 6;
                    default -> -1;
                }
                : switch (register) {
                    case 0, 1, 3, 5 -> 0;
                    case 2 -> 3;
                    case 4 -> 2;
                    default -> -1;
                };
            case 0x1E -> lightingSingleBoundary(control, register, false, false);
            case 0x1B -> lightingSingleBoundary(control, register, true, false);
            case 0x13 -> lightingSingleBoundary(control, register, true, true);
            case 0x20 -> lightingTripleBoundary(control, register, 0);
            case 0x3F -> lightingTripleBoundary(control, register, 1);
            case 0x16 -> lightingTripleBoundary(control, register, 2);
            case 0x1C -> control
                ? switch (register) {
                    case 13, 14, 15, 16, 17, 18, 20 -> 0;
                    case 19 -> 1;
                    default -> -1;
                }
                : switch (register) {
                    case 6 -> 0;
                    case 9 -> 1;
                    case 10, 11 -> 2;
                    default -> -1;
                };
            case 0x14 -> control
                ? switch (register) {
                    case 13, 14, 15, 16, 17, 18, 20, 21, 23 -> 0;
                    case 19 -> 1;
                    case 22 -> 2;
                    default -> -1;
                }
                : switch (register) {
                    case 6 -> 1;
                    case 8, 9, 11 -> 2;
                    case 10 -> 3;
                    default -> -1;
                };
            case 0x10 -> control
                ? switch (register) {
                    case 21, 22, 23 -> 0;
                    default -> -1;
                }
                : switch (register) {
                    case 6 -> 0;
                    case 8 -> 1;
                    default -> -1;
                };
            case 0x2A -> control
                ? switch (register) {
                    case 21 -> 1;
                    case 22 -> 2;
                    case 23 -> 3;
                    default -> -1;
                }
                : switch (register) {
                    case 22 -> 0;
                    case 8, 20, 21 -> 4;
                    default -> -1;
                };
            case 0x29 -> control
                ? switch (register) {
                    case 21, 22, 23 -> 0;
                    default -> -1;
                }
                : switch (register) {
                    case 6, 8, 10 -> 0;
                    case 9, 11 -> 1;
                    default -> -1;
                };
            case 0x11 -> control
                ? switch (register) {
                    case 21, 22, 23 -> 0;
                    default -> -1;
                }
                : switch (register) {
                    case 9, 11 -> 0;
                    case 8, 10 -> 1;
                    default -> -1;
                };
            case 0x28 -> !control
                ? switch (register) {
                    case 9, 10 -> 0;
                    case 11 -> 1;
                    default -> -1;
                }
                : -1;
            case 0x0C -> control
                ? switch (register) {
                    case 0, 2, 4 -> 0;
                    default -> -1;
                }
                : switch (register) {
                    case 9, 11 -> 0;
                    case 10 -> 1;
                    default -> -1;
                };
            case 0x06 -> !control
                ? switch (register) {
                    case 12 -> 0;
                    case 13, 14 -> 1;
                    default -> -1;
                }
                : -1;
            case 0x2D -> control
                ? (register == 29 ? 0 : -1)
                : switch (register) {
                    case 17, 18, 19 -> 0;
                    default -> -1;
                };
            case 0x2E -> control
                ? (register == 30 ? 0 : -1)
                : switch (register) {
                    case 16, 17, 18, 19 -> 0;
                    default -> -1;
                };
            case 0x3D -> !control && register >= 8 && register <= 11 ? 0 : -1;
            case 0x3E -> !control && register >= 8 && register <= 11
                ? 0
                : !control && register >= 25 && register <= 27 ? 0 : -1;
            case 0x12 -> mvmvaLatchBoundary(command, control, register);
            default -> -1;
        };
    }

    private static int lightingSingleBoundary(
        boolean control, int register, boolean readsRgbc, boolean readsFarColor
    ) {
        if (!control) {
            return switch (register) {
                case 0 -> 0;
                case 1 -> readsRgbc ? 1 : 0;
                case 6 -> readsRgbc ? 3 : -1;
                default -> -1;
            };
        }
        return switch (register) {
            case 8, 9, 10, 11, 12, 13 -> 0;
            case 15, 17, 18 -> 1;
            case 14, 16, 19 -> 2;
            case 20 -> 3;
            case 21 -> readsFarColor ? 2 : -1;
            case 22 -> readsFarColor ? 3 : -1;
            case 23 -> readsFarColor ? 4 : -1;
            default -> -1;
        };
    }

    private static int lightingTripleBoundary(boolean control, int register, int variant) {
        if (!control) {
            return switch (register) {
                case 0 -> 0;
                case 1 -> variant == 2 ? 0 : 2;
                case 2 -> variant == 2 ? 0 : 0;
                case 3 -> 1;
                case 4 -> variant == 0 ? 1 : 3;
                case 5 -> variant == 0 || variant == 1 ? 3 : 4;
                case 6 -> variant == 0 ? -1 : variant == 1 ? 12 : 15;
                default -> -1;
            };
        }
        return switch (variant) {
            case 0 -> switch (register) {
                case 8, 9, 11, 12 -> 0;
                case 10 -> 3;
                case 13, 18, 19 -> 8;
                case 14, 15 -> 9;
                case 16, 20 -> 6;
                case 17 -> 3;
                default -> -1;
            };
            case 1 -> switch (register) {
                case 9 -> 0;
                case 8, 11, 12 -> 1;
                case 10, 17 -> 3;
                case 13, 15 -> 9;
                case 14 -> 6;
                case 16, 20 -> 5;
                case 18 -> 8;
                case 19 -> 7;
                default -> -1;
            };
            default -> switch (register) {
                case 9 -> 0;
                case 8 -> 1;
                case 10 -> 3;
                case 11, 12 -> 2;
                case 13, 14, 15, 18, 19 -> 7;
                case 16, 20 -> 5;
                case 17 -> 4;
                case 21 -> 13;
                case 22, 23 -> 14;
                default -> -1;
            };
        };
    }

    private static int mvmvaLatchBoundary(int command, boolean control, int register) {
        int matrix = (command >>> 17) & 3;
        int vector = (command >>> 15) & 3;
        int translation = (command >>> 13) & 3;
        if (!control) {
            if (matrix == 3 && (register == 6 || register == 8)) {
                return 1;
            }
            return switch (vector) {
                case 0 -> register == 0 ? 0 : register == 1 ? (matrix == 0 ? 0 : 2) : -1;
                case 1 -> register == 2 ? 0 : register == 3 ? 2 : -1;
                case 2 -> register == 4 ? 1 : register == 5 ? 2 : -1;
                default -> switch (register) {
                    case 9, 11 -> 1;
                    case 10 -> 0;
                    default -> -1;
                };
            };
        }
        if (matrix < 3) {
            int matrixBase = switch (matrix) {
                case 1 -> 8;
                case 2 -> 16;
                default -> 0;
            };
            if (register >= matrixBase && register <= matrixBase + 4) {
                return matrix == 0 ? 0 : 1;
            }
        } else if (register == 1 || register == 2) {
            return 1;
        }
        int translationBase = switch (translation) {
            case 0 -> 5;
            case 1 -> 13;
            case 2 -> 21;
            default -> -32;
        };
        return register >= translationBase && register <= translationBase + 2 ? 0 : -1;
    }

    private void applyReadyGteWrites(long cycle) {
        int writeIndex = 0;
        for (int i = 0; i < pendingGteWriteCount; i++) {
            if (pendingGteWriteReadyCycle[i] <= cycle) {
                if (pendingGteWriteControl[i]) {
                    gte.writeControl(pendingGteWriteIndex[i], pendingGteWriteValue[i]);
                } else {
                    gte.writeData(pendingGteWriteIndex[i], pendingGteWriteValue[i]);
                }
            } else {
                pendingGteWriteReadyCycle[writeIndex] = pendingGteWriteReadyCycle[i];
                pendingGteWriteIndex[writeIndex] = pendingGteWriteIndex[i];
                pendingGteWriteValue[writeIndex] = pendingGteWriteValue[i];
                pendingGteWriteControl[writeIndex] = pendingGteWriteControl[i];
                writeIndex++;
            }
        }
        pendingGteWriteCount = writeIndex;
    }

    private void stallUntilCycle(long targetCycle) {
        long currentCycle = currentCpuCycle();
        if (targetCycle > currentCycle) {
            currentInstructionCycles += (int) (targetCycle - currentCycle);
        }
    }

    private void retireCyclesToCurrentInstructionCycle() {
        int delta = currentInstructionCycles - advancedInstructionCycles;
        if (delta <= 0) {
            return;
        }
        advanceElapsedCpuCycles(delta);
    }

    private void advanceCpuStallCycles(int cycles) {
        if (cycles <= 0) {
            return;
        }
        currentInstructionCycles += cycles;
        advanceElapsedCpuCycles(cycles);
    }

    private void advanceElapsedCpuCycles(int cycles) {
        int remaining = cycles;
        while (remaining > 0) {
            int chunk = remaining;
            if (pendingDataReadValid && !pendingDataReadReady) {
                completePendingDataReadIfDue(elapsedCpuCycleBoundary());
                long untilRead = pendingDataReadReadyCycle - elapsedCpuCycleBoundary();
                if (untilRead > 0 && untilRead < chunk) {
                    chunk = (int) untilRead;
                }
            }
            advanceCpuClocks(chunk);
            advancedInstructionCycles += chunk;
            remaining -= chunk;
            if (pendingDataReadValid && !pendingDataReadReady) {
                completePendingDataReadIfDue(elapsedCpuCycleBoundary());
            }
        }
    }

    private void advanceCpuClocks(int cycles) {
        if (cycleAdvancer != null) {
            lastStepSystemCycles += cycleAdvancer.advanceCpuCycles(cycles);
        }
        // Bus transactions complete at the end of the elapsed CPU interval.
        bus.advanceCpuCycles(cycles);
    }

    private long elapsedCpuCycleBoundary() {
        return totalCycles + advancedInstructionCycles;
    }

    private long currentCpuCycle() {
        return totalCycles + currentInstructionCycles - 1L;
    }

    private void serviceDeferredUnits(long cycle) {
        if (mulDivPending && cycle >= mulDivReadyCycle) {
            hi = pendingHi;
            lo = pendingLo;
            mulDivPending = false;
        }
        if (gteCommandPending) {
            completePendingGteCommand(cycle);
        }
        if (pendingGteWriteCount > 0) {
            applyReadyGteWrites(cycle);
        }
    }

    private boolean shouldUseGteInterruptEpcQuirk() {
        return lastCompletedInstructionWasGteCommand
            && !lastCompletedInstructionWasDelaySlot
            && !lastCompletedInstructionStartedWithInterruptPending;
    }

    private void recordCompletedInstruction(int pc, int opcode, boolean branchDelay) {
        retiredInstructionCount++;
        applyReadyCop0StatusEffects();
        lastCompletedInstructionWasGteCommand = currentInstructionWasGteCommand;
        lastCompletedInstructionWasDelaySlot = branchDelay;
        lastCompletedInstructionStartedWithInterruptPending = currentInstructionStartedWithInterruptPending;
        lastCompletedInstructionPc = pc;
        lastCompletedInstructionOpcode = opcode;
    }

    private static int unsignedMultiplyLatency(int rsValue) {
        int highestBit = 31 - Integer.numberOfLeadingZeros(rsValue);
        if (highestBit <= 10) {
            return 6;
        }
        if (highestBit <= 19) {
            return 9;
        }
        return 13;
    }

    private static int signedMultiplyLatency(int rsValue) {
        if (rsValue >= -0x800 && rsValue <= 0x7FF) {
            return 6;
        }
        if ((rsValue >= 0x800 && rsValue <= 0xF_FFFF) || (rsValue >= -0xFFFFF && rsValue <= -0x801)) {
            return 9;
        }
        return 13;
    }

    private static boolean isControlTransferInstruction(int instruction) {
        int opcode = instruction >>> 26;
        if (opcode == 0x01 || (opcode >= 0x02 && opcode <= 0x07)) {
            return true;
        }
        if (opcode == 0x00) {
            int function = instruction & 0x3F;
            return function == 0x08 || function == 0x09;
        }
        if (opcode >= 0x10 && opcode <= 0x13) {
            return ((instruction >>> 21) & 0x1F) == 0x08;
        }
        return false;
    }

    private static int rs(int instruction) {
        return (instruction >>> 21) & 0x1F;
    }

    private static int rt(int instruction) {
        return (instruction >>> 16) & 0x1F;
    }

    private static int rd(int instruction) {
        return (instruction >>> 11) & 0x1F;
    }

    private static int sa(int instruction) {
        return (instruction >>> 6) & 0x1F;
    }

    private static int imm16u(int instruction) {
        return instruction & 0xFFFF;
    }

    private static int imm16s(int instruction) {
        return (short) (instruction & 0xFFFF);
    }

    private static int[][] copyCoprocessorLatches(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    private static void loadCoprocessorLatches(int[][] destination, int[][] source) {
        for (int i = 0; i < destination.length; i++) {
            Arrays.fill(destination[i], 0);
            if (source != null && i < source.length && source[i] != null) {
                System.arraycopy(source[i], 0, destination[i], 0,
                    Math.min(source[i].length, destination[i].length));
            }
        }
    }

    public static final class State {
        public int[] gpr;
        public boolean[] memoryLoadOrigin;
        public int[][] coprocessorDataLatches;
        public int[][] coprocessorControlLatches;
        public int pc;
        public int nextPc;
        public int hi;
        public int lo;
        public boolean inDelaySlot;
        public boolean pendingBranchTaken;
        public int pendingBranchTarget;
        public boolean pendingLoadValid;
        public int pendingLoadRegister;
        public int pendingLoadValue;
        public boolean queuedLoadValid;
        public int queuedLoadRegister;
        public int queuedLoadValue;
        public boolean pendingDataReadValid;
        public long pendingDataReadId;
        public long nextDataReadId;
        public int pendingDataReadAddress;
        public int pendingDataReadWidth;
        public int pendingDataReadKind;
        public int pendingDataReadRegister;
        public int pendingDataReadMergeValue;
        public int pendingDataReadOriginalAddress;
        public long pendingDataReadReadyCycle;
        public boolean pendingDataReadReady;
        public int pendingDataReadValue;
        public boolean pendingDataReadWritebackCancelled;
        public boolean pendingDataReadShadowPending;
        public long totalCycles;
        public long mulDivReadyCycle;
        public boolean mulDivPending;
        public int pendingHi;
        public int pendingLo;
        public long gteBusyUntilCycle;
        public long gteCommandStartCycle;
        public int pendingGteCommand;
        public boolean gteCommandPending;
        public int[] pendingGteData;
        public int[] pendingGteControl;
        public long[] pendingGteWriteReadyCycle;
        public int[] pendingGteWriteIndex;
        public int[] pendingGteWriteValue;
        public boolean[] pendingGteWriteControl;
        public int pendingGteWriteCount;
        public boolean statusPipelineStatePresent;
        public int effectiveCop0Status;
        public int observedCop0Status;
        public long retiredInstructionCount;
        public long[] pendingStatusEffectReadyInstruction;
        public int[] pendingStatusEffectValue;
        public int pendingStatusEffectCount;
        public int cop0ReadLatch;
        public long cop0ReadLatchCycle;
        public boolean cop0ReadLatchValid;
        public boolean cpuWedged;
        public long cop2EnableVisibleCycle;
        public boolean lastCompletedInstructionWasGteCommand;
        public boolean lastCompletedInstructionWasDelaySlot;
        public boolean lastCompletedInstructionStartedWithInterruptPending;
        public int lastCompletedInstructionPc;
        public int lastCompletedInstructionOpcode;
    }
}
