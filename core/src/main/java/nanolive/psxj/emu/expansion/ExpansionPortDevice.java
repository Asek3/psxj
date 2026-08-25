package nanolive.psxj.emu.expansion;

/**
 * Electrical endpoint connected to the parallel expansion bus.
 *
 * <p>The {@code widthBytes} argument is the width of one physical bus strobe,
 * not the width of the original CPU instruction.  Consequently a 32-bit CPU
 * access can arrive as four 8-bit strobes, two 16-bit strobes, or one 32-bit
 * wide-DMA strobe.  The address is the complete physical address placed on
 * the external bus.</p>
 */
public interface ExpansionPortDevice {

    int read(int address, int widthBytes);

    void write(int address, int value, int widthBytes);

    // State of the DMA request pin for PIO channel 5.
    default boolean dmaRequest(boolean fromRam) {
        return false;
    }

    // Returns the current level of the expansion/light-pen IRQ10 input.
    default boolean interruptRequest() {
        return false;
    }

    // Advances devices which contain their own clocked state machine.
    default void tick(int cycles) {
    }
}
