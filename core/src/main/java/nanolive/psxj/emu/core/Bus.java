package nanolive.psxj.emu.core;

import nanolive.psxj.emu.devices.CdRomController;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.Mdec;
import nanolive.psxj.emu.devices.Sio1Controller;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.devices.Spu;
import nanolive.psxj.emu.devices.TimerController;
import nanolive.psxj.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Bus {
    private static final boolean DIAGNOSTIC_TRACE_ENABLED = Log.isDebugEnabled();
    private static final int MMIO_TRACE_LENGTH = 64;
    private static final int RAM_TRACE_LENGTH = 2048;
    private static final int EXP2_DEFAULT_BASE = 0x1F80_2000;
    private static final int EXP3_DEFAULT_BASE = 0x1FA0_0000;
    private static final int EXP1_MAX_SIZE = 8 * 1024 * 1024;
    private static final int EXP2_MAX_SIZE = 8 * 1024;
    private static final int EXP3_MAX_SIZE = 2 * 1024 * 1024;
    private static final int BIOS_MAX_SIZE = 4 * 1024 * 1024;
    private static final int BIOS_DEFAULT_BASE = 0x1FC0_0000;
    private static final int ICACHE_LINE_COUNT = 256;
    private static final int ICACHE_WORDS_PER_LINE = 4;
    private static final int ICACHE_LINE_BYTES = ICACHE_WORDS_PER_LINE * 4;
    private static final int CACHE_CONTROL_TAG = 1 << 2;
    private static final int CACHE_CONTROL_RAM = 1 << 3;
    private static final int CACHE_CONTROL_DS = 1 << 7;
    private static final int CACHE_CONTROL_IS1 = 1 << 11;
    private static final int CACHE_CONTROL_LDSCH = 1 << 16;
    private static final int MEMDELAY_WRITE_MASK = 0xAF1F_FFFF;
    private static final int MEMDELAY_ADDRESS_ERROR = 1 << 28;
    private static final int MEMDELAY_STORED_MASK = MEMDELAY_WRITE_MASK | MEMDELAY_ADDRESS_ERROR;
    private static final int COMDELAY_WRITE_MASK = 0x0003_FFFF;
    private static final int WRITE_QUEUE_DEPTH = 4;
    private static final int TIMING_COMMIT_EXTERNAL_ACCESSES = 2;

    public static final int RAM_SIZE = 2 * 1024 * 1024;
    public static final int SCRATCHPAD_SIZE = 1024;
    public static final int RESET_VECTOR = 0xBFC00000;

    /**
     * A CPU data read has two distinct hardware moments: the BIU accepts the
     * request, then the addressed responder is sampled when the transfer
     * completes.  Keeping those moments separate is required for load
     * scheduling: cached instructions can execute while the read buffer is
     * waiting, but MMIO read side effects must not happen at issue time.
     */
    public record CpuReadRequest(int address, int widthBytes, int extraCycles) {
    }

    private final ByteBuffer ram = ByteBuffer.allocate(RAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer scratchpad = ByteBuffer.allocate(SCRATCHPAD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final int[] iCacheTags = new int[ICACHE_LINE_COUNT];
    private final int[] iCacheData = new int[ICACHE_LINE_COUNT * ICACHE_WORDS_PER_LINE];

    private BiosImage bios;
    private InterruptController interruptController;
    private Gpu gpu;
    private Spu spu;
    private DmaController dma;
    private TimerController timerController;
    private CdRomController cdRomController;
    private SioController sioController;
    private Sio1Controller sio1Controller;
    private Mdec mdec;
    private Runnable deviceSynchronizer;

    private final int[] memoryControl = {
        0x1F00_0000,
        0x1F80_2000,
        0x0013_243F,
        0x0000_3022,
        0x0013_243F,
        0x2209_31E1,
        0x0002_0843,
        0x0007_0777,
        0x0003_1125,
        0x0000_0B88
    };
    private final int[] timingMemoryControl = initialTimingMemoryControl();
    private final int[] pendingTimingMemoryControl = new int[memoryControl.length];
    private final int[] cpuWriteQueueAddress = new int[WRITE_QUEUE_DEPTH];
    private final int[] cpuWriteQueueWidth = new int[WRITE_QUEUE_DEPTH];
    private final int[] cpuWriteQueueRemainingCycles = new int[WRITE_QUEUE_DEPTH];
    private final int[] cpuWriteQueueValue = new int[WRITE_QUEUE_DEPTH];
    private final boolean[] cpuWriteQueueValueValid = new boolean[WRITE_QUEUE_DEPTH];
    private int cacheControl = 0x0001_E988;
    private int configuredRamWindowSize = decodeRamWindowSize(memoryControl[9]);
    private boolean scratchpadMapped = decodeScratchpadMapped(cacheControl);
    private boolean instructionCacheEnabled = decodeInstructionCacheEnabled(cacheControl);
    private int instructionCacheBlockWords = decodeInstructionCacheBlockWords(cacheControl);
    private int postRegister;
    private boolean cacheIsolated;
    private int cpuWriteQueueHead;
    private int cpuWriteQueueSize;
    private int cpuWriteQueueAdvanceCredit;
    private boolean issuedCpuWriteUsesQueue;
    private boolean deferredCpuWriteValid;
    private int deferredCpuWriteAddress;
    private int deferredCpuWriteWidth;
    private int deferredCpuWriteCycles;
    private int deferredCpuWriteValue;
    private int pendingTimingMemoryControlExternalAccesses;
    private int lastInstructionFetchExtraCycles;
    private boolean isolatedCacheReadObserved;
    private boolean isolatedCacheReadHit;
    private boolean instructionFetchInProgress;
    private boolean cpuDataAccessInProgress;
    private boolean dataCacheFillInProgress;
    private boolean deferCpuCycleAdvance;
    private int deferredCpuCycleAdvance;
    private final int[] recentMmioPc = new int[MMIO_TRACE_LENGTH];
    private final int[] recentMmioAddress = new int[MMIO_TRACE_LENGTH];
    private final int[] recentMmioValue = new int[MMIO_TRACE_LENGTH];
    private final int[] recentMmioMeta = new int[MMIO_TRACE_LENGTH];
    private final int[] recentRamPc = new int[RAM_TRACE_LENGTH];
    private final int[] recentRamAddress = new int[RAM_TRACE_LENGTH];
    private final int[] recentRamValue = new int[RAM_TRACE_LENGTH];
    private final int[] recentRamMeta = new int[RAM_TRACE_LENGTH];
    private int recentMmioIndex;
    private int recentRamIndex;
    private int currentCpuPc = -1;
    private int lastBusValue = 0xFFFF_FFFF;

    public int resetVector() {
        return RESET_VECTOR;
    }

    public void setBios(BiosImage bios) {
        this.bios = bios;
    }

    public void setInterruptController(InterruptController interruptController) {
        this.interruptController = interruptController;
    }

    public void setGpu(Gpu gpu) {
        this.gpu = gpu;
    }

    public void setSpu(Spu spu) {
        this.spu = spu;
    }

    public void setDma(DmaController dma) {
        this.dma = dma;
        if (dma != null) {
            dma.setBus(this);
        }
    }

    public void setTimerController(TimerController timerController) {
        this.timerController = timerController;
    }

    public void setCdRomController(CdRomController cdRomController) {
        this.cdRomController = cdRomController;
    }

    public void setSioController(SioController sioController) {
        this.sioController = sioController;
    }

    public void setSio1Controller(Sio1Controller sio1Controller) {
        this.sio1Controller = sio1Controller;
    }

    public void setMdec(Mdec mdec) {
        this.mdec = mdec;
    }

    public void setDeviceSynchronizer(Runnable deviceSynchronizer) {
        this.deviceSynchronizer = deviceSynchronizer;
    }

    public boolean interruptPending() {
        return interruptController != null && interruptController.pending();
    }

    public String interruptSummary() {
        return interruptController == null ? "<none>" : interruptController.describePending();
    }

    public void setCacheIsolated(boolean cacheIsolated) {
        this.cacheIsolated = cacheIsolated;
    }

    public void setCurrentCpuPc(int currentCpuPc) {
        if (DIAGNOSTIC_TRACE_ENABLED) {
            this.currentCpuPc = currentCpuPc;
        }
    }

    public String recentMmioSummary() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < MMIO_TRACE_LENGTH; i++) {
            int index = (recentMmioIndex + i) % MMIO_TRACE_LENGTH;
            int address = recentMmioAddress[index];
            if (address == 0) {
                continue;
            }
            int meta = recentMmioMeta[index];
            int width = 1 << (meta & 0x3);
            boolean write = (meta & 0x4) != 0;
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("MMIO trace: pc=0x")
                .append(Integer.toHexString(recentMmioPc[index]))
                .append(", ")
                .append(write ? "write" : "read")
                .append(width * 8)
                .append(" addr=0x")
                .append(Integer.toHexString(address))
                .append(", value=0x")
                .append(Integer.toHexString(recentMmioValue[index]));
        }
        return builder.toString();
    }

    public String recentRamWriteSummary(int startAddress, int endAddress) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < RAM_TRACE_LENGTH; i++) {
            int index = (recentRamIndex + i) % RAM_TRACE_LENGTH;
            int address = recentRamAddress[index];
            if (address == 0) {
                continue;
            }
            int meta = recentRamMeta[index];
            int width = 1 << (meta & 0x3);
            int end = address + width - 1;
            if (end < startAddress || address > endAddress) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("RAM trace: pc=0x")
                .append(Integer.toHexString(recentRamPc[index]))
                .append(", write")
                .append(width * 8)
                .append(" addr=0x")
                .append(Integer.toHexString(address))
                .append(", value=0x")
                .append(Integer.toHexString(recentRamValue[index]));
        }
        return builder.toString();
    }

    public boolean canFetchInstruction(int address) {
        int physical = normalizeAddress(address);
        int ramSize = ramWindowSize();
        if (physical >= 0 && physical <= ramSize - 4) {
            return true;
        }

        int biosSize = Math.min(configuredWindowSize(memoryControl[4]), BIOS_MAX_SIZE);
        if (bios != null && containsWord(BIOS_DEFAULT_BASE, biosSize, physical)) {
            return true;
        }

        int expansion1Size = Math.min(configuredWindowSize(memoryControl[2]), EXP1_MAX_SIZE);
        int expansion1Base = alignedExpansionBase(memoryControl[0], expansion1Size);
        if (containsWord(expansion1Base, expansion1Size, physical)) {
            return true;
        }

        int expansion3Size = Math.min(configuredWindowSize(memoryControl[3]), EXP3_MAX_SIZE);
        return containsWord(EXP3_DEFAULT_BASE, expansion3Size, physical)
            || (physical >= 0x1F80_1080 && physical <= 0x1F80_10FC)
            || (physical >= 0x1F80_1C00 && physical <= 0x1F80_1FFC);
    }

    public boolean canReadData(int address) {
        if (shouldIsolateCpuDataAddress(address)) {
            return true;
        }
        int physical = normalizeAddress(address);
        return isRam(physical)
            || isScratchpadAccessible(address, physical)
            || isBios(physical)
            || isExpansion1(physical)
            || isExpansion2(physical)
            || isExpansion3(physical)
            || isMappedIo(physical)
            || isUnlockedCpuControlAddress(physical);
    }

    public boolean canReadData(int address, int widthBytes) {
        if (shouldIsolateCpuDataAddress(address)) {
            return true;
        }
        int physical = normalizeAddress(address);
        int width = Math.max(1, widthBytes);
        if (physical >= 0 && physical <= ramWindowSize() - width) {
            return true;
        }
        if (!canReadData(address)) {
            return false;
        }
        if (isExpansion1(physical) || isExpansion2(physical) || isExpansion3(physical)) {
            return true;
        }
        return canReadData(address + Math.max(1, widthBytes) - 1);
    }

    public boolean canWriteData(int address) {
        if (shouldIsolateCpuDataAddress(address)) {
            return true;
        }
        int physical = normalizeAddress(address);
        return isRam(physical)
            || isScratchpadAccessible(address, physical)
            || isBios(physical)
            || isExpansion1(physical)
            || isExpansion2(physical)
            || isExpansion3(physical)
            || isMappedIo(physical)
            || isUnlockedCpuControlAddress(physical);
    }

    public boolean canWriteData(int address, int widthBytes) {
        if (shouldIsolateCpuDataAddress(address)) {
            return true;
        }
        int physical = normalizeAddress(address);
        int width = Math.max(1, widthBytes);
        if (physical >= 0 && physical <= ramWindowSize() - width) {
            return true;
        }
        if (!canWriteData(address)) {
            return false;
        }
        if (isExpansion1(physical) || isExpansion2(physical) || isExpansion3(physical)) {
            return true;
        }
        return canWriteData(address + Math.max(1, widthBytes) - 1);
    }

    public int fetchInstruction(int address, boolean isolateCache) {
        int physical = normalizeAddress(address);
        lastInstructionFetchExtraCycles = 0;
        if (!isInstructionCacheableAddress(address) || !instructionCacheEnabled()) {
            lastInstructionFetchExtraCycles = instructionFetchAccessCycles(address);
            return readInstructionWord(address);
        }

        int line = iCacheLineIndex(physical);
        int word = iCacheWordIndex(physical);
        int tagAddress = iCacheTagAddress(physical);
        int tag = iCacheTags[line];
        int validMask = tag & 0xF;
        boolean tagMatches = (tag & 0xFFFF_F000) == tagAddress;
        if (tagMatches && (validMask & (1 << word)) != 0) {
            return iCacheData[line * ICACHE_WORDS_PER_LINE + word];
        }

        synchronizeDeferredDevices();

        int fillMask = iCacheFillMask(word, tagMatches);
        if (!tagMatches) {
            iCacheTags[line] = tagAddress;
        }
        for (int i = 0; i < ICACHE_WORDS_PER_LINE; i++) {
            if ((fillMask & (1 << i)) == 0) {
                continue;
            }
            int wordAddress = (physical & -ICACHE_LINE_BYTES) + (i * 4);
            // I-cache hits do not see RAM writes.
            if (cpuWriteQueueSize > 0 && cpuWriteQueueOverlaps(wordAddress, 4)) {
                lastInstructionFetchExtraCycles += flushCpuWriteQueue();
            }
            lastInstructionFetchExtraCycles += instructionCacheFillWordExtraCycles(wordAddress);
            consumePendingTimingExternalAccess(wordAddress);
            iCacheData[line * ICACHE_WORDS_PER_LINE + i] = readInstructionWord(wordAddress);
        }
        iCacheTags[line] = (iCacheTags[line] & 0xFFFF_F000) | ((iCacheTags[line] | fillMask) & 0xF);
        return iCacheData[line * ICACHE_WORDS_PER_LINE + word];
    }

    public boolean instructionFetchUsesSystemBus(int address) {
        if (!isInstructionCacheableAddress(address) || !instructionCacheEnabled()) {
            return true;
        }
        int physical = normalizeAddress(address);
        int line = iCacheLineIndex(physical);
        int word = iCacheWordIndex(physical);
        int tag = iCacheTags[line];
        return (tag & 0xFFFF_F000) != iCacheTagAddress(physical)
            || (tag & (1 << word)) == 0;
    }

    public boolean isDmaRamAddress(int physicalAddress) {
        return physicalAddress >= 0 && physicalAddress < ramWindowSize();
    }

    public int dmaWordCycles(int channel) {
        return switch (channel) {
            // CDROM_DELAY=00020843h is the BIOS 24-cycle setting.
            case 3 -> (timingMemoryControl[6] & (1 << 8)) != 0 ? 40 : 24;
            case 4 -> 4;
            case 5 -> 20;
            default -> 1;
        };
    }

    public int lastInstructionFetchExtraCycles() {
        return lastInstructionFetchExtraCycles;
    }

    public void invalidateInstructionCacheRange(int address, int size) {
        if (size <= 0) {
            return;
        }
        int firstPhysical = normalizeAddress(address);
        long lastPhysical = Integer.toUnsignedLong(firstPhysical) + size - 1L;
        for (long physical = Integer.toUnsignedLong(firstPhysical) & -ICACHE_LINE_BYTES;
             physical <= lastPhysical;
             physical += ICACHE_LINE_BYTES) {
            int line = iCacheLineIndex((int) physical);
            iCacheTags[line] &= ~0xF;
        }
    }

    public int consumeIsolatedCacheReadResult() {
        if (!isolatedCacheReadObserved) {
            return -1;
        }
        int result = isolatedCacheReadHit ? 1 : 0;
        isolatedCacheReadObserved = false;
        isolatedCacheReadHit = false;
        return result;
    }

    public int cpuAccessCycles(int address, boolean write, int widthBytes) {
        int physical = normalizeAddress(address);
        synchronizeDeferredDevices();
        if (!instructionFetchInProgress && shouldIsolateCpuDataAddress(address)) {
            return 0;
        }
        // RAM accounts for nearly every retail-game data transaction.
        if (physical >= 0 && physical < ramWindowSize()) {
            int width = Math.clamp(widthBytes, 1, 4);
            boolean queuedSegment = isWriteQueueEnabledSegment(address);
            if (write && queuedSegment) {
                boolean queueHasFreeEntry = cpuWriteQueueSize < WRITE_QUEUE_DEPTH;
                int cycles = enqueueCpuWrite(address, physical, width, 4);
                if (dma != null && !queueHasFreeEntry) {
                    cycles += dma.cpuAccessPenalty(physical, true, cycles);
                }
                return cycles;
            }

            int cycles = write ? 4 : 6;
            if (cpuWriteQueueSize > 0
                && (isUncachedSegment(address)
                    || (!write && cpuWriteQueueOverlaps(physical, width)))) {
                cycles += flushCpuWriteQueue();
            }
            if (dma != null) {
                cycles += dma.cpuAccessPenalty(physical, write, cycles);
            }
            return cycles;
        }
        int accessCycles = baseCpuAccessCycles(address, physical, write, widthBytes);
        int cycles;
        boolean writeQueueHasFreeEntry = write
            && usesWriteQueue(address, physical)
            && cpuWriteQueueSize < WRITE_QUEUE_DEPTH;
        if (cpuWriteQueueSize > 0 && flushesWriteQueue(address, physical, write, widthBytes)) {
            accessCycles += flushCpuWriteQueue();
        }
        if (write && usesWriteQueue(address, physical)) {
            cycles = enqueueCpuWrite(address, physical, widthBytes, accessCycles + 1);
        } else {
            cycles = accessCycles;
            consumePendingTimingExternalAccess(physical);
        }
        if (dma != null && !writeQueueHasFreeEntry) {
            cycles += dma.cpuAccessPenalty(physical, write, cycles);
        }
        return Math.max(0, cycles);
    }

    // Begins, but does not complete, a CPU data read.
    public CpuReadRequest beginCpuRead(int address, int widthBytes) {
        int width = Math.clamp(widthBytes, 1, 4);
        return new CpuReadRequest(address, width, beginCpuReadExtraCycles(address, width));
    }

    public int beginCpuReadExtraCycles(int address, int widthBytes) {
        int width = Math.clamp(widthBytes, 1, 4);
        return cpuAccessCycles(address, false, width);
    }

    // Issues a CPU store with its data already present on the BIU.
    public int beginCpuWriteExtraCycles(int address, int value, int widthBytes) {
        int width = Math.clamp(widthBytes, 1, 4);
        // A queued store changes which writes may consume later bus-free clocks.
        synchronizeDeferredDevices();
        int physical = normalizeAddress(address);
        if (!instructionFetchInProgress && shouldIsolateCpuDataAddress(address)) {
            issuedCpuWriteUsesQueue = false;
            return 0;
        }
        if (!usesWriteQueue(address, physical)) {
            issuedCpuWriteUsesQueue = false;
            return cpuAccessCycles(address, true, width);
        }
        if (deferredCpuWriteValid) {
            throw new IllegalStateException("A CPU store is already waiting for the write queue");
        }

        int accessCycles = isRam(physical)
            ? 4
            : baseCpuAccessCycles(address, physical, true, width) + 1;
        issuedCpuWriteUsesQueue = true;
        if (cpuWriteQueueSize < WRITE_QUEUE_DEPTH) {
            enqueueCpuWrite(physical, width, accessCycles, value, true);
            return 0;
        }

        // A fifth store waits only until the oldest entry frees one register.
        deferredCpuWriteValid = true;
        deferredCpuWriteAddress = physical;
        deferredCpuWriteWidth = width;
        deferredCpuWriteCycles = accessCycles;
        deferredCpuWriteValue = value;
        int cycles = Math.max(1, cpuWriteQueueRemainingCycles[cpuWriteQueueHead]);
        if (dma != null) {
            cycles += dma.cpuAccessPenalty(physical, true, cycles);
        }
        return cycles;
    }

    public int pendingCpuWriteStallCycles() {
        if (!deferredCpuWriteValid) {
            return 0;
        }
        int cycles = cpuWriteQueueSize == 0
            ? 1
            : Math.max(1, cpuWriteQueueRemainingCycles[cpuWriteQueueHead]);
        if (dma != null) {
            cycles += dma.cpuAccessPenalty(deferredCpuWriteAddress, true, cycles);
        }
        return Math.max(1, cycles);
    }

    public int completeCpuRead(CpuReadRequest request) {
        return completeCpuRead(request.address(), request.widthBytes());
    }

    public int completeCpuRead(int address, int widthBytes) {
        int physical = normalizeAddress(address);
        boolean ordinaryRam = physical >= 0
            && physical <= ramWindowSize() - Math.max(1, widthBytes)
            && !cacheIsolated
            && ((cacheControl & CACHE_CONTROL_RAM) != 0
                || (cacheControl & CACHE_CONTROL_DS) == 0);
        if (ordinaryRam) {
            int offset = ramOffset(physical);
            return switch (widthBytes) {
                case 1 -> latchRead(Byte.toUnsignedInt(ram.get(offset)), 1);
                case 2 -> latchRead(Short.toUnsignedInt(ram.getShort(offset)), 2);
                case 4 -> latchRead(ram.getInt(offset), 4);
                default -> completeCpuReadWide(address, widthBytes);
            };
        }
        return completeCpuReadWide(address, widthBytes);
    }

    private int completeCpuReadWide(int address, int widthBytes) {
        boolean previousCpuDataAccess = cpuDataAccessInProgress;
        cpuDataAccessInProgress = true;
        try {
            return switch (widthBytes) {
                case 1 -> read8(address);
                case 2 -> read16(address);
                case 3 -> read16(address) | (read8(address + 2) << 16);
                case 4 -> read32(address);
                default -> throw new IllegalArgumentException("Unsupported CPU read width: " + widthBytes);
            };
        } finally {
            cpuDataAccessInProgress = previousCpuDataAccess;
        }
    }

    public boolean loadSchedulingEnabled() {
        return (cacheControl & CACHE_CONTROL_LDSCH) != 0;
    }

    public boolean cpuDataAccessDeadlocks(int address) {
        int physical = normalizeAddress(address);
        int segment = address >>> 29;
        return !cacheIsolated
            && segment != 0b101
            && isScratchpad(physical)
            && (cacheControl & CACHE_CONTROL_RAM) == 0
            && (cacheControl & CACHE_CONTROL_DS) != 0;
    }

    public void advanceCpuCycles(int cycles) {
        if (cycles <= 0) {
            return;
        }
        if (deferCpuCycleAdvance) {
            deferredCpuCycleAdvance = (int) Math.min(
                Integer.MAX_VALUE,
                (long) deferredCpuCycleAdvance + cycles
            );
            return;
        }
        advanceCpuCyclesNow(cycles);
    }

    public void setCpuCycleAdvanceDeferred(boolean deferred) {
        deferCpuCycleAdvance = deferred;
    }

    public void flushDeferredCpuCycles() {
        int cycles = deferredCpuCycleAdvance;
        deferredCpuCycleAdvance = 0;
        if (cycles > 0) {
            advanceCpuCyclesNow(cycles);
        }
    }

    private void advanceCpuCyclesNow(int cycles) {
        int drainableCycles = cycles;
        if (dma != null) {
            drainableCycles -= dma.consumeSharedBusOwnedCycles(cycles);
        }
        if (cpuWriteQueueSize == 0 && cpuWriteQueueAdvanceCredit == 0) {
            return;
        }
        if (drainableCycles <= 0) {
            return;
        }
        int credit = Math.min(drainableCycles, cpuWriteQueueAdvanceCredit);
        cpuWriteQueueAdvanceCredit -= credit;
        drainCpuWriteQueue(drainableCycles - credit);
    }

    public void completeCpuWrite8(int address, int value) {
        completeCpuWrite(address, value, 1);
    }

    public void completeCpuWrite16(int address, int value) {
        completeCpuWrite(address, value, 2);
    }

    public void completeCpuWrite24(int address, int value) {
        completeCpuWrite(address, value, 3);
    }

    public void completeCpuWrite32(int address, int value) {
        completeCpuWrite(address, value, 4);
    }

    private void completeCpuWrite(int address, int value, int widthBytes) {
        boolean previousCpuDataAccess = cpuDataAccessInProgress;
        cpuDataAccessInProgress = true;
        try {
            if (issuedCpuWriteUsesQueue) {
                issuedCpuWriteUsesQueue = false;
                if (deferredCpuWriteValid) {
                    throw new IllegalStateException("CPU completed a store before its write-queue slot was available");
                }
                return;
            }
            int physical = normalizeAddress(address);
            if (shouldIsolateCpuDataAddress(address)) {
                writeByWidth(address, value, widthBytes);
                return;
            }
            boolean ordinaryQueuedRam = isRam(physical)
                && isWriteQueueEnabledSegment(address);
            if (!ordinaryQueuedRam && !usesWriteQueue(address, physical)) {
                synchronizeDeferredDevices();
                writeByWidth(address, value, widthBytes);
                return;
            }

            for (int i = cpuWriteQueueSize - 1; i >= 0; i--) {
                int index = (cpuWriteQueueHead + i) % WRITE_QUEUE_DEPTH;
                if (!cpuWriteQueueValueValid[index]
                    && cpuWriteQueueAddress[index] == physical
                    && cpuWriteQueueWidth[index] == widthBytes) {
                    cpuWriteQueueValue[index] = value;
                    cpuWriteQueueValueValid[index] = true;
                    return;
                }
            }

            // Fallback when timing was not started.
            writeByWidth(address, value, widthBytes);
        } finally {
            cpuDataAccessInProgress = previousCpuDataAccess;
        }
    }

    private void writeByWidth(int address, int value, int widthBytes) {
        if (widthBytes < 4
            && !isIsolatedCpuDataAccess(address)
            && writeOnDieWordRegister(address, value, widthBytes)) {
            return;
        }
        switch (widthBytes) {
            case 1 -> write8(address, value);
            case 2 -> write16(address, value);
            case 3 -> {
                write16(address, value);
                write8(address + 2, value >>> 16);
            }
            case 4 -> write32(address, value);
            default -> throw new IllegalArgumentException("Unsupported CPU store width: " + widthBytes);
        }
    }

    public int read8(int address) {
        int physical = normalizeAddress(address);
        physical = normalizeDmaRegisterAddress(physical);
        synchronizeDevicesForMappedIo(physical);
        if (isIsolatedCpuDataAccess(address)) {
            return isolatedCacheRead(address, 1);
        }
        if (shouldFillDataCache(address, physical)) {
            return dataCacheFillRead(address, 1);
        }
        if (isSystemControlRegisterAccess(physical, 1)) {
            int value = readSystemControlRegister32(physical & ~3);
            int byteValue = (value >>> ((physical & 3) * 8)) & 0xFF;
            recordMmioAccess(physical, byteValue, 1, false);
            return byteValue;
        }
        if (isUnlockedCpuControlAddress(physical)) {
            return cpuControlGarbageRead(physical, 1);
        }
        if (isRam(physical)) {
            int offset = ramOffset(physical);
            return latchRead(Byte.toUnsignedInt(ram.get(offset)), 1);
        }
        if (isScratchpadAccessible(address, physical)) {
            return latchRead(Byte.toUnsignedInt(scratchpad.get(physical - 0x1F80_0000)), 1);
        }
        if (isBios(physical) && bios != null) {
            return latchRead(bios.read8(biosOffset(physical)), 1);
        }
        if (isSpuUnused(physical)) {
            return latchRead(0xFF, 1);
        }
        if (isSpuRegister(physical) && spu != null) {
            int halfword = spu.read16(physical & ~1);
            int value = (halfword >>> ((physical & 1) * 8)) & 0xFF;
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (physical >= 0x1F80_1040 && physical <= 0x1F80_104F && sioController != null) {
            int value = sioController.read8(physical);
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (physical >= 0x1F80_1050 && physical <= 0x1F80_105F && sio1Controller != null) {
            int value = sio1Controller.read8(physical);
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (physical >= 0x1F80_1800 && physical <= 0x1F80_1803 && cdRomController != null) {
            int value = cdRomController.read8(physical);
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (isDmaRegister(physical) && dma != null) {
            int value = dma.read8(physical);
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (physical == 0x1F80_2041 || physical == 0x1F80_2042) {
            int value = postRegister & 0xFF;
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (physical >= 0x1F80_2060 && physical <= 0x1F80_2065) {
            int value = switch (physical) {
                case 0x1F80_2060 -> 'E';
                case 0x1F80_2061 -> 'X';
                case 0x1F80_2062 -> 'P';
                case 0x1F80_2063 -> 0x01;
                default -> 0;
            };
            recordMmioAccess(physical, value, 1, false);
            return value;
        }
        if (isExpansion1(physical) || isExpansion2(physical) || isExpansion3(physical)) {
            return openBusRead(1);
        }
        return openBusRead(1);
    }

    public int peekRam8(int physicalAddress) {
        if (physicalAddress >= 0 && physicalAddress < RAM_SIZE) {
            return Byte.toUnsignedInt(ram.get(physicalAddress));
        }
        if (physicalAddress >= 0x1F80_0000
            && physicalAddress < 0x1F80_0000 + SCRATCHPAD_SIZE) {
            return Byte.toUnsignedInt(scratchpad.get(physicalAddress - 0x1F80_0000));
        }
        return -1;
    }

    public void copyAchievementMemory(byte[] destination) {
        if (destination.length < RAM_SIZE + SCRATCHPAD_SIZE) {
            throw new IllegalArgumentException("Achievement memory snapshot is too small");
        }
        System.arraycopy(ram.array(), 0, destination, 0, RAM_SIZE);
        System.arraycopy(scratchpad.array(), 0, destination, RAM_SIZE, SCRATCHPAD_SIZE);
    }

    public int read16(int address) {
        int physical = normalizeAddress(address);
        physical = normalizeDmaRegisterAddress(physical);
        synchronizeDevicesForMappedIo(physical);
        if (isIsolatedCpuDataAccess(address)) {
            return isolatedCacheRead(address, 2);
        }
        if (shouldFillDataCache(address, physical)) {
            return dataCacheFillRead(address, 2);
        }
        if (isSystemControlRegisterAccess(physical, 2)) {
            int value = readSystemControlRegister32(physical & ~3);
            int halfValue = (value >>> ((physical & 2) * 8)) & 0xFFFF;
            recordMmioAccess(physical, halfValue, 2, false);
            return halfValue;
        }
        if (isUnlockedCpuControlAddress(physical)) {
            return cpuControlGarbageRead(physical, 2);
        }
        if (physical == 0x1F80_1070) {
            int value = interruptController == null ? 0 : (interruptController.status() & 0xFFFF);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (physical == 0x1F80_1074) {
            int value = interruptController == null ? 0 : (interruptController.mask() & 0xFFFF);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (isDmaRegister(physical) && dma != null) {
            int value = dma.read16(physical);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (physical >= 0x1F80_1040 && physical <= 0x1F80_104E && sioController != null) {
            int value = sioController.read16(physical);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (physical >= 0x1F80_1050 && physical <= 0x1F80_105E && sio1Controller != null) {
            int value = sio1Controller.read16(physical);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (physical >= 0x1F80_1100 && physical <= 0x1F80_112E && timerController != null) {
            int value = timerController.read16(physical);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (isSpuRegister(physical) && spu != null) {
            int value = spu.read16(physical);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (isSpuUnused(physical)) {
            return latchRead(0xFFFF, 2);
        }
        if (physical == 0x1F80_1800 && cdRomController != null) {
            // CDROM_DELAY has address auto-increment disabled after reset.
            int status = cdRomController.read8(physical);
            int value = status | (status << 8);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }

        if (isTimerRegister(physical) && timerController != null) {
            // Root counters sit on the on-die word decoder.
            int value = timerController.read16(physical);
            recordMmioAccess(physical, value, 4, false);
            return value;
        }
        if (physical == 0x1F80_1802 && cdRomController != null) {
            // RDDATA accepts both 8-bit and 16-bit reads.
            int value = cdRomController.read8(physical)
                | (cdRomController.read8(physical) << 8);
            recordMmioAccess(physical, value, 2, false);
            return value;
        }
        if (isOpenBusAddress(address, physical)) {
            return openBusRead(2);
        }
        int value = read8(address) | (read8(address + 1) << 8);
        return latchRead(value, 2);
    }

    public int read32(int address) {
        int physical = normalizeAddress(address);
        physical = normalizeDmaRegisterAddress(physical);
        synchronizeDevicesForMappedIo(physical);
        if (isIsolatedCpuDataAccess(address) && (physical & 3) == 0) {
            return isolatedCacheRead(address, 4);
        }
        if (shouldFillDataCache(address, physical)) {
            return dataCacheFillRead(address, 4);
        }
        if (isSystemControlRegisterAccess(physical, 4)) {
            int value = readSystemControlRegister32(physical);
            recordMmioAccess(physical, value, 4, false);
            return value;
        }
        if (isUnlockedCpuControlAddress(physical)) {
            return cpuControlGarbageRead(physical, 4);
        }
        if (isRam(physical) && (physical & 3) == 0) {
            int offset = ramOffset(physical);
            return latchRead(ram.getInt(offset), 4);
        }
        if (isScratchpadAccessible(address, physical) && ((physical - 0x1F80_0000) & 3) == 0) {
            return latchRead(scratchpad.getInt(physical - 0x1F80_0000), 4);
        }
        if (isBios(physical) && bios != null) {
            return latchRead(bios.read32(biosOffset(physical)), 4);
        }
        if (isSpuUnused(physical)) {
            return latchRead(0xFFFF_FFFF, 4);
        }

        if (physical == 0x1F80_1800 && cdRomController != null) {
            int status = cdRomController.read8(physical);
            int value = status * 0x0101_0101;
            recordMmioAccess(physical, value, 4, false);
            return value;
        }

        if (isOpenBusAddress(address, physical)) {
            return openBusRead(4);
        }

        int value = switch (physical) {
            case 0x1F80_1040, 0x1F80_1044, 0x1F80_1048, 0x1F80_104C ->
                sioController == null ? 0xFFFF_FFFF : sioController.read32(physical);
            case 0x1F80_1050, 0x1F80_1054, 0x1F80_1058, 0x1F80_105C ->
                sio1Controller == null ? 0xFFFF_FFFF : sio1Controller.read32(physical);
            case 0x1F80_1070 -> interruptController == null ? 0 : interruptController.status();
            case 0x1F80_1074 -> interruptController == null ? 0 : interruptController.mask();
            case 0x1F80_1080, 0x1F80_1084, 0x1F80_1088,
                 0x1F80_1090, 0x1F80_1094, 0x1F80_1098,
                 0x1F80_10A0, 0x1F80_10A4, 0x1F80_10A8,
                 0x1F80_10B0, 0x1F80_10B4, 0x1F80_10B8,
                 0x1F80_10C0, 0x1F80_10C4, 0x1F80_10C8,
                 0x1F80_10D0, 0x1F80_10D4, 0x1F80_10D8,
                 0x1F80_10E0, 0x1F80_10E4, 0x1F80_10E8,
                 0x1F80_10F0, 0x1F80_10F4, 0x1F80_10F8, 0x1F80_10FC -> dma == null ? 0 : dma.read32(physical);
            case 0x1F80_1810 -> gpu == null ? 0 : gpu.gpuread();
            case 0x1F80_1814 -> gpu == null ? 0 : gpu.status();
            case 0x1F80_1820 -> mdec == null ? 0 : mdec.readData();
            case 0x1F80_1824 -> mdec == null ? 0 : mdec.status();
            default -> read16(address) | (read16(address + 2) << 16);
        };
        if (isMappedIo(physical) || physical == 0xFFFE_0130) {
            recordMmioAccess(physical, value, 4, false);
        } else {
            latchBus(value, 4);
        }
        return value;
    }

    public void write8(int address, int value) {
        int physical = normalizeAddress(address);
        physical = normalizeDmaRegisterAddress(physical);
        synchronizeDevicesForMappedIo(physical);
        if (isIsolatedCpuDataAccess(address)) {
            isolatedCacheWrite(address, value, 1);
        } else if (writeSystemControlRegister(physical, value, 1)) {
            recordMmioAccess(physical, value & 0xFF, 1, true);
        } else if (isUnlockedCpuControlAddress(physical)) {
            latchBus(value, 1);
        } else if (isRam(physical)) {
            int offset = ramOffset(physical);
            ram.put(offset, (byte) value);
            recordRamWrite(address, value & 0xFF, 1);
        } else if (isScratchpadAccessible(address, physical)) {
            scratchpad.put(physical - 0x1F80_0000, (byte) value);
            recordRamWrite(address, value & 0xFF, 1);
        } else if (physical >= 0x1F80_1040 && physical <= 0x1F80_104F && sioController != null) {
            sioController.write8(physical, value);
            recordMmioAccess(physical, value, 1, true);
        } else if (physical >= 0x1F80_1050 && physical <= 0x1F80_105F && sio1Controller != null) {
            sio1Controller.write8(physical, value);
            recordMmioAccess(physical, value, 1, true);
        } else if (physical >= 0x1F80_1800 && physical <= 0x1F80_1803 && cdRomController != null) {
            cdRomController.write8(physical, value);
            recordMmioAccess(physical, value, 1, true);
        } else if (writeOnDieWordRegister(physical, value, 1)) {
        } else if (isSpuRegister(physical) && spu != null) {
            if ((physical & 1) == 0) {
                spu.write16(physical, value & 0xFFFF);
                recordMmioAccess(physical, value & 0xFFFF, 1, true);
            }
        } else if (physical == 0x1F80_2041 || physical == 0x1F80_2042) {
            postRegister = value & 0xFF;
            recordMmioAccess(physical, value, 1, true);
        }
    }

    public void write16(int address, int value) {
        int physical = normalizeAddress(address);
        physical = normalizeDmaRegisterAddress(physical);
        synchronizeDevicesForMappedIo(physical);
        if (isIsolatedCpuDataAccess(address)) {
            isolatedCacheWrite(address, value, 2);
            return;
        }

        if (writeSystemControlRegister(physical, value, 2)) {
            recordMmioAccess(physical, value & 0xFFFF, 2, true);
            return;
        }
        if (isUnlockedCpuControlAddress(physical)) {
            latchBus(value, 2);
            return;
        }
        if (writeOnDieWordRegister(physical, value, 2)) {
            return;
        }
        if (physical >= 0x1F80_1040 && physical <= 0x1F80_104E && sioController != null) {
            sioController.write16(physical, value);
            recordMmioAccess(physical, value & 0xFFFF, 2, true);
            return;
        }
        if (physical >= 0x1F80_1050 && physical <= 0x1F80_105E && sio1Controller != null) {
            sio1Controller.write16(physical, value);
            recordMmioAccess(physical, value & 0xFFFF, 2, true);
            return;
        }
        if (isSpuRegister(physical) && spu != null) {
            spu.write16(physical, value);
            recordMmioAccess(physical, value & 0xFFFF, 2, true);
            return;
        }
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    public void write32(int address, int value) {
        int physical = normalizeAddress(address);
        physical = normalizeDmaRegisterAddress(physical);
        synchronizeDevicesForMappedIo(physical);
        if (isIsolatedCpuDataAccess(address) && (physical & 3) == 0) {
            isolatedCacheWrite(address, value, 4);
            return;
        }
        if (writeSystemControlRegister(physical, value, 4)) {
            recordMmioAccess(physical, value, 4, true);
            return;
        }
        if (isUnlockedCpuControlAddress(physical)) {
            latchBus(value, 4);
            return;
        }
        if (isRam(physical) && (physical & 3) == 0) {
            int offset = ramOffset(physical);
            ram.putInt(offset, value);
            recordRamWrite(address, value, 4);
            return;
        }
        if (isScratchpadAccessible(address, physical) && ((physical - 0x1F80_0000) & 3) == 0) {
            scratchpad.putInt(physical - 0x1F80_0000, value);
            recordRamWrite(address, value, 4);
            return;
        }

        if (isTimerRegister(physical)) {
            // sw drives the timer register once.
            if (timerController != null) {
                timerController.write16(physical, value);
            }
            recordMmioAccess(physical, value, 4, true);
            return;
        }

        switch (physical) {
            case 0x1F80_1070 -> {
                if (interruptController != null) {
                    interruptController.writeStatus(value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            case 0x1F80_1074 -> {
                if (interruptController != null) {
                    interruptController.writeMask(value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            case 0x1F80_1080, 0x1F80_1084, 0x1F80_1088,
                 0x1F80_1090, 0x1F80_1094, 0x1F80_1098,
                 0x1F80_10A0, 0x1F80_10A4, 0x1F80_10A8,
                 0x1F80_10B0, 0x1F80_10B4, 0x1F80_10B8,
                 0x1F80_10C0, 0x1F80_10C4, 0x1F80_10C8,
                 0x1F80_10D0, 0x1F80_10D4, 0x1F80_10D8,
                 0x1F80_10E0, 0x1F80_10E4, 0x1F80_10E8,
                 0x1F80_10F0, 0x1F80_10F4, 0x1F80_10F8, 0x1F80_10FC -> {
                if (dma != null) {
                    dma.write32(physical, value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            case 0x1F80_1810 -> {
                if (gpu != null) {
                    gpu.gp0(value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            case 0x1F80_1814 -> {
                if (gpu != null) {
                    gpu.gp1(value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            case 0x1F80_1820 -> {
                if (mdec != null) {
                    mdec.writeParameter(value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            case 0x1F80_1824 -> {
                if (mdec != null) {
                    mdec.writeControl(value);
                }
                recordMmioAccess(physical, value, 4, true);
            }
            default -> {
                write16(address, value);
                write16(address + 2, value >>> 16);
            }
        }
    }

    private boolean writeOnDieWordRegister(int address, int value, int widthBytes) {
        int physical = normalizeDmaRegisterAddress(normalizeAddress(address));
        int base = physical & ~3;
        int shiftedValue = widthBytes >= 4
            ? value
            : value << ((physical & 3) * 8);

        switch (base) {
            case 0x1F80_1070 -> {
                if (interruptController != null) {
                    interruptController.writeStatus(shiftedValue);
                }
            }
            case 0x1F80_1074 -> {
                if (interruptController != null) {
                    interruptController.writeMask(shiftedValue);
                }
            }
            case 0x1F80_1810 -> {
                if (gpu != null) {
                    gpu.gp0(shiftedValue);
                }
            }
            case 0x1F80_1814 -> {
                if (gpu != null) {
                    gpu.gp1(shiftedValue);
                }
            }
            case 0x1F80_1820 -> {
                if (mdec != null) {
                    mdec.writeParameter(shiftedValue);
                }
            }
            case 0x1F80_1824 -> {
                if (mdec != null) {
                    mdec.writeControl(shiftedValue);
                }
            }
            default -> {
                if (isDmaRegister(base)) {
                    if (dma != null) {
                        dma.write32(base, shiftedValue);
                    }
                } else if (base >= 0x1F80_1100 && base <= 0x1F80_1128
                    && (base & 0xF) <= 0x8) {
                    if (timerController != null) {
                        timerController.write16(base, shiftedValue);
                    }
                } else {
                    return false;
                }
            }
        }
        recordMmioAccess(physical, shiftedValue, widthBytes, true);
        return true;
    }

    private static boolean isTimerRegister(int physical) {
        return physical >= 0x1F80_1100
            && physical <= 0x1F80_1128
            && (physical & 0xF) <= 0x8
            && (physical & 0x3) == 0;
    }

    public byte[] copyRam() {
        return Arrays.copyOf(ram.array(), ram.capacity());
    }

    public void loadRam(byte[] image) {
        Arrays.fill(ram.array(), (byte) 0);
        System.arraycopy(image, 0, ram.array(), 0, Math.min(image.length, ram.capacity()));
    }

    public byte[] copyScratchpad() {
        return Arrays.copyOf(scratchpad.array(), scratchpad.capacity());
    }

    public void loadScratchpad(byte[] image) {
        Arrays.fill(scratchpad.array(), (byte) 0);
        System.arraycopy(image, 0, scratchpad.array(), 0, Math.min(image.length, scratchpad.capacity()));
    }

    public State copyState() {
        State state = new State();
        state.memoryControl = memoryControl.clone();
        state.timingMemoryControl = timingMemoryControl.clone();
        state.pendingTimingMemoryControl = pendingTimingMemoryControl.clone();
        state.cacheControl = cacheControl;
        state.postRegister = postRegister;
        state.cacheIsolated = cacheIsolated;
        state.cpuWriteQueueAddress = cpuWriteQueueAddress.clone();
        state.cpuWriteQueueWidth = cpuWriteQueueWidth.clone();
        state.cpuWriteQueueRemainingCycles = cpuWriteQueueRemainingCycles.clone();
        state.cpuWriteQueueValue = cpuWriteQueueValue.clone();
        state.cpuWriteQueueValueValid = cpuWriteQueueValueValid.clone();
        state.cpuWriteQueueHead = cpuWriteQueueHead;
        state.cpuWriteQueueSize = cpuWriteQueueSize;
        state.cpuWriteQueueAdvanceCredit = cpuWriteQueueAdvanceCredit;
        state.cpuWriteQueueCycles = cpuWriteQueueTotalCycles();
        state.pendingTimingMemoryControlExternalAccesses =
            pendingTimingMemoryControlExternalAccesses;
        state.iCacheTags = iCacheTags.clone();
        state.iCacheData = iCacheData.clone();
        state.lastBusValue = lastBusValue;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        deferCpuCycleAdvance = false;
        deferredCpuCycleAdvance = 0;
        if (state.memoryControl != null) {
            System.arraycopy(state.memoryControl, 0, memoryControl, 0, Math.min(memoryControl.length, state.memoryControl.length));
        }
        if (state.timingMemoryControl != null) {
            System.arraycopy(state.timingMemoryControl, 0, timingMemoryControl, 0,
                Math.min(timingMemoryControl.length, state.timingMemoryControl.length));
        } else {
            System.arraycopy(memoryControl, 0, timingMemoryControl, 0, timingMemoryControl.length);
        }
        if (state.pendingTimingMemoryControl != null) {
            System.arraycopy(state.pendingTimingMemoryControl, 0, pendingTimingMemoryControl, 0,
                Math.min(pendingTimingMemoryControl.length, state.pendingTimingMemoryControl.length));
        } else {
            System.arraycopy(timingMemoryControl, 0, pendingTimingMemoryControl, 0, pendingTimingMemoryControl.length);
        }
        cacheControl = normalizeCacheControl(state.cacheControl);
        configuredRamWindowSize = decodeRamWindowSize(memoryControl[9]);
        scratchpadMapped = decodeScratchpadMapped(cacheControl);
        instructionCacheEnabled = decodeInstructionCacheEnabled(cacheControl);
        instructionCacheBlockWords = decodeInstructionCacheBlockWords(cacheControl);
        postRegister = state.postRegister & 0xFF;
        cacheIsolated = state.cacheIsolated;
        clearCpuWriteQueue();
        if (state.cpuWriteQueueAddress != null
            && state.cpuWriteQueueWidth != null
            && state.cpuWriteQueueRemainingCycles != null) {
            System.arraycopy(state.cpuWriteQueueAddress, 0, cpuWriteQueueAddress, 0,
                Math.min(cpuWriteQueueAddress.length, state.cpuWriteQueueAddress.length));
            System.arraycopy(state.cpuWriteQueueWidth, 0, cpuWriteQueueWidth, 0,
                Math.min(cpuWriteQueueWidth.length, state.cpuWriteQueueWidth.length));
            System.arraycopy(state.cpuWriteQueueRemainingCycles, 0, cpuWriteQueueRemainingCycles, 0,
                Math.min(cpuWriteQueueRemainingCycles.length,
                    state.cpuWriteQueueRemainingCycles.length));
            if (state.cpuWriteQueueValue != null) {
                System.arraycopy(state.cpuWriteQueueValue, 0, cpuWriteQueueValue, 0,
                    Math.min(cpuWriteQueueValue.length, state.cpuWriteQueueValue.length));
            }
            if (state.cpuWriteQueueValueValid != null) {
                System.arraycopy(state.cpuWriteQueueValueValid, 0, cpuWriteQueueValueValid, 0,
                    Math.min(cpuWriteQueueValueValid.length,
                        state.cpuWriteQueueValueValid.length));
            }
            cpuWriteQueueHead = Math.floorMod(state.cpuWriteQueueHead, WRITE_QUEUE_DEPTH);
            cpuWriteQueueSize = Math.clamp(state.cpuWriteQueueSize, 0, WRITE_QUEUE_DEPTH);
            cpuWriteQueueAdvanceCredit = Math.max(0, state.cpuWriteQueueAdvanceCredit);
        } else if (state.cpuWriteQueueCycles > 0) {
            cpuWriteQueueRemainingCycles[0] = state.cpuWriteQueueCycles;
            cpuWriteQueueWidth[0] = 4;
            cpuWriteQueueSize = 1;
        }
        pendingTimingMemoryControlExternalAccesses = Math.max(0,
            state.pendingTimingMemoryControlExternalAccesses);
        if (pendingTimingMemoryControlExternalAccesses == 0
            && state.pendingTimingMemoryControlCycles > 0) {
            pendingTimingMemoryControlExternalAccesses = TIMING_COMMIT_EXTERNAL_ACCESSES;
        }
        if (state.iCacheTags != null) {
            System.arraycopy(state.iCacheTags, 0, iCacheTags, 0, Math.min(iCacheTags.length, state.iCacheTags.length));
        } else {
            Arrays.fill(iCacheTags, 0);
        }
        if (state.iCacheData != null) {
            System.arraycopy(state.iCacheData, 0, iCacheData, 0, Math.min(iCacheData.length, state.iCacheData.length));
        } else {
            Arrays.fill(iCacheData, 0);
        }
        lastBusValue = state.lastBusValue;
        lastInstructionFetchExtraCycles = 0;
        instructionFetchInProgress = false;
        cpuDataAccessInProgress = false;
        dataCacheFillInProgress = false;
    }

    private boolean isIsolatedCpuDataAccess(int address) {
        return cpuDataAccessInProgress && shouldIsolateCpuDataAddress(address);
    }

    private boolean shouldIsolateCpuDataAddress(int address) {
        // FFFE0130h is a BIU control port rather than a system-bus target.
        return cacheIsolated && (normalizeAddress(address) & ~3) != 0xFFFE_0130;
    }

    private static int normalizeAddress(int address) {
        if ((address & 0xE000_0000) == 0x8000_0000 || (address & 0xE000_0000) == 0xA000_0000) {
            address &= 0x1FFF_FFFF;
        }
        return address;
    }

    private int ramOffset(int physical) {
        return physical & (RAM_SIZE - 1);
    }

    private int biosOffset(int physical) {
        int size = Math.max(1, bios.size());
        return (physical - BIOS_DEFAULT_BASE) % size;
    }

    private int normalizeDmaRegisterAddress(int physical) {
        if (physical >= 0x1F80_1080 && physical <= 0x1F80_10EF && (physical & 0xF) >= 0xC) {
            return physical - 0x4;
        }
        return physical;
    }

    private static boolean isDmaRegister(int physical) {
        return physical >= 0x1F80_1080 && physical <= 0x1F80_10FF;
    }

    private boolean isSystemControlRegisterAccess(int physical, int widthBytes) {
        if ((physical & (widthBytes - 1)) != 0 || (physical & 3) + widthBytes > 4) {
            return false;
        }
        if (physical == 0xFFFE_0130) {
            return true;
        }
        return systemControlRegisterIndex(physical & ~3) >= 0;
    }

    private int readSystemControlRegister32(int base) {
        int index = systemControlRegisterIndex(base);
        if (index >= 0) {
            return memoryControl[index];
        }
        if (base == 0xFFFE_0130) {
            return cacheControl;
        }
        return lastBusValue;
    }

    private boolean writeSystemControlRegister(int physical, int value, int widthBytes) {
        if (!isSystemControlRegisterAccess(physical, widthBytes)) {
            return false;
        }
        int base = physical & ~3;
        if (base == 0xFFFE_0130) {
            cacheControl = normalizeCacheControl(value);
            scratchpadMapped = decodeScratchpadMapped(cacheControl);
            instructionCacheEnabled = decodeInstructionCacheEnabled(cacheControl);
            instructionCacheBlockWords = decodeInstructionCacheBlockWords(cacheControl);
            latchBus(value, 4);
            return true;
        }

        int shift = (physical & 3) * 8;
        int shiftedValue = value << shift;
        int index = systemControlRegisterIndex(base);
        if (index >= 0) {
            int writeMask = switch (index) {
                case 0, 1, 9 -> -1;
                case 8 -> COMDELAY_WRITE_MASK;
                default -> MEMDELAY_WRITE_MASK;
            };
            int next = (memoryControl[index] & ~writeMask) | (shiftedValue & writeMask);
            if (index >= 2 && index <= 7 && (shiftedValue & MEMDELAY_ADDRESS_ERROR) != 0) {
                next &= ~MEMDELAY_ADDRESS_ERROR;
            }
            memoryControl[index] = normalizeMemoryControlRegister(index, next);
            if (index == 9) {
                configuredRamWindowSize = decodeRamWindowSize(memoryControl[index]);
            }
            if (index >= 2 && index <= 8) {
                scheduleMemoryControlTimingCommit();
            }
        }
        latchBus(value, 4);
        return true;
    }

    private void scheduleMemoryControlTimingCommit() {
        System.arraycopy(memoryControl, 0, pendingTimingMemoryControl, 0, pendingTimingMemoryControl.length);
        pendingTimingMemoryControlExternalAccesses = TIMING_COMMIT_EXTERNAL_ACCESSES;
    }

    private int instructionFetchAccessCycles(int address) {
        instructionFetchInProgress = true;
        try {
            return cpuAccessCycles(address, false, 4);
        } finally {
            instructionFetchInProgress = false;
        }
    }

    private int instructionCacheFillWordExtraCycles(int physical) {
        // Main RAM supplies a cached refill as a one-word-per-clock burst.
        if (isRam(physical)) {
            return 1;
        }
        return baseCpuAccessCycles(physical, physical, false, 4);
    }

    private int readInstructionWord(int address) {
        instructionFetchInProgress = true;
        try {
            return read32(address);
        } finally {
            instructionFetchInProgress = false;
        }
    }

    private boolean shouldFillDataCache(int address, int physical) {
        return !instructionFetchInProgress
            && !dataCacheFillInProgress
            && !cacheIsolated
            && isInstructionCacheableAddress(address)
            && !isScratchpad(physical)
            && (cacheControl & CACHE_CONTROL_RAM) == 0
            && (cacheControl & CACHE_CONTROL_DS) != 0;
    }

    private int dataCacheFillRead(int address, int widthBytes) {
        int alignedAddress = address & ~3;
        int physical = normalizeAddress(address);
        int word;
        dataCacheFillInProgress = true;
        try {
            word = read32(alignedAddress);
        } finally {
            dataCacheFillInProgress = false;
        }

        int scratchOffset = ((physical >>> 2) & 0xFF) * 4;
        scratchpad.putInt(scratchOffset, word);
        int shifted = word >>> ((physical & 3) * 8);
        return latchRead(shifted, widthBytes);
    }

    private int isolatedCacheRead(int address, int widthBytes) {
        int physical = normalizeAddress(address);
        int line = iCacheLineIndex(physical);
        int word = iCacheWordIndex(physical);
        int tag = iCacheTags[line];
        boolean tagMatches = (tag & 0xFFFF_F000) == iCacheTagAddress(physical);
        boolean wordValid = (tag & (1 << word)) != 0;
        isolatedCacheReadObserved = true;
        isolatedCacheReadHit = tagMatches && wordValid;
        int data = iCacheData[line * ICACHE_WORDS_PER_LINE + word];
        if ((cacheControl & CACHE_CONTROL_TAG) != 0) {
            data = (data & 0xFFFF_FFE0) | (tag & 0xF) | (tagMatches ? 0x10 : 0);
        }
        return latchRead(data >>> ((physical & 3) * 8), widthBytes);
    }

    private void isolatedCacheWrite(int address, int value, int widthBytes) {
        int physical = normalizeAddress(address);
        int line = iCacheLineIndex(physical);
        int word = iCacheWordIndex(physical);
        if ((cacheControl & CACHE_CONTROL_TAG) != 0) {
            iCacheTags[line] = iCacheTagAddress(physical) | (value & 0xF);
            latchBus(value, widthBytes);
            return;
        }
        int index = line * ICACHE_WORDS_PER_LINE + word;
        int shift = (physical & 3) * 8;
        int mask = widthBytes == 4 ? -1 : ((1 << (widthBytes * 8)) - 1) << shift;
        iCacheData[index] = (iCacheData[index] & ~mask) | ((value << shift) & mask);
        latchBus(value, widthBytes);
    }

    private int iCacheFillMask(int requestedWord, boolean tagMatches) {
        if (tagMatches) {
            return 0xF;
        }
        int blockSize = instructionCacheBlockWords();
        int endWord = requestedWord == 0 ? Math.min(ICACHE_WORDS_PER_LINE - 1, blockSize - 1) : ICACHE_WORDS_PER_LINE - 1;
        int mask = 0;
        for (int i = requestedWord; i <= endWord; i++) {
            mask |= 1 << i;
        }
        return mask;
    }

    private int instructionCacheBlockWords() {
        return instructionCacheBlockWords;
    }

    private boolean instructionCacheEnabled() {
        return instructionCacheEnabled;
    }

    private static int decodeInstructionCacheBlockWords(int value) {
        return ((value >>> 8) & 0x3) == 0 ? 2 : 4;
    }

    private static boolean decodeInstructionCacheEnabled(int value) {
        return (value & CACHE_CONTROL_IS1) != 0;
    }

    private static boolean isInstructionCacheableAddress(int address) {
        int segment = address & 0xE000_0000;
        return segment == 0x0000_0000 || segment == 0x8000_0000;
    }

    private static int iCacheLineIndex(int physical) {
        return (physical >>> 4) & (ICACHE_LINE_COUNT - 1);
    }

    private static int iCacheWordIndex(int physical) {
        return (physical >>> 2) & (ICACHE_WORDS_PER_LINE - 1);
    }

    private static int iCacheTagAddress(int physical) {
        return physical & 0xFFFF_F000;
    }

    private int systemControlRegisterIndex(int base) {
        return switch (base) {
            case 0x1F80_1000 -> 0;
            case 0x1F80_1004 -> 1;
            case 0x1F80_1008 -> 2;
            case 0x1F80_100C -> 3;
            case 0x1F80_1010 -> 4;
            case 0x1F80_1014 -> 5;
            case 0x1F80_1018 -> 6;
            case 0x1F80_101C -> 7;
            case 0x1F80_1020 -> 8;
            case 0x1F80_1060 -> 9;
            default -> -1;
        };
    }

    private int normalizeMemoryControlRegister(int index, int value) {
        return switch (index) {
            case 0, 1 -> 0x1F00_0000 | (value & 0x00FF_FFFF);
            case 2, 3, 4, 5, 6, 7 -> value & MEMDELAY_STORED_MASK;
            case 8 -> value & COMDELAY_WRITE_MASK;
            default -> value;
        };
    }

    private void recordMmioAccess(int physical, int value, int widthBytes, boolean write) {
        latchBus(value, widthBytes);
        if (!DIAGNOSTIC_TRACE_ENABLED || !isKernelSegmentPc(currentCpuPc)) {
            return;
        }
        if (physical == 0x1F80_1070 || physical == 0x1F80_1074) {
            return;
        }
        recentMmioPc[recentMmioIndex] = currentCpuPc;
        recentMmioAddress[recentMmioIndex] = physical;
        recentMmioValue[recentMmioIndex] = value;
        int widthCode = switch (widthBytes) {
            case 1 -> 0;
            case 2 -> 1;
            default -> 2;
        };
        recentMmioMeta[recentMmioIndex] = widthCode | (write ? 0x4 : 0);
        recentMmioIndex = (recentMmioIndex + 1) % MMIO_TRACE_LENGTH;
    }

    private void recordRamWrite(int address, int value, int widthBytes) {
        latchBus(value, widthBytes);
        if (!DIAGNOSTIC_TRACE_ENABLED || !isKernelSegmentPc(currentCpuPc)) {
            return;
        }
        recentRamPc[recentRamIndex] = currentCpuPc;
        recentRamAddress[recentRamIndex] = address;
        recentRamValue[recentRamIndex] = value;
        int widthCode = switch (widthBytes) {
            case 1 -> 0;
            case 2 -> 1;
            default -> 2;
        };
        recentRamMeta[recentRamIndex] = widthCode | 0x4;
        recentRamIndex = (recentRamIndex + 1) % RAM_TRACE_LENGTH;
    }

    private boolean isKernelSegmentPc(int pc) {
        return (pc & 0x8000_0000) != 0;
    }

    private int latchRead(int value, int widthBytes) {
        latchBus(value, widthBytes);
        return maskBusValue(value, widthBytes);
    }

    private void latchBus(int value, int widthBytes) {
        switch (widthBytes) {
            case 1 -> lastBusValue = (lastBusValue & ~0xFF) | (value & 0xFF);
            case 2 -> lastBusValue = (lastBusValue & ~0xFFFF) | (value & 0xFFFF);
            default -> lastBusValue = value;
        }
    }

    private int openBusRead(int widthBytes) {
        return maskBusValue(lastBusValue, widthBytes);
    }

    private static int maskBusValue(int value, int widthBytes) {
        return switch (widthBytes) {
            case 1 -> value & 0xFF;
            case 2 -> value & 0xFFFF;
            default -> value;
        };
    }

    private boolean isOpenBusAddress(int address, int physical) {
        if (isRam(physical) || isScratchpadAccessible(address, physical)) {
            return false;
        }
        if (isBios(physical)) {
            return bios == null;
        }
        if (isExpansion1(physical) || isExpansion2(physical) || isExpansion3(physical)) {
            return true;
        }
        return !isMappedIo(physical) && !isUnlockedCpuControlAddress(physical);
    }

    private boolean isRam(int physical) {
        return physical >= 0 && physical < ramWindowSize();
    }

    private static boolean isScratchpad(int physical) {
        return physical >= 0x1F80_0000 && physical < 0x1F80_0000 + SCRATCHPAD_SIZE;
    }

    private boolean isScratchpadAccessible(int address, int physical) {
        if (!isScratchpad(physical)) {
            return false;
        }
        int segment = address >>> 29;
        return segment != 0b101 && scratchpadMapped();
    }

    private boolean isBios(int physical) {
        return bios != null
            && physical >= BIOS_DEFAULT_BASE
            && physical < BIOS_DEFAULT_BASE + Math.min(configuredWindowSize(memoryControl[4]), BIOS_MAX_SIZE);
    }

    private boolean isExpansion1(int physical) {
        int size = Math.min(configuredWindowSize(memoryControl[2]), EXP1_MAX_SIZE);
        int base = alignedExpansionBase(memoryControl[0], size);
        return physical >= base && physical < base + size;
    }

    private boolean isExpansion2(int physical) {
        int size = Math.min(configuredWindowSize(memoryControl[7]), EXP2_MAX_SIZE);
        int base = alignedExpansionBase(memoryControl[1], size);
        return base == EXP2_DEFAULT_BASE && physical >= base && physical < base + size;
    }

    private boolean isExpansion3(int physical) {
        int size = Math.min(configuredWindowSize(memoryControl[3]), EXP3_MAX_SIZE);
        return physical >= EXP3_DEFAULT_BASE && physical < EXP3_DEFAULT_BASE + size;
    }

    private static boolean isSpuRegister(int physical) {
        return physical >= 0x1F80_1C00 && physical <= 0x1F80_1E7F;
    }

    private static boolean isSpuUnused(int physical) {
        return physical >= 0x1F80_1E80 && physical <= 0x1F80_1FFF;
    }

    private static boolean isUnlockedCpuControlAddress(int physical) {
        return (physical >= 0xFFFE_0000 && physical <= 0xFFFE_001F)
            || (physical >= 0xFFFE_0100 && physical <= 0xFFFE_013F);
    }

    private int cpuControlGarbageRead(int physical, int widthBytes) {
        // Most unlocked holes read as zero.
        int value = (physical & 0xF) == 0 ? physical & 0xFF : 0;
        return latchRead(value, widthBytes);
    }

    private boolean isMappedIo(int physical) {
        return (physical >= 0x1F80_1000 && physical <= 0x1F80_1023)
            || (physical >= 0x1F80_1060 && physical <= 0x1F80_1063)
            || (physical >= 0x1F80_1070 && physical <= 0x1F80_1077)
            || (physical >= 0x1F80_1080 && physical <= 0x1F80_10FF)
            || (physical >= 0x1F80_1040 && physical <= 0x1F80_104F)
            || (physical >= 0x1F80_1050 && physical <= 0x1F80_105F)
            || (physical >= 0x1F80_1100 && physical <= 0x1F80_113F)
            || (physical >= 0x1F80_1800 && physical <= 0x1F80_1803)
            || (physical >= 0x1F80_1810 && physical <= 0x1F80_1817)
            || (physical >= 0x1F80_1820 && physical <= 0x1F80_1827)
            || (physical >= 0x1F80_1C00 && physical <= 0x1F80_1FFF)
            || physical == 0x1F80_2041
            || physical == 0x1F80_2042
            || (physical >= 0x1F80_2060 && physical <= 0x1F80_2065);
    }

    private void synchronizeDevicesForMappedIo(int physical) {
        Runnable synchronizer = deviceSynchronizer;
        if (synchronizer != null && isMappedIo(physical)) {
            synchronizer.run();
        }
    }

    private void synchronizeDeferredDevices() {
        Runnable synchronizer = deviceSynchronizer;
        if (deferredCpuCycleAdvance > 0 && synchronizer != null) {
            synchronizer.run();
        }
    }

    private int baseCpuAccessCycles(int address, int physical, boolean write, int widthBytes) {
        int width = Math.clamp(widthBytes, 1, 4);
        if (isScratchpadAccessible(address, physical)) {
            return 0;
        }
        if (isRam(physical)) {
            return write ? 4 : 6;
        }
        if (isBios(physical)) {
            return externalAccessExtraCycles(timingMemoryControl[4], write, width);
        }
        if (isExpansion1(physical)) {
            return externalAccessExtraCycles(timingMemoryControl[2], write, width);
        }
        if (physical >= 0x1F80_1800 && physical <= 0x1F80_1803) {
            // The CD-ROM port adds one clock after the expansion-port wait states.
            return externalAccessExtraCycles(timingMemoryControl[6], write, width) + 1;
        }
        if (physical >= 0x1F80_1C00 && physical <= 0x1F80_1FFF) {
            // The SPU is a 16-bit device.
            if ((timingMemoryControl[5] & ~0x0200_0000) == 0x2009_31E1
                && width <= 2) {
                return 17;
            }
            return externalAccessExtraCycles(timingMemoryControl[5], write, width);
        }
        if (isExpansion2(physical)) {
            return expansion2AccessExtraCycles(timingMemoryControl[7], write, width);
        }
        if (isExpansion3(physical)) {
            return externalAccessExtraCycles(timingMemoryControl[3], write, width);
        }
        if (isUnlockedCpuControlAddress(physical)) {
            return width == 1 ? 0 : 1;
        }
        if (isMappedIo(physical)) {
            if (!write) {
                return 4;
            }
            boolean partialRegisterAccess = width < 4
                && ((physical >= 0x1F80_1070 && physical <= 0x1F80_1077)
                    || (physical >= 0x1F80_1080 && physical <= 0x1F80_10FF)
                    || (physical >= 0x1F80_1820 && physical <= 0x1F80_1827));
            boolean gpuWordAccess = width == 4
                && physical >= 0x1F80_1810 && physical <= 0x1F80_1817;
            return partialRegisterAccess || gpuWordAccess ? 3 : 2;
        }
        return 1;
    }

    private int expansion2AccessExtraCycles(int delaySize, boolean write, int widthBytes) {
        int accessTime = (delaySize >>> (write ? 0 : 4)) & 0xF;
        int busBytes = (delaySize & (1 << 12)) != 0 ? 2 : 1;
        int transfers = Math.max(1, (widthBytes + busBytes - 1) / busBytes);
        if (delaySize == 0x0007_0777 && !write) {
            return 10 + (transfers - 1) * 15;
        }
        return externalAccessExtraCycles(delaySize, write, widthBytes);
    }

    private int externalAccessExtraCycles(int delaySize, boolean write, int widthBytes) {
        int accessTime = (delaySize >>> (write ? 0 : 4)) & 0xF;
        int busBytes = (delaySize & (1 << 12)) != 0 ? 2 : 1;
        int transfers = Math.max(1, (widthBytes + busBytes - 1) / busBytes);
        int total = firstExternalAccessCycles(delaySize, accessTime);
        int sequential = sequentialExternalAccessCycles(delaySize, accessTime);
        total += (transfers - 1) * sequential;
        return Math.max(0, total - 1);
    }

    private int firstExternalAccessCycles(int delaySize, int accessTime) {
        int common = timingMemoryControl[8];
        int first = 0;
        int minimum = 0;
        if ((delaySize & (1 << 8)) != 0) {
            first += (common & 0xF) - 1;
        }
        if ((delaySize & (1 << 10)) != 0) {
            first += (common >>> 8) & 0xF;
        }
        if ((delaySize & (1 << 11)) != 0) {
            minimum = (common >>> 12) & 0xF;
        }
        if (first < 6) {
            first++;
        }
        first += accessTime + 2;
        return Math.max(first, minimum + 6);
    }

    private int sequentialExternalAccessCycles(int delaySize, int accessTime) {
        int common = timingMemoryControl[8];
        int sequential = 0;
        int minimum = 0;
        if ((delaySize & (1 << 8)) != 0) {
            sequential += (common & 0xF) - 1;
        }
        if ((delaySize & (1 << 10)) != 0) {
            sequential += (common >>> 8) & 0xF;
        }
        if ((delaySize & (1 << 11)) != 0) {
            minimum = (common >>> 12) & 0xF;
        }
        sequential += accessTime + 2;
        return Math.max(sequential, minimum + 2);
    }

    private boolean usesWriteQueue(int address, int physical) {
        if (isScratchpadAccessible(address, physical)) {
            return false;
        }
        return isWriteQueueEnabledSegment(address);
    }

    private boolean flushesWriteQueue(int address, int physical, boolean write, int widthBytes) {
        return isUncachedSegment(address)
            || isScratchpadAccessible(address, physical)
            || (!write && cpuWriteQueueOverlaps(physical, widthBytes));
    }

    private int enqueueCpuWrite(int address, int physical, int widthBytes, int accessCycles) {
        int stallCycles = 0;
        if (cpuWriteQueueSize == WRITE_QUEUE_DEPTH) {
            stallCycles = Math.max(1, cpuWriteQueueRemainingCycles[cpuWriteQueueHead]);
            drainCpuWriteQueue(stallCycles);
            cpuWriteQueueAdvanceCredit += stallCycles;
        }
        enqueueCpuWrite(physical, widthBytes, accessCycles, 0, false);
        return stallCycles;
    }

    private void enqueueCpuWrite(int physical, int widthBytes, int accessCycles,
                                 int value, boolean valueValid) {
        int drainCycles = queuedWriteDrainCycles(physical, accessCycles);
        int tail = (cpuWriteQueueHead + cpuWriteQueueSize) % WRITE_QUEUE_DEPTH;
        cpuWriteQueueAddress[tail] = physical;
        cpuWriteQueueWidth[tail] = Math.clamp(widthBytes, 1, 4);
        cpuWriteQueueRemainingCycles[tail] = drainCycles;
        cpuWriteQueueValue[tail] = value;
        cpuWriteQueueValueValid[tail] = valueValid;
        cpuWriteQueueSize++;
    }

    private int queuedWriteDrainCycles(int physical, int accessCycles) {
        if (isRam(physical) && cpuWriteQueueSize > 0) {
            int previous = (cpuWriteQueueHead + cpuWriteQueueSize - 1) % WRITE_QUEUE_DEPTH;
            if ((cpuWriteQueueAddress[previous] & ~0x3FF) == (physical & ~0x3FF)) {
                return 2;
            }
        }
        return Math.max(1, accessCycles);
    }

    private void drainCpuWriteQueue(int cycles) {
        int remaining = Math.max(0, cycles);
        while (remaining > 0 && cpuWriteQueueSize > 0) {
            int elapsed = Math.min(remaining, cpuWriteQueueRemainingCycles[cpuWriteQueueHead]);
            cpuWriteQueueRemainingCycles[cpuWriteQueueHead] -= elapsed;
            remaining -= elapsed;
            if (cpuWriteQueueRemainingCycles[cpuWriteQueueHead] == 0) {
                commitCpuWriteQueueHead();
                cpuWriteQueueAddress[cpuWriteQueueHead] = 0;
                cpuWriteQueueWidth[cpuWriteQueueHead] = 0;
                cpuWriteQueueValue[cpuWriteQueueHead] = 0;
                cpuWriteQueueValueValid[cpuWriteQueueHead] = false;
                cpuWriteQueueHead = (cpuWriteQueueHead + 1) % WRITE_QUEUE_DEPTH;
                cpuWriteQueueSize--;
                if (deferredCpuWriteValid && cpuWriteQueueSize < WRITE_QUEUE_DEPTH) {
                    enqueueCpuWrite(deferredCpuWriteAddress, deferredCpuWriteWidth,
                        deferredCpuWriteCycles, deferredCpuWriteValue, true);
                    deferredCpuWriteValid = false;
                }
            }
        }
    }

    private int flushCpuWriteQueue() {
        int cycles = cpuWriteQueueTotalCycles();
        drainCpuWriteQueue(cycles);
        cpuWriteQueueAdvanceCredit = 0;
        return cycles;
    }

    public void completeCpuWritesBeforeHostMemoryReplacement() {
        flushCpuWriteQueue();
    }

    private void commitCpuWriteQueueHead() {
        if (!cpuWriteQueueValueValid[cpuWriteQueueHead]) {
            return;
        }
        boolean isolatedAtCompletion = cacheIsolated;
        cacheIsolated = false;
        try {
            int address = cpuWriteQueueAddress[cpuWriteQueueHead];
            int value = cpuWriteQueueValue[cpuWriteQueueHead];
            int width = cpuWriteQueueWidth[cpuWriteQueueHead];
            consumePendingTimingExternalAccess(address);
            if (isRam(address)) {
                writeRamDirect(address, value, width);
            } else {
                writeByWidth(address, value, width);
            }
        } finally {
            cacheIsolated = isolatedAtCompletion;
        }
    }

    private void writeRamDirect(int address, int value, int widthBytes) {
        int offset = ramOffset(address);
        switch (widthBytes) {
            case 1 -> ram.put(offset, (byte) value);
            case 2 -> ram.putShort(offset, (short) value);
            case 3 -> {
                ram.putShort(offset, (short) value);
                ram.put(offset + 2, (byte) (value >>> 16));
            }
            case 4 -> ram.putInt(offset, value);
            default -> throw new IllegalArgumentException("Unsupported RAM store width: " + widthBytes);
        }
        recordRamWrite(address, value, widthBytes);
    }

    private int cpuWriteQueueTotalCycles() {
        int cycles = 0;
        for (int i = 0; i < cpuWriteQueueSize; i++) {
            int index = (cpuWriteQueueHead + i) % WRITE_QUEUE_DEPTH;
            cycles += cpuWriteQueueRemainingCycles[index];
        }
        return cycles;
    }

    private boolean cpuWriteQueueOverlaps(int physical, int widthBytes) {
        long accessStart = Integer.toUnsignedLong(physical) >>> 2;
        long accessEnd = (Integer.toUnsignedLong(physical)
            + Math.max(1, widthBytes) - 1L) >>> 2;
        for (int i = 0; i < cpuWriteQueueSize; i++) {
            int index = (cpuWriteQueueHead + i) % WRITE_QUEUE_DEPTH;
            long writeStart = Integer.toUnsignedLong(cpuWriteQueueAddress[index]) >>> 2;
            long writeEnd = (Integer.toUnsignedLong(cpuWriteQueueAddress[index])
                + Math.max(1, cpuWriteQueueWidth[index]) - 1L) >>> 2;
            if (accessStart <= writeEnd && writeStart <= accessEnd) {
                return true;
            }
        }
        return false;
    }

    private void clearCpuWriteQueue() {
        Arrays.fill(cpuWriteQueueAddress, 0);
        Arrays.fill(cpuWriteQueueWidth, 0);
        Arrays.fill(cpuWriteQueueRemainingCycles, 0);
        Arrays.fill(cpuWriteQueueValue, 0);
        Arrays.fill(cpuWriteQueueValueValid, false);
        cpuWriteQueueHead = 0;
        cpuWriteQueueSize = 0;
        cpuWriteQueueAdvanceCredit = 0;
        issuedCpuWriteUsesQueue = false;
        deferredCpuWriteValid = false;
        deferredCpuWriteAddress = 0;
        deferredCpuWriteWidth = 0;
        deferredCpuWriteCycles = 0;
        deferredCpuWriteValue = 0;
    }

    private void consumePendingTimingExternalAccess(int physical) {
        if (pendingTimingMemoryControlExternalAccesses <= 0 || !isExternalBusAddress(physical)) {
            return;
        }
        pendingTimingMemoryControlExternalAccesses--;
        if (pendingTimingMemoryControlExternalAccesses == 0) {
            System.arraycopy(pendingTimingMemoryControl, 0, timingMemoryControl, 0,
                timingMemoryControl.length);
        }
    }

    private boolean isExternalBusAddress(int physical) {
        return isBios(physical)
            || isExpansion1(physical)
            || isExpansion2(physical)
            || isExpansion3(physical)
            || (physical >= 0x1F80_1800 && physical <= 0x1F80_1803)
            || (physical >= 0x1F80_1C00 && physical <= 0x1F80_1FFF);
    }

    private int[] initialTimingMemoryControl() {
        int[] result = memoryControl.clone();
        for (int index = 2; index <= 7; index++) {
            result[index] = (result[index] & ~0xFF) | 0xFF;
        }
        return result;
    }

    private static boolean isWriteQueueEnabledSegment(int address) {
        int segment = address & 0xE000_0000;
        return segment == 0x0000_0000 || segment == 0x8000_0000;
    }

    private static boolean isUncachedSegment(int address) {
        return (address & 0xE000_0000) == 0xA000_0000;
    }

    private int ramWindowSize() {
        return configuredRamWindowSize;
    }

    private static int decodeRamWindowSize(int ramSizeRegister) {
        return switch ((ramSizeRegister >>> 9) & 0x7) {
            case 0b000 -> 1 * 1024 * 1024;
            case 0b001 -> 4 * 1024 * 1024;
            case 0b010 -> 2 * 1024 * 1024;
            case 0b011 -> 8 * 1024 * 1024;
            case 0b100 -> 2 * 1024 * 1024;
            case 0b101 -> 8 * 1024 * 1024;
            case 0b110 -> 4 * 1024 * 1024;
            case 0b111 -> 16 * 1024 * 1024;
            default -> RAM_SIZE;
        };
    }

    private static boolean containsWord(int base, int size, int physical) {
        return size >= 4
            && physical >= base
            && Integer.compareUnsigned(physical - base, size - 4) <= 0;
    }

    private boolean scratchpadMapped() {
        return scratchpadMapped;
    }

    private static boolean decodeScratchpadMapped(int value) {
        return (value & 0x88) == 0x88;
    }

    private int configuredWindowSize(int delaySize) {
        int addressBits = (delaySize >>> 16) & 0x1F;
        if (addressBits >= 31) {
            return Integer.MAX_VALUE;
        }
        return 1 << addressBits;
    }

    private int alignedExpansionBase(int rawBase, int size) {
        if (size <= 0 || size == Integer.MAX_VALUE) {
            return rawBase;
        }
        return rawBase & -size;
    }

    private int normalizeCacheControl(int value) {
        return value & ~((1 << 6) | (1 << 10));
    }

    public static final class State {
        int[] memoryControl;
        int[] timingMemoryControl;
        int[] pendingTimingMemoryControl;
        int cacheControl;
        int postRegister;
        boolean cacheIsolated;
        int[] cpuWriteQueueAddress;
        int[] cpuWriteQueueWidth;
        int[] cpuWriteQueueRemainingCycles;
        int[] cpuWriteQueueValue;
        boolean[] cpuWriteQueueValueValid;
        int cpuWriteQueueHead;
        int cpuWriteQueueSize;
        int cpuWriteQueueAdvanceCredit;
        int pendingTimingMemoryControlExternalAccesses;
        int cpuWriteQueueCycles;
        int pendingTimingMemoryControlCycles;
        int[] iCacheTags;
        int[] iCacheData;
        int lastBusValue;
    }
}
