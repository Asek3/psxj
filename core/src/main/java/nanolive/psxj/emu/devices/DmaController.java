package nanolive.psxj.emu.devices;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.dma.DmaChannel;
import nanolive.psxj.emu.dma.DmaPort;
import nanolive.psxj.util.Log;

import java.util.Arrays;

public final class DmaController {

    private static final int OTC_END_MARKER = 0x00FF_FFFF;
    private static final int DMA_ADDRESS_MASK = 0x00FF_FFFC;
    private static final int DMA_UNKNOWN_F8_RESET = 0x7FFA_C68B;
    private static final int DMA_UNKNOWN_F8_AFTER_TRANSFER = 0x7FE3_58D1;
    private static final int DMA_UNKNOWN_FC = 0x00FF_FFF7;
    private static final int LINKED_LIST_HEADER_READ_CYCLES = 8;
    private static final int LINKED_LIST_BLOCK_SETUP_CYCLES = 5;
    private static final int GPU_REQUEST_BLOCK_GAP_CYCLES = 10;
    private static final int CHOPPING_SLICE_ARBITRATION_CYCLES = 6;
    private static final int CHCR_GENERAL_WRITE_MASK = 0x3
        | (1 << 8)
        | (0x3 << 9)
        | (0x7 << 16)
        | (0x7 << 20)
        | (1 << 24)
        | (1 << 28)
        | (1 << 29)
        | (1 << 30);
    private static final DmaPort PIO_PORT = new DmaPort() {
        @Override
        public int read() {
            return 0xFFFF_FFFF;
        }

        @Override
        public void write(int value) {
        }

        @Override
        public boolean dmaRequest(boolean fromRam) {
            return true;
        }
    };

    private final InterruptController interruptController;
    private final DmaChannel[] channels = new DmaChannel[7];
    private final DmaPort[] ports = new DmaPort[7];
    private final int[] remainingWords = new int[7];
    private final int[] remainingBlocks = new int[7];
    private final int[] blockWordsRemaining = new int[7];
    private final int[] currentAddress = new int[7];
    private final int[] linkedListNextAddress = new int[7];
    private final int[] linkedListNodeWords = new int[7];
    private final int[] channelCycleCarry = new int[7];
    private final int[] dramBurstPosition = new int[7];
    private final int[] choppingCpuWindowRemaining = new int[7];
    private final int[] choppingDmaWordsRemaining = new int[7];
    private final int[] requestBlockGapRemaining = new int[7];
    private final boolean[] linkedListEnd = new boolean[7];
    private final boolean[] transferStarted = new boolean[7];
    private Bus bus;
    private Gpu gpu;
    private int dpcr = 0x0765_4321;
    private int dicr;
    private boolean dmaIrqMasterFlag;
    private int unknownF8 = DMA_UNKNOWN_F8_RESET;
    private int enabledChannelMask;
    private int cpuWindowChannelMask;
    private boolean sharedBusOwnedLastTick;
    private int sharedBusOwnedCyclesPending;

    public DmaController(InterruptController interruptController) {
        this.interruptController = interruptController;
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new DmaChannel();
        }
        channels[6].setChannelControl(normalizeChcr(6, 0));
        ports[5] = PIO_PORT;
    }

    public void setBus(Bus bus) {
        this.bus = bus;
    }

    public void setGpu(Gpu gpu) {
        this.gpu = gpu;
    }

    public void attachPort(int index, DmaPort port) {
        ports[index & 0x7] = port;
    }

    public int read32(int address) {
        address = normalizeRegisterAddress(address);
        int offset = address - 0x1F80_1080;
        if (offset >= 0 && offset < 0x70) {
            int channel = offset / 0x10;
            int register = offset & 0xF;
            DmaChannel dmaChannel = channels[channel];
            return switch (register) {
                case 0x0 -> dmaChannel.baseAddress();
                case 0x4 -> dmaChannel.blockControl();
                case 0x8 -> normalizeChcr(channel, dmaChannel.channelControl());
                default -> 0;
            };
        }
        return switch (address) {
            case 0x1F80_10F0 -> dpcr;
            case 0x1F80_10F4 -> dicr;
            case 0x1F80_10F8 -> unknownF8;
            case 0x1F80_10FC -> DMA_UNKNOWN_FC;
            default -> 0;
        };
    }

    public int read16(int address) {
        int normalized = normalizeRegisterAddress(address);
        int word = read32(normalized & ~0x3);
        return (word >>> ((normalized & 0x2) * 8)) & 0xFFFF;
    }

    public int read8(int address) {
        int normalized = normalizeRegisterAddress(address);
        int word = read32(normalized & ~0x3);
        return (word >>> ((normalized & 0x3) * 8)) & 0xFF;
    }

    public void write32(int address, int value) {
        address = normalizeRegisterAddress(address);
        int offset = address - 0x1F80_1080;
        if (offset >= 0 && offset < 0x70) {
            int channel = offset / 0x10;
            int register = offset & 0xF;
            DmaChannel dmaChannel = channels[channel];
            switch (register) {
                case 0x0 -> {
                    dmaChannel.setBaseAddress(value);
                    reinitializePendingTransfer(channel);
                }
                case 0x4 -> {
                    dmaChannel.setBlockControl(value);
                    reinitializePendingTransfer(channel);
                }
                case 0x8 -> {
                    boolean wasEnabled = dmaChannel.enabled();
                    dmaChannel.setChannelControl(normalizeChcr(channel, value));
                    updateEnabledChannel(channel);
                    if (dmaChannel.enabled() && !wasEnabled) {
                        initializeTransferState(channel);
                    } else if (!dmaChannel.enabled()) {
                        clearTransferState(channel);
                    }
                }
                default -> {
                    return;
                }
            }
            return;
        }

        switch (address) {
            case 0x1F80_10F0 -> dpcr = value;
            case 0x1F80_10F4 -> dicr = mergeDicr(value);
            default -> {
            }
        }
    }

    public void write16(int address, int value) {
        writeSized(address, value, 2);
    }

    public void write8(int address, int value) {
        writeSized(address, value, 1);
    }

    private void writeSized(int address, int value, int widthBytes) {
        int normalized = normalizeRegisterAddress(address);
        int wordAddress = normalized & ~0x3;
        int shift = (normalized & 0x3) * 8;
        int shiftedValue = value << shift;
        write32(wordAddress, shiftedValue);
    }

    private static int normalizeRegisterAddress(int address) {
        if (address >= 0x1F80_1080 && address <= 0x1F80_10EF && (address & 0xF) >= 0xC) {
            return address - 0x4;
        }
        return address;
    }

    private int mergeDicr(int value) {
        boolean oldMasterFlag = dmaIrqMasterFlag;
        int ackMask = (value >>> 24) & 0x7F;
        int flags = (dicr >>> 24) & 0x7F;
        flags &= ~ackMask;
        int result = dicr;
        result = (result & ~0x00FF_8000) | (value & 0x00FF_8000);
        result = (result & ~0x0000_007F) | (value & 0x0000_007F);
        result = (result & ~0x7F00_0000) | (flags << 24);
        result = refreshMasterFlag(result);
        syncInterruptLine(oldMasterFlag, dmaIrqMasterFlag);
        return result;
    }

    private int refreshMasterFlag(int value) {
        int flags = (value >>> 24) & 0x7F;
        int forceIrq = (value >>> 15) & 0x1;
        int masterEnable = (value >>> 23) & 0x1;
        dmaIrqMasterFlag = forceIrq != 0 || (masterEnable != 0 && flags != 0);
        if (dmaIrqMasterFlag) {
            value |= 0x8000_0000;
        } else {
            value &= ~0x8000_0000;
        }
        return value;
    }

    private void syncInterruptLine(boolean oldMasterFlag, boolean newMasterFlag) {
        if (!oldMasterFlag && newMasterFlag) {
            interruptController.raise(3);
        } else if (oldMasterFlag && !newMasterFlag) {
            interruptController.clear(3);
        }
    }

    public void tick(int cycles) {
        sharedBusOwnedLastTick = false;
        if (cycles <= 0 || bus == null || enabledChannelMask == 0) {
            return;
        }
        if (cycles == 1 && serviceSoleGpuLinkedListClock()) {
            return;
        }
        int remainingCycles = cycles;
        int ownedCycles = 0;
        while (remainingCycles > 0) {
            int existingWindowMask = cpuWindowMask();
            int channel = highestPriorityRunnableChannel();
            if (channel < 0) {
                int cpuWindowCycles = activeCpuWindowCycles();
                if (cpuWindowCycles <= 0) {
                    break;
                }
                int elapsed = Math.min(remainingCycles, cpuWindowCycles);
                decrementCpuWindows(elapsed, existingWindowMask);
                remainingCycles -= elapsed;
                continue;
            }
            sharedBusOwnedLastTick = true;
            int consumed = serviceChannel(channel, remainingCycles);
            if (consumed <= 0) {
                break;
            }
            ownedCycles += Math.min(consumed, remainingCycles);
            remainingCycles -= consumed;
            decrementCpuWindows(consumed, existingWindowMask);
            int syncMode = channels[channel].syncMode();
            if (syncMode == 1 || syncMode == 2) {
                decrementCpuWindows(remainingCycles, cpuWindowMask());
                break;
            }
        }
        sharedBusOwnedCyclesPending = (int) Math.min(Integer.MAX_VALUE,
            (long) sharedBusOwnedCyclesPending + ownedCycles);
    }

    // Fast path for GPU list DMA.
    private boolean serviceSoleGpuLinkedListClock() {
        final int index = 2;
        if (enabledChannelMask != (1 << index) || cpuWindowChannelMask != 0) {
            return false;
        }
        DmaChannel channel = channels[index];
        if (gpu == null || !channel.fromRam() || channel.syncMode() != 2
            || ((dpcr >>> (index * 4 + 3)) & 1) == 0) {
            return false;
        }
        if (!channelRunnable(index, channel, ports[index])) {
            return true;
        }
        sharedBusOwnedLastTick = true;
        int consumed = serviceChannel(index, 1);
        if (consumed > 0) {
            sharedBusOwnedCyclesPending = (int) Math.min(Integer.MAX_VALUE,
                (long) sharedBusOwnedCyclesPending + 1L);
        }
        return true;
    }

    public boolean tickSoleGpuLinkedListClockAndReportActive() {
        sharedBusOwnedLastTick = false;
        final int index = 2;
        DmaChannel channel = channels[index];
        if (linkedListNodeWords[index] > 0) {
            if (!gpu.canAcceptDmaBlockWord()) {
                return true;
            }
            sharedBusOwnedLastTick = true;
            if (sharedBusOwnedCyclesPending != Integer.MAX_VALUE) {
                sharedBusOwnedCyclesPending++;
            }
            int credit = channelCycleCarry[index] + 1;
            int wordCycles = dmaWordCycleCost(index, dramBurstPosition[index]);
            if (credit < wordCycles) {
                channelCycleCarry[index] = credit;
                return true;
            }
            channelCycleCarry[index] = credit - wordCycles;
            int address = currentAddress[index] & DMA_ADDRESS_MASK;
            if (!dmaRamAddressValid(index, address)) {
                return false;
            }
            gpu.gp0(bus.read32(address));
            currentAddress[index] = (address + 4) & DMA_ADDRESS_MASK;
            linkedListNodeWords[index]--;
            dramBurstPosition[index] = (dramBurstPosition[index] + 1) & 0xF;
            if (linkedListNodeWords[index] == 0) {
                if (linkedListEnd[index]) {
                    channel.setBaseAddress(linkedListNextAddress[index]);
                    finishChannel(index, channel);
                    clearTransferState(index);
                    signalChannelEvent(index, true);
                    unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
                    return false;
                }
                currentAddress[index] = linkedListNextAddress[index] & DMA_ADDRESS_MASK;
                signalChannelEvent(index, false);
            }
            channel.setBaseAddress(currentAddress[index]);
            return (enabledChannelMask & (1 << index)) != 0;
        }
        if (!channelRunnable(index, channel, ports[index])) {
            return true;
        }
        sharedBusOwnedLastTick = true;
        int consumed = serviceChannel(index, 1);
        if (consumed > 0 && sharedBusOwnedCyclesPending != Integer.MAX_VALUE) {
            sharedBusOwnedCyclesPending++;
        }
        return (enabledChannelMask & (1 << index)) != 0;
    }

    public boolean arbitrationIdleFor(int cycles) {
        if (cycles <= 0 || bus == null) {
            sharedBusOwnedLastTick = false;
            return true;
        }
        if (cpuWindowChannelMask != 0) {
            return false;
        }
        int candidates = enabledChannelMask;
        while (candidates != 0) {
            int index = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            DmaChannel channel = channels[index];
            DmaPort port = ports[index];
            if (channelRunnable(index, channel, port)) {
                return false;
            }
            if (staticallyBlocked(index, channel)) {
                continue;
            }
            if (port == null || !port.dmaRequestStableFor(channel.fromRam(), cycles)) {
                return false;
            }
        }
        sharedBusOwnedLastTick = false;
        return true;
    }

    public int cyclesUntilNextArbitrationBoundary(int maximumCycles) {
        int limit = Math.max(1, maximumCycles);
        if (bus == null || enabledChannelMask == 0 || limit == 1) {
            return 1;
        }
        int index = highestPriorityRunnableChannel();
        if (index < 0) {
            return 1;
        }
        DmaChannel channel = channels[index];
        int clocks;
        if (index == 2 && channel.syncMode() == 2) {
            if (linkedListNodeWords[index] > 0) {
                clocks = dmaWordCycleCost(index, dramBurstPosition[index])
                    - channelCycleCarry[index];
            } else {
                int rawAddress = currentAddress[index] & 0x00FF_FFFF;
                if (isLinkedListEndMarker(rawAddress)
                    || !bus.isDmaRamAddress(rawAddress & DMA_ADDRESS_MASK)) {
                    clocks = 1;
                } else {
                    int header = bus.read32(rawAddress & DMA_ADDRESS_MASK);
                    int words = (header >>> 24) & 0xFF;
                    clocks = LINKED_LIST_HEADER_READ_CYCLES
                        + (words > 0 ? LINKED_LIST_BLOCK_SETUP_CYCLES : 0)
                        - channelCycleCarry[index];
                }
            }
        } else {
            clocks = dmaWordCycleCost(index, dramBurstPosition[index])
                - channelCycleCarry[index];
        }
        clocks = Math.clamp(clocks, 1, limit);
        if ((enabledChannelMask & (enabledChannelMask - 1)) != 0
            && !higherPriorityChannelsStableFor(index, clocks)) {
            return 1;
        }
        return clocks;
    }

    private boolean higherPriorityChannelsStableFor(int selected, int cycles) {
        int candidates = enabledChannelMask & ~(1 << selected);
        int selectedPriority = channelPriority(selected);
        while (candidates != 0) {
            int index = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            int priority = channelPriority(index);
            if (priority > selectedPriority
                || (priority == selectedPriority && index < selected)) {
                continue;
            }
            DmaChannel channel = channels[index];
            if (staticallyBlocked(index, channel)) {
                continue;
            }
            int cpuWindow = Math.max(choppingCpuWindowRemaining[index],
                requestBlockGapRemaining[index]);
            if (cpuWindow >= cycles) {
                continue;
            }
            DmaPort port = ports[index];
            if (port == null || !port.dmaRequestStableFor(channel.fromRam(), cycles)) {
                return false;
            }
        }
        return true;
    }

    public int soleGpuLinkedListIdleClocks(int maximumCycles) {
        final int index = 2;
        int limit = Math.max(1, maximumCycles);
        DmaChannel channel = channels[index];
        boolean runnable = linkedListNodeWords[index] > 0
            ? gpu.canAcceptDmaBlockWord()
            : transferStarted[index] || channel.trigger() || gpu.readyToReceiveDmaBlock();
        return runnable ? 0 : gpu.cyclesUntilDmaAvailabilityMayChange(limit);
    }

    // Batches FIFO writes only while the current GPU command cannot finish.
    public int soleGpuLinkedListActiveBatchClocks(int maximumCycles) {
        final int index = 2;
        int stableClocks = gpu.dmaIngressStableClocks(maximumCycles);
        int safeWords = Math.min(
            Math.max(0, linkedListNodeWords[index] - 1),
            gpu.dmaIngressFreeWords()
        );
        if (stableClocks <= 1 || safeWords <= 0) {
            return 1;
        }
        int wordClocks = Math.max(1,
            transferCyclesForWords(index, safeWords) - channelCycleCarry[index]);
        return Math.min(stableClocks, wordClocks);
    }

    public boolean soleGpuLinkedListTransferConfigured() {
        final int index = 2;
        if (bus == null || gpu == null
            || enabledChannelMask != (1 << index)
            || cpuWindowChannelMask != 0
            || ((dpcr >>> (index * 4 + 3)) & 1) == 0) {
            return false;
        }
        DmaChannel channel = channels[index];
        return channel.enabled() && channel.fromRam() && channel.syncMode() == 2;
    }

    public boolean soleGpuLinkedListInterruptStable() {
        if (dmaIrqMasterFlag || (dicr & (1 << 23)) == 0) {
            return true;
        }
        return (dicr & (1 << (16 + 2))) == 0;
    }

    private boolean staticallyBlocked(int index, DmaChannel channel) {
        if (((dpcr >>> (index * 4 + 3)) & 1) == 0
            || channel.syncMode() == 3
            || (channel.syncMode() == 1 && channel.choppingEnabled())
            || (channel.syncMode() == 0 && channel.trigger()
                && channel.pauseOrRetainTrigger())) {
            return true;
        }
        return index == 2 && channel.syncMode() == 2
            && (gpu == null || !channel.fromRam());
    }

    public int cpuAccessPenalty(int physicalAddress, boolean write) {
        return cpuAccessPenalty(physicalAddress, write, 1);
    }

    public int cpuAccessPenalty(int physicalAddress, boolean write, int accessCycles) {
        if (enabledChannelMask == 0 || !touchesSharedBus(physicalAddress)) {
            return 0;
        }
        int channel = highestPriorityRunnableChannel();
        if (channel < 0) {
            return 0;
        }
        return channelCpuStallCycles(channel);
    }

    public boolean sharedBusOwnedByDma() {
        return sharedBusOwnedLastTick;
    }

    public Diagnostic diagnostic() {
        return new Diagnostic(
            dpcr, dicr, enabledChannelMask, cpuWindowChannelMask,
            channels[2].channelControl(), remainingWords[2], remainingBlocks[2],
            channels[3].channelControl(), remainingWords[3], remainingBlocks[3]
        );
    }

    public record Diagnostic(
        int dpcr, int dicr, int enabledChannels, int cpuWindowChannels,
        int gpuControl, int gpuRemainingWords, int gpuRemainingBlocks,
        int cdControl, int cdRemainingWords, int cdRemainingBlocks
    ) {
    }

    public int consumeSharedBusOwnedCycles(int elapsedCpuCycles) {
        int owned = Math.min(Math.max(0, elapsedCpuCycles), sharedBusOwnedCyclesPending);
        sharedBusOwnedCyclesPending -= owned;
        return owned;
    }

    private int normalTransferWordsRemaining(int index, DmaChannel channel) {
        return channel.syncMode() == 1 ? blockWordsRemaining[index] : remainingWords[index];
    }

    private int channelCpuStallCycles(int index) {
        DmaChannel channel = channels[index];
        int cyclesPerWord = channelWordCycles(index);
        if (index == 2 && channel.syncMode() == 2) {
            if (linkedListNodeWords[index] > 0) {
                return Math.max(1, transferCyclesForWords(index,
                    linkedListNodeWords[index]) - channelCycleCarry[index]);
            }
            int address = currentAddress[index] & 0x00FF_FFFF;
            if (isLinkedListEndMarker(address)) {
                return 1;
            }
            if (bus != null && bus.isDmaRamAddress(address & DMA_ADDRESS_MASK)) {
                int words = (bus.read32(address & DMA_ADDRESS_MASK) >>> 24) & 0xFF;
                int payloadCycles = linkedListPayloadCycles(words);
                return Math.max(1, LINKED_LIST_HEADER_READ_CYCLES
                    + (words > 0 ? LINKED_LIST_BLOCK_SETUP_CYCLES + payloadCycles : 0)
                    - channelCycleCarry[index]);
            }
            return LINKED_LIST_HEADER_READ_CYCLES;
        }
        int words = normalTransferWordsRemaining(index, channel);
        if (channel.choppingEnabled()) {
            words = Math.clamp(choppingDmaWordsRemaining[index], 1, words);
        }
        return Math.max(1, transferCyclesForWords(index, words));
    }

    private int serviceChannel(int index, int cycleBudget) {
        DmaChannel channel = channels[index];
        DmaPort port = ports[index];
        beginTransferIfNeeded(index, channel, port);

        if (index == 2 && channel.syncMode() == 2 && gpu != null && channel.fromRam()) {
            int oldCredit = channelCycleCarry[index];
            int wordsBudget = availableWords(index, cycleBudget, channelWordCycles(index));
            if (wordsBudget <= 0) {
                return cycleBudget;
            }
            int consumedCredit = serviceGpuLinkedList(channel, index, wordsBudget);
            if (consumedCredit <= 0) {
                return cycleBudget;
            }
            return Math.clamp(consumedCredit - oldCredit, 1, cycleBudget);
        }

        if (index == 6) {
            int wordsBudget = affordableTransferWords(index, cycleBudget,
                remainingWords[index]);
            if (wordsBudget <= 0) {
                return accrueTransferCredit(index, cycleBudget);
            }
            int transferred = serviceOtc(channel, index, wordsBudget);
            int consumedCycles = settleTransferredWordCycles(index, cycleBudget, transferred);
            if (remainingWords[index] == 0 && channel.enabled()) {
                finishChannel(index, channel);
                clearTransferState(index);
                signalChannelEvent(index, true);
                unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
            }
            return consumedCycles;
        }

        if (port == null) {
            Log.warn("DMA port " + index + " is not attached; transfer dropped. chcr=0x" + Integer.toHexString(channel.channelControl()));
            finishChannel(index, channel);
            clearTransferState(index);
            return 0;
        }
        int increment = channel.stepBackwards() ? -4 : 4;
        int wordLimit = normalTransferWordsRemaining(index, channel);
        if (channel.choppingEnabled()) {
            wordLimit = Math.min(wordLimit, choppingDmaWordsRemaining[index]);
        }
        if (channel.syncMode() == 1) {
            wordLimit = Math.min(wordLimit, blockWordsRemaining[index]);
        }
        int maxWordsThisTick = affordableTransferWords(index, cycleBudget, wordLimit);
        if (maxWordsThisTick <= 0) {
            return accrueTransferCredit(index, cycleBudget);
        }
        int transferred = 0;
        while (normalTransferWordsRemaining(index, channel) > 0 && transferred < maxWordsThisTick) {
            int address = currentAddress[index];
            if (!dmaRamAddressValid(index, address)) {
                return Math.max(1, transferred * channelWordCycles(index));
            }
            if (channel.fromRam()) {
                port.write(bus.read32(address));
            } else {
                bus.write32(address, port.read());
            }
            if (channel.syncMode() == 1) {
                blockWordsRemaining[index]--;
                remainingWords[index] = blockWordsRemaining[index];
            } else {
                remainingWords[index]--;
            }
            currentAddress[index] = (address + increment) & DMA_ADDRESS_MASK;
            transferred++;
        }
        int consumedCycles = settleTransferredWordCycles(index, cycleBudget, transferred);
        if (channel.syncMode() == 0 && channel.choppingEnabled()) {
            channel.setBaseAddress(currentAddress[index]);
            channels[index].setBlockControl(
                (channels[index].blockControl() & 0xFFFF_0000) | (remainingWords[index] & 0xFFFF));
        } else if (channel.syncMode() != 0) {
            channel.setBaseAddress(currentAddress[index]);
        }
        if (channel.syncMode() == 1 && blockWordsRemaining[index] == 0) {
            int nextBlocks = Math.max(0, remainingBlocks[index] - 1);
            int blockSizeField = channel.blockControl() & 0xFFFF;
            remainingBlocks[index] = nextBlocks;
            transferStarted[index] = false;
            if (remainingBlocks[index] > 0) {
                blockWordsRemaining[index] = channel.wordCount();
                remainingWords[index] = blockWordsRemaining[index];
                if (index == 2) {
                    // GPU DREQ returns about ten clocks after a block.
                    requestBlockGapRemaining[index] = GPU_REQUEST_BLOCK_GAP_CYCLES;
                    refreshCpuWindowChannel(index);
                }
            }
            channel.setBlockControl(((nextBlocks & 0xFFFF) << 16) | blockSizeField);
            if (remainingBlocks[index] == 0) {
                finishChannel(index, channel);
                clearTransferState(index);
                signalChannelEvent(index, true);
                unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
            } else {
                signalChannelEvent(index, false);
            }
            completeChoppingDmaSlice(channel, index, transferred);
            return consumedCycles;
        }
        if (remainingWords[index] == 0) {
            finishChannel(index, channel);
            clearTransferState(index);
            signalChannelEvent(index, true);
            unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
        }
        completeChoppingDmaSlice(channel, index, transferred);
        return consumedCycles;
    }

    private int serviceGpuLinkedList(DmaChannel channel, int index, int cycleBudget) {
        int budget = Math.max(1, cycleBudget);
        int consumedCycles = 0;
        if (linkedListNodeWords[index] <= 0) {
            int rawAddress = currentAddress[index] & 0x00FF_FFFF;
            if (isLinkedListEndMarker(rawAddress)) {
                channel.setBaseAddress(rawAddress);
                finishChannel(index, channel);
                clearTransferState(index);
                signalChannelEvent(index, true);
                unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
                return 1;
            }
            int headerAddress = currentAddress[index] & DMA_ADDRESS_MASK;
            if (!dmaRamAddressValid(index, headerAddress)) {
                return 1;
            }
            int header = bus.read32(headerAddress);
            int nodeWords = (header >>> 24) & 0xFF;
            int setupCycles = LINKED_LIST_HEADER_READ_CYCLES
                + (nodeWords > 0 ? LINKED_LIST_BLOCK_SETUP_CYCLES : 0);
            if (budget < setupCycles) {
                channelCycleCarry[index] += budget;
                return 0;
            }
            budget -= setupCycles;
            consumedCycles += setupCycles;
            linkedListNodeWords[index] = nodeWords;
            linkedListNextAddress[index] = header & 0x00FF_FFFF;
            linkedListEnd[index] = isLinkedListEndMarker(linkedListNextAddress[index]);
            currentAddress[index] = (headerAddress + 4) & DMA_ADDRESS_MASK;
            // The header opens the previous DRAM row.
            dramBurstPosition[index] = 1;

            // SyncMode=2 yields the bus between linked-list entries.
            if (linkedListNodeWords[index] == 0) {
                if (linkedListEnd[index]) {
                    channel.setBaseAddress(linkedListNextAddress[index]);
                    finishChannel(index, channel);
                    clearTransferState(index);
                    signalChannelEvent(index, true);
                    unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
                    return consumedCycles;
                }
                currentAddress[index] = linkedListNextAddress[index] & DMA_ADDRESS_MASK;
                channel.setBaseAddress(currentAddress[index]);
                signalChannelEvent(index, false);
                return consumedCycles;
            }
            if (budget <= 0) {
                channel.setBaseAddress(currentAddress[index]);
                return consumedCycles;
            }
        }

        int transferredWords = 0;
        while (linkedListNodeWords[index] > 0) {
            if (!gpu.canAcceptDmaBlockWord()) {
                break;
            }
            int wordCycles = dmaWordCycleCost(index, dramBurstPosition[index]);
            if (budget < wordCycles) {
                channelCycleCarry[index] += budget;
                budget = 0;
                break;
            }
            int address = currentAddress[index] & DMA_ADDRESS_MASK;
            if (!dmaRamAddressValid(index, address)) {
                return Math.max(1, consumedCycles);
            }
            gpu.gp0(bus.read32(address));
            currentAddress[index] = (address + 4) & DMA_ADDRESS_MASK;
            linkedListNodeWords[index]--;
            budget -= wordCycles;
            consumedCycles += wordCycles;
            dramBurstPosition[index] = (dramBurstPosition[index] + 1) & 0xF;
            transferredWords++;
            if (budget <= 0) {
                break;
            }
        }

        if (linkedListNodeWords[index] == 0) {
            if (linkedListEnd[index]) {
                channel.setBaseAddress(linkedListNextAddress[index]);
                finishChannel(index, channel);
                clearTransferState(index);
                signalChannelEvent(index, true);
                unknownF8 = DMA_UNKNOWN_F8_AFTER_TRANSFER;
                return consumedCycles;
            }
            currentAddress[index] = linkedListNextAddress[index] & DMA_ADDRESS_MASK;
            signalChannelEvent(index, false);
        }
        channel.setBaseAddress(currentAddress[index]);
        if (transferredWords == 0 && consumedCycles == 0) {
            return 0;
        }
        return consumedCycles;
    }

    private int serviceOtc(DmaChannel channel, int index, int wordBudget) {
        int maxWordsThisTick = Math.max(1, wordBudget);
        if (channel.choppingEnabled()) {
            maxWordsThisTick = Math.min(maxWordsThisTick, channel.choppingDmaWindow());
        }
        int transferred = 0;
        while (remainingWords[index] > 0 && transferred < maxWordsThisTick) {
            int address = currentAddress[index];
            if (!dmaRamAddressValid(index, address)) {
                return Math.max(1, transferred);
            }
            int next = remainingWords[index] == 1 ? OTC_END_MARKER : ((address - 4) & DMA_ADDRESS_MASK);
            bus.write32(address, next);
            currentAddress[index] = (address - 4) & DMA_ADDRESS_MASK;
            remainingWords[index]--;
            transferred++;
        }
        completeChoppingDmaSlice(channel, index, transferred);
        return transferred;
    }

    private void completeChoppingDmaSlice(DmaChannel channel, int index, int transferredWords) {
        if (transferredWords <= 0 || channel.syncMode() != 0 || !channel.choppingEnabled()) {
            return;
        }
        choppingDmaWordsRemaining[index] = Math.max(0,
            choppingDmaWordsRemaining[index] - transferredWords);
        if (choppingDmaWordsRemaining[index] > 0) {
            return;
        }
        if (!channel.enabled()) {
            return;
        }
        choppingDmaWordsRemaining[index] = channel.choppingDmaWindow();
        choppingCpuWindowRemaining[index] = Math.max(
            choppingCpuWindowRemaining[index],
            channel.choppingCpuWindow() + CHOPPING_SLICE_ARBITRATION_CYCLES);
        refreshCpuWindowChannel(index);
    }

    private int activeCpuWindowCycles() {
        int result = Integer.MAX_VALUE;
        int active = cpuWindowChannelMask;
        while (active != 0) {
            int index = Integer.numberOfTrailingZeros(active);
            int chopping = choppingCpuWindowRemaining[index];
            int requestGap = requestBlockGapRemaining[index];
            if (chopping > 0) result = Math.min(result, chopping);
            if (requestGap > 0) result = Math.min(result, requestGap);
            active &= active - 1;
        }
        return result == Integer.MAX_VALUE ? 0 : result;
    }

    private int cpuWindowMask() {
        return cpuWindowChannelMask;
    }

    private void decrementCpuWindows(int cycles, int channelMask) {
        if (cycles <= 0 || channelMask == 0) {
            return;
        }
        for (int i = 0; i < choppingCpuWindowRemaining.length; i++) {
            if ((channelMask & (1 << i)) == 0) {
                continue;
            }
            choppingCpuWindowRemaining[i] = Math.max(0, choppingCpuWindowRemaining[i] - cycles);
            requestBlockGapRemaining[i] = Math.max(0, requestBlockGapRemaining[i] - cycles);
            refreshCpuWindowChannel(i);
        }
    }

    private void refreshCpuWindowChannel(int index) {
        int bit = 1 << index;
        if (choppingCpuWindowRemaining[index] > 0 || requestBlockGapRemaining[index] > 0) {
            cpuWindowChannelMask |= bit;
        } else {
            cpuWindowChannelMask &= ~bit;
        }
    }

    private void signalChannelEvent(int channel, boolean transferComplete) {
        boolean oldMasterFlag = dmaIrqMasterFlag;
        boolean perBlockIrq = ((dicr >>> channel) & 0x1) != 0;
        boolean channelEnabled = ((dicr >>> (16 + channel)) & 0x1) != 0;
        boolean masterEnabled = (dicr & (1 << 23)) != 0;
        if ((transferComplete || perBlockIrq) && channelEnabled && masterEnabled) {
            dicr |= 1 << (24 + channel);
        }
        dicr = refreshMasterFlag(dicr);
        syncInterruptLine(oldMasterFlag, dmaIrqMasterFlag);
    }

    private boolean dmaRamAddressValid(int channel, int address) {
        int physical = address & DMA_ADDRESS_MASK;
        if (bus != null && bus.isDmaRamAddress(physical)) {
            return true;
        }
        boolean oldMasterFlag = dmaIrqMasterFlag;
        channels[channel].setBaseAddress(address);
        finishChannel(channel, channels[channel]);
        clearTransferState(channel);
        dicr |= 1 << 15;
        dicr = refreshMasterFlag(dicr);
        syncInterruptLine(oldMasterFlag, dmaIrqMasterFlag);
        return false;
    }

    private static boolean isLinkedListEndMarker(int address) {
        int value = address & 0x00FF_FFFF;
        return (value & 0x0080_0000) != 0 || value == 0x00FF_FFFF;
    }

    private int channelPriority(int index) {
        return (dpcr >>> (index * 4)) & 0x7;
    }

    private void initializeTransferState(int index) {
        DmaChannel channel = channels[index];
        remainingWords[index] = channel.wordCount();
        remainingBlocks[index] = channel.syncMode() == 1 ? channel.blockCount() : 0;
        blockWordsRemaining[index] = channel.syncMode() == 1 ? channel.wordCount() : 0;
        currentAddress[index] = index == 2 && channel.syncMode() == 2
            ? channel.baseAddress()
            : channel.transferAddress();
        linkedListNodeWords[index] = 0;
        linkedListNextAddress[index] = 0;
        linkedListEnd[index] = false;
        transferStarted[index] = false;
        channelCycleCarry[index] = 0;
        dramBurstPosition[index] = 0;
        choppingCpuWindowRemaining[index] = 0;
        choppingDmaWordsRemaining[index] = channel.choppingEnabled()
            && channel.syncMode() == 0 ? channel.choppingDmaWindow() : 0;
        requestBlockGapRemaining[index] = 0;
        refreshCpuWindowChannel(index);
    }

    private void reinitializePendingTransfer(int index) {
        if (channels[index].enabled() && !transferStarted[index]) {
            initializeTransferState(index);
        }
    }

    private void clearTransferState(int index) {
        remainingWords[index] = 0;
        remainingBlocks[index] = 0;
        blockWordsRemaining[index] = 0;
        linkedListNodeWords[index] = 0;
        linkedListNextAddress[index] = 0;
        linkedListEnd[index] = false;
        transferStarted[index] = false;
        channelCycleCarry[index] = 0;
        dramBurstPosition[index] = 0;
        choppingCpuWindowRemaining[index] = 0;
        choppingDmaWordsRemaining[index] = 0;
        requestBlockGapRemaining[index] = 0;
        refreshCpuWindowChannel(index);
    }

    private void finishChannel(int index, DmaChannel channel) {
        channel.finish();
        enabledChannelMask &= ~(1 << index);
    }

    private void updateEnabledChannel(int index) {
        if (channels[index].enabled()) {
            enabledChannelMask |= 1 << index;
        } else {
            enabledChannelMask &= ~(1 << index);
        }
    }

    private int highestPriorityRunnableChannel() {
        int candidates = enabledChannelMask;
        if (candidates == 0) {
            return -1;
        }
        // Most games use one DMA channel at a time.
        if ((candidates & (candidates - 1)) == 0) {
            int channel = Integer.numberOfTrailingZeros(candidates);
            return channelRunnable(channel, channels[channel], ports[channel])
                ? channel
                : -1;
        }
        int best = -1;
        int bestPriority = Integer.MAX_VALUE;
        while (candidates != 0) {
            int channel = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            if (!channelRunnable(channel, channels[channel], ports[channel])) {
                continue;
            }
            if (best < 0) {
                best = channel;
                bestPriority = channelPriority(channel);
                continue;
            }
            int currentPriority = channelPriority(channel);
            if (currentPriority < bestPriority || (currentPriority == bestPriority && channel > best)) {
                best = channel;
                bestPriority = currentPriority;
            }
        }
        return best;
    }

    private boolean channelRunnable(int index, DmaChannel channel, DmaPort port) {
        if (!channel.enabled()) {
            return false;
        }
        if (choppingCpuWindowRemaining[index] > 0) {
            return false;
        }
        if (requestBlockGapRemaining[index] > 0) {
            return false;
        }
        if (((dpcr >>> (index * 4 + 3)) & 0x1) == 0) {
            return false;
        }
        if (channel.syncMode() == 3) {
            return false;
        }
        if (channel.syncMode() == 1 && channel.choppingEnabled()) {
            return false;
        }
        if (channel.syncMode() == 0 && channel.trigger() && channel.pauseOrRetainTrigger()) {
            return false;
        }
        if (index == 6) {
            return transferStarted[index] || channel.trigger();
        }
        if (index == 2 && channel.syncMode() == 2) {
            if (gpu == null || !channel.fromRam()) {
                return false;
            }
            // An entry may be paused at any word while the command FIFO is full.
            if (linkedListNodeWords[index] > 0) {
                return gpu.canAcceptDmaBlockWord();
            }
            return transferStarted[index] || channel.trigger() || gpu.readyToReceiveDmaBlock()
                || gpu.awaitingDmaPacketParameters();
        }
        if (channel.syncMode() == 1) {
            // Request mode samples DREQ at block boundaries.
            return transferStarted[index]
                || (port != null && port.dmaRequest(channel.fromRam()));
        }
        return transferStarted[index] || requestAllowsStart(index, channel, port);
    }

    private boolean requestAllowsStart(int index, DmaChannel channel, DmaPort port) {
        if (index == 6) {
            return channel.trigger();
        }
        if (channel.syncMode() == 0) {
            return channel.trigger() || (port != null && port.dmaRequest(channel.fromRam()));
        }
        if (channel.syncMode() == 1) {
            return channel.trigger() || (port != null && port.dmaRequest(channel.fromRam()));
        }
        if (index == 2 && channel.syncMode() == 2 && gpu != null && channel.fromRam()) {
            return channel.trigger() || gpu.readyToReceiveDmaBlock();
        }
        return true;
    }

    private void beginTransferIfNeeded(int index, DmaChannel channel, DmaPort port) {
        if (transferStarted[index]) {
            return;
        }
        boolean continuingSavedGpuPacket = index == 2 && channel.syncMode() == 2
            && gpu != null && channel.fromRam() && gpu.awaitingDmaPacketParameters();
        if (!continuingSavedGpuPacket && !requestAllowsStart(index, channel, port)) {
            return;
        }
        transferStarted[index] = true;
        boolean causedByDeviceRequest;
        if (channel.syncMode() == 0) {
            causedByDeviceRequest = false;
        } else if (index == 2 && gpu != null && channel.fromRam()) {
            causedByDeviceRequest = gpu.readyToReceiveDmaBlock();
        } else {
            causedByDeviceRequest = port != null && port.dmaRequest(channel.fromRam());
        }
        if (channel.trigger()
            && (!channel.pauseOrRetainTrigger() || causedByDeviceRequest || channel.syncMode() == 0)) {
            channels[index].setChannelControl(channel.channelControl() & ~(1 << 28));
        }
    }

    private int availableWords(int index, int cycleBudget, int cyclesPerWord) {
        if (cycleBudget <= 0) {
            return 0;
        }
        channelCycleCarry[index] += cycleBudget;
        int words = channelCycleCarry[index] / Math.max(1, cyclesPerWord);
        if (words <= 0) {
            return 0;
        }
        channelCycleCarry[index] -= words * Math.max(1, cyclesPerWord);
        return words;
    }

    private int affordableTransferWords(int index, int cycleBudget, int wordLimit) {
        if (cycleBudget <= 0 || wordLimit <= 0) return 0;
        long credit = (long) channelCycleCarry[index] + cycleBudget;
        int phase = dramBurstPosition[index];
        int words = 0;
        while (words < wordLimit) {
            int cost = dmaWordCycleCost(index, phase);
            if (credit < cost) break;
            credit -= cost;
            phase = (phase + 1) & 0xF;
            words++;
        }
        return words;
    }

    private int accrueTransferCredit(int index, int cycleBudget) {
        channelCycleCarry[index] += Math.max(0, cycleBudget);
        return Math.max(0, cycleBudget);
    }

    // Commit timing only for words that were actually transferred.
    private int settleTransferredWordCycles(int index, int cycleBudget, int words) {
        if (words <= 0) return accrueTransferCredit(index, cycleBudget);
        int cost = transferCyclesForWords(index, words);
        int oldCredit = channelCycleCarry[index];
        int usedOldCredit = Math.min(oldCredit, cost);
        channelCycleCarry[index] = oldCredit - usedOldCredit;
        for (int i = 0; i < words; i++) {
            dramBurstPosition[index] = (dramBurstPosition[index] + 1) & 0xF;
        }
        return Math.max(1, cost - usedOldCredit);
    }

    private int transferCyclesForWords(int index, int words) {
        int phase = dramBurstPosition[index];
        int cycles = 0;
        for (int i = 0; i < Math.max(0, words); i++) {
            cycles += dmaWordCycleCost(index, phase);
            phase = (phase + 1) & 0xF;
        }
        return cycles;
    }

    private int linkedListPayloadCycles(int words) {
        int phase = 1; // the immediately preceding node header opened the row
        int cycles = 0;
        for (int i = 0; i < Math.max(0, words); i++) {
            cycles += dmaWordCycleCost(2, phase);
            phase = (phase + 1) & 0xF;
        }
        return cycles;
    }

    private int dmaWordCycleCost(int index, int burstPosition) {
        int cycles = channelWordCycles(index);
        if (burstPosition != 0) return cycles;
        return switch (index) {
            case 0, 1, 2, 6 -> cycles + 1;
            case 4 -> cycles + 2;
            default -> cycles;
        };
    }

    private int channelWordCycles(int index) {
        return bus == null ? switch (index) {
            case 3 -> 24;
            case 4 -> 4;
            case 5 -> 20;
            default -> 1;
        } : bus.dmaWordCycles(index);
    }

    private int normalizeChcr(int channel, int value) {
        if (channel == 6) {
            int normalized = value & ((1 << 24) | (1 << 28) | (1 << 30));
            normalized |= 0x2;
            return normalized;
        }
        return value & CHCR_GENERAL_WRITE_MASK;
    }

    private static boolean touchesSharedBus(int physicalAddress) {
        return (physicalAddress >= 0 && physicalAddress < 0x0080_0000)
            || (physicalAddress >= 0x1F80_0000 && physicalAddress < 0x1F80_2000)
            || (physicalAddress >= 0x1FC0_0000 && physicalAddress < 0x1FC8_0000);
    }

    public State copyState() {
        State state = new State();
        state.channels = new DmaChannel.State[channels.length];
        for (int i = 0; i < channels.length; i++) {
            state.channels[i] = channels[i].copyState();
        }
        state.remainingWords = remainingWords.clone();
        state.remainingBlocks = remainingBlocks.clone();
        state.blockWordsRemaining = blockWordsRemaining.clone();
        state.currentAddress = currentAddress.clone();
        state.linkedListNextAddress = linkedListNextAddress.clone();
        state.linkedListNodeWords = linkedListNodeWords.clone();
        state.channelCycleCarry = channelCycleCarry.clone();
        state.dramBurstPosition = dramBurstPosition.clone();
        state.choppingCpuWindowRemaining = choppingCpuWindowRemaining.clone();
        state.choppingDmaWordsRemaining = choppingDmaWordsRemaining.clone();
        state.requestBlockGapRemaining = requestBlockGapRemaining.clone();
        state.linkedListEnd = linkedListEnd.clone();
        state.transferStarted = transferStarted.clone();
        state.dpcr = dpcr;
        state.dicr = dicr;
        state.dmaIrqMasterFlag = dmaIrqMasterFlag;
        state.unknownF8 = unknownF8;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        if (state.channels != null) {
            for (int i = 0; i < Math.min(channels.length, state.channels.length); i++) {
                channels[i].loadState(state.channels[i]);
                channels[i].setChannelControl(normalizeChcr(i, channels[i].channelControl()));
            }
        }
        copyInto(state.remainingWords, remainingWords);
        copyInto(state.remainingBlocks, remainingBlocks);
        copyInto(state.blockWordsRemaining, blockWordsRemaining);
        copyInto(state.currentAddress, currentAddress);
        copyInto(state.linkedListNextAddress, linkedListNextAddress);
        copyInto(state.linkedListNodeWords, linkedListNodeWords);
        copyInto(state.channelCycleCarry, channelCycleCarry);
        copyInto(state.dramBurstPosition, dramBurstPosition);
        if (state.choppingCpuWindowRemaining != null) {
            copyInto(state.choppingCpuWindowRemaining, choppingCpuWindowRemaining);
        } else {
            Arrays.fill(choppingCpuWindowRemaining, 0);
        }
        if (state.choppingDmaWordsRemaining != null) {
            copyInto(state.choppingDmaWordsRemaining, choppingDmaWordsRemaining);
        } else {
            for (int i = 0; i < choppingDmaWordsRemaining.length; i++) {
                DmaChannel channel = channels[i];
                choppingDmaWordsRemaining[i] = channel.enabled()
                    && channel.choppingEnabled() && channel.syncMode() == 0
                    ? channel.choppingDmaWindow() : 0;
            }
        }
        if (state.requestBlockGapRemaining != null) {
            copyInto(state.requestBlockGapRemaining, requestBlockGapRemaining);
        } else {
            java.util.Arrays.fill(requestBlockGapRemaining, 0);
        }
        copyInto(state.linkedListEnd, linkedListEnd);
        copyInto(state.transferStarted, transferStarted);
        dpcr = state.dpcr;
        dicr = state.dicr;
        dmaIrqMasterFlag = state.dmaIrqMasterFlag;
        unknownF8 = state.unknownF8;
        sharedBusOwnedLastTick = false;
        sharedBusOwnedCyclesPending = 0;
        enabledChannelMask = 0;
        cpuWindowChannelMask = 0;
        for (int i = 0; i < channels.length; i++) {
            updateEnabledChannel(i);
            refreshCpuWindowChannel(i);
        }
    }

    private static void copyInto(int[] source, int[] target) {
        java.util.Arrays.fill(target, 0);
        if (source == null) {
            return;
        }
        System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
    }

    private static void copyInto(boolean[] source, boolean[] target) {
        java.util.Arrays.fill(target, false);
        if (source == null) {
            return;
        }
        System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
    }

    public static final class State {
        DmaChannel.State[] channels;
        int[] remainingWords;
        int[] remainingBlocks;
        int[] blockWordsRemaining;
        int[] currentAddress;
        int[] linkedListNextAddress;
        int[] linkedListNodeWords;
        int[] channelCycleCarry;
        int[] dramBurstPosition;
        int[] choppingCpuWindowRemaining;
        int[] choppingDmaWordsRemaining;
        int[] requestBlockGapRemaining;
        boolean[] linkedListEnd;
        boolean[] transferStarted;
        int dpcr;
        int dicr;
        boolean dmaIrqMasterFlag;
        int unknownF8;
    }
}
