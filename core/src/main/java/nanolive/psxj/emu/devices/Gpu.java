package nanolive.psxj.emu.devices;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.dma.DmaPort;
import nanolive.psxj.emu.hardware.HardwareProfile;
import nanolive.psxj.emu.video.GpuFrame;
import nanolive.psxj.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class Gpu implements DmaPort {

    private static final int[] RGB555_TO_ARGB = createRgb555ToArgbTable();

    private static final int VRAM_WIDTH = 1024;
    private static final int VRAM_HEIGHT = 512;
    private static final int POLYLINE_TERMINATOR_MASK = 0xF000_F000;
    private static final int POLYLINE_TERMINATOR = 0x5000_5000;
    private static final int NTSC_CRTC_TICKS_PER_LINE = 3413;
    private static final int PAL_CRTC_TICKS_PER_LINE = 3406;
    private static final int NTSC_TOTAL_SCANLINES = 263;
    private static final int PAL_TOTAL_SCANLINES = 314;
    private static final int MAX_PRIMITIVE_WIDTH = 1023;
    private static final int MAX_PRIMITIVE_HEIGHT = 511;
    private static final int GP0_FIFO_CAPACITY_WORDS = 16;
    private static final int MAX_RENDER_BUSY_CYCLES = 1 << 24;
    private static final int TEXTURE_CACHE_ENTRY_COUNT = 256;
    private static final int TEXTURE_CACHE_WORDS_PER_ENTRY = 4;
    private static final int[][] DITHER_MATRIX = {
        {-4, 0, -3, 1},
        {2, -2, 3, -1},
        {-3, 1, -4, 0},
        {3, -1, 2, -2}
    };

    private final InterruptController interruptController;
    private final HardwareProfile hardwareProfile;
    private final int gpuClockRatioNumerator;
    private final int gpuClockRatioDenominator;
    private final short[] vram = new short[VRAM_WIDTH * VRAM_HEIGHT];
    private final short[] clutCache = new short[256];
    private final short[] textureCache =
        new short[TEXTURE_CACHE_ENTRY_COUNT * TEXTURE_CACHE_WORDS_PER_ENTRY];
    private final int[] textureCacheTags = new int[TEXTURE_CACHE_ENTRY_COUNT];
    private final int[] gp0IngressFifo = new int[GP0_FIFO_CAPACITY_WORDS];
    private int gp0IngressHead;
    private int gp0IngressSize;
    private int[] commandFifo = new int[16];
    private int commandFifoSize;
    private final Vertex texturedVertex0 = new Vertex();
    private final Vertex texturedVertex1 = new Vertex();
    private final Vertex texturedVertex2 = new Vertex();
    private final Vertex texturedVertex3 = new Vertex();
    private final Set<Integer> unsupportedOpcodesLogged = new HashSet<>();

    private int frameCounter;
    private int displayMode;
    private int drawMode;
    private int textureWindow;
    private int displayStartX;
    private int displayStartY;
    private int displayRangeX1;
    private int displayRangeX2 = 3200;
    private int displayRangeY1;
    private int displayRangeY2 = 240;
    private int drawAreaLeft;
    private int drawAreaTop;
    private int drawAreaRight = VRAM_WIDTH - 1;
    private int drawAreaBottom = VRAM_HEIGHT - 1;
    private int drawOffsetX;
    private int drawOffsetY;
    private int frameBufferWidth = 320;
    private int frameBufferHeight = 240;
    private int displayedStartX;
    private int displayedStartY;
    private int displayedWidth = 256;
    private int displayedHeight = 240;
    private int displayedMode;
    private boolean displayedDisabled = true;
    private int completedStartX;
    private int completedStartY;
    private int completedWidth = 256;
    private int completedHeight = 240;
    private int completedMode;
    private boolean completedDisabled = true;
    private int[] completedFramePixels;
    private int completedFrameWidth;
    private int completedFrameHeight;
    private int status = 0x1480_2000;
    private int currentCommand;
    private int wordsRemaining;
    private int transferX;
    private int transferY;
    private int transferWidth;
    private int transferHeight;
    private int transferOriginX;
    private int transferOriginY;
    private int transferColumn;
    private int transferRow;
    private int transferPixelsRemaining;
    private int gpureadLatch;
    private int gp0FifoWords;
    private int gp0FifoDrainCarry;
    private int renderBusyCycles;
    private int[] activeRenderWords;
    private int renderTotalCycles;
    private int renderElapsedCycles;
    private int renderSetupCycles;
    private int renderJournalCursor;
    private int renderJournalCount;
    private int renderJournalNextCommitCycle = Integer.MAX_VALUE;
    private int[] renderJournalIndices = new int[0];
    private short[] renderJournalValues = new short[0];
    private short[] planningJournalOldValues = new short[0];
    private boolean planningRender;
    private boolean activeRenderJournalPlanned;
    private int activeRenderDrawModeAfter;
    private int commandWorkPixels;
    private int commandTimingPixels;
    private boolean cpuToVramTransfer;
    private boolean vramToCpuTransfer;
    private boolean displayDisabled;
    private boolean checkMaskBit;
    private boolean forceMaskBit;
    private boolean currentSemiTransparent;
    private boolean currentSemiTransparencyRequiresBit15;
    private boolean pendingPolyline;
    private boolean pendingShadedPolyline;
    private boolean streamingQuadContinuation;
    private boolean streamingPolyline;
    private boolean streamingPolylineAwaitingVertex;
    private int streamingPolylinePreviousXy;
    private int streamingPolylinePreviousColor;
    private int streamingPolylineNextColor;
    private int dirtyMinX = VRAM_WIDTH;
    private int dirtyMinY = VRAM_HEIGHT;
    private int dirtyMaxX = -1;
    private int dirtyMaxY = -1;
    private long totalGp0Words;
    private long totalImageWords;
    private int vblankCycleAccumulator;
    // Vertical CRTC position in half video-clock ticks.
    private long fieldCrtcHalfTicks;
    private int scanlineCycleAccumulator;
    private int scanline;
    private int nextHorizontalBoundaryTick;
    private long nextVerticalBoundaryHalfTick;
    private boolean interlacedFieldOdd;
    private boolean interlacedDisplayFieldOdd;
    private boolean inHblank;
    private boolean inVblank;
    private long crtcFractionalTicks;
    private int crtcTicksLastTick;
    private int hblankRisesLastTick;
    private int hblankFallsLastTick;
    private int vblankRisesLastTick;
    private int vblankFallsLastTick;
    private int dotClockDividerPhase;
    private int timerDotClockDivider = 10;
    private int dotClockTicksThisLine;
    private int dotClockTicksLastTick;
    private int completedScanlineDotClockTicks;
    private boolean irqRequested;
    private boolean allowSecondVramBank;
    private int cachedClutBits = -1;
    private boolean cachedClutIs8Bit;
    public Gpu(InterruptController interruptController) {
        this(interruptController, HardwareProfile.SCPH_5501_PU_18_NTSC_U);
    }

    public Gpu(InterruptController interruptController, HardwareProfile hardwareProfile) {
        this.interruptController = interruptController;
        this.hardwareProfile = Objects.requireNonNull(hardwareProfile);
        this.gpuClockRatioNumerator = hardwareProfile.gpuClockRatioNumerator();
        this.gpuClockRatioDenominator = hardwareProfile.gpuClockRatioDenominator();
        resetGpu();
    }

    public void gp0(int value) {
        if (cpuToVramTransfer) {
            if (gp0IngressSize > 0) {
                processGp0Ingress();
            }
            if (cpuToVramTransfer) {
                totalGp0Words++;
                writeImageData(value);
                return;
            }
        }
        int opcode = (value >>> 24) & 0xFF;
        if ((renderBusyCycles > 0 || activeRenderWords != null)
            && gp0IngressSize == 0
            && !awaitingCommandData()
            && isFifoFreeOpcode(opcode)) {
            totalGp0Words++;
            executeFifoFreeCommand(value, opcode);
            return;
        }
        if (gp0IngressSize >= GP0_FIFO_CAPACITY_WORDS) {
            return;
        }
        totalGp0Words++;
        gp0IngressFifo[(gp0IngressHead + gp0IngressSize) & (GP0_FIFO_CAPACITY_WORDS - 1)] = value;
        gp0IngressSize++;
        updateGp0FifoCount();
        processGp0Ingress();
    }

    private void executeFifoFreeCommand(int value, int opcode) {
        switch (opcode) {
            case 0xE3 -> {
                int xy = value & 0x00FF_FFFF;
                drawAreaLeft = xy & 0x3FF;
                drawAreaTop = decodeVramY(xy >>> 10);
            }
            case 0xE4 -> {
                int xy = value & 0x00FF_FFFF;
                drawAreaRight = xy & 0x3FF;
                drawAreaBottom = decodeVramY(xy >>> 10);
            }
            case 0xE5 -> {
                drawOffsetX = sign11(value & 0x7FF);
                drawOffsetY = sign11((value >>> 11) & 0x7FF);
            }
            default -> {
            }
        }
    }

    private void processGp0Ingress() {
        if (renderBusyCycles > 0 || activeRenderWords != null || vramToCpuTransfer) {
            return;
        }
        while (gp0IngressSize > 0) {
            int value = gp0IngressFifo[gp0IngressHead];
            gp0IngressHead = (gp0IngressHead + 1) & (GP0_FIFO_CAPACITY_WORDS - 1);
            gp0IngressSize--;
            updateGp0FifoCount();
            if (cpuToVramTransfer) {
                writeImageData(value);
            } else {
                parseGp0Word(value);
            }
            if (renderBusyCycles > 0 || activeRenderWords != null
                || vramToCpuTransfer) {
                return;
            }
        }
    }

    private void parseGp0Word(int value) {
        if (pendingPolyline) {
            parsePolylineWord(value);
            return;
        }
        if (wordsRemaining == 0) {
            currentCommand = value;
            clearCommandWords();
            appendCommandWord(value);
            int opcode = (value >>> 24) & 0xFF;
            if (isPolylineOpcode(opcode)) {
                pendingPolyline = true;
                pendingShadedPolyline = isGouraudShadedOpcode(opcode);
                return;
            }
            wordsRemaining = commandLength(opcode) - 1;
            if (wordsRemaining == 0) {
                completeParsedCommand();
            }
            return;
        }
        appendCommandWord(value);
        wordsRemaining--;
        if (wordsRemaining == 0) {
            completeParsedCommand();
        } else if (isPolygonOpcode((currentCommand >>> 24) & 0xFF)
            && isQuadOpcode((currentCommand >>> 24) & 0xFF)
            && commandFifoSize == polygonCommandLength(
                ((currentCommand >>> 24) & 0xFF) & ~0x08
            )) {
            beginStreamingQuad();
        }
    }

    private void parsePolylineWord(int value) {
        if (!streamingPolyline) {
            appendCommandWord(value);
            int requiredWords = pendingShadedPolyline ? 4 : 3;
            if (commandFifoSize < requiredWords) {
                return;
            }
            int[] firstSegment = words();
            firstSegment[0] &= ~0x0800_0000;
            streamingPolyline = true;
            if (pendingShadedPolyline) {
                streamingPolylinePreviousColor = firstSegment[2] & 0x00FF_FFFF;
                streamingPolylinePreviousXy = firstSegment[3];
            } else {
                streamingPolylinePreviousColor = currentCommand & 0x00FF_FFFF;
                streamingPolylinePreviousXy = firstSegment[2];
            }
            clearCommandWords();
            appendCommandWord(currentCommand);
            beginDeferredRenderPacket(firstSegment, 16);
            return;
        }

        if (pendingShadedPolyline) {
            if (!streamingPolylineAwaitingVertex) {
                if (isPolylineTerminator(value)) {
                    finishStreamingPolyline();
                    return;
                }
                streamingPolylineNextColor = value & 0x00FF_FFFF;
                streamingPolylineAwaitingVertex = true;
                return;
            }
            int command = (currentCommand & 0xFF00_0000)
                | streamingPolylinePreviousColor;
            command &= ~0x0800_0000;
            int[] segment = {
                command,
                streamingPolylinePreviousXy,
                streamingPolylineNextColor,
                value
            };
            streamingPolylinePreviousColor = streamingPolylineNextColor;
            streamingPolylinePreviousXy = value;
            streamingPolylineAwaitingVertex = false;
            beginDeferredRenderPacket(segment, 0);
            return;
        }

        if (isPolylineTerminator(value)) {
            finishStreamingPolyline();
            return;
        }
        int command = (currentCommand & ~0x0800_0000);
        int[] segment = {command, streamingPolylinePreviousXy, value};
        streamingPolylinePreviousXy = value;
        beginDeferredRenderPacket(segment, 0);
    }

    private void finishStreamingPolyline() {
        pendingPolyline = false;
        pendingShadedPolyline = false;
        streamingPolyline = false;
        streamingPolylineAwaitingVertex = false;
        streamingPolylinePreviousXy = 0;
        streamingPolylinePreviousColor = 0;
        streamingPolylineNextColor = 0;
        wordsRemaining = 0;
        clearCommandWords();
    }

    private void beginStreamingQuad() {
        streamingQuadContinuation = true;
        int[] firstTriangle = words();
        firstTriangle[0] &= ~0x0800_0000;
        beginDeferredRenderPacket(firstTriangle, -1);
    }

    public void gp1(int value) {
        int op = (value >>> 24) & 0xFF;
        if (op >= 0x40) {
            op &= 0x3F;
        }
        if (op >= 0x10 && op <= 0x1F) {
            gpureadLatch = readInfo(value & 0xF);
            return;
        }
        switch (op) {
            case 0x00 -> resetGpu();
            case 0x01 -> {
                resetCommandBuffer();
                invalidateClutCache();
            }
            case 0x02 -> {
                irqRequested = false;
                interruptController.clear(1);
            }
            case 0x03 -> {
                displayDisabled = (value & 1) != 0;
                status = (status & ~(1 << 23)) | ((value & 1) << 23);
            }
            case 0x04 -> status = (status & ~(0x3 << 29)) | ((value & 0x3) << 29);
            case 0x05 -> {
                displayStartX = value & 0x3FF;
                displayStartY = decodeVramY(value >>> 10);
            }
            case 0x06 -> {
                displayRangeX1 = value & 0xFFF;
                displayRangeX2 = (value >>> 12) & 0xFFF;
                recomputeDisplayDimensions();
                synchronizeTimerHblankToDisplayRange();
            }
            case 0x07 -> {
                displayRangeY1 = value & 0x3FF;
                displayRangeY2 = (value >>> 10) & 0x3FF;
                recomputeDisplayDimensions();
            }
            case 0x08 -> updateDisplayDimensions(value & 0xFF);
            case 0x09 -> allowSecondVramBank = (value & 0x1) != 0;
            default -> {
                if (Log.isDebugEnabled()) {
                    Log.debug("Unhandled GP1 command 0x" + Integer.toHexString(op)
                        + " value=0x" + Integer.toHexString(value));
                }
            }
        }
    }

    public int gpuread() {
        if (vramToCpuTransfer) {
            int lo = readImagePixel();
            int hi = readImagePixel();
            if (!vramToCpuTransfer) {
                processGp0Ingress();
            }
            return lo | (hi << 16);
        }
        return gpureadLatch;
    }

    private void updateGp0FifoCount() {
        gp0FifoWords = gp0IngressSize;
    }

    public void dmaLinkedList(Bus bus, int baseAddress) {
        int address = baseAddress & 0x00FF_FFFC;
        int safety = 0;
        while (safety++ < 0x10000) {
            int header = bus.read32(address);
            int wordCount = (header >>> 24) & 0xFF;
            int nextAddress = header & 0x00FF_FFFC;
            int current = (address + 4) & 0x00FF_FFFC;
            for (int i = 0; i < wordCount; i++) {
                gp0(bus.read32(current));
                current = (current + 4) & 0x00FF_FFFC;
            }
            if ((header & 0x0080_0000) != 0 || (header & 0x00FF_FFFF) == 0x00FF_FFFF) {
                break;
            }
            address = nextAddress;
        }
    }

    private int readInfo(int index) {
        return switch (index & 0xF) {
            case 2 -> textureWindow;
            case 3 -> drawAreaLeft | (drawAreaTop << 10);
            case 4 -> drawAreaRight | (drawAreaBottom << 10);
            case 5 -> (drawOffsetX & 0x7FF) | ((drawOffsetY & 0x7FF) << 11);
            case 7 -> 2;
            case 8 -> 0;
            default -> gpureadLatch;
        };
    }

    private void resetGpu() {
        boolean retainedSecondVramBank = allowSecondVramBank;
        resetCommandBuffer();
        irqRequested = false;
        interruptController.clear(1);
        status = 0x1480_2000;
        displayMode = 0;
        drawMode = 0;
        textureWindow = 0;
        displayStartX = 0;
        displayStartY = 0;
        displayRangeX1 = 0x200;
        displayRangeX2 = 0xC00;
        displayRangeY1 = 0x010;
        displayRangeY2 = 0x100;
        drawAreaLeft = 0;
        drawAreaTop = 0;
        drawAreaRight = 0;
        drawAreaBottom = 0;
        drawOffsetX = 0;
        drawOffsetY = 0;
        frameBufferWidth = 256;
        frameBufferHeight = 240;
        displayDisabled = true;
        checkMaskBit = false;
        forceMaskBit = false;
        dirtyMinX = VRAM_WIDTH;
        dirtyMinY = VRAM_HEIGHT;
        dirtyMaxX = -1;
        dirtyMaxY = -1;
        totalGp0Words = 0;
        totalImageWords = 0;
        dotClockDividerPhase = 0;
        dotClockTicksThisLine = 0;
        dotClockTicksLastTick = 0;
        completedScanlineDotClockTicks = 0;
        currentSemiTransparencyRequiresBit15 = false;
        allowSecondVramBank = retainedSecondVramBank;
        invalidateTextureAndClutCaches();
        completedFramePixels = null;
        recomputeDisplayDimensions();
        normalizeCrtcTimingState();
        latchDisplayedState();
        snapshotCompletedDisplayState();
        inHblank = hblankLevelAt(scanlineCycleAccumulator);
        inVblank = isVblankScanline(scanline, verticalRangeLimit());
    }

    private void resetCommandBuffer() {
        gp0IngressHead = 0;
        gp0IngressSize = 0;
        clearCommandWords();
        wordsRemaining = 0;
        currentCommand = 0;
        gp0FifoWords = 0;
        gp0FifoDrainCarry = 0;
        renderBusyCycles = 0;
        activeRenderWords = null;
        renderTotalCycles = 0;
        renderElapsedCycles = 0;
        renderSetupCycles = 0;
        renderJournalCursor = 0;
        renderJournalCount = 0;
        renderJournalNextCommitCycle = Integer.MAX_VALUE;
        planningRender = false;
        activeRenderJournalPlanned = false;
        activeRenderDrawModeAfter = drawMode;
        commandWorkPixels = 0;
        cpuToVramTransfer = false;
        vramToCpuTransfer = false;
        transferColumn = 0;
        transferRow = 0;
        transferPixelsRemaining = 0;
        pendingPolyline = false;
        pendingShadedPolyline = false;
        streamingQuadContinuation = false;
        streamingPolyline = false;
        streamingPolylineAwaitingVertex = false;
        streamingPolylinePreviousXy = 0;
        streamingPolylinePreviousColor = 0;
        streamingPolylineNextColor = 0;
    }

    private int commandLength(int opcode) {
        if (isPolygonOpcode(opcode)) {
            return polygonCommandLength(opcode);
        }
        if (isRectangleOpcode(opcode)) {
            return rectangleCommandLength(opcode);
        }
        if (isLineOpcode(opcode)) {
            return lineCommandLength(opcode);
        }
        if (opcode >= 0x80 && opcode <= 0x9F) {
            return 4;
        }
        if (opcode >= 0xA0 && opcode <= 0xDF) {
            return 3;
        }
        return switch (opcode) {
            case 0x00, 0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E,
                 0x1F,
                 0xE0, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF, 0xFF -> 1;
            case 0x02 -> 3;
            case 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6 -> 1;
            default -> {
                if (unsupportedOpcodesLogged.add(opcode)) {
                    Log.warn("Unknown GP0 opcode length requested: 0x" + Integer.toHexString(opcode));
                }
                yield 1;
            }
        };
    }

    private void completeParsedCommand() {
        if (streamingQuadContinuation) {
            int[] secondTriangle = secondTrianglePacket(words());
            streamingQuadContinuation = false;
            beginDeferredRenderPacket(secondTriangle, 36);
            return;
        }
        int opcode = (currentCommand >>> 24) & 0xFF;
        if (isDeferredVramCommand(opcode)) {
            beginDeferredRenderPacket(words(), -1);
            return;
        }
        executeCommand();
    }

    private void beginDeferredRenderPacket(int[] packet, int setupGpuTicksOverride) {
        int parserCommand = currentCommand;
        int parserWordsRemaining = wordsRemaining;
        boolean parserPendingPolyline = pendingPolyline;
        boolean parserPendingShadedPolyline = pendingShadedPolyline;
        boolean parserStreamingQuad = streamingQuadContinuation;
        boolean parserStreamingPolyline = streamingPolyline;
        boolean parserStreamingPolylineAwaitingVertex = streamingPolylineAwaitingVertex;
        int parserStreamingPolylinePreviousXy = streamingPolylinePreviousXy;
        int parserStreamingPolylinePreviousColor = streamingPolylinePreviousColor;
        int parserStreamingPolylineNextColor = streamingPolylineNextColor;
        int[] parserWords = words();

        clearCommandWords();
        for (int word : packet) {
            appendCommandWord(word);
        }
        currentCommand = packet[0];
        int opcode = (currentCommand >>> 24) & 0xFF;
        boolean polygon = isPolygonOpcode(opcode);
        int workPixels = polygon ? 16 : estimateRenderWorkPixels(opcode);
        activeRenderWords = packet;
        long setupGpuTicks = setupGpuTicksOverride >= 0
            ? setupGpuTicksOverride
            : renderSetupGpuTicks(currentCommand);
        renderTotalCycles = setupGpuTicksOverride >= 0
            ? renderCyclesForStreamingPrimitive(currentCommand, workPixels, setupGpuTicks)
            : renderCyclesFor(currentCommand, activeRenderWords.length, workPixels);
        renderBusyCycles = renderTotalCycles;
        renderElapsedCycles = 0;
        renderSetupCycles = Math.min(
            renderTotalCycles,
            gpuTicksToCpuCyclesAllowZero(setupGpuTicks)
        );
        planActiveRenderCommand(workPixels);
        if (polygon) {
            workPixels = commandTimingPixels;
            renderTotalCycles = setupGpuTicksOverride >= 0
                ? renderCyclesForStreamingPrimitive(currentCommand, workPixels, setupGpuTicks)
                : renderCyclesFor(currentCommand, activeRenderWords.length, workPixels);
            renderBusyCycles = renderTotalCycles;
            renderSetupCycles = Math.min(
                renderTotalCycles,
                gpuTicksToCpuCyclesAllowZero(setupGpuTicks)
            );
            renderJournalNextCommitCycle = nextRenderJournalCommitCycle();
        }

        clearCommandWords();
        for (int word : parserWords) {
            appendCommandWord(word);
        }
        currentCommand = parserCommand;
        wordsRemaining = parserWordsRemaining;
        pendingPolyline = parserPendingPolyline;
        pendingShadedPolyline = parserPendingShadedPolyline;
        streamingQuadContinuation = parserStreamingQuad;
        streamingPolyline = parserStreamingPolyline;
        streamingPolylineAwaitingVertex = parserStreamingPolylineAwaitingVertex;
        streamingPolylinePreviousXy = parserStreamingPolylinePreviousXy;
        streamingPolylinePreviousColor = parserStreamingPolylinePreviousColor;
        streamingPolylineNextColor = parserStreamingPolylineNextColor;
    }

    private int renderCyclesForStreamingPrimitive(
        int command,
        int workPixels,
        long setupGpuTicks
    ) {
        int opcode = (command >>> 24) & 0xFF;
        long gpuTicks;
        if (isPolygonOpcode(opcode)) {
            gpuTicks = setupGpuTicks + primitivePixelTicks(
                workPixels,
                isTexturedOpcode(opcode),
                (opcode & 0x2) != 0
            );
        } else if (isLineOpcode(opcode)) {
            gpuTicks = setupGpuTicks + Math.max(1, workPixels);
        } else {
            throw new IllegalArgumentException("Streaming packet is not a polygon or line");
        }
        return gpuTicksToCpuCycles(gpuTicks);
    }

    private static int[] secondTrianglePacket(int[] quad) {
        int opcode = ((quad[0] >>> 24) & 0xFF) & ~0x08;
        boolean textured = isTexturedOpcode(opcode);
        boolean gouraud = isGouraudShadedOpcode(opcode);
        if (!textured && !gouraud) {
            return new int[] {(opcode << 24) | (quad[0] & 0x00FF_FFFF),
                quad[2], quad[3], quad[4]};
        }
        if (!textured) {
            return new int[] {(opcode << 24) | (quad[2] & 0x00FF_FFFF),
                quad[3], quad[4], quad[5], quad[6], quad[7]};
        }
        if (!gouraud) {
            int uv0 = (quad[2] & 0xFFFF_0000) | (quad[4] & 0xFFFF);
            int uv1 = (quad[4] & 0xFFFF_0000) | (quad[6] & 0xFFFF);
            return new int[] {(opcode << 24) | (quad[0] & 0x00FF_FFFF),
                quad[3], uv0, quad[5], uv1, quad[7], quad[8]};
        }
        int uv0 = (quad[2] & 0xFFFF_0000) | (quad[5] & 0xFFFF);
        int uv1 = (quad[5] & 0xFFFF_0000) | (quad[8] & 0xFFFF);
        return new int[] {(opcode << 24) | (quad[3] & 0x00FF_FFFF),
            quad[4], uv0, quad[6], quad[7], uv1, quad[9], quad[10], quad[11]};
    }

    private void executeCommand() {
        int opcode = (currentCommand >>> 24) & 0xFF;
        commandWorkPixels = 0;
        commandTimingPixels = 0;
        if (isPolygonOpcode(opcode)) {
            executePolygonCommand(opcode);
            finishCommand();
            return;
        }
        if (isLineOpcode(opcode)) {
            executeLineCommand(opcode);
            finishCommand();
            return;
        }
        if (isRectangleOpcode(opcode)) {
            executeRectangleCommand(opcode);
            finishCommand();
            return;
        }
        if (opcode >= 0x80 && opcode <= 0x9F) {
            copyRectangle();
            finishCommand();
            return;
        }
        if (opcode >= 0xA0 && opcode <= 0xBF) {
            beginCpuToVramTransfer();
            finishCommand();
            return;
        }
        if (opcode >= 0xC0 && opcode <= 0xDF) {
            beginVramToCpuTransfer();
            finishCommand();
            return;
        }
        switch (opcode) {
            case 0x00, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E,
                 0xE0, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF, 0xFF -> {
            }
            case 0x01 -> invalidateTextureAndClutCaches();
            case 0x02 -> fillRectangle();
            case 0x1F -> requestInterrupt();
            case 0xE1 -> setDrawMode(currentCommand & 0x3FFF);
            case 0xE2 -> textureWindow = currentCommand & 0xFFFFF;
            case 0xE3 -> {
                int xy = currentCommand & 0x00FF_FFFF;
                drawAreaLeft = xy & 0x3FF;
                drawAreaTop = decodeVramY(xy >>> 10);
            }
            case 0xE4 -> {
                int xy = currentCommand & 0x00FF_FFFF;
                drawAreaRight = xy & 0x3FF;
                drawAreaBottom = decodeVramY(xy >>> 10);
            }
            case 0xE5 -> {
                drawOffsetX = sign11(currentCommand & 0x7FF);
                drawOffsetY = sign11((currentCommand >>> 11) & 0x7FF);
            }
            case 0xE6 -> {
                forceMaskBit = (currentCommand & 0x1) != 0;
                checkMaskBit = (currentCommand & 0x2) != 0;
            }
            default -> {
                if (Log.isDebugEnabled()) {
                    Log.debug("Unhandled GP0 opcode 0x" + Integer.toHexString(opcode)
                        + " value=0x" + Integer.toHexString(currentCommand));
                }
            }
        }
        finishCommand();
    }

    private void finishCommand() {
        resetParserState();
    }

    private void resetParserState() {
        clearCommandWords();
        pendingPolyline = false;
        pendingShadedPolyline = false;
        streamingQuadContinuation = false;
        streamingPolyline = false;
        streamingPolylineAwaitingVertex = false;
        streamingPolylinePreviousXy = 0;
        streamingPolylinePreviousColor = 0;
        streamingPolylineNextColor = 0;
        wordsRemaining = 0;
    }

    private int renderCyclesFor(int command, int words, int workPixels) {
        int opcode = (command >>> 24) & 0xFF;
        long gpuTicks;
        if (isPolygonOpcode(opcode)) {
            // Measured PSX command setup costs.
            boolean quad = isQuadOpcode(opcode);
            boolean gouraud = isGouraudShadedOpcode(opcode);
            boolean textured = isTexturedOpcode(opcode);
            int setupTicks;
            if (quad) {
                setupTicks = gouraud ? (textured ? 532 : 370) : (textured ? 262 : 82);
            } else {
                setupTicks = gouraud ? (textured ? 496 : 334) : (textured ? 226 : 46);
            }
            gpuTicks = setupTicks + primitivePixelTicks(workPixels, textured, (opcode & 0x2) != 0);
        } else if (isRectangleOpcode(opcode)) {
            boolean textured = isTexturedOpcode(opcode);
            gpuTicks = 16L + primitivePixelTicks(workPixels, textured, (opcode & 0x2) != 0);
        } else if (isLineOpcode(opcode)) {
            gpuTicks = (isPolylineOpcode(opcode) ? 16L : 0L) + Math.max(1, workPixels);
        } else if (opcode == 0x02) {
            gpuTicks = 46L + Math.max(1, workPixels / 8L);
        } else if (opcode >= 0x80 && opcode <= 0x9F) {
            gpuTicks = Math.max(1L, workPixels * 2L);
        } else if (opcode >= 0xC0 && opcode <= 0xDF) {
            gpuTicks = Math.max(1L, transferPixelsRemaining / 2L);
        } else {
            gpuTicks = Math.max(1, words);
        }
        return gpuTicksToCpuCycles(gpuTicks);
    }

    private static boolean isDeferredVramCommand(int opcode) {
        return isPolygonOpcode(opcode)
            || isLineOpcode(opcode)
            || isRectangleOpcode(opcode)
            || opcode == 0x02
            || (opcode >= 0x80 && opcode <= 0x9F);
    }

    private int estimateRenderWorkPixels(int opcode) {
        int[] packet = commandFifo;
        if (opcode == 0x02) {
            int width = ((packet[2] & 0x3FF) + 0xF) & ~0xF;
            int height = (packet[2] >>> 16) & 0x1FF;
            if (width == 0 || height == 0) {
                return 0;
            }
            int y = decodeVramY(packet[1] >>> 16);
            int rows = 0;
            for (int py = 0; py < height; py++) {
                if (drawTargetsCurrentField((y + py) & vramYAddressMask())) {
                    rows++;
                }
            }
            return width * rows;
        }
        if (opcode >= 0x80 && opcode <= 0x9F) {
            int dstX = packet[2] & 0x3FF;
            int dstY = decodeVramY(packet[2] >>> 16);
            int width = decodeCopyWidth(packet[3]);
            int height = decodeCopyHeight(packet[3]);
            int count = 0;
            for (int py = 0; py < height; py++) {
                int y = (dstY + py) & vramYAddressMask();
                if (y >= VRAM_HEIGHT) {
                    count += width;
                    continue;
                }
                for (int px = 0; px < width; px++) {
                    int x = (dstX + px) & 0x3FF;
                    if (!checkMaskBit || (vram[y * VRAM_WIDTH + x] & 0x8000) == 0) {
                        count++;
                    }
                }
            }
            return count;
        }
        if (isRectangleOpcode(opcode)) {
            int x = truncateRectangleCoordinate(decodeX(packet[1]) + drawOffsetX);
            int y = truncateRectangleCoordinate(decodeY(packet[1]) + drawOffsetY);
            int sizeCode = rectangleSizeCode(opcode);
            int sizeWord = packet[commandFifoSize - 1];
            int width = switch (sizeCode) {
                case 1 -> 1;
                case 2 -> 8;
                case 3 -> 16;
                default -> sizeWord & 0x3FF;
            };
            int height = switch (sizeCode) {
                case 1 -> 1;
                case 2 -> 8;
                case 3 -> 16;
                default -> (sizeWord >>> 16) & 0x1FF;
            };
            if (width > MAX_PRIMITIVE_WIDTH || height > MAX_PRIMITIVE_HEIGHT) {
                return 0;
            }
            return countDrawableRectangle(x, y, width, height);
        }
        if (isLineOpcode(opcode)) {
            return estimateLineWorkPixels(packet, commandFifoSize,
                isGouraudShadedOpcode(opcode));
        }
        if (isPolygonOpcode(opcode)) {
            int stride = 1
                + (isTexturedOpcode(opcode) ? 1 : 0)
                + (isGouraudShadedOpcode(opcode) ? 1 : 0);
            int x0 = decodeX(packet[1]);
            int y0 = decodeY(packet[1]);
            int x1 = decodeX(packet[1 + stride]);
            int y1 = decodeY(packet[1 + stride]);
            int x2 = decodeX(packet[1 + (2 * stride)]);
            int y2 = decodeY(packet[1 + (2 * stride)]);
            int count = countTriangleCoverage(x0, y0, x1, y1, x2, y2);
            if (isQuadOpcode(opcode)) {
                int x3 = decodeX(packet[1 + (3 * stride)]);
                int y3 = decodeY(packet[1 + (3 * stride)]);
                count += countTriangleCoverage(x1, y1, x2, y2, x3, y3);
            }
            return count;
        }
        return 0;
    }

    private int countDrawableRectangle(int x, int y, int width, int height) {
        int firstX = Math.max(0, Math.max(drawAreaLeft, x));
        int lastX = Math.min(VRAM_WIDTH - 1,
            Math.min(drawAreaRight, x + width - 1));
        int firstY = Math.max(0, Math.max(drawAreaTop, y));
        int lastY = Math.min(VRAM_HEIGHT - 1,
            Math.min(drawAreaBottom, y + height - 1));
        if (firstX > lastX || firstY > lastY) {
            return 0;
        }
        int columns = lastX - firstX + 1;
        int count = 0;
        for (int dstY = firstY; dstY <= lastY; dstY++) {
            if (!drawTargetsCurrentField(dstY)) {
                continue;
            }
            if (!checkMaskBit) {
                count += columns;
                continue;
            }
            int rowIndex = dstY * VRAM_WIDTH;
            for (int dstX = firstX; dstX <= lastX; dstX++) {
                if ((vram[rowIndex + dstX] & 0x8000) == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private int estimateLineWorkPixels(int[] packet, int packetLength, boolean gouraud) {
        int count = 0;
        int previous = packet[1];
        if (!isPolylineOpcode((packet[0] >>> 24) & 0xFF)) {
            int next = packet[gouraud ? 3 : 2];
            return countLineCoverage(previous, next);
        }
        int index = 2;
        while (index < packetLength) {
            int word = packet[index];
            if (isPolylineTerminatorAt(index, gouraud, word)) {
                break;
            }
            int next;
            if (gouraud) {
                if (index + 1 >= packetLength) {
                    break;
                }
                next = packet[index + 1];
                index += 2;
            } else {
                next = word;
                index++;
            }
            count += countLineCoverage(previous, next);
            previous = next;
        }
        return count;
    }

    private int countLineCoverage(int startWord, int endWord) {
        int x0 = decodeX(startWord) + drawOffsetX;
        int y0 = decodeY(startWord) + drawOffsetY;
        int x1 = decodeX(endWord) + drawOffsetX;
        int y1 = decodeY(endWord) + drawOffsetY;
        if (Math.abs(x1 - x0) > MAX_PRIMITIVE_WIDTH
            || Math.abs(y1 - y0) > MAX_PRIMITIVE_HEIGHT) {
            return 0;
        }
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = Integer.compare(x1, x0);
        int sy = Integer.compare(y1, y0);
        int error = dx - dy;
        int count = 0;
        int x = x0;
        int y = y0;
        while (true) {
            if (isDrawableTimingPixel(x, y)) {
                count++;
            }
            if (x == x1 && y == y1) {
                return count;
            }
            int doubled = error << 1;
            if (doubled > -dy) {
                error -= dy;
                x += sx;
            }
            if (doubled < dx) {
                error += dx;
                y += sy;
            }
        }
    }

    private int countTriangleCoverage(
        int x0, int y0, int x1, int y1, int x2, int y2
    ) {
        x0 += drawOffsetX;
        y0 += drawOffsetY;
        x1 += drawOffsetX;
        y1 += drawOffsetY;
        x2 += drawOffsetX;
        y2 += drawOffsetY;
        if (primitiveTooLarge(x0, y0, x1, y1, x2, y2)) {
            return 0;
        }
        int area = edge(x0, y0, x1, y1, x2, y2);
        if (area == 0) {
            return 0;
        }
        if (area < 0) {
            int swapX = x1;
            int swapY = y1;
            x1 = x2;
            y1 = y2;
            x2 = swapX;
            y2 = swapY;
        }
        int minX = Math.max(0, Math.max(drawAreaLeft, Math.min(x0, Math.min(x1, x2))));
        int minY = Math.max(0, Math.max(drawAreaTop, Math.min(y0, Math.min(y1, y2))));
        int maxX = Math.min(VRAM_WIDTH - 1,
            Math.min(drawAreaRight, Math.max(x0, Math.max(x1, x2))));
        int maxY = Math.min(VRAM_HEIGHT - 1,
            Math.min(drawAreaBottom, Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) {
            return 0;
        }
        int bias0 = isTopLeftEdge(x1, y1, x2, y2) ? 0 : -1;
        int bias1 = isTopLeftEdge(x2, y2, x0, y0) ? 0 : -1;
        int bias2 = isTopLeftEdge(x0, y0, x1, y1) ? 0 : -1;
        int w0StepX = y2 - y1;
        int w1StepX = y0 - y2;
        int w2StepX = y1 - y0;
        int w0StepY = x1 - x2;
        int w1StepY = x2 - x0;
        int w2StepY = x0 - x1;
        int rowW0 = edge(x1, y1, x2, y2, minX, minY);
        int rowW1 = edge(x2, y2, x0, y0, minX, minY);
        int rowW2 = edge(x0, y0, x1, y1, minX, minY);
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            if (drawTargetsCurrentField(y)) {
                long span = triangleRowSpan(
                    maxX - minX + 1,
                    rowW0 + bias0, w0StepX,
                    rowW1 + bias1, w1StepX,
                    rowW2 + bias2, w2StepX
                );
                if (span >= 0) {
                    int first = (int) (span >>> 32);
                    int last = (int) span;
                    if (!checkMaskBit) {
                        count += last - first + 1;
                    } else {
                        int index = y * VRAM_WIDTH + minX + first;
                        int end = index + last - first;
                        while (index <= end) {
                            if ((vram[index++] & 0x8000) == 0) {
                                count++;
                            }
                        }
                    }
                }
            }
            rowW0 += w0StepY;
            rowW1 += w1StepY;
            rowW2 += w2StepY;
        }
        return count;
    }

    static long triangleRowSpan(int width,
                                int value0, int step0,
                                int value1, int step1,
                                int value2, int step2) {
        int first = 0;
        int last = width - 1;

        if (step0 > 0) first = Math.max(first, -Math.floorDiv(value0, step0));
        else if (step0 < 0) last = Math.min(last, Math.floorDiv(value0, -step0));
        else if (value0 < 0) return -1L;

        if (step1 > 0) first = Math.max(first, -Math.floorDiv(value1, step1));
        else if (step1 < 0) last = Math.min(last, Math.floorDiv(value1, -step1));
        else if (value1 < 0) return -1L;

        if (step2 > 0) first = Math.max(first, -Math.floorDiv(value2, step2));
        else if (step2 < 0) last = Math.min(last, Math.floorDiv(value2, -step2));
        else if (value2 < 0) return -1L;

        first = Math.max(0, first);
        last = Math.min(width - 1, last);
        return first > last
            ? -1L
            : ((long) first << 32) | (last & 0xFFFF_FFFFL);
    }

    private boolean isDrawableTimingPixel(int x, int y) {
        return x >= 0 && x < VRAM_WIDTH && y >= 0 && y < VRAM_HEIGHT
            && x >= drawAreaLeft && x <= drawAreaRight
            && y >= drawAreaTop && y <= drawAreaBottom
            && drawTargetsCurrentField(y)
            && (!checkMaskBit || (vram[y * VRAM_WIDTH + x] & 0x8000) == 0);
    }

    private long primitivePixelTicks(int workPixels, boolean textured, boolean semiTransparent) {
        long ticks = Math.max(0, workPixels);
        if (textured) {
            ticks *= 2;
        }
        if (semiTransparent || checkMaskBit) {
            ticks += (ticks + 1) / 2;
        }
        return ticks;
    }

    private int gpuTicksToCpuCycles(long gpuTicks) {
        long numerator = Math.max(1L, gpuTicks) * hardwareProfile.gpuClockRatioDenominator();
        long denominator = hardwareProfile.gpuClockRatioNumerator();
        return (int) Math.clamp(
            (numerator + denominator - 1L) / denominator,
            1L,
            MAX_RENDER_BUSY_CYCLES
        );
    }

    private int gpuTicksToCpuCyclesAllowZero(long gpuTicks) {
        if (gpuTicks <= 0) {
            return 0;
        }
        return gpuTicksToCpuCycles(gpuTicks);
    }

    private static long renderSetupGpuTicks(int command) {
        int opcode = (command >>> 24) & 0xFF;
        if (isPolygonOpcode(opcode)) {
            boolean quad = isQuadOpcode(opcode);
            boolean gouraud = isGouraudShadedOpcode(opcode);
            boolean textured = isTexturedOpcode(opcode);
            if (quad) {
                return gouraud ? (textured ? 532 : 370) : (textured ? 262 : 82);
            }
            return gouraud ? (textured ? 496 : 334) : (textured ? 226 : 46);
        }
        if (isRectangleOpcode(opcode)) {
            return 16;
        }
        if (isLineOpcode(opcode)) {
            return isPolylineOpcode(opcode) ? 16 : 0;
        }
        if (opcode == 0x02) {
            return 46;
        }
        return 0;
    }

    private void planActiveRenderCommand(int expectedWrites) {
        int oldDrawMode = drawMode;
        int oldWorkPixels = commandWorkPixels;
        boolean oldSemiTransparent = currentSemiTransparent;
        boolean oldSemiTransparencyRequiresBit15 = currentSemiTransparencyRequiresBit15;
        int oldDirtyMinX = dirtyMinX;
        int oldDirtyMinY = dirtyMinY;
        int oldDirtyMaxX = dirtyMaxX;
        int oldDirtyMaxY = dirtyMaxY;

        int initialCapacity = Math.clamp(expectedWrites, 16, VRAM_WIDTH * VRAM_HEIGHT);
        ensureRenderJournalCapacity(initialCapacity);
        renderJournalCount = 0;
        renderJournalCursor = 0;
        planningRender = true;
        int plannedDrawMode = oldDrawMode;
        try {
            executeCommand();
            plannedDrawMode = drawMode;
        } finally {
            for (int i = renderJournalCount - 1; i >= 0; i--) {
                vram[renderJournalIndices[i]] = planningJournalOldValues[i];
            }
            planningRender = false;
            drawMode = oldDrawMode;
            commandWorkPixels = oldWorkPixels;
            currentSemiTransparent = oldSemiTransparent;
            currentSemiTransparencyRequiresBit15 = oldSemiTransparencyRequiresBit15;
            dirtyMinX = oldDirtyMinX;
            dirtyMinY = oldDirtyMinY;
            dirtyMaxX = oldDirtyMaxX;
            dirtyMaxY = oldDirtyMaxY;
        }
        activeRenderDrawModeAfter = plannedDrawMode;
        activeRenderJournalPlanned = true;
        renderJournalNextCommitCycle = nextRenderJournalCommitCycle();
    }

    private void appendPlannedVramWrite(int index, short oldValue, short newValue) {
        if (renderJournalCount == renderJournalIndices.length
            || renderJournalCount == renderJournalValues.length
            || renderJournalCount == planningJournalOldValues.length) {
            int nextCapacity = Math.min(
                MAX_RENDER_BUSY_CYCLES,
                Math.max(
                    Math.max(renderJournalIndices.length, renderJournalValues.length),
                    Math.max(planningJournalOldValues.length, Math.max(16, renderJournalCount * 2))
                )
            );
            if (nextCapacity <= renderJournalCount) {
                throw new IllegalStateException("GPU render journal exceeded VRAM-sized command limit");
            }
            renderJournalIndices = Arrays.copyOf(renderJournalIndices, nextCapacity);
            renderJournalValues = Arrays.copyOf(renderJournalValues, nextCapacity);
            planningJournalOldValues = Arrays.copyOf(planningJournalOldValues, nextCapacity);
        }
        renderJournalIndices[renderJournalCount] = index;
        renderJournalValues[renderJournalCount] = newValue;
        planningJournalOldValues[renderJournalCount] = oldValue;
        renderJournalCount++;
    }

    private void ensureRenderJournalCapacity(int capacity) {
        int required = Math.max(
            capacity,
            Math.max(renderJournalIndices.length,
                Math.max(renderJournalValues.length, planningJournalOldValues.length))
        );
        if (renderJournalIndices.length < required) {
            renderJournalIndices = Arrays.copyOf(renderJournalIndices, required);
        }
        if (renderJournalValues.length < required) {
            renderJournalValues = Arrays.copyOf(renderJournalValues, required);
        }
        if (planningJournalOldValues.length < required) {
            planningJournalOldValues = Arrays.copyOf(planningJournalOldValues, required);
        }
    }

    private void executePolygonCommand(int opcode) {
        boolean textured = isTexturedOpcode(opcode);
        boolean gouraud = isGouraudShadedOpcode(opcode);
        boolean quad = isQuadOpcode(opcode);
        if (textured) {
            if (quad) {
                drawTexturedQuad(gouraud);
            } else {
                drawTexturedTriangle(gouraud);
            }
            return;
        }
        if (gouraud) {
            if (quad) {
                drawShadedQuad();
            } else {
                drawShadedTriangle();
            }
            return;
        }
        if (quad) {
            drawFlatQuad();
        } else {
            drawFlatTriangle();
        }
    }

    private void executeLineCommand(int opcode) {
        boolean gouraud = isGouraudShadedOpcode(opcode);
        if (isPolylineOpcode(opcode)) {
            drawPolyline(gouraud);
        } else if (gouraud) {
            drawShadedLine();
        } else {
            drawFlatLine();
        }
    }

    private void executeRectangleCommand(int opcode) {
        if (isTexturedOpcode(opcode)) {
            drawTexturedRectangle(opcode);
        } else {
            drawFlatRectangle();
        }
    }

    private void fillRectangle() {
        int[] words = commandFifo;
        short color = (short) rgb24ToRgb555(words[0] & 0x00FF_FFFF);
        int x = (words[1] & 0x3F0);
        int y = decodeVramY(words[1] >>> 16);
        int width = ((words[2] & 0x3FF) + 0xF) & ~0xF;
        int height = (words[2] >>> 16) & 0x1FF;
        if (width == 0 || height == 0) {
            return;
        }
        for (int py = 0; py < height; py++) {
            int dstY = (y + py) & vramYAddressMask();
            if (!drawTargetsCurrentField(dstY)) {
                continue;
            }
            for (int px = 0; px < width; px++) {
                putPixel((x + px) & 0x3FF, dstY, color, false, false);
            }
        }
    }

    private void drawFlatTriangle() {
        int[] words = commandFifo;
        int color = words[0] & 0x00FF_FFFF;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        rasterizeTriangle(color, color, color,
            decodeX(words[1]), decodeY(words[1]),
            decodeX(words[2]), decodeY(words[2]),
            decodeX(words[3]), decodeY(words[3]),
            false);
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void drawFlatQuad() {
        int[] words = commandFifo;
        int color = words[0] & 0x00FF_FFFF;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        int x0 = decodeX(words[1]);
        int y0 = decodeY(words[1]);
        int x1 = decodeX(words[2]);
        int y1 = decodeY(words[2]);
        int x2 = decodeX(words[3]);
        int y2 = decodeY(words[3]);
        int x3 = decodeX(words[4]);
        int y3 = decodeY(words[4]);
        rasterizeTriangle(color, color, color, x0, y0, x1, y1, x2, y2, false);
        rasterizeTriangle(color, color, color, x1, y1, x2, y2, x3, y3, false);
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void drawShadedTriangle() {
        int[] words = commandFifo;
        int c0 = words[0] & 0x00FF_FFFF;
        int c1 = words[2] & 0x00FF_FFFF;
        int c2 = words[4] & 0x00FF_FFFF;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        rasterizeTriangle(c0, c1, c2,
            decodeX(words[1]), decodeY(words[1]),
            decodeX(words[3]), decodeY(words[3]),
            decodeX(words[5]), decodeY(words[5]),
            hasDithering());
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void drawShadedQuad() {
        int[] words = commandFifo;
        int c0 = words[0] & 0x00FF_FFFF;
        int c1 = words[2] & 0x00FF_FFFF;
        int c2 = words[4] & 0x00FF_FFFF;
        int c3 = words[6] & 0x00FF_FFFF;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        int x0 = decodeX(words[1]);
        int y0 = decodeY(words[1]);
        int x1 = decodeX(words[3]);
        int y1 = decodeY(words[3]);
        int x2 = decodeX(words[5]);
        int y2 = decodeY(words[5]);
        int x3 = decodeX(words[7]);
        int y3 = decodeY(words[7]);
        boolean dither = hasDithering();
        rasterizeTriangle(c0, c1, c2, x0, y0, x1, y1, x2, y2, dither);
        rasterizeTriangle(c1, c2, c3, x1, y1, x2, y2, x3, y3, dither);
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void drawTexturedTriangle(boolean gouraud) {
        int[] words = commandFifo;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = true;
        Vertex v0;
        Vertex v1;
        Vertex v2;
        if (gouraud) {
            int clutWord = words[2];
            int texpage = texpageFromUvWord(words[5]);
            updateDrawModeFromTexpageAttribute(texpage);
            v0 = texturedVertex0.set(words[1], words[2], words[0], clutWord, texpage);
            v1 = texturedVertex1.set(words[4], words[5], words[3], clutWord, texpage);
            v2 = texturedVertex2.set(words[7], words[8], words[6], clutWord, texpage);
        } else {
            int texpage = texpageFromUvWord(words[4]);
            updateDrawModeFromTexpageAttribute(texpage);
            v0 = texturedVertex0.set(words[1], words[2], words[0], words[2], texpage);
            v1 = texturedVertex1.set(words[3], words[4], words[0], words[2], texpage);
            v2 = texturedVertex2.set(words[5], words[6], words[0], words[2], texpage);
        }
        prepareClut(v0.paletteWord, v0.texpage);
        rasterizeTexturedTriangle(v0, v1, v2, gouraud, isRawTextureCommand(words[0]));
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void drawTexturedQuad(boolean gouraud) {
        int[] words = commandFifo;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = true;
        Vertex v0;
        Vertex v1;
        Vertex v2;
        Vertex v3;
        if (gouraud) {
            int clutWord = words[2];
            int texpage = texpageFromUvWord(words[5]);
            updateDrawModeFromTexpageAttribute(texpage);
            v0 = texturedVertex0.set(words[1], words[2], words[0], clutWord, texpage);
            v1 = texturedVertex1.set(words[4], words[5], words[3], clutWord, texpage);
            v2 = texturedVertex2.set(words[7], words[8], words[6], clutWord, texpage);
            v3 = texturedVertex3.set(words[10], words[11], words[9], clutWord, texpage);
        } else {
            int clutWord = words[2];
            int texpage = texpageFromUvWord(words[4]);
            updateDrawModeFromTexpageAttribute(texpage);
            v0 = texturedVertex0.set(words[1], words[2], words[0], clutWord, texpage);
            v1 = texturedVertex1.set(words[3], words[4], words[0], clutWord, texpage);
            v2 = texturedVertex2.set(words[5], words[6], words[0], clutWord, texpage);
            v3 = texturedVertex3.set(words[7], words[8], words[0], clutWord, texpage);
        }
        prepareClut(v0.paletteWord, v0.texpage);
        rasterizeTexturedTriangle(v0, v1, v2, gouraud, isRawTextureCommand(words[0]));
        rasterizeTexturedTriangle(v1, v2, v3, gouraud, isRawTextureCommand(words[0]));
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void drawFlatLine() {
        int[] words = commandFifo;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        int color = words[0] & 0x00FF_FFFF;
        drawLine(decodeX(words[1]), decodeY(words[1]), decodeX(words[2]), decodeY(words[2]), color, color);
    }

    private void drawShadedLine() {
        int[] words = commandFifo;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        int c0 = words[0] & 0x00FF_FFFF;
        int c1 = words[2] & 0x00FF_FFFF;
        drawLine(decodeX(words[1]), decodeY(words[1]), decodeX(words[3]), decodeY(words[3]), c0, c1);
    }

    private void drawPolyline(boolean gouraud) {
        int[] words = commandFifo;
        if (commandFifoSize < 3) {
            return;
        }
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        int prevColor = words[0] & 0x00FF_FFFF;
        int prevXY = words[1];
        int index = 2;
        while (index < commandFifoSize) {
            int maybeTerm = words[index];
            if (isPolylineTerminatorAt(index, gouraud, maybeTerm)) {
                break;
            }
            int nextColor = prevColor;
            int nextXY;
            if (gouraud) {
                nextColor = maybeTerm & 0x00FF_FFFF;
                if (index + 1 >= commandFifoSize) {
                    break;
                }
                nextXY = words[index + 1];
                index += 2;
            } else {
                nextXY = maybeTerm;
                index += 1;
            }
            drawLine(decodeX(prevXY), decodeY(prevXY), decodeX(nextXY), decodeY(nextXY), prevColor, nextColor);
            prevXY = nextXY;
            prevColor = nextColor;
        }
    }

    private void drawFlatRectangle() {
        int[] words = commandFifo;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = false;
        short color = withMaskBit((short) rgb24ToRgb555(words[0] & 0x00FF_FFFF));
        int x = truncateRectangleCoordinate(decodeX(words[1]) + drawOffsetX);
        int y = truncateRectangleCoordinate(decodeY(words[1]) + drawOffsetY);
        int opcode = (words[0] >>> 24) & 0xFF;
        int sizeCode = rectangleSizeCode(opcode);
        int width = switch (sizeCode) {
            case 1 -> 1;
            case 2 -> 8;
            case 3 -> 16;
            default -> words[2] & 0x3FF;
        };
        int height = switch (sizeCode) {
            case 1 -> 1;
            case 2 -> 8;
            case 3 -> 16;
            default -> (words[2] >>> 16) & 0x1FF;
        };
        if (width > MAX_PRIMITIVE_WIDTH || height > MAX_PRIMITIVE_HEIGHT) {
            return;
        }
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                drawPixel(x + px, y + py, color);
            }
        }
    }

    private void drawTexturedRectangle(int opcode) {
        int[] words = commandFifo;
        int x = truncateRectangleCoordinate(decodeX(words[1]) + drawOffsetX);
        int y = truncateRectangleCoordinate(decodeY(words[1]) + drawOffsetY);
        int uvWord = words[2];
        int baseU = uvWord & 0xFF;
        int baseV = (uvWord >>> 8) & 0xFF;
        int modulateColor = words[0] & 0x00FF_FFFF;
        currentSemiTransparent = isSemiTransparentCommand(words[0]);
        currentSemiTransparencyRequiresBit15 = true;
        boolean rawTexture = isRawTextureCommand(words[0]);
        int width;
        int height;
        int sizeCode = rectangleSizeCode(opcode);
        if (sizeCode == 0) {
            width = words[3] & 0x3FF;
            height = (words[3] >>> 16) & 0x1FF;
        } else {
            width = switch (sizeCode) {
                case 1 -> 1;
                case 2 -> 8;
                case 3 -> 16;
                default -> 8;
            };
            height = width;
        }
        if (width > MAX_PRIMITIVE_WIDTH || height > MAX_PRIMITIVE_HEIGHT) {
            currentSemiTransparent = false;
            currentSemiTransparencyRequiresBit15 = false;
            return;
        }
        int texpage = drawMode & 0x3FFF;
        prepareClut(uvWord, texpage);
        int stepX = ((drawMode >>> 12) & 0x1) != 0 ? -1 : 1;
        int stepY = ((drawMode >>> 13) & 0x1) != 0 ? -1 : 1;
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                short texel = sampleTexture(baseU + (px * stepX), baseV + (py * stepY), uvWord, texpage);
                if ((texel & 0xFFFF) == 0) {
                    continue;
                }
                short finalColor = rawTexture ? texel : modulate(texel, modulateColor, x + px, y + py, false);
                drawPixel(x + px, y + py, withMaskBit(finalColor));
            }
        }
        currentSemiTransparent = false;
        currentSemiTransparencyRequiresBit15 = false;
    }

    private void copyRectangle() {
        int[] words = commandFifo;
        int srcX = words[1] & 0x3FF;
        int srcY = decodeVramY(words[1] >>> 16);
        int dstX = words[2] & 0x3FF;
        int dstY = decodeVramY(words[2] >>> 16);
        int width = decodeCopyWidth(words[3]);
        int height = decodeCopyHeight(words[3]);

        boolean copyRightToLeft = srcX < dstX
            || (((srcX + width - 1) & 0x3FF) < ((dstX + width - 1) & 0x3FF));
        for (int y = 0; y < height; y++) {
            if (copyRightToLeft) {
                for (int x = width - 1; x >= 0; x--) {
                    rawPutPixel(
                        (dstX + x) & 0x3FF,
                        (dstY + y) & vramYAddressMask(),
                        getPixel((srcX + x) & 0x3FF, (srcY + y) & vramYAddressMask())
                    );
                }
            } else {
                for (int x = 0; x < width; x++) {
                    rawPutPixel(
                        (dstX + x) & 0x3FF,
                        (dstY + y) & vramYAddressMask(),
                        getPixel((srcX + x) & 0x3FF, (srcY + y) & vramYAddressMask())
                    );
                }
            }
        }
    }

    private void beginCpuToVramTransfer() {
        int[] words = commandFifo;
        transferOriginX = words[1] & 0x3FF;
        transferOriginY = decodeVramY(words[1] >>> 16);
        transferWidth = decodeCopyWidth(words[2]);
        transferHeight = decodeCopyHeight(words[2]);
        transferColumn = 0;
        transferRow = 0;
        transferX = transferOriginX;
        transferY = transferOriginY;
        transferPixelsRemaining = transferWidth * transferHeight;
        cpuToVramTransfer = true;
        vramToCpuTransfer = false;
        if (Log.isDebugEnabled()) {
            Log.debug("GPU image upload begin: x=" + transferOriginX + " y=" + transferOriginY
                + " w=" + transferWidth + " h=" + transferHeight
                + " words=" + ((transferPixelsRemaining + 1) / 2));
        }
    }

    private void beginVramToCpuTransfer() {
        int[] words = commandFifo;
        transferOriginX = words[1] & 0x3FF;
        transferOriginY = decodeVramY(words[1] >>> 16);
        transferWidth = decodeCopyWidth(words[2]);
        transferHeight = decodeCopyHeight(words[2]);
        transferColumn = 0;
        transferRow = 0;
        transferX = transferOriginX;
        transferY = transferOriginY;
        transferPixelsRemaining = transferWidth * transferHeight;
        cpuToVramTransfer = false;
        vramToCpuTransfer = true;
    }

    private void writeImageData(int word) {
        totalImageWords++;
        writeImagePixel(word & 0xFFFF);
        if (transferPixelsRemaining > 0) {
            writeImagePixel((word >>> 16) & 0xFFFF);
        }
        if (transferPixelsRemaining <= 0) {
            cpuToVramTransfer = false;
            // All upload words have been consumed by the image-transfer engine.
            gp0FifoWords = 0;
            gp0FifoDrainCarry = 0;
            if (Log.isDebugEnabled()) {
                Log.debug("GPU image upload completed at x=" + transferOriginX + " y=" + transferOriginY
                    + " w=" + transferWidth + " h=" + transferHeight
                    + " totalImageWords=" + totalImageWords);
            }
        }
    }

    private void writeImagePixel(int rgb555) {
        if (transferPixelsRemaining <= 0) {
            return;
        }
        rawPutPixel(transferX, transferY, (short) rgb555);
        advanceTransferPosition();
    }

    private int readImagePixel() {
        if (transferPixelsRemaining <= 0) {
            vramToCpuTransfer = false;
            return 0;
        }
        int pixel = getPixel(transferX, transferY) & 0xFFFF;
        advanceTransferPosition();
        if (transferPixelsRemaining <= 0) {
            vramToCpuTransfer = false;
        }
        return pixel;
    }

    private void advanceTransferPosition() {
        transferPixelsRemaining--;
        if (transferPixelsRemaining <= 0) {
            return;
        }
        transferColumn++;
        if (transferColumn >= transferWidth) {
            transferColumn = 0;
            transferRow++;
        }
        transferX = (transferOriginX + transferColumn) & 0x3FF;
        transferY = (transferOriginY + transferRow) & vramYAddressMask();
    }

    private void drawLine(int x0, int y0, int x1, int y1, int c0, int c1) {
        x0 += drawOffsetX;
        y0 += drawOffsetY;
        x1 += drawOffsetX;
        y1 += drawOffsetY;
        if (Math.abs(x1 - x0) > MAX_PRIMITIVE_WIDTH || Math.abs(y1 - y0) > MAX_PRIMITIVE_HEIGHT) {
            return;
        }
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        boolean dither = hasDithering();
        if (steps == 0) {
            short color = withMaskBit(rgb24ToVramColor(c0, x0, y0, dither, 0));
            drawPixel(x0, y0, color);
            return;
        }
        int sx = Integer.compare(x1, x0);
        int sy = Integer.compare(y1, y0);
        int err = dx - dy;
        int x = x0;
        int y = y0;
        for (int i = 0; ; i++) {
            int color24 = interpolateLineColor(c0, c1, i, steps);
            short color = withMaskBit(rgb24ToVramColor(color24, x, y, dither, 0));
            drawPixel(x, y, color);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = err << 1;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void rasterizeTriangle(int c0, int c1, int c2, int x0, int y0, int x1, int y1, int x2, int y2, boolean dither) {
        x0 += drawOffsetX;
        y0 += drawOffsetY;
        x1 += drawOffsetX;
        y1 += drawOffsetY;
        x2 += drawOffsetX;
        y2 += drawOffsetY;
        if (primitiveTooLarge(x0, y0, x1, y1, x2, y2)) {
            return;
        }
        int area = edge(x0, y0, x1, y1, x2, y2);
        if (area == 0) {
            return;
        }
        if (area < 0) {
            int tmpX = x1;
            int tmpY = y1;
            int tmpC = c1;
            x1 = x2;
            y1 = y2;
            c1 = c2;
            x2 = tmpX;
            y2 = tmpY;
            c2 = tmpC;
            area = -area;
        }
        int minX = Math.max(0, Math.max(drawAreaLeft, Math.min(x0, Math.min(x1, x2))));
        int minY = Math.max(0, Math.max(drawAreaTop, Math.min(y0, Math.min(y1, y2))));
        int maxX = Math.min(VRAM_WIDTH - 1,
            Math.min(drawAreaRight, Math.max(x0, Math.max(x1, x2))));
        int maxY = Math.min(VRAM_HEIGHT - 1,
            Math.min(drawAreaBottom, Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) {
            return;
        }
        int bias0 = isTopLeftEdge(x1, y1, x2, y2) ? 0 : -1;
        int bias1 = isTopLeftEdge(x2, y2, x0, y0) ? 0 : -1;
        int bias2 = isTopLeftEdge(x0, y0, x1, y1) ? 0 : -1;
        int w0StepX = y2 - y1;
        int w1StepX = y0 - y2;
        int w2StepX = y1 - y0;
        int w0StepY = x1 - x2;
        int w1StepY = x2 - x0;
        int w2StepY = x0 - x1;
        int rowW0 = edge(x1, y1, x2, y2, minX, minY);
        int rowW1 = edge(x2, y2, x0, y0, minX, minY);
        int rowW2 = edge(x0, y0, x1, y1, minX, minY);
        long areaReciprocal = unsignedDivisionReciprocal(area);
        boolean flatColor = c0 == c1 && c1 == c2;
        for (int y = minY; y <= maxY; y++) {
            if (drawTargetsCurrentField(y)) {
                long span = triangleRowSpan(
                    maxX - minX + 1,
                    rowW0 + bias0, w0StepX,
                    rowW1 + bias1, w1StepX,
                    rowW2 + bias2, w2StepX
                );
                if (span >= 0) {
                    int first = (int) (span >>> 32);
                    int last = (int) span;
                    int w0 = rowW0 + w0StepX * first;
                    int w1 = rowW1 + w1StepX * first;
                    int w2 = rowW2 + w2StepX * first;
                    for (int offset = first; offset <= last; offset++) {
                        int x = minX + offset;
                        int color24 = flatColor
                            ? c0
                            : blend(c0, c1, c2, w0, w1, w2, area, areaReciprocal);
                        short color = withMaskBit(rgb24ToVramColor(color24, x, y, dither, 0));
                        putRasterPixel(x, y, color);
                        w0 += w0StepX;
                        w1 += w1StepX;
                        w2 += w2StepX;
                    }
                }
            }
            rowW0 += w0StepY;
            rowW1 += w1StepY;
            rowW2 += w2StepY;
        }
    }

    private void rasterizeTexturedTriangle(Vertex v0, Vertex v1, Vertex v2, boolean gouraud, boolean rawTexture) {
        int x0 = v0.x + drawOffsetX;
        int y0 = v0.y + drawOffsetY;
        int x1 = v1.x + drawOffsetX;
        int y1 = v1.y + drawOffsetY;
        int x2 = v2.x + drawOffsetX;
        int y2 = v2.y + drawOffsetY;
        if (primitiveTooLarge(x0, y0, x1, y1, x2, y2)) {
            return;
        }
        int area = edge(x0, y0, x1, y1, x2, y2);
        if (area == 0) {
            return;
        }
        if (area < 0) {
            Vertex tmp = v1;
            v1 = v2;
            v2 = tmp;
            x1 = v1.x + drawOffsetX;
            y1 = v1.y + drawOffsetY;
            x2 = v2.x + drawOffsetX;
            y2 = v2.y + drawOffsetY;
            area = -area;
        }
        int minX = Math.max(0, Math.max(drawAreaLeft, Math.min(x0, Math.min(x1, x2))));
        int minY = Math.max(0, Math.max(drawAreaTop, Math.min(y0, Math.min(y1, y2))));
        int maxX = Math.min(VRAM_WIDTH - 1,
            Math.min(drawAreaRight, Math.max(x0, Math.max(x1, x2))));
        int maxY = Math.min(VRAM_HEIGHT - 1,
            Math.min(drawAreaBottom, Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) {
            return;
        }
        int bias0 = isTopLeftEdge(x1, y1, x2, y2) ? 0 : -1;
        int bias1 = isTopLeftEdge(x2, y2, x0, y0) ? 0 : -1;
        int bias2 = isTopLeftEdge(x0, y0, x1, y1) ? 0 : -1;
        boolean dither = hasDithering() && !rawTexture;
        int w0StepX = y2 - y1;
        int w1StepX = y0 - y2;
        int w2StepX = y1 - y0;
        int w0StepY = x1 - x2;
        int w1StepY = x2 - x0;
        int w2StepY = x0 - x1;
        int rowW0 = edge(x1, y1, x2, y2, minX, minY);
        int rowW1 = edge(x2, y2, x0, y0, minX, minY);
        int rowW2 = edge(x0, y0, x1, y1, minX, minY);
        int uStepX = v0.u * w0StepX + v1.u * w1StepX + v2.u * w2StepX;
        int vStepX = v0.v * w0StepX + v1.v * w1StepX + v2.v * w2StepX;
        int uStepY = v0.u * w0StepY + v1.u * w1StepY + v2.u * w2StepY;
        int vStepY = v0.v * w0StepY + v1.v * w1StepY + v2.v * w2StepY;
        int rowUNumerator = v0.u * rowW0 + v1.u * rowW1 + v2.u * rowW2;
        int rowVNumerator = v0.v * rowW0 + v1.v * rowW1 + v2.v * rowW2;
        long areaReciprocal = unsignedDivisionReciprocal(area);
        int texturePageX = (v0.texpage & 0xF) * 64;
        int texturePageY = effectiveTexturePageY(v0.texpage);
        int textureDepth = (v0.texpage >>> 7) & 0x3;
        int textureWindowXMask = (textureWindow & 0x1F) * 8;
        int textureWindowYMask = ((textureWindow >>> 5) & 0x1F) * 8;
        int textureWindowXOffset = ((textureWindow >>> 10) & 0x1F) * 8;
        int textureWindowYOffset = ((textureWindow >>> 15) & 0x1F) * 8;
        boolean flatShade = gouraud
            && v0.color24 == v1.color24
            && v1.color24 == v2.color24;
        boolean interpolatedShade = gouraud && !flatShade;
        int shadeRStepX = 0;
        int shadeGStepX = 0;
        int shadeBStepX = 0;
        if (interpolatedShade) {
            shadeRStepX = (v0.color24 & 0xFF) * w0StepX
                + (v1.color24 & 0xFF) * w1StepX
                + (v2.color24 & 0xFF) * w2StepX;
            shadeGStepX = ((v0.color24 >>> 8) & 0xFF) * w0StepX
                + ((v1.color24 >>> 8) & 0xFF) * w1StepX
                + ((v2.color24 >>> 8) & 0xFF) * w2StepX;
            shadeBStepX = ((v0.color24 >>> 16) & 0xFF) * w0StepX
                + ((v1.color24 >>> 16) & 0xFF) * w1StepX
                + ((v2.color24 >>> 16) & 0xFF) * w2StepX;
        }
        for (int y = minY; y <= maxY; y++) {
            if (drawTargetsCurrentField(y)) {
                long span = triangleRowSpan(
                    maxX - minX + 1,
                    rowW0 + bias0, w0StepX,
                    rowW1 + bias1, w1StepX,
                    rowW2 + bias2, w2StepX
                );
                if (span >= 0) {
                    int first = (int) (span >>> 32);
                    int last = (int) span;
                    int w0 = rowW0 + w0StepX * first;
                    int w1 = rowW1 + w1StepX * first;
                    int w2 = rowW2 + w2StepX * first;
                    int uNumerator = rowUNumerator + uStepX * first;
                    int vNumerator = rowVNumerator + vStepX * first;
                    int shadeRNumerator = interpolatedShade
                        ? (v0.color24 & 0xFF) * w0
                            + (v1.color24 & 0xFF) * w1
                            + (v2.color24 & 0xFF) * w2
                        : 0;
                    int shadeGNumerator = interpolatedShade
                        ? ((v0.color24 >>> 8) & 0xFF) * w0
                            + ((v1.color24 >>> 8) & 0xFF) * w1
                            + ((v2.color24 >>> 8) & 0xFF) * w2
                        : 0;
                    int shadeBNumerator = interpolatedShade
                        ? ((v0.color24 >>> 16) & 0xFF) * w0
                            + ((v1.color24 >>> 16) & 0xFF) * w1
                            + ((v2.color24 >>> 16) & 0xFF) * w2
                        : 0;
                    for (int offset = first; offset <= last; offset++) {
                        int x = minX + offset;
                        int targetIndex = y * VRAM_WIDTH + x;
                        short target = vram[targetIndex];
                        if (checkMaskBit && (target & 0x8000) != 0) {
                            w0 += w0StepX;
                            w1 += w1StepX;
                            w2 += w2StepX;
                            uNumerator += uStepX;
                            vNumerator += vStepX;
                            shadeRNumerator += shadeRStepX;
                            shadeGNumerator += shadeGStepX;
                            shadeBNumerator += shadeBStepX;
                            continue;
                        }
                        commandTimingPixels++;
                        int u = dividePositive(uNumerator, area, areaReciprocal);
                        int v = dividePositive(vNumerator, area, areaReciprocal);
                        short texel = samplePreparedTexture(
                            u, v, texturePageX, texturePageY, textureDepth,
                            textureWindowXMask, textureWindowYMask,
                            textureWindowXOffset, textureWindowYOffset
                        );
                        if ((texel & 0xFFFF) != 0) {
                            short finalColor = texel;
                            if (!rawTexture) {
                                int shadeColor = interpolatedShade
                                    ? blendNumerators(shadeRNumerator, shadeGNumerator,
                                        shadeBNumerator, area, areaReciprocal)
                                    : v0.color24;
                                finalColor = modulate(texel, shadeColor, x, y, dither);
                            }
                            writeRasterPixel(targetIndex, x, y, withMaskBit(finalColor), target);
                        }
                        w0 += w0StepX;
                        w1 += w1StepX;
                        w2 += w2StepX;
                        uNumerator += uStepX;
                        vNumerator += vStepX;
                        shadeRNumerator += shadeRStepX;
                        shadeGNumerator += shadeGStepX;
                        shadeBNumerator += shadeBStepX;
                    }
                }
            }
            rowW0 += w0StepY;
            rowW1 += w1StepY;
            rowW2 += w2StepY;
            rowUNumerator += uStepY;
            rowVNumerator += vStepY;
        }
    }

    private short sampleTexture(int u, int v, int paletteWord, int texpage) {
        int maskedU = applyTextureWindowX(u & 0xFF);
        int maskedV = applyTextureWindowY(v & 0xFF);
        int texturePageX = (texpage & 0xF) * 64;
        int texturePageY = effectiveTexturePageY(texpage);
        int textureDepth = (texpage >>> 7) & 0x3;
        return switch (textureDepth) {
            case 0 -> read4bppTexel(texturePageX, texturePageY + maskedV, maskedU, paletteWord);
            case 1 -> read8bppTexel(texturePageX, texturePageY + maskedV, maskedU, paletteWord);
            default -> readTextureWord(texturePageX + maskedU, texturePageY + maskedV, textureDepth);
        };
    }

    private short samplePreparedTexture(int u, int v,
                                        int texturePageX, int texturePageY,
                                        int textureDepth,
                                        int windowXMask, int windowYMask,
                                        int windowXOffset, int windowYOffset) {
        int sourceU = u & 0xFF;
        int sourceV = v & 0xFF;
        int maskedU = (sourceU & ~windowXMask) | (windowXOffset & windowXMask);
        int maskedV = (sourceV & ~windowYMask) | (windowYOffset & windowYMask);
        int textureY = texturePageY + maskedV;
        if (textureDepth == 0) {
            short packed = readTextureWord(texturePageX + (maskedU >>> 2), textureY, 0);
            return clutCache[(packed >>> ((maskedU & 0x3) * 4)) & 0xF];
        }
        if (textureDepth == 1) {
            short packed = readTextureWord(texturePageX + (maskedU >>> 1), textureY, 1);
            return clutCache[(maskedU & 1) == 0
                ? packed & 0xFF
                : (packed >>> 8) & 0xFF];
        }
        return readTextureWord(texturePageX + maskedU, textureY, textureDepth);
    }

    private short read4bppTexel(int x, int y, int u, int paletteWord) {
        short packed = readTextureWord(x + (u >>> 2), y, 0);
        int shift = (u & 0x3) * 4;
        int index = (packed >>> shift) & 0xF;
        return readClut(paletteWord, index);
    }

    private short read8bppTexel(int x, int y, int u, int paletteWord) {
        short packed = readTextureWord(x + (u >>> 1), y, 1);
        int index = ((u & 1) == 0) ? (packed & 0xFF) : ((packed >>> 8) & 0xFF);
        return readClut(paletteWord, index);
    }

    private short readTextureWord(int x, int y, int textureDepth) {
        int wrappedX = x & (VRAM_WIDTH - 1);
        int wrappedY = y & vramYAddressMask();
        if (wrappedY >= VRAM_HEIGHT) {
            return 0;
        }
        int address = wrappedY * VRAM_WIDTH + wrappedX;
        int lineAddress = address & -TEXTURE_CACHE_WORDS_PER_ENTRY;
        // Each entry contains four VRAM words.
        int entry = textureDepth < 2
            ? ((address >>> 2) & 0x3) | ((address >>> 8) & 0xFC)
            : ((address >>> 2) & 0x7) | ((address >>> 7) & 0xF8);
        if (textureCacheTags[entry] != lineAddress) {
            int cacheBase = entry * TEXTURE_CACHE_WORDS_PER_ENTRY;
            System.arraycopy(vram, lineAddress, textureCache, cacheBase, TEXTURE_CACHE_WORDS_PER_ENTRY);
            textureCacheTags[entry] = lineAddress;
        }
        return textureCache[entry * TEXTURE_CACHE_WORDS_PER_ENTRY
            + (address & (TEXTURE_CACHE_WORDS_PER_ENTRY - 1))];
    }

    private short readClut(int paletteWord, int index) {
        return clutCache[index & 0xFF];
    }

    private void prepareClut(int paletteWord, int texpage) {
        int textureDepth = (texpage >>> 7) & 0x3;
        if (textureDepth >= 2) {
            return;
        }
        int clutBits = (paletteWord >>> 16) & (allowSecondVramBank ? 0xFFFF : 0x7FFF);
        boolean needs8Bit = textureDepth == 1;
        // An 8-bit load also fills the 4-bit cache.
        if (cachedClutBits == clutBits && (!needs8Bit || cachedClutIs8Bit)) {
            return;
        }
        int clutX = (clutBits & 0x3F) * 16;
        int clutY = (clutBits >>> 6) & vramYAddressMask();
        int entries = needs8Bit ? 256 : 16;
        for (int i = 0; i < entries; i++) {
            clutCache[i] = getAddressedVramPixel(clutX + i, clutY);
        }
        cachedClutBits = clutBits;
        cachedClutIs8Bit = needs8Bit;
    }

    private void invalidateClutCache() {
        cachedClutBits = -1;
        cachedClutIs8Bit = false;
    }

    private void invalidateTextureCache() {
        Arrays.fill(textureCacheTags, -1);
    }

    private void invalidateTextureAndClutCaches() {
        invalidateTextureCache();
        invalidateClutCache();
    }

    private int applyTextureWindowX(int u) {
        int maskBits = textureWindow & 0x1F;
        int offsetBits = (textureWindow >>> 10) & 0x1F;
        int mask = maskBits * 8;
        int offset = offsetBits * 8;
        return (u & ~mask) | (offset & mask);
    }

    private int applyTextureWindowY(int v) {
        int maskBits = (textureWindow >>> 5) & 0x1F;
        int offsetBits = (textureWindow >>> 15) & 0x1F;
        int mask = maskBits * 8;
        int offset = offsetBits * 8;
        return (v & ~mask) | (offset & mask);
    }

    private short modulate(short texel, int shadeColor, int x, int y, boolean dither) {
        int tr = (texel & 0x1F) << 3;
        int tg = ((texel >>> 5) & 0x1F) << 3;
        int tb = ((texel >>> 10) & 0x1F) << 3;
        int sr = shadeColor & 0xFF;
        int sg = (shadeColor >>> 8) & 0xFF;
        int sb = (shadeColor >>> 16) & 0xFF;
        int r = Math.min(255, (tr * sr) / 128);
        int g = Math.min(255, (tg * sg) / 128);
        int b = Math.min(255, (tb * sb) / 128);
        return rgb24ToVramColor(r | (g << 8) | (b << 16), x, y, dither, texel & 0x8000);
    }

    private int blend(int c0, int c1, int c2,
                      int w0, int w1, int w2,
                      int area, long areaReciprocal) {
        if (c0 == c1 && c1 == c2) {
            return c0;
        }
        return blendNumerators(
            ((c0 & 0xFF) * w0) + ((c1 & 0xFF) * w1) + ((c2 & 0xFF) * w2),
            (((c0 >>> 8) & 0xFF) * w0)
                + (((c1 >>> 8) & 0xFF) * w1)
                + (((c2 >>> 8) & 0xFF) * w2),
            (((c0 >>> 16) & 0xFF) * w0)
                + (((c1 >>> 16) & 0xFF) * w1)
                + (((c2 >>> 16) & 0xFF) * w2),
            area, areaReciprocal
        );
    }

    private int blendNumerators(int rNumerator, int gNumerator, int bNumerator,
                                int area, long areaReciprocal) {
        int r = dividePositive(rNumerator, area, areaReciprocal);
        int g = dividePositive(gNumerator, area, areaReciprocal);
        int b = dividePositive(bNumerator, area, areaReciprocal);
        return clamp8(r) | (clamp8(g) << 8) | (clamp8(b) << 16);
    }

    static long unsignedDivisionReciprocal(int divisor) {
        return ((1L << 32) + divisor - 1L) / divisor;
    }

    static int dividePositive(int numerator, int divisor, long reciprocal) {
        int quotient = (int) (((long) numerator * reciprocal) >>> 32);
        return (long) quotient * divisor > numerator ? quotient - 1 : quotient;
    }

    private void drawPixel(int x, int y, short color) {
        if (x < 0 || y < 0 || x >= VRAM_WIDTH || y >= VRAM_HEIGHT) {
            return;
        }
        if (x < drawAreaLeft || x > drawAreaRight || y < drawAreaTop || y > drawAreaBottom) {
            return;
        }
        if (!drawTargetsCurrentField(y)) {
            return;
        }
        putPixel(x, y, color, true, true);
    }

    private void rawPutPixel(int x, int y, short color) {
        // Both CPU-to-VRAM and VRAM-to-VRAM transfers obey GP0(E6h).
        putPixel(x, y, withMaskBit(color), true, false);
    }

    private short withMaskBit(short color) {
        return forceMaskBit ? (short) (color | 0x8000) : color;
    }

    private void markDirty(int x, int y) {
        dirtyMinX = Math.min(dirtyMinX, x);
        dirtyMinY = Math.min(dirtyMinY, y);
        dirtyMaxX = Math.max(dirtyMaxX, x);
        dirtyMaxY = Math.max(dirtyMaxY, y);
    }

    private short applySemiTransparency(short dst, short src) {
        int mode = (drawMode >>> 5) & 0x3;
        int sr = src & 0x1F;
        int sg = (src >>> 5) & 0x1F;
        int sb = (src >>> 10) & 0x1F;
        int dr = dst & 0x1F;
        int dg = (dst >>> 5) & 0x1F;
        int db = (dst >>> 10) & 0x1F;
        int r;
        int g;
        int b;
        switch (mode) {
            case 0 -> { r = (sr >> 1) + (dr >> 1); g = (sg >> 1) + (dg >> 1); b = (sb >> 1) + (db >> 1); }
            case 1 -> { r = Math.min(31, sr + dr); g = Math.min(31, sg + dg); b = Math.min(31, sb + db); }
            case 2 -> { r = Math.max(0, dr - sr); g = Math.max(0, dg - sg); b = Math.max(0, db - sb); }
            default -> { r = Math.min(31, dr + (sr / 4)); g = Math.min(31, dg + (sg / 4)); b = Math.min(31, db + (sb / 4)); }
        }
        return (short) (r | (g << 5) | (b << 10) | (src & 0x8000));
    }

    private short getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= VRAM_WIDTH || y >= VRAM_HEIGHT) {
            return 0;
        }
        return vram[y * VRAM_WIDTH + x];
    }

    private short getAddressedVramPixel(int x, int y) {
        int addressedY = y & vramYAddressMask();
        if (addressedY >= VRAM_HEIGHT) {
            return 0;
        }
        return vram[addressedY * VRAM_WIDTH + (x & (VRAM_WIDTH - 1))];
    }

    private int vramYAddressMask() {
        return allowSecondVramBank ? 0x3FF : 0x1FF;
    }

    private int decodeVramY(int value) {
        return value & vramYAddressMask();
    }

    private void updateDisplayDimensions(int value) {
        displayMode = value & 0xFF;
        normalizeCrtcTimingState();
        applyDisplayModeToStatus();
        recomputeDisplayDimensions();
    }

    private void applyDisplayModeToStatus() {
        status &= ~((1 << 14) | (1 << 16) | (0x3F << 17));
        status |= ((displayMode >>> 7) & 0x1) << 14;
        status |= ((displayMode >>> 6) & 0x1) << 16;
        status |= (displayMode & 0x3F) << 17;
    }

    private void applyDrawStateToStatus() {
        status &= ~0x00009FFF;
        status |= drawMode & 0x07FF;
        status = setBit(status, 11, forceMaskBit);
        status = setBit(status, 12, checkMaskBit);
        status |= ((drawMode >>> 11) & 0x1) << 15;
    }

    private void recomputeDisplayDimensions() {
        int hResBits = (displayMode & 0b11) | (((displayMode >>> 6) & 0x1) << 2);
        int nominalWidth = switch (hResBits & 0x7) {
            case 0 -> 256;
            case 1 -> 320;
            case 2 -> 512;
            case 3 -> 640;
            case 4, 5, 6, 7 -> 368;
            default -> throw new AssertionError();
        };
        int cyclesPerPixel = switch (nominalWidth) {
            case 256 -> 10;
            case 320 -> 8;
            case 368, 384 -> 7;
            case 512 -> 5;
            case 640 -> 4;
            default -> 8;
        };
        timerDotClockDivider = cyclesPerPixel;
        int rangeWidth = Math.max(0, displayRangeX2 - displayRangeX1);
        int widthFromRange = (((rangeWidth / cyclesPerPixel) + 2) & ~0x3);
        frameBufferWidth = Math.clamp(widthFromRange, 0, 640);
        boolean interlaced480 = isInterlaced480Mode();
        int nominalHeight = interlaced480 ? 480 : 240;
        int derivedHeight = Math.max(1, displayRangeY2 - displayRangeY1);
        int heightFromRange = interlaced480 ? derivedHeight * 2 : derivedHeight;
        frameBufferHeight = Math.clamp(Math.min(nominalHeight, heightFromRange), 1, 480);
    }

    public void tick(int cycles) {
        if (cycles <= 0) {
            return;
        }
        advanceRenderPipeline(cycles);
        hblankRisesLastTick = 0;
        hblankFallsLastTick = 0;
        vblankRisesLastTick = 0;
        vblankFallsLastTick = 0;
        dotClockTicksLastTick = 0;
        vblankCycleAccumulator += cycles;

        long scaledTicks = crtcFractionalTicks + (long) cycles * gpuClockRatioNumerator;
        int crtcTicks;
        if (cycles == 1) {
            crtcTicks = 0;
            while (scaledTicks >= gpuClockRatioDenominator) {
                scaledTicks -= gpuClockRatioDenominator;
                crtcTicks++;
            }
            crtcFractionalTicks = scaledTicks;
        } else {
            crtcTicks = (int) (scaledTicks / gpuClockRatioDenominator);
            crtcFractionalTicks = scaledTicks % gpuClockRatioDenominator;
        }
        crtcTicksLastTick = crtcTicks;
        advanceCrtcTicks(crtcTicks);
    }

    private void advanceCrtcTicks(int ticks) {
        int remaining = Math.max(0, ticks);
        while (remaining > 0) {
            int horizontalBoundary = nextHorizontalBoundaryTick;
            int horizontalDistance = horizontalBoundary - scanlineCycleAccumulator;
            long verticalBoundary = nextVerticalBoundaryHalfTick;
            long verticalDistanceHalfTicks = verticalBoundary - fieldCrtcHalfTicks;
            if (remaining < horizontalDistance
                && (long) remaining * 2L < verticalDistanceHalfTicks) {
                // Almost every CPU-clock slice lies wholly inside the current CRTC region.
                advanceTimerDotClock(remaining);
                scanlineCycleAccumulator += remaining;
                fieldCrtcHalfTicks += (long) remaining * 2L;
                return;
            }
            int verticalDistance = (int) Math.max(1L, (verticalDistanceHalfTicks + 1L) / 2L);
            int elapsed = Math.min(remaining, Math.min(horizontalDistance, verticalDistance));
            advanceTimerDotClock(elapsed);
            scanlineCycleAccumulator += elapsed;
            fieldCrtcHalfTicks += (long) elapsed * 2L;
            remaining -= elapsed;

            processHorizontalBoundary(horizontalBoundary);
            processVerticalBoundary(verticalBoundary);
        }
    }

    private int computeNextHorizontalBoundary() {
        if (timerHblankForcedHigh()) {
            return crtcTicksPerLine();
        }
        int next = crtcTicksPerLine();
        if (displayRangeX1 > scanlineCycleAccumulator && displayRangeX1 < next) {
            next = displayRangeX1;
        }
        if (displayRangeX2 > scanlineCycleAccumulator && displayRangeX2 < next) {
            next = displayRangeX2;
        }
        return next;
    }

    private void processHorizontalBoundary(int boundary) {
        if (scanlineCycleAccumulator != boundary) {
            return;
        }
        if (timerHblankForcedHigh()) {
            finishTimerDotClockScanline();
            scanlineCycleAccumulator = 0;
            nextHorizontalBoundaryTick = computeNextHorizontalBoundary();
            return;
        }
        if (boundary == displayRangeX1) {
            setHblank(false);
        }
        if (boundary == displayRangeX2) {
            setHblank(true);
        }
        if (boundary == crtcTicksPerLine()) {
            finishTimerDotClockScanline();
            scanlineCycleAccumulator = 0;
            // The displayed range is relative to HSYNC.
            setHblank(hblankLevelAt(0));
        }
        nextHorizontalBoundaryTick = computeNextHorizontalBoundary();
    }

    private long computeNextVerticalBoundaryHalfTicks() {
        long ticksPerLineHalf = (long) crtcTicksPerLine() * 2L;
        long nextScanline = ((long) scanline + 1L) * ticksPerLineHalf;
        return Math.min(nextScanline, fieldDurationHalfTicks());
    }

    private void processVerticalBoundary(long boundary) {
        if (fieldCrtcHalfTicks < boundary) {
            return;
        }
        long fieldDuration = fieldDurationHalfTicks();
        if (boundary == fieldDuration) {
            fieldCrtcHalfTicks -= fieldDuration;
            scanline = 0;
            vblankCycleAccumulator = 0;
            if (isVerticalInterlaceEnabled()) {
                interlacedFieldOdd = !interlacedFieldOdd;
            } else {
                interlacedFieldOdd = false;
                interlacedDisplayFieldOdd = false;
            }
        } else {
            scanline++;
        }
        updateVblankComparator();
        nextVerticalBoundaryHalfTick = computeNextVerticalBoundaryHalfTicks();
    }

    private void updateVblankComparator() {
        if (scanline == displayRangeY2) {
            setVblank(true);
        }
        if (scanline == displayRangeY1) {
            setVblank(false);
        }
    }

    private void setHblank(boolean value) {
        if (inHblank == value) {
            return;
        }
        inHblank = value;
        if (value) {
            hblankRisesLastTick++;
        } else {
            hblankFallsLastTick++;
        }
    }

    private boolean timerHblankForcedHigh() {
        return displayRangeX2 <= displayRangeX1;
    }

    private boolean hblankLevelAt(int crtcTick) {
        return timerHblankForcedHigh()
            || crtcTick < displayRangeX1
            || crtcTick >= displayRangeX2;
    }

    private void synchronizeTimerHblankToDisplayRange() {
        if (timerHblankForcedHigh()) {
            setHblank(true);
        } else {
            setHblank(hblankLevelAt(scanlineCycleAccumulator));
        }
        nextHorizontalBoundaryTick = computeNextHorizontalBoundary();
    }

    private void setVblank(boolean value) {
        if (inVblank == value) {
            return;
        }
        inVblank = value;
        if (value) {
            vblankRisesLastTick++;
            interlacedDisplayFieldOdd = isInterlaced480Mode()
                && !interlacedFieldOdd;
            latchDisplayedState();
            snapshotCompletedDisplayState();
            snapshotCompletedFrame();
            frameCounter++;
            interruptController.raise(0);
        } else {
            vblankFallsLastTick++;
            interruptController.clear(0);
        }
    }

    private boolean isVblankScanline(int line, int totalLines) {
        int displayStart = Math.min(displayRangeY1, totalLines);
        int displayEnd = Math.min(displayRangeY2, totalLines);
        return line < displayStart || line >= displayEnd;
    }

    private int crtcTicksPerLine() {
        return isPalMode() ? PAL_CRTC_TICKS_PER_LINE : NTSC_CRTC_TICKS_PER_LINE;
    }

    private void advanceTimerDotClock(int crtcTicks) {
        if (crtcTicks <= 0) {
            return;
        }
        int divider = timerDotClockDivider;
        int accumulated = dotClockDividerPhase + crtcTicks;
        int edges;
        if (accumulated < divider) {
            dotClockDividerPhase = accumulated;
            return;
        } else if (accumulated < divider * 2) {
            edges = 1;
            dotClockDividerPhase = accumulated - divider;
        } else {
            edges = accumulated / divider;
            dotClockDividerPhase = accumulated % divider;
        }
        dotClockTicksThisLine += edges;
        dotClockTicksLastTick += edges;
    }

    private void finishTimerDotClockScanline() {
        if (isPalMode() && timerDotClockDivider == 8 && dotClockDividerPhase != 0) {
            dotClockTicksThisLine++;
            dotClockTicksLastTick++;
        }
        completedScanlineDotClockTicks = dotClockTicksThisLine;
        dotClockTicksThisLine = 0;
        dotClockDividerPhase = 0;
    }

    private static int dotClockDivider(int mode) {
        int horizontalMode = (mode & 0x3) | (((mode >>> 6) & 0x1) << 2);
        return switch (horizontalMode) {
            case 0 -> 10;
            case 1 -> 8;
            case 2 -> 5;
            case 3 -> 4;
            case 4, 5, 6, 7 -> 7;
            default -> throw new AssertionError();
        };
    }

    private long fieldDurationHalfTicks() {
        int halfLines;
        if (isVerticalInterlaceEnabled()) {
            halfLines = isPalMode() ? 625 : 525;
        } else {
            halfLines = (isPalMode() ? PAL_TOTAL_SCANLINES : NTSC_TOTAL_SCANLINES) * 2;
        }
        return (long) crtcTicksPerLine() * halfLines;
    }

    private int verticalRangeLimit() {
        if (!isVerticalInterlaceEnabled()) {
            return isPalMode() ? PAL_TOTAL_SCANLINES : NTSC_TOTAL_SCANLINES;
        }
        // The last numbered line is only half a line long in interlaced mode.
        return isPalMode() ? 313 : 263;
    }

    private void normalizeCrtcTimingState() {
        int ticksPerLine = crtcTicksPerLine();
        scanlineCycleAccumulator = Math.floorMod(scanlineCycleAccumulator, ticksPerLine);
        long duration = fieldDurationHalfTicks();
        fieldCrtcHalfTicks = Math.floorMod(fieldCrtcHalfTicks, duration);
        scanline = (int) Math.min(
            verticalRangeLimit() - 1L,
            fieldCrtcHalfTicks / ((long) ticksPerLine * 2L)
        );
        if (!isVerticalInterlaceEnabled()) {
            interlacedFieldOdd = false;
            interlacedDisplayFieldOdd = false;
        }
        nextHorizontalBoundaryTick = computeNextHorizontalBoundary();
        nextVerticalBoundaryHalfTick = computeNextVerticalBoundaryHalfTicks();
    }

    private void advanceRenderPipeline(int cycles) {
        int remaining = Math.max(0, cycles);
        while (remaining > 0) {
            if (renderBusyCycles == 0) {
                processGp0Ingress();
                if (renderBusyCycles == 0) {
                    return;
                }
            }
            int elapsed = Math.min(remaining, renderBusyCycles);
            remaining -= elapsed;
            renderBusyCycles -= elapsed;
            renderElapsedCycles = Math.min(renderTotalCycles, renderElapsedCycles + elapsed);
            commitRenderJournalThrough(renderElapsedCycles);
            if (renderBusyCycles > 0) {
                return;
            }
            completeActiveRenderCommand();
            // Packet decode/dispatch itself does not consume another CPU cycle.
            processGp0Ingress();
        }
    }

    private void commitRenderJournalThrough(int elapsedCycles) {
        if (!activeRenderJournalPlanned || renderJournalCount == 0) {
            return;
        }
        if (elapsedCycles < renderJournalNextCommitCycle) {
            return;
        }
        int setupEnd = Math.clamp(renderTotalCycles - 1, 0, renderSetupCycles);
        int workCycles = Math.max(1, renderTotalCycles - setupEnd);
        int workElapsed = Math.clamp(elapsedCycles - setupEnd, 0, workCycles);
        int target = (int) Math.min(
            renderJournalCount,
            ((long) workElapsed * renderJournalCount) / workCycles
        );
        int minX = dirtyMinX;
        int minY = dirtyMinY;
        int maxX = dirtyMaxX;
        int maxY = dirtyMaxY;
        while (renderJournalCursor < target) {
            int index = renderJournalIndices[renderJournalCursor];
            vram[index] = renderJournalValues[renderJournalCursor];
            int x = index & (VRAM_WIDTH - 1);
            int y = index >>> 10;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            renderJournalCursor++;
        }
        dirtyMinX = minX;
        dirtyMinY = minY;
        dirtyMaxX = maxX;
        dirtyMaxY = maxY;
        renderJournalNextCommitCycle = nextRenderJournalCommitCycle();
    }

    private int nextRenderJournalCommitCycle() {
        if (!activeRenderJournalPlanned || renderJournalCursor >= renderJournalCount) {
            return Integer.MAX_VALUE;
        }
        int setupEnd = Math.clamp(renderTotalCycles - 1, 0, renderSetupCycles);
        int workCycles = Math.max(1, renderTotalCycles - setupEnd);
        long numerator = (long) (renderJournalCursor + 1) * workCycles;
        int requiredWorkCycles = (int) ((numerator + renderJournalCount - 1L)
            / renderJournalCount);
        return setupEnd + requiredWorkCycles;
    }

    private void completeActiveRenderCommand() {
        if (!activeRenderJournalPlanned) {
            executeLegacyCompletedRenderCommand();
            return;
        }
        commitRenderJournalThrough(renderTotalCycles);
        drawMode = activeRenderDrawModeAfter;
        commandWorkPixels = renderJournalCount;
        activeRenderWords = null;
        renderTotalCycles = 0;
        renderElapsedCycles = 0;
        renderSetupCycles = 0;
        renderJournalCursor = 0;
        renderJournalCount = 0;
        renderJournalNextCommitCycle = Integer.MAX_VALUE;
        activeRenderJournalPlanned = false;
    }

    private void executeLegacyCompletedRenderCommand() {
        int[] packet = activeRenderWords;
        activeRenderWords = null;
        if (packet == null || packet.length == 0) {
            return;
        }
        clearCommandWords();
        for (int word : packet) {
            appendCommandWord(word);
        }
        currentCommand = packet[0];
        wordsRemaining = 0;
        pendingPolyline = false;
        pendingShadedPolyline = false;
        executeCommand();
    }

    public boolean inHblank() {
        return inHblank;
    }

    public boolean inVblank() {
        return inVblank;
    }

    public GpuFrame captureFrame() {
        if (completedFramePixels != null) {
            return new GpuFrame(
                completedFrameWidth,
                completedFrameHeight,
                completedFramePixels,
                frameCounter
            );
        }
        return captureLiveFrame();
    }

    private GpuFrame captureLiveFrame() {
        return captureLiveFrame(null);
    }

    private GpuFrame captureLiveFrame(int[] reusablePixels) {
        int width = Math.max(1, completedWidth);
        int height = Math.max(1, completedHeight);
        CaptureRegion region = chooseCaptureRegion(width, height);
        int pixelCount = region.width * region.height;
        int[] pixels = reusablePixels != null && reusablePixels.length == pixelCount
            ? reusablePixels
            : new int[pixelCount];
        boolean visible = false;
        boolean display24Bit = isDisplayArea24Bit(completedMode);
        // GP1(03h).0 disables the video DAC output.
        boolean allowCapture = !completedDisabled;
        if (allowCapture) {
            for (int y = 0; y < region.height; y++) {
                int srcY = (region.y + y) & vramYAddressMask();
                int destinationOffset = y * region.width;
                if (display24Bit) {
                    capture24BitRow(region.x, srcY, region.width, pixels, destinationOffset);
                    for (int x = 0; x < region.width; x++) {
                        visible |= (pixels[destinationOffset + x] & 0x00FF_FFFF) != 0;
                    }
                    continue;
                }
                int sourceOffset = srcY * VRAM_WIDTH;
                for (int x = 0; x < region.width; x++) {
                    int srcX = (region.x + x) & (VRAM_WIDTH - 1);
                    int rgb = srcY < VRAM_HEIGHT ? vram[sourceOffset + srcX] & 0xFFFF : 0;
                    visible |= (rgb & 0x7FFF) != 0;
                    pixels[destinationOffset + x] = rgb555ToArgb(rgb);
                }
            }
        }
        if (!visible && (frameCounter % 120) == 0 && Log.isDebugEnabled()) {
            boolean gpuIdle = totalGp0Words == 0 && totalImageWords == 0 && dirtyMaxX < dirtyMinX;
            if (gpuIdle) {
                Log.debug("GPU capture still black during startup. displayStart=" + completedStartX + "," + completedStartY
                    + " size=" + region.width + "x" + region.height
                    + " displayRangeX=" + displayRangeX1 + "-" + displayRangeX2
                    + " displayRangeY=" + displayRangeY1 + "-" + displayRangeY2
                    + " displayDisabled=" + completedDisabled);
            } else {
                Log.debug("GPU capture still black. displayStart=" + completedStartX + "," + completedStartY
                    + " size=" + region.width + "x" + region.height
                    + " displayRangeX=" + displayRangeX1 + "-" + displayRangeX2
                    + " displayRangeY=" + displayRangeY1 + "-" + displayRangeY2
                    + " drawArea=" + drawAreaLeft + "," + drawAreaTop + "-" + drawAreaRight + "," + drawAreaBottom
                    + " cmd=0x" + Integer.toHexString(currentCommand)
                    + " drawMode=0x" + Integer.toHexString(drawMode)
                    + " displayDisabled=" + completedDisabled
                    + " dirty=" + dirtyMinX + "," + dirtyMinY + "-" + dirtyMaxX + "," + dirtyMaxY
                    + " gp0Words=" + totalGp0Words + " imageWords=" + totalImageWords);
            }
        }
        return new GpuFrame(region.width, region.height, pixels, frameCounter);
    }

    private void snapshotCompletedFrame() {
        GpuFrame frame = captureLiveFrame(completedFramePixels);
        completedFrameWidth = frame.width();
        completedFrameHeight = frame.height();
        completedFramePixels = frame.pixels();
    }

    private CaptureRegion chooseCaptureRegion(int preferredWidth, int preferredHeight) {
        int width = Math.min(VRAM_WIDTH, preferredWidth);
        int height = Math.min(VRAM_HEIGHT, preferredHeight);
        int startX = completedStartX & (VRAM_WIDTH - 1);
        int startY = completedStartY & vramYAddressMask();
        return new CaptureRegion(startX, startY, width, height);
    }

    private boolean isPalMode() {
        return (displayMode & 0x8) != 0;
    }

    private static boolean isDisplayArea24Bit(int mode) {
        return ((mode >>> 4) & 0x1) != 0;
    }

    private boolean isVerticalInterlaceEnabled() {
        return ((displayMode >>> 5) & 0x1) != 0;
    }

    private boolean isInterlaced480Mode() {
        return isVerticalInterlaceEnabled() && ((displayMode >>> 2) & 0x1) != 0;
    }

    private boolean currentFieldOdd() {
        return interlacedFieldOdd;
    }

    private boolean interlaceFieldBit() {
        if (!isVerticalInterlaceEnabled()) {
            return true;
        }
        return !interlacedFieldOdd;
    }

    private boolean awaitingCommandData() {
        return wordsRemaining > 0 || pendingPolyline;
    }

    private boolean readyToReceiveCommandWord() {
        return !cpuToVramTransfer
            && !vramToCpuTransfer
            && !awaitingCommandData()
            && gp0FifoWords < GP0_FIFO_CAPACITY_WORDS
            && renderBusyCycles == 0
            && activeRenderWords == null;
    }

    private boolean readyToSendVramToCpu() {
        return vramToCpuTransfer;
    }

    boolean readyToReceiveDmaBlock() {
        if (vramToCpuTransfer || gp0IngressSize != 0) {
            return false;
        }
        if (awaitingCommandData()) {
            int opcode = (currentCommand >>> 24) & 0xFF;
            if (isPolygonOpcode(opcode) || isLineOpcode(opcode)) {
                return false;
            }
        }
        return true;
    }

    private boolean drawTargetsCurrentField(int y) {
        if (!isInterlaced480Mode() || (drawMode & (1 << 10)) != 0) {
            return true;
        }
        int activeLineLsb = (displayStartY + (interlacedDisplayFieldOdd ? 1 : 0)) & 1;
        return (y & 1) != activeLineLsb;
    }

    private void latchDisplayedState() {
        displayedStartX = displayStartX;
        displayedStartY = displayStartY;
        displayedWidth = Math.max(1, frameBufferWidth);
        displayedHeight = Math.max(1, frameBufferHeight);
        displayedMode = displayMode;
        displayedDisabled = displayDisabled || frameBufferWidth == 0 || frameBufferHeight == 0;
    }

    private void snapshotCompletedDisplayState() {
        completedStartX = displayedStartX;
        completedStartY = displayedStartY;
        completedWidth = displayedWidth;
        completedHeight = displayedHeight;
        completedMode = displayedMode;
        completedDisabled = displayedDisabled;
    }

    public int crtcTicksLastTick() {
        return crtcTicksLastTick;
    }

    public int beamScanline() {
        return scanline;
    }

    public int beamCrtcTick() {
        return scanlineCycleAccumulator;
    }

    public int beamField() {
        return interlacedFieldOdd ? 1 : 0;
    }

    public int crtcClockNumerator() {
        return hardwareProfile.gpuClockRatioNumerator();
    }

    public int crtcClockDenominator() {
        return hardwareProfile.gpuClockRatioDenominator();
    }

    public int dotClockTicksLastTick() {
        return dotClockTicksLastTick;
    }

    public int completedScanlineDotClockTicks() {
        return completedScanlineDotClockTicks;
    }

    public HardwareProfile hardwareProfile() {
        return hardwareProfile;
    }

    // Safe window with no new GPU IRQ.
    public boolean interruptStableFor(int enabledMask, int cycles) {
        if (cycles <= 0) {
            return true;
        }
        if ((enabledMask & (1 << 1)) != 0 && !irqRequested && gp0IngressSize != 0) {
            return false;
        }
        if ((enabledMask & 1) == 0 || inVblank) {
            return true;
        }
        int totalLines = verticalRangeLimit();
        int comparator = displayRangeY2;
        if (comparator < 0 || comparator >= totalLines) {
            return true;
        }
        int precedingLine = comparator == 0 ? totalLines - 1 : comparator - 1;
        // Thirty-two CPU clocks cannot span a complete scanline.
        return scanline != precedingLine;
    }

    public int hblankRisesLastTick() {
        return hblankRisesLastTick;
    }

    public int hblankRisesWithinSystemClocks(int systemClocks) {
        if (systemClocks <= 0 || timerHblankForcedHigh()) {
            return 0;
        }
        int ticksPerLine = crtcTicksPerLine();
        int rangeStart = Math.clamp(displayRangeX1, 0, ticksPerLine);
        if (rangeStart == ticksPerLine
            || (rangeStart == 0 && displayRangeX2 >= ticksPerLine)) {
            return 0;
        }
        int riseTick = Math.min(displayRangeX2, ticksPerLine);
        int ticksToFirstRise = scanlineCycleAccumulator < riseTick
            ? riseTick - scanlineCycleAccumulator
            : ticksPerLine - scanlineCycleAccumulator + riseTick;
        long scaledTicks = crtcFractionalTicks
            + (long) systemClocks * gpuClockRatioNumerator;
        long generatedTicks = scaledTicks / gpuClockRatioDenominator;
        if (generatedTicks < ticksToFirstRise) {
            return 0;
        }
        long rises = 1L + (generatedTicks - ticksToFirstRise) / ticksPerLine;
        return rises > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rises;
    }

    public int hblankFallsLastTick() {
        return hblankFallsLastTick;
    }

    public int vblankRisesLastTick() {
        return vblankRisesLastTick;
    }

    public int vblankFallsLastTick() {
        return vblankFallsLastTick;
    }

    private static int setBit(int value, int bit, boolean enabled) {
        return enabled ? (value | (1 << bit)) : (value & ~(1 << bit));
    }

    private void capture24BitRow(int startX, int y, int width, int[] destination, int destinationOffset) {
        if (y >= VRAM_HEIGHT) {
            Arrays.fill(destination, destinationOffset, destinationOffset + width, 0xFF00_0000);
            return;
        }
        int rowBytes = VRAM_WIDTH * 2;
        int byteOffset = (startX * 2) & (rowBytes - 1);
        int sourceRow = y * VRAM_WIDTH;
        for (int x = 0; x < width; x++) {
            int firstWord = vram[sourceRow + (byteOffset >>> 1)] & 0xFFFF;
            int r;
            int g;
            int b;
            if ((byteOffset & 1) == 0) {
                r = firstWord & 0xFF;
                g = firstWord >>> 8;
                int nextOffset = byteOffset + 2;
                if (nextOffset == rowBytes) {
                    nextOffset = 0;
                }
                b = vram[sourceRow + (nextOffset >>> 1)] & 0xFF;
            } else {
                r = firstWord >>> 8;
                int nextOffset = byteOffset + 1;
                if (nextOffset == rowBytes) {
                    nextOffset = 0;
                }
                int nextWord = vram[sourceRow + (nextOffset >>> 1)] & 0xFFFF;
                g = nextWord & 0xFF;
                b = nextWord >>> 8;
            }
            destination[destinationOffset + x] = 0xFF00_0000 | (r << 16) | (g << 8) | b;
            byteOffset += 3;
            if (byteOffset >= rowBytes) {
                byteOffset -= rowBytes;
            }
        }
    }

    private void requestInterrupt() {
        irqRequested = true;
        interruptController.raise(1);
    }

    public int status() {
        applyDisplayModeToStatus();
        applyDrawStateToStatus();
        boolean dmaBlockReady = readyToReceiveDmaBlock();
        int result = status;
        result = setBit(result, 13, interlaceFieldBit());
        result = setBit(result, 26, readyToReceiveCommandWord());
        result = setBit(result, 27, readyToSendVramToCpu());
        result = setBit(result, 28, dmaBlockReady);
        int dmaDirection = (status >>> 29) & 0x3;
        boolean dmaRequest = switch (dmaDirection) {
            case 0 -> false;
            case 1 -> gp0FifoWords < GP0_FIFO_CAPACITY_WORDS;
            case 2 -> dmaBlockReady;
            case 3 -> readyToSendVramToCpu();
            default -> false;
        };
        result = setBit(result, 25, dmaRequest);
        result = setBit(result, 23, displayDisabled);
        result = setBit(result, 24, irqRequested);
        if (inVblank) {
            result &= ~(1 << 31);
        } else if (isInterlaced480Mode()) {
            result = (result & ~(1 << 31)) | ((currentFieldOdd() ? 1 : 0) << 31);
        } else {
            result = (result & ~(1 << 31)) | ((scanline & 0x1) << 31);
        }
        return result;
    }

    public short[] copyVram() {
        return vram.clone();
    }


    public void loadVram(short[] snapshot) {
        Arrays.fill(vram, (short) 0);
        System.arraycopy(snapshot, 0, vram, 0, Math.min(snapshot.length, vram.length));
        invalidateTextureAndClutCaches();
        completedFramePixels = null;
    }

    public State copyState() {
        State state = new State();
        state.frameCounter = frameCounter;
        state.displayMode = displayMode;
        state.drawMode = drawMode;
        state.textureWindow = textureWindow;
        state.displayStartX = displayStartX;
        state.displayStartY = displayStartY;
        state.displayRangeX1 = displayRangeX1;
        state.displayRangeX2 = displayRangeX2;
        state.displayRangeY1 = displayRangeY1;
        state.displayRangeY2 = displayRangeY2;
        state.drawAreaLeft = drawAreaLeft;
        state.drawAreaTop = drawAreaTop;
        state.drawAreaRight = drawAreaRight;
        state.drawAreaBottom = drawAreaBottom;
        state.drawOffsetX = drawOffsetX;
        state.drawOffsetY = drawOffsetY;
        state.frameBufferWidth = frameBufferWidth;
        state.frameBufferHeight = frameBufferHeight;
        state.displayedStartX = displayedStartX;
        state.displayedStartY = displayedStartY;
        state.displayedWidth = displayedWidth;
        state.displayedHeight = displayedHeight;
        state.displayedMode = displayedMode;
        state.displayedDisabled = displayedDisabled;
        state.completedStartX = completedStartX;
        state.completedStartY = completedStartY;
        state.completedWidth = completedWidth;
        state.completedHeight = completedHeight;
        state.completedMode = completedMode;
        state.completedDisabled = completedDisabled;
        state.status = status;
        state.currentCommand = currentCommand;
        state.wordsRemaining = wordsRemaining;
        state.transferX = transferX;
        state.transferY = transferY;
        state.transferWidth = transferWidth;
        state.transferHeight = transferHeight;
        state.transferOriginX = transferOriginX;
        state.transferOriginY = transferOriginY;
        state.transferColumn = transferColumn;
        state.transferRow = transferRow;
        state.transferPixelsRemaining = transferPixelsRemaining;
        state.gpureadLatch = gpureadLatch;
        state.gp0FifoWords = gp0FifoWords;
        state.gp0FifoDrainCarry = gp0FifoDrainCarry;
        state.renderBusyCycles = renderBusyCycles;
        state.renderQueueCycles = new int[0];
        state.renderQueueWords = new int[0];
        state.gp0IngressFifo = copyGp0IngressFifo();
        state.activeRenderWords = activeRenderWords == null ? null : activeRenderWords.clone();
        state.renderTotalCycles = renderTotalCycles;
        state.renderElapsedCycles = renderElapsedCycles;
        state.renderSetupCycles = renderSetupCycles;
        state.renderJournalCursor = renderJournalCursor;
        state.renderJournalIndices = Arrays.copyOf(renderJournalIndices, renderJournalCount);
        state.renderJournalValues = Arrays.copyOf(renderJournalValues, renderJournalCount);
        state.activeRenderJournalPlanned = activeRenderJournalPlanned;
        state.activeRenderDrawModeAfter = activeRenderDrawModeAfter;
        state.commandWorkPixels = commandWorkPixels;
        state.cpuToVramTransfer = cpuToVramTransfer;
        state.vramToCpuTransfer = vramToCpuTransfer;
        state.displayDisabled = displayDisabled;
        state.checkMaskBit = checkMaskBit;
        state.forceMaskBit = forceMaskBit;
        state.currentSemiTransparent = currentSemiTransparent;
        state.currentSemiTransparencyRequiresBit15 = currentSemiTransparencyRequiresBit15;
        state.pendingPolyline = pendingPolyline;
        state.pendingShadedPolyline = pendingShadedPolyline;
        state.streamingQuadContinuation = streamingQuadContinuation;
        state.streamingPolyline = streamingPolyline;
        state.streamingPolylineAwaitingVertex = streamingPolylineAwaitingVertex;
        state.streamingPolylinePreviousXy = streamingPolylinePreviousXy;
        state.streamingPolylinePreviousColor = streamingPolylinePreviousColor;
        state.streamingPolylineNextColor = streamingPolylineNextColor;
        state.dirtyMinX = dirtyMinX;
        state.dirtyMinY = dirtyMinY;
        state.dirtyMaxX = dirtyMaxX;
        state.dirtyMaxY = dirtyMaxY;
        state.totalGp0Words = totalGp0Words;
        state.totalImageWords = totalImageWords;
        state.vblankCycleAccumulator = vblankCycleAccumulator;
        state.halfLineCrtcState = true;
        state.fieldCrtcHalfTicks = fieldCrtcHalfTicks;
        state.scanlineCycleAccumulator = scanlineCycleAccumulator;
        state.scanline = scanline;
        state.interlacedFieldOdd = interlacedFieldOdd;
        state.interlacedDisplayFieldOdd = interlacedDisplayFieldOdd;
        state.inHblank = inHblank;
        state.inVblank = inVblank;
        state.crtcFractionalTicks = crtcFractionalTicks;
        state.dotClockDividerPhase = dotClockDividerPhase;
        state.dotClockTicksThisLine = dotClockTicksThisLine;
        state.completedScanlineDotClockTicks = completedScanlineDotClockTicks;
        state.irqRequested = irqRequested;
        state.allowSecondVramBank = allowSecondVramBank;
        state.clutCache = clutCache.clone();
        state.cachedClutBits = cachedClutBits;
        state.cachedClutIs8Bit = cachedClutIs8Bit;
        state.textureCache = textureCache.clone();
        state.textureCacheTags = textureCacheTags.clone();
        state.commandFifo = words();
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        frameCounter = state.frameCounter;
        displayMode = state.displayMode;
        drawMode = state.drawMode;
        textureWindow = state.textureWindow;
        displayStartX = state.displayStartX;
        displayStartY = state.displayStartY;
        displayRangeX1 = state.displayRangeX1;
        displayRangeX2 = state.displayRangeX2;
        displayRangeY1 = state.displayRangeY1;
        displayRangeY2 = state.displayRangeY2;
        drawAreaLeft = state.drawAreaLeft;
        drawAreaTop = state.drawAreaTop;
        drawAreaRight = state.drawAreaRight;
        drawAreaBottom = state.drawAreaBottom;
        drawOffsetX = state.drawOffsetX;
        drawOffsetY = state.drawOffsetY;
        frameBufferWidth = state.frameBufferWidth;
        frameBufferHeight = state.frameBufferHeight;
        displayedStartX = state.displayedStartX;
        displayedStartY = state.displayedStartY;
        displayedWidth = state.displayedWidth;
        displayedHeight = state.displayedHeight;
        displayedMode = state.displayedMode;
        displayedDisabled = state.displayedDisabled;
        completedStartX = state.completedStartX;
        completedStartY = state.completedStartY;
        completedWidth = state.completedWidth;
        completedHeight = state.completedHeight;
        completedMode = state.completedMode;
        completedDisabled = state.completedDisabled;
        completedFramePixels = null;
        status = state.status;
        currentCommand = state.currentCommand;
        wordsRemaining = state.wordsRemaining;
        transferX = state.transferX;
        transferY = state.transferY;
        transferWidth = state.transferWidth;
        transferHeight = state.transferHeight;
        transferOriginX = state.transferOriginX;
        transferOriginY = state.transferOriginY;
        transferColumn = state.transferColumn;
        transferRow = state.transferRow;
        transferPixelsRemaining = state.transferPixelsRemaining;
        gpureadLatch = state.gpureadLatch;
        gp0IngressHead = 0;
        gp0IngressSize = 0;
        if (state.gp0IngressFifo != null) {
            int count = Math.min(GP0_FIFO_CAPACITY_WORDS, state.gp0IngressFifo.length);
            for (int i = 0; i < count; i++) {
                gp0IngressFifo[i] = state.gp0IngressFifo[i];
            }
            gp0IngressSize = count;
        }
        updateGp0FifoCount();
        gp0FifoDrainCarry = 0;
        renderBusyCycles = Math.max(0, state.renderBusyCycles);
        activeRenderWords = state.activeRenderWords == null
            ? null
            : state.activeRenderWords.clone();
        renderTotalCycles = state.renderTotalCycles > 0
            ? state.renderTotalCycles
            : renderBusyCycles;
        renderElapsedCycles = Math.clamp(state.renderElapsedCycles, 0, renderTotalCycles);
        renderSetupCycles = Math.clamp(state.renderSetupCycles, 0, renderTotalCycles);
        activeRenderJournalPlanned = state.activeRenderJournalPlanned;
        activeRenderDrawModeAfter = state.activeRenderDrawModeAfter;
        if (activeRenderJournalPlanned
            && state.renderJournalIndices != null
            && state.renderJournalValues != null) {
            renderJournalCount = Math.min(
                state.renderJournalIndices.length,
                state.renderJournalValues.length
            );
            renderJournalIndices = Arrays.copyOf(state.renderJournalIndices, renderJournalCount);
            renderJournalValues = Arrays.copyOf(state.renderJournalValues, renderJournalCount);
            renderJournalCursor = Math.clamp(state.renderJournalCursor, 0, renderJournalCount);
        } else {
            renderJournalCount = 0;
            renderJournalCursor = 0;
            activeRenderJournalPlanned = false;
        }
        renderJournalNextCommitCycle = nextRenderJournalCommitCycle();
        planningRender = false;
        commandWorkPixels = Math.max(0, state.commandWorkPixels);
        cpuToVramTransfer = state.cpuToVramTransfer;
        vramToCpuTransfer = state.vramToCpuTransfer;
        displayDisabled = state.displayDisabled;
        checkMaskBit = state.checkMaskBit;
        forceMaskBit = state.forceMaskBit;
        currentSemiTransparent = state.currentSemiTransparent;
        currentSemiTransparencyRequiresBit15 = state.currentSemiTransparencyRequiresBit15;
        pendingPolyline = state.pendingPolyline;
        pendingShadedPolyline = state.pendingShadedPolyline;
        streamingQuadContinuation = state.streamingQuadContinuation;
        streamingPolyline = state.streamingPolyline;
        streamingPolylineAwaitingVertex = state.streamingPolylineAwaitingVertex;
        streamingPolylinePreviousXy = state.streamingPolylinePreviousXy;
        streamingPolylinePreviousColor = state.streamingPolylinePreviousColor;
        streamingPolylineNextColor = state.streamingPolylineNextColor;
        dirtyMinX = state.dirtyMinX;
        dirtyMinY = state.dirtyMinY;
        dirtyMaxX = state.dirtyMaxX;
        dirtyMaxY = state.dirtyMaxY;
        totalGp0Words = state.totalGp0Words;
        totalImageWords = state.totalImageWords;
        vblankCycleAccumulator = state.vblankCycleAccumulator;
        scanlineCycleAccumulator = state.scanlineCycleAccumulator;
        if (state.halfLineCrtcState) {
            fieldCrtcHalfTicks = state.fieldCrtcHalfTicks;
            scanline = state.scanline;
            interlacedFieldOdd = state.interlacedFieldOdd;
            interlacedDisplayFieldOdd = state.interlacedDisplayFieldOdd;
        } else {
            // Rebuild the missing half-line phase from the saved scanline.
            int ticksPerLine = crtcTicksPerLine();
            fieldCrtcHalfTicks = ((long) Math.max(0, state.scanline) * ticksPerLine
                + Math.floorMod(state.scanlineCycleAccumulator, ticksPerLine)) * 2L;
            scanline = Math.max(0, state.scanline);
            interlacedFieldOdd = isVerticalInterlaceEnabled()
                && (frameCounter & 1) != 0;
            interlacedDisplayFieldOdd = isInterlaced480Mode()
                && (frameCounter & 1) != 0;
        }
        normalizeCrtcTimingState();
        inHblank = state.inHblank;
        inVblank = state.inVblank;
        crtcFractionalTicks = Math.floorMod(
            state.crtcFractionalTicks,
            hardwareProfile.gpuClockRatioDenominator()
        );
        timerDotClockDivider = dotClockDivider(displayMode);
        dotClockDividerPhase = Math.max(0, state.dotClockDividerPhase) % timerDotClockDivider;
        dotClockTicksThisLine = Math.max(0, state.dotClockTicksThisLine);
        completedScanlineDotClockTicks = Math.max(0, state.completedScanlineDotClockTicks);
        dotClockTicksLastTick = 0;
        crtcTicksLastTick = 0;
        hblankRisesLastTick = 0;
        hblankFallsLastTick = 0;
        vblankRisesLastTick = 0;
        vblankFallsLastTick = 0;
        irqRequested = state.irqRequested;
        allowSecondVramBank = state.allowSecondVramBank;
        if (state.clutCache != null) {
            Arrays.fill(clutCache, (short) 0);
            System.arraycopy(state.clutCache, 0, clutCache, 0,
                Math.min(state.clutCache.length, clutCache.length));
            cachedClutBits = state.cachedClutBits;
            cachedClutIs8Bit = state.cachedClutIs8Bit;
        } else {
            invalidateClutCache();
        }
        if (state.textureCache != null && state.textureCacheTags != null) {
            Arrays.fill(textureCache, (short) 0);
            System.arraycopy(state.textureCache, 0, textureCache, 0,
                Math.min(state.textureCache.length, textureCache.length));
            Arrays.fill(textureCacheTags, -1);
            System.arraycopy(state.textureCacheTags, 0, textureCacheTags, 0,
                Math.min(state.textureCacheTags.length, textureCacheTags.length));
        } else {
            invalidateTextureCache();
        }
        clearCommandWords();
        if (state.commandFifo != null) {
            for (int word : state.commandFifo) {
                appendCommandWord(word);
            }
        }
    }

    public int frameCounter() {
        return frameCounter;
    }

    public int completedFrameHash() {
        return completedFramePixels == null ? 0 : Arrays.hashCode(completedFramePixels);
    }

    public Diagnostic diagnostic() {
        return new Diagnostic(
            Arrays.hashCode(vram), displayStartX, displayStartY,
            completedStartX, completedStartY, completedWidth, completedHeight,
            dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY,
            totalGp0Words, totalImageWords, currentCommand,
            wordsRemaining, renderBusyCycles, renderJournalCount
        );
    }

    public record Diagnostic(
        int vramHash, int displayStartX, int displayStartY,
        int completedStartX, int completedStartY, int completedWidth, int completedHeight,
        int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY,
        long totalGp0Words, long totalImageWords, int command,
        int wordsRemaining, int renderBusyCycles, int renderJournalCount
    ) {
    }

    public int frameBufferWidth() {
        return frameBufferWidth;
    }

    public int frameBufferHeight() {
        return frameBufferHeight;
    }

    public int displayMode() {
        return displayMode;
    }

    public int drawMode() {
        return drawMode;
    }

    public int command() {
        return currentCommand;
    }


    private static boolean isSemiTransparentCommand(int commandWord) {
        return (commandWord & 0x0200_0000) != 0;
    }

    private static boolean isRawTextureCommand(int commandWord) {
        return (commandWord & 0x0100_0000) != 0;
    }

    private static int texpageFromUvWord(int uvWord) {
        return (uvWord >>> 16) & 0x9FF;
    }

    private void updateDrawModeFromTexpageAttribute(int texpage) {
        int preserved = drawMode & 0x3600;
        setDrawMode(preserved | (texpage & 0x01FF) | (texpage & 0x0800));
    }

    private void setDrawMode(int value) {
        drawMode = value & 0x3FFF;
    }

    private int effectiveTexturePageY(int texpage) {
        int pageY = (texpage >>> 4) & 0x1;
        if (allowSecondVramBank) {
            pageY |= ((texpage >>> 11) & 0x1) << 1;
        }
        return pageY * 256;
    }

    @Override
    public int read() {
        return gpuread();
    }

    @Override
    public void write(int value) {
        gp0(value);
    }

    @Override
    public boolean dmaRequest() {
        return switch ((status >>> 29) & 0x3) {
            case 1 -> gp0FifoWords < GP0_FIFO_CAPACITY_WORDS;
            case 2 -> readyToReceiveDmaBlock();
            case 3 -> readyToSendVramToCpu();
            default -> false;
        };
    }

    @Override
    public boolean dmaRequest(boolean fromRam) {
        return switch ((status >>> 29) & 0x3) {
            case 1 -> fromRam && gp0FifoWords < GP0_FIFO_CAPACITY_WORDS;
            case 2 -> fromRam && readyToReceiveDmaBlock();
            case 3 -> !fromRam && readyToSendVramToCpu();
            default -> false;
        };
    }

    boolean canAcceptDmaBlockWord() {
        if (cpuToVramTransfer) {
            return true;
        }
        return !vramToCpuTransfer && gp0IngressSize < GP0_FIFO_CAPACITY_WORDS;
    }

    boolean awaitingDmaPacketParameters() {
        return !cpuToVramTransfer && !vramToCpuTransfer && awaitingCommandData();
    }

    int cyclesUntilDmaAvailabilityMayChange(int maximumCycles) {
        int limit = Math.max(1, maximumCycles);
        if (renderBusyCycles > 0) {
            return Math.min(limit, renderBusyCycles);
        }
        return limit;
    }

    int dmaIngressStableClocks(int maximumCycles) {
        if (renderBusyCycles <= 1 || vramToCpuTransfer) {
            return 1;
        }
        return Math.min(Math.max(1, maximumCycles), renderBusyCycles - 1);
    }

    int dmaIngressFreeWords() {
        if (cpuToVramTransfer) {
            return GP0_FIFO_CAPACITY_WORDS;
        }
        return vramToCpuTransfer ? 0 : GP0_FIFO_CAPACITY_WORDS - gp0IngressSize;
    }

    private int[] words() {
        return Arrays.copyOf(commandFifo, commandFifoSize);
    }

    private void appendCommandWord(int value) {
        if (commandFifoSize == commandFifo.length) {
            commandFifo = Arrays.copyOf(commandFifo, commandFifo.length * 2);
        }
        commandFifo[commandFifoSize++] = value;
    }

    private void clearCommandWords() {
        commandFifoSize = 0;
    }

    private int[] copyGp0IngressFifo() {
        int[] result = new int[gp0IngressSize];
        for (int i = 0; i < gp0IngressSize; i++) {
            result[i] = gp0IngressFifo[
                (gp0IngressHead + i) & (GP0_FIFO_CAPACITY_WORDS - 1)
            ];
        }
        return result;
    }

    private static int decodeX(int word) {
        return sign11(word & 0x7FF);
    }

    private static int decodeY(int word) {
        return sign11((word >>> 16) & 0x7FF);
    }

    private static int edge(int ax, int ay, int bx, int by, int px, int py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static int rgb24ToRgb555(int rgb) {
        int r = (rgb >> 3) & 0x1F;
        int g = (rgb >> 11) & 0x1F;
        int b = (rgb >> 19) & 0x1F;
        return r | (g << 5) | (b << 10);
    }

    private static int rgb555ToArgb(int rgb555) {
        return RGB555_TO_ARGB[rgb555 & 0x7FFF];
    }

    private static int[] createRgb555ToArgbTable() {
        int[] table = new int[1 << 15];
        for (int rgb555 = 0; rgb555 < table.length; rgb555++) {
            table[rgb555] = expandRgb555ToArgb(rgb555);
        }
        return table;
    }

    private static int expandRgb555ToArgb(int rgb555) {
        int r = (rgb555 & 0x1F) << 3;
        int g = ((rgb555 >> 5) & 0x1F) << 3;
        int b = ((rgb555 >> 10) & 0x1F) << 3;
        return 0xFF00_0000 | (r << 16) | (g << 8) | b;
    }

    private static boolean isPolylineTerminator(int word) {
        return (word & POLYLINE_TERMINATOR_MASK) == POLYLINE_TERMINATOR;
    }

    private static boolean isPolylineTerminatorAt(int wordIndex, boolean gouraud, int word) {
        boolean terminatorPosition = gouraud
            ? wordIndex >= 4 && (wordIndex & 1) == 0
            : wordIndex >= 3;
        return terminatorPosition && isPolylineTerminator(word);
    }

    private static int truncateRectangleCoordinate(int value) {
        return sign11(value & 0x7FF);
    }

    private static int sign11(int value) {
        return (value & 0x400) != 0 ? value | ~0x7FF : value;
    }

    private boolean hasDithering() {
        return ((drawMode >>> 9) & 0x1) != 0;
    }

    private static boolean primitiveTooLarge(int x0, int y0, int x1, int y1, int x2, int y2) {
        int minX = Math.min(x0, Math.min(x1, x2));
        int maxX = Math.max(x0, Math.max(x1, x2));
        int minY = Math.min(y0, Math.min(y1, y2));
        int maxY = Math.max(y0, Math.max(y1, y2));
        return (maxX - minX) > MAX_PRIMITIVE_WIDTH || (maxY - minY) > MAX_PRIMITIVE_HEIGHT;
    }

    private static boolean isTopLeftEdge(int ax, int ay, int bx, int by) {
        int dy = by - ay;
        int dx = bx - ax;
        return dy > 0 || (dy == 0 && dx < 0);
    }

    private int interpolateLineColor(int c0, int c1, int step, int steps) {
        if (steps <= 0 || c0 == c1) {
            return c0;
        }
        int r = ((c0 & 0xFF) * (steps - step) + (c1 & 0xFF) * step) / steps;
        int g = ((((c0 >>> 8) & 0xFF) * (steps - step)) + (((c1 >>> 8) & 0xFF) * step)) / steps;
        int b = ((((c0 >>> 16) & 0xFF) * (steps - step)) + (((c1 >>> 16) & 0xFF) * step)) / steps;
        return r | (g << 8) | (b << 16);
    }

    private short rgb24ToVramColor(int rgb, int x, int y, boolean dither, int bit15) {
        int r = rgb & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = (rgb >>> 16) & 0xFF;
        if (dither) {
            int d = DITHER_MATRIX[y & 3][x & 3];
            r = clamp8(r + d);
            g = clamp8(g + d);
            b = clamp8(b + d);
        }
        return (short) ((r >>> 3) | ((g >>> 3) << 5) | ((b >>> 3) << 10) | (bit15 & 0x8000));
    }

    private static int clamp8(int value) {
        return Math.clamp(value, 0, 255);
    }

    private static int decodeCopyWidth(int word) {
        return (((word & 0xFFFF) - 1) & 0x3FF) + 1;
    }

    private static int decodeCopyHeight(int word) {
        return ((((word >>> 16) & 0xFFFF) - 1) & 0x1FF) + 1;
    }

    private static boolean isPolygonOpcode(int opcode) {
        return (opcode & 0xE0) == 0x20;
    }

    private static boolean isLineOpcode(int opcode) {
        return (opcode & 0xE0) == 0x40;
    }

    private static boolean isRectangleOpcode(int opcode) {
        return (opcode & 0xE0) == 0x60;
    }

    private static boolean isFifoFreeOpcode(int opcode) {
        return opcode == 0x00
            || (opcode >= 0x04 && opcode <= 0x1E)
            || opcode == 0xE0
            || (opcode >= 0xE3 && opcode <= 0xE5)
            || (opcode >= 0xE7 && opcode <= 0xEF);
    }

    private static boolean isTexturedOpcode(int opcode) {
        return (opcode & 0x04) != 0;
    }

    private static boolean isGouraudShadedOpcode(int opcode) {
        return (opcode & 0x10) != 0;
    }

    private static boolean isQuadOpcode(int opcode) {
        return (opcode & 0x08) != 0;
    }

    private static boolean isPolylineOpcode(int opcode) {
        return isLineOpcode(opcode) && isQuadOpcode(opcode);
    }

    private static int rectangleSizeCode(int opcode) {
        return (opcode >>> 3) & 0x3;
    }

    private static int polygonCommandLength(int opcode) {
        int vertices = isQuadOpcode(opcode) ? 4 : 3;
        boolean textured = isTexturedOpcode(opcode);
        boolean gouraud = isGouraudShadedOpcode(opcode);
        if (textured) {
            return gouraud ? vertices * 3 : 1 + (vertices * 2);
        }
        return gouraud ? (vertices * 2) : (1 + vertices);
    }

    private static int lineCommandLength(int opcode) {
        if (isPolylineOpcode(opcode)) {
            return 1;
        }
        return isGouraudShadedOpcode(opcode) ? 4 : 3;
    }

    private static int rectangleCommandLength(int opcode) {
        boolean textured = isTexturedOpcode(opcode);
        boolean variableSize = rectangleSizeCode(opcode) == 0;
        if (textured) {
            return variableSize ? 4 : 3;
        }
        return variableSize ? 3 : 2;
    }

    private void putPixel(int x, int y, short color, boolean honorMask, boolean allowBlend) {
        if (x < 0 || y < 0 || x >= VRAM_WIDTH || y >= VRAM_HEIGHT) {
            return;
        }
        int index = y * VRAM_WIDTH + x;
        short dst = vram[index];
        if (honorMask && checkMaskBit && (dst & 0x8000) != 0) {
            return;
        }
        boolean doBlend = allowBlend && currentSemiTransparent && (!currentSemiTransparencyRequiresBit15 || (color & 0x8000) != 0);
        short next = doBlend ? applySemiTransparency(dst, color) : color;
        if (planningRender) {
            appendPlannedVramWrite(index, dst, next);
        }
        vram[index] = next;
        commandWorkPixels++;
        if (!planningRender) {
            markDirty(x, y);
        }
    }

    // Pixel write for polygon loops whose clip and field checks are hoisted.
    private void putRasterPixel(int x, int y, short color) {
        int index = y * VRAM_WIDTH + x;
        short dst = vram[index];
        if (checkMaskBit && (dst & 0x8000) != 0) {
            return;
        }
        commandTimingPixels++;
        writeRasterPixel(index, x, y, color, dst);
    }

    private void writeRasterPixel(int index, int x, int y, short color, short dst) {
        boolean doBlend = currentSemiTransparent
            && (!currentSemiTransparencyRequiresBit15 || (color & 0x8000) != 0);
        short next = doBlend ? applySemiTransparency(dst, color) : color;
        if (planningRender) {
            appendPlannedVramWrite(index, dst, next);
        }
        vram[index] = next;
        commandWorkPixels++;
        if (!planningRender) {
            markDirty(x, y);
        }
    }

    private record CaptureRegion(int x, int y, int width, int height) {}

    public static final class State {
        int frameCounter;
        int displayMode;
        int drawMode;
        int textureWindow;
        int displayStartX;
        int displayStartY;
        int displayRangeX1;
        int displayRangeX2;
        int displayRangeY1;
        int displayRangeY2;
        int drawAreaLeft;
        int drawAreaTop;
        int drawAreaRight;
        int drawAreaBottom;
        int drawOffsetX;
        int drawOffsetY;
        int frameBufferWidth;
        int frameBufferHeight;
        int displayedStartX;
        int displayedStartY;
        int displayedWidth;
        int displayedHeight;
        int displayedMode;
        boolean displayedDisabled;
        int completedStartX;
        int completedStartY;
        int completedWidth;
        int completedHeight;
        int completedMode;
        boolean completedDisabled;
        int status;
        int currentCommand;
        int wordsRemaining;
        int transferX;
        int transferY;
        int transferWidth;
        int transferHeight;
        int transferOriginX;
        int transferOriginY;
        int transferColumn;
        int transferRow;
        int transferPixelsRemaining;
        int gpureadLatch;
        int gp0FifoWords;
        int gp0FifoDrainCarry;
        int renderBusyCycles;
        int[] renderQueueCycles;
        int[] renderQueueWords;
        int[] gp0IngressFifo;
        int[] activeRenderWords;
        int renderTotalCycles;
        int renderElapsedCycles;
        int renderSetupCycles;
        int renderJournalCursor;
        int[] renderJournalIndices;
        short[] renderJournalValues;
        boolean activeRenderJournalPlanned;
        int activeRenderDrawModeAfter;
        int commandWorkPixels;
        boolean cpuToVramTransfer;
        boolean vramToCpuTransfer;
        boolean displayDisabled;
        boolean checkMaskBit;
        boolean forceMaskBit;
        boolean currentSemiTransparent;
        boolean currentSemiTransparencyRequiresBit15;
        boolean pendingPolyline;
        boolean pendingShadedPolyline;
        boolean streamingQuadContinuation;
        boolean streamingPolyline;
        boolean streamingPolylineAwaitingVertex;
        int streamingPolylinePreviousXy;
        int streamingPolylinePreviousColor;
        int streamingPolylineNextColor;
        int dirtyMinX;
        int dirtyMinY;
        int dirtyMaxX;
        int dirtyMaxY;
        long totalGp0Words;
        long totalImageWords;
        int vblankCycleAccumulator;
        boolean halfLineCrtcState;
        long fieldCrtcHalfTicks;
        int scanlineCycleAccumulator;
        int scanline;
        boolean interlacedFieldOdd;
        boolean interlacedDisplayFieldOdd;
        boolean inHblank;
        boolean inVblank;
        long crtcFractionalTicks;
        int dotClockDividerPhase;
        int dotClockTicksThisLine;
        int completedScanlineDotClockTicks;
        boolean irqRequested;
        boolean allowSecondVramBank;
        short[] clutCache;
        int cachedClutBits;
        boolean cachedClutIs8Bit;
        short[] textureCache;
        int[] textureCacheTags;
        int[] commandFifo;
    }

    private static final class Vertex {
        private int x;
        private int y;
        private int u;
        private int v;
        private int color24;
        private int paletteWord;
        private int texpage;

        private Vertex set(int xyWord, int uvWord, int colorWord, int paletteWord, int texpage) {
            this.x = decodeX(xyWord);
            this.y = decodeY(xyWord);
            this.u = uvWord & 0xFF;
            this.v = (uvWord >>> 8) & 0xFF;
            this.color24 = colorWord & 0x00FF_FFFF;
            this.paletteWord = paletteWord;
            this.texpage = texpage;
            return this;
        }
    }
}
