package nanolive.psxj.emu.dma;

public interface DmaPort {
    int read();
    void write(int value);

    default boolean dmaRequest() {
        return false;
    }

    default boolean dmaRequest(boolean fromRam) {
        return dmaRequest();
    }

    default boolean dmaRequestStableFor(boolean fromRam, int cycles) {
        return false;
    }
}
