package nanolive.psxj.emu.cop0;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class Cop0Test {

    @Test
    void resetMatchesRetailR3000aCop0State() {
        Cop0 cop0 = new Cop0();
        cop0.reset();

        assertEquals(0x0060_0000, cop0.status());
        assertEquals(0x0000_0002, cop0.readRegister(15));
    }

    @Test
    void statusAndDcicOnlyAcceptHardwareWritableBits() {
        Cop0 cop0 = new Cop0();
        cop0.reset();
        cop0.writeRegister(12, -1);
        assertEquals(0xF27F_FF3F, cop0.status());

        cop0.writeRegister(12, 0);
        assertEquals(0, cop0.status(), "MTC0 SR must clear the unimplemented bits");

        cop0.writeRegister(7, -1);
        assertEquals(0xFF80_F03F, cop0.readRegister(7));
    }

    @Test
    void causeWriteOnlyChangesSoftwareInterruptPendingBits() {
        Cop0 cop0 = new Cop0();
        cop0.reset();
        cop0.setHardwareInterruptLine(true);

        cop0.writeRegister(13, -1);

        assertEquals(0x0000_0700, cop0.cause());
    }

    @Test
    void exceptionPreservesInterruptPendingAndSetsDelayMetadata() {
        Cop0 cop0 = new Cop0();
        cop0.reset();
        cop0.setHardwareInterruptLine(true);
        cop0.writeRegister(13, 0x0000_0300);

        cop0.enterException(CpuException.ADDRESS_ERROR_LOAD, 0x0000_0104,
            true, 0, true, 0x0000_0200);

        assertEquals(0xC000_0710, cop0.cause());
        assertEquals(0x0000_0100, cop0.epc());
        assertEquals(0x0000_0200, cop0.readRegister(6));
    }

    @Test
    void rfeRestoresPreviousModeWithoutDestroyingOldModeBits() {
        Cop0 cop0 = new Cop0();
        cop0.reset();
        cop0.writeRegister(12, (cop0.status() & ~0x3F) | 0b10_11_00);

        cop0.returnFromException();

        assertEquals(0b10_10_11, cop0.status() & 0x3F);
    }

    @Test
    void exceptionOwnedRegistersIgnoreMtc0Writes() {
        Cop0 cop0 = new Cop0();
        cop0.reset();
        cop0.setBadVaddr(0x1234_5678);
        cop0.enterException(CpuException.SYSCALL, 0x8000_1000,
            false, 0, false, 0);

        cop0.writeRegister(6, -1);
        cop0.writeRegister(8, -1);
        cop0.writeRegister(14, -1);
        cop0.writeRegister(15, -1);

        assertEquals(0, cop0.readRegister(6));
        assertEquals(0x1234_5678, cop0.readRegister(8));
        assertEquals(0x8000_1000, cop0.readRegister(14));
        assertEquals(2, cop0.readRegister(15));
    }
}
