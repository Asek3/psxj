package nanolive.psxj.emu.dma;

public final class DmaChannel {

    private int baseAddress;
    private int blockControl;
    private int channelControl;
    private int choppingCpuWindow;
    private int choppingDmaWindow;

    public int baseAddress() {
        return baseAddress & 0x00FF_FFFF;
    }

    public int transferAddress() {
        return baseAddress & 0x00FF_FFFC;
    }

    public void setBaseAddress(int baseAddress) {
        this.baseAddress = baseAddress & 0x00FF_FFFF;
    }

    public int blockControl() {
        return blockControl;
    }

    public void setBlockControl(int blockControl) {
        this.blockControl = blockControl;
    }

    public int channelControl() {
        return channelControl;
    }

    public void setChannelControl(int channelControl) {
        this.channelControl = channelControl;
        choppingDmaWindow = 1 << ((channelControl >>> 16) & 0x7);
        choppingCpuWindow = 1 << ((channelControl >>> 20) & 0x7);
    }

    public boolean enabled() {
        return (channelControl & (1 << 24)) != 0;
    }

    public boolean trigger() {
        return (channelControl & (1 << 28)) != 0;
    }

    public boolean pauseOrRetainTrigger() {
        return (channelControl & (1 << 29)) != 0;
    }

    public int syncMode() {
        return (channelControl >>> 9) & 0x3;
    }

    // DMA CHCR bit0 uses 0=device->RAM and 1=RAM->device.
    public boolean fromRam() {
        return (channelControl & 0x1) != 0;
    }

    public boolean stepBackwards() {
        return (channelControl & 0x2) != 0;
    }

    public boolean choppingEnabled() {
        return (channelControl & (1 << 8)) != 0;
    }

    public int choppingCpuWindow() {
        return Math.max(1, choppingCpuWindow);
    }

    public int choppingDmaWindow() {
        return Math.max(1, choppingDmaWindow);
    }

    public int wordCount() {
        int words = blockControl & 0xFFFF;
        return words == 0 ? 0x10000 : words;
    }

    public int blockCount() {
        int blocks = (blockControl >>> 16) & 0xFFFF;
        return blocks == 0 ? 0x10000 : blocks;
    }

    public void finish() {
        channelControl &= ~(1 << 24);
        channelControl &= ~(1 << 28);
    }

    public State copyState() {
        State state = new State();
        state.baseAddress = baseAddress;
        state.blockControl = blockControl;
        state.channelControl = channelControl;
        state.choppingCpuWindow = choppingCpuWindow;
        state.choppingDmaWindow = choppingDmaWindow;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        baseAddress = state.baseAddress;
        blockControl = state.blockControl;
        channelControl = state.channelControl;
        choppingCpuWindow = state.choppingCpuWindow;
        choppingDmaWindow = state.choppingDmaWindow;
    }

    public static final class State {
        int baseAddress;
        int blockControl;
        int channelControl;
        int choppingCpuWindow;
        int choppingDmaWindow;
    }
}
