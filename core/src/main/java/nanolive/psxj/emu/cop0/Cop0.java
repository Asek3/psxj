package nanolive.psxj.emu.cop0;

import java.util.Arrays;

public final class Cop0 {

    private static final int REG_BPC = 3;
    private static final int REG_BDA = 5;
    private static final int REG_BAD_VADDR = 8;
    private static final int REG_BDAM = 9;
    private static final int REG_BPCM = 11;
    private static final int REG_STATUS = 12;
    private static final int REG_CAUSE = 13;
    private static final int REG_EPC = 14;
    private static final int REG_PRID = 15;
    private static final int REG_TAR = 6;
    private static final int REG_DCIC = 7;
    private static final int STATUS_CM = 1 << 19;
    private static final int STATUS_USER_MODE = 1 << 1;
    private static final int DCIC_BREAK = 1;
    private static final int DCIC_PROGRAM_COUNTER = 1 << 1;
    private static final int DCIC_DATA_ADDRESS = 1 << 2;
    private static final int DCIC_DATA_READ = 1 << 3;
    private static final int DCIC_DATA_WRITE = 1 << 4;
    private static final int DCIC_TRACE = 1 << 5;
    private static final int DCIC_JUMP_REDIRECTION_MASK = 0x3 << 12;
    private static final int DCIC_DEBUG_ENABLE = 1 << 23;
    private static final int DCIC_PROGRAM_COUNTER_ENABLE = 1 << 24;
    private static final int DCIC_DATA_ADDRESS_ENABLE = 1 << 25;
    private static final int DCIC_DATA_READ_ENABLE = 1 << 26;
    private static final int DCIC_DATA_WRITE_ENABLE = 1 << 27;
    private static final int DCIC_TRACE_ENABLE = 1 << 28;
    private static final int DCIC_KERNEL_DEBUG_ENABLE = 1 << 29;
    private static final int DCIC_USER_DEBUG_ENABLE = 1 << 30;
    private static final int DCIC_TRAP_ENABLE = 1 << 31;
    // R3000A SR fields implemented by the PSX CPU.
    private static final int STATUS_WRITE_MASK = 0xF27F_FF3F;
    // LSI LR33300 debug/cache control register writable fields.
    private static final int DCIC_WRITE_MASK = 0xFF80_F03F;
    private static final int CAUSE_SOFTWARE_INTERRUPT_MASK = 0x0000_0300;
    private static final int CAUSE_INTERRUPT_PENDING_MASK = 0x0000_FF00;
    private static final int RESET_STATUS = (1 << 22) | (1 << 21);

    private final int[] registers = new int[32];

    public void reset() {
        Arrays.fill(registers, 0);
        registers[REG_STATUS] = RESET_STATUS;
        registers[REG_PRID] = 0x0000_0002;
    }

    public int readRegister(int index) {
        return registers[index & 31];
    }

    public int[] copyRawRegisters() {
        return registers.clone();
    }

    public void loadRawRegisters(int[] snapshot) {
        if (snapshot == null) {
            return;
        }
        Arrays.fill(registers, 0);
        System.arraycopy(snapshot, 0, registers, 0, Math.min(snapshot.length, registers.length));
        registers[REG_STATUS] &= STATUS_WRITE_MASK;
        registers[REG_PRID] = 0x0000_0002;
    }

    public void writeRegister(int index, int value) {
        switch (index & 31) {
            case REG_TAR, REG_BAD_VADDR, REG_EPC, REG_PRID -> {
                return;
            }
            case REG_CAUSE -> {
                // On R3000A only software interrupt pending bits IP0/IP1 are writable.
                registers[REG_CAUSE] = (registers[REG_CAUSE] & ~CAUSE_SOFTWARE_INTERRUPT_MASK)
                    | (value & CAUSE_SOFTWARE_INTERRUPT_MASK);
            }
            case REG_STATUS -> registers[REG_STATUS] = value & STATUS_WRITE_MASK;
            case REG_DCIC -> registers[REG_DCIC] =
                (registers[REG_DCIC] & ~DCIC_WRITE_MASK) | (value & DCIC_WRITE_MASK);
            case REG_BPC, REG_BDA, REG_BDAM, REG_BPCM -> registers[index & 31] = value;
            default -> registers[index & 31] = value;
        }
    }

    public void setBadVaddr(int value) {
        registers[REG_BAD_VADDR] = value;
    }

    public int status() {
        return registers[REG_STATUS];
    }

    public void setCacheIsolatedLoadResult(boolean hit) {
        if (hit) {
            registers[REG_STATUS] |= STATUS_CM;
        } else {
            registers[REG_STATUS] &= ~STATUS_CM;
        }
    }

    public int cause() {
        return registers[REG_CAUSE];
    }

    public int epc() {
        return registers[REG_EPC];
    }

    public boolean interruptsEnabled() {
        return (registers[REG_STATUS] & 0x1) != 0;
    }

    public boolean userMode() {
        return (registers[REG_STATUS] & 0x2) != 0;
    }

    public boolean coprocessorEnabled(int coprocessorId) {
        if (coprocessorId == 0) {
            return !userMode() || ((registers[REG_STATUS] & (1 << 28)) != 0);
        }
        return (registers[REG_STATUS] & (1 << (28 + coprocessorId))) != 0;
    }

    public void setHardwareInterruptLine(boolean active) {
        if (active) {
            registers[REG_CAUSE] |= 1 << 10;
        } else {
            registers[REG_CAUSE] &= ~(1 << 10);
        }
    }

    public boolean shouldTakeInterrupt() {
        return shouldTakeInterrupt(registers[REG_STATUS]);
    }

    public boolean shouldTakeInterrupt(int effectiveStatus) {
        return (effectiveStatus & registers[REG_CAUSE] & 0x0000_FF00) != 0
            && (effectiveStatus & 0x1) != 0;
    }

    public boolean instructionBreakpointChecksEnabled(int effectiveStatus) {
        int dcic = registers[REG_DCIC];
        return (dcic & DCIC_DEBUG_ENABLE) != 0
            && (dcic & (DCIC_KERNEL_DEBUG_ENABLE | DCIC_USER_DEBUG_ENABLE)) != 0
            && (dcic & (DCIC_PROGRAM_COUNTER_ENABLE | DCIC_TRACE_ENABLE)) != 0;
    }

    public boolean traceBreakpointCheckEnabled() {
        return (registers[REG_DCIC] & DCIC_TRACE_ENABLE) != 0;
    }

    public boolean testProgramBreakpoint(int address, int effectiveStatus) {
        int dcic = registers[REG_DCIC];
        int mask = registers[REG_BPCM];
        if (!debugAddressEnabled(dcic, address)
            || (dcic & DCIC_PROGRAM_COUNTER_ENABLE) == 0
            || mask == 0
            || (((address ^ registers[REG_BPC]) & mask) != 0)) {
            return false;
        }
        registers[REG_DCIC] |= DCIC_BREAK | DCIC_PROGRAM_COUNTER;
        return (dcic & DCIC_TRAP_ENABLE) != 0;
    }

    public boolean testDataBreakpoint(int address, boolean write, int effectiveStatus) {
        int dcic = registers[REG_DCIC];
        int directionEnable = write ? DCIC_DATA_WRITE_ENABLE : DCIC_DATA_READ_ENABLE;
        int mask = registers[REG_BDAM];
        if (!debugAddressEnabled(dcic, address)
            || (dcic & DCIC_DATA_ADDRESS_ENABLE) == 0
            || (dcic & directionEnable) == 0
            || mask == 0
            || (((address ^ registers[REG_BDA]) & mask) != 0)) {
            return false;
        }
        registers[REG_DCIC] |= DCIC_BREAK | DCIC_DATA_ADDRESS
            | (write ? DCIC_DATA_WRITE : DCIC_DATA_READ);
        return (dcic & DCIC_TRAP_ENABLE) != 0;
    }

    public boolean testTraceBreakpoint(boolean controlTransfer, int effectiveStatus, int address) {
        int dcic = registers[REG_DCIC];
        if (!controlTransfer
            || !debugAddressEnabled(dcic, address)
            || (dcic & DCIC_TRACE_ENABLE) == 0) {
            return false;
        }
        registers[REG_DCIC] |= DCIC_BREAK | DCIC_TRACE;
        return (dcic & DCIC_TRAP_ENABLE) != 0;
    }

    public boolean jumpRedirectionEnabled() {
        return (registers[REG_DCIC] & DCIC_JUMP_REDIRECTION_MASK) != 0;
    }

    private static boolean debugAddressEnabled(int dcic, int address) {
        if ((dcic & DCIC_DEBUG_ENABLE) == 0) {
            return false;
        }
        return address < 0
            ? (dcic & DCIC_KERNEL_DEBUG_ENABLE) != 0
            : (dcic & DCIC_USER_DEBUG_ENABLE) != 0;
    }

    public void enterException(CpuException exception, int currentPc, boolean branchDelay, int coprocessorId, boolean branchTaken, int branchTarget) {
        enterException(exception, currentPc, branchDelay, coprocessorId,
            branchTaken, branchTarget, registers[REG_STATUS]);
    }

    public void enterException(CpuException exception, int currentPc, boolean branchDelay,
                               int coprocessorId, boolean branchTaken, int branchTarget,
                               int effectiveStatus) {
        int status = effectiveStatus & STATUS_WRITE_MASK;
        registers[REG_STATUS] = (status & ~0x3F) | ((status << 2) & 0x3F);

        int cause = registers[REG_CAUSE] & CAUSE_INTERRUPT_PENDING_MASK;
        cause |= exception.code() << 2;
        if (branchDelay) {
            cause |= 1 << 31;
            registers[REG_TAR] = branchTarget;
            if (branchTaken) {
                cause |= 1 << 30;
            }
        }
        cause |= (coprocessorId & 0x3) << 28;
        registers[REG_CAUSE] = cause;
        registers[REG_EPC] = branchDelay ? currentPc - 4 : currentPc;
    }

    public void returnFromException() {
        int status = registers[REG_STATUS];
        registers[REG_STATUS] = (status & ~0x0F) | ((status >>> 2) & 0x0F);
    }

    public int exceptionVector() {
        return exceptionVector(false);
    }

    public int exceptionVector(boolean debugBreakpoint) {
        if (debugBreakpoint) {
            return (registers[REG_STATUS] & (1 << 22)) != 0 ? 0xBFC0_0140 : 0x8000_0040;
        }
        return (registers[REG_STATUS] & (1 << 22)) != 0 ? 0xBFC0_0180 : 0x8000_0080;
    }
}
