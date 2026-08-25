package nanolive.psxj.emu.cop0;

public enum CpuException {
    INTERRUPT(0x0),
    ADDRESS_ERROR_LOAD(0x4),
    ADDRESS_ERROR_STORE(0x5),
    BUS_ERROR_FETCH(0x6),
    BUS_ERROR_LOAD_STORE(0x7),
    SYSCALL(0x8),
    BREAKPOINT(0x9),
    RESERVED_INSTRUCTION(0xA),
    COPROCESSOR_UNUSABLE(0xB),
    OVERFLOW(0xC);

    private final int code;

    CpuException(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
