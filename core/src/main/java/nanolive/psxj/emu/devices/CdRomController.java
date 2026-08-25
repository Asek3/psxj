package nanolive.psxj.emu.devices;

import nanolive.psxj.emu.cd.CdImage;
import nanolive.psxj.emu.cd.CdSector;
import nanolive.psxj.emu.cd.XaAdpcmDecoder;
import nanolive.psxj.emu.dma.DmaPort;
import nanolive.psxj.emu.hardware.CdDriveProfile;
import nanolive.psxj.emu.hardware.HardwareProfile;
import nanolive.psxj.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.function.IntSupplier;

public final class CdRomController implements DmaPort, AutoCloseable {

    private static final short[] EMPTY_PCM = new short[0];

    // PSX CPU cycles at 33.868 MHz.
    private static final int RESET_CYCLES               = 0x400000;
    private static final int CDDA_SCAN_STEP_SECTORS     = 12;
    private static final int CDDA_REPORT_INTERVAL       = 10;
    private static final int FIRMWARE_MAINTENANCE_SLOTS = 32;
    private static final int HOST_READ_AHEAD_SECTORS     = 12;
    private static final int BUSY_TO_IRQ_MIN_CYCLES     = 1_000;
    private static final int BUSY_TO_IRQ_MAX_CYCLES     = 6_000;

    private static final int PARAM_FIFO_CAPACITY      = 16;          // per No$psx
    private static final int RESPONSE_FIFO_CAPACITY   = 16;
    private static final int SOUND_MAP_FIFO_CAPACITY  = 0x930;
    private static final int XA_AUDIO_DECODER_SLOTS = 3;

    // Status byte returned with command responses.
    private static final int STATUS_ERROR      = 0x01;
    private static final int STATUS_MOTOR_ON   = 0x02;
    private static final int STATUS_SEEK_ERROR = 0x04;
    private static final int STATUS_ID_ERROR   = 0x08;
    private static final int STATUS_SHELL_OPEN = 0x10;
    private static final int STATUS_READING    = 0x20;
    private static final int STATUS_SEEKING    = 0x40;
    private static final int STATUS_PLAYING    = 0x80;

    // Absolute MSF starts at 00:02:00.
    private static final int LBA_MSF_OFFSET = 150;

    // HSTS register bits (1F801800h).
    private static final int HSTS_INDEX_MASK = 0x03;
    private static final int HSTS_ADPBUSY   = 0x04;
    private static final int HSTS_PRMEMPT   = 0x08;
    private static final int HSTS_PRMWRDY   = 0x10;
    private static final int HSTS_RSLRRDY   = 0x20;
    private static final int HSTS_DRQSTS    = 0x40;
    private static final int HSTS_BUSYSTS   = 0x80;

    private static final int HCLR_ACK_MASK = 0x1F;

    // Setmode bits.
    private static final int MODE_SPEED       = 0x80;   // 0=1x, 1=2x
    private static final int MODE_XA_ADPCM    = 0x40;   // enable XA delivery
    private static final int MODE_SECTOR_SIZE = 0x20;   // 0=800h bytes, 1=924h bytes
    private static final int MODE_IGNORE      = 0x10;   // ignore sector size bit in mode (use latched)
    private static final int MODE_FILTER       = 0x08;   // XA filter by file/channel
    private static final int MODE_REPORT       = 0x04;   // CD-DA report interrupts
    private static final int MODE_AUTOPAUSE    = 0x02;
    private static final int MODE_CDDA         = 0x01;   // allow reading audio tracks

    private final InterruptController interruptController;
    private final HardwareProfile hardwareProfile;
    private final CdDriveProfile driveProfile;
    private final int sectorCycles1x;
    private final int sectorCycles2x;
    private final Queue<Integer>      parameterFifo     = new ArrayDeque<>();
    private final Queue<Integer>      responseFifo      = new ArrayDeque<>();
    // Normally contains zero to two firmware events.
    private final List<PendingEvent> pendingEvents      = new ArrayList<>(4);
    private final Queue<Integer>      soundMapWriteFifo = new ArrayDeque<>();
    private final XaAdpcmDecoder      xaDecoder         = new XaAdpcmDecoder();
    private IntSupplier queuedAudioFrames = () -> 0;

    private CdImage mountedImage;
    private int     registerIndex;
    private int     interruptEnable  = 0x1F;
    private int     interruptFlags;
    private boolean irqLineAsserted;
    private int     queuedCommand    = -1;
    private int     mode             = MODE_SECTOR_SIZE;
    private int     sectorSizeLatch  = MODE_SECTOR_SIZE;
    private int     currentLba;
    private int     targetLba;
    private boolean targetPending;
    private int     filterFile;
    private int     filterChannel;
    private int     commandBusyCycles;
    private int     currentCommandResponseCycles;
    private long    firmwareControllerTicks;
    private long    firmwareClockRemainder;
    private int     rotationalPhaseCycles;
    private int     readCyclesRemaining;
    private int     playCyclesRemaining;
    private int     playStepSectors = 1;
    private int     playReportCountdown = CDDA_REPORT_INTERVAL;
    private int     seekCyclesRemaining;
    private boolean reading;
    private boolean playing;
    private boolean seeking;
    private boolean seekError;
    private boolean seekFailurePending;
    private boolean pendingReadStart;
    private boolean pendingPlayStart;
    private boolean motorOn;
    private boolean streamMuted;
    private boolean bufferWriteRequested;
    private boolean bufferReadRequested;
    private boolean soundMapEnabled;
    private boolean xaMuted;
    private boolean initInProgress;
    private boolean shellOpen = true;
    // Nop clears the stored lid status.
    private boolean shellOpenSticky = true;
    private boolean mediaLifecycleInitialized;
    private int     secretUnlockStep;
    private boolean secretUnlocked;
    private int     readSessionId;
    private short[] xaPcm            = EMPTY_PCM;
    private int     xaCurrentFile;
    private int     xaCurrentChannel;
    private boolean xaCurrentStreamSet;
    private boolean audioResetPending;
    private int[]   lastResponseWindow = new int[RESPONSE_FIFO_CAPACITY];
    private int     responseReadOffset;
    private boolean responseWindowValid;

    // ATV0=L→L, ATV1=L→R, ATV2=R→R, ATV3=R→L; 80h is unity gain.
    private int pendingAtv0 = 0x80, pendingAtv1 = 0x00;
    private int pendingAtv2 = 0x80, pendingAtv3 = 0x00;
    private int appliedAtv0 = 0x80, appliedAtv1 = 0x00;
    private int appliedAtv2 = 0x80, appliedAtv3 = 0x00;

    private CdSector lastBufferedSector;
    private int      lastBufferedLba    = -1;
    private CdSector newestBufferedSector;
    private int      newestBufferedLba  = -1;
    private int[]    lastValidSubchannelLocation;
    private boolean  activeSectorInterrupt;
    private long     eventClockCycles;
    private final Deque<byte[]> pendingDataBlocks = new ArrayDeque<>();
    private byte[]   activeDataBlock    = new byte[0];
    private int      activeDataOffset;

    public CdRomController(InterruptController interruptController) {
        this(interruptController, HardwareProfile.SCPH_5501_PU_18_NTSC_U);
    }

    public CdRomController(
        InterruptController interruptController,
        HardwareProfile hardwareProfile
    ) {
        this.interruptController = interruptController;
        this.hardwareProfile = hardwareProfile;
        this.driveProfile = hardwareProfile.cdDriveProfile();
        this.sectorCycles1x = hardwareProfile.cpuClockHz() / 75;
        this.sectorCycles2x = hardwareProfile.cpuClockHz() / 150;
    }

    public void mount(Path image) {
        try {
            CdImage openedImage = CdImage.open(image);
            boolean initialImage = !mediaLifecycleInitialized;
            boolean shellInterruptPending = interruptFlags == 5;
            boolean replacingClosedDisc = !shellOpen && mountedImage != null;
            CdImage previousImage = mountedImage;
            mountedImage = openedImage;
            if (previousImage != null) {
                previousImage.close();
            }
            resetTransportState();
            shellOpen = false;
            shellOpenSticky = !initialImage;
            mediaLifecycleInitialized = true;
            if (!initialImage && (replacingClosedDisc || shellInterruptPending)) {
                deliverShellOpenInterrupt();
            }
            Log.info("CD mounted: path=" + mountedImage.path()
                + ", sectors=" + mountedImage.sectorCount()
                + ", region=" + mountedImage.regionCode());
        } catch (IOException e) {
            Log.error("Failed to mount CD image " + image, e);
        }
    }

    public void eject() {
        boolean wasClosed = !shellOpen;
        CdImage previousImage = mountedImage;
        mountedImage = null;
        if (previousImage != null) {
            previousImage.close();
        }
        resetTransportState();
        shellOpen = true;
        shellOpenSticky = true;
        mediaLifecycleInitialized = true;
        if (wasClosed) {
            deliverShellOpenInterrupt();
        }
        Log.info("CD ejected");
    }

    public Path mountedImage() {
        return mountedImage == null ? null : mountedImage.path();
    }

    @Override
    public void close() {
        CdImage previousImage = mountedImage;
        mountedImage = null;
        if (previousImage != null) {
            previousImage.close();
        }
    }

    public int read8(int address) {
        return switch (address) {
            case 0x1F80_1800 -> hostStatus();
            case 0x1F80_1801 -> readResultByte();
            case 0x1F80_1802 -> readDataByte();
            case 0x1F80_1803 -> (registerIndex == 0 || registerIndex == 2)
                ? ((interruptEnable & 0x1F) | 0xE0)
                : ((interruptFlags  & 0x1F) | 0xE0);
            default -> 0xFF;
        };
    }

    public void write8(int address, int value) {
        value &= 0xFF;
        switch (address) {
            case 0x1F80_1800 -> registerIndex = value & 0x03;
            case 0x1F80_1801 -> write1801(value);
            case 0x1F80_1802 -> write1802(value);
            case 0x1F80_1803 -> write1803(value);
            default -> { }
        }
    }

    public void tick(int cycles) {
        if (cycles <= 0) return;
        eventClockCycles += cycles;
        advanceControllerClock(cycles);
        if (!motorOn
            && commandBusyCycles == 0
            && !seeking
            && !reading
            && !playing
            && !pendingReadStart
            && !pendingPlayStart
            && pendingEvents.isEmpty()
            && queuedCommand < 0) {
            return;
        }
        advanceRotationalPhase(cycles);
        if (commandBusyCycles > 0) {
            commandBusyCycles = Math.max(0, commandBusyCycles - cycles);
        }
        if (cycles == 1 && reading && mountedImage != null && !seeking && !playing) {
            // Fast path for data and XA streaming.
            if (--readCyclesRemaining <= 0) {
                receiveSector(currentLba++);
                readCyclesRemaining += sectorCycles();
            }
        } else if (seeking || reading || playing) {
            serviceMechanicalState(cycles);
        }
        boolean deliveredEvent = !pendingEvents.isEmpty()
            && interruptFlags == 0
            && pendingEvents.getFirst().dueCycle <= eventClockCycles;
        if (deliveredEvent) {
            dispatchPendingEventsIfPossible();
        }
        if (queuedCommand >= 0) {
            dispatchQueuedCommandIfPossible();
        }
        if (deliveredEvent) {
            updateIrqLine();
        }
    }

    public short[] drainXaPcm() {
        if (xaPcm.length == 0) {
            return EMPTY_PCM;
        }
        short[] copy = xaPcm;
        xaPcm = EMPTY_PCM;
        return copy;
    }

    public void setQueuedAudioFramesSupplier(IntSupplier supplier) {
        queuedAudioFrames = supplier == null ? () -> 0 : supplier;
    }

    public boolean consumeAudioResetRequest() {
        boolean pending = audioResetPending;
        audioResetPending = false;
        return pending;
    }

    public boolean audioClockCoupled() {
        return playing
            || pendingPlayStart
            || ((reading || pendingReadStart) && (mode & MODE_XA_ADPCM) != 0)
            || audioResetPending
            || xaPcm.length != 0
            || queuedCommand >= 0
            || !pendingEvents.isEmpty();
    }

    public boolean audioInputStableFor(int cycles) {
        if (cycles <= 0) {
            return true;
        }
        if (audioResetPending || xaPcm.length != 0) {
            return false;
        }
        long limit = eventClockCycles + cycles;
        if (!pendingEvents.isEmpty() && pendingEvents.getFirst().dueCycle <= limit) {
            return false;
        }
        if (queuedCommand >= 0
            && interruptFlags == 0
            && commandBusyCycles <= cycles) {
            return false;
        }
        if (seeking && seekCyclesRemaining <= cycles
            && (pendingPlayStart
                || (pendingReadStart && (mode & MODE_XA_ADPCM) != 0))) {
            return false;
        }
        if (playing && !seeking && playCyclesRemaining <= cycles) {
            return false;
        }
        return !reading
            || seeking
            || (mode & MODE_XA_ADPCM) == 0
            || readCyclesRemaining > cycles;
    }

    public boolean interruptStableFor(int cycles) {
        if (cycles <= 0 || irqLineAsserted || interruptFlags != 0) {
            return true;
        }
        if (pendingEvents.isEmpty()
            && queuedCommand < 0
            && !seeking
            && !reading
            && !playing) {
            return true;
        }
        long limit = eventClockCycles + cycles;
        if (!pendingEvents.isEmpty() && pendingEvents.getFirst().dueCycle <= limit) {
            return false;
        }
        if (queuedCommand >= 0 && commandBusyCycles <= cycles) {
            return false;
        }
        if (seeking && seekCyclesRemaining <= cycles) {
            return false;
        }
        if (reading && !seeking && !playing && readCyclesRemaining <= cycles) {
            return false;
        }
        return !playing || playCyclesRemaining > cycles;
    }

    public boolean cddaPlaying() { return playing; }

    public Diagnostic diagnostic() {
        long nextEventCycles = pendingEvents.isEmpty()
            ? -1L
            : Math.max(0L, pendingEvents.getFirst().dueCycle - eventClockCycles);
        return new Diagnostic(
            currentLba, targetLba, mode, interruptFlags, interruptEnable,
            reading, playing, seeking, readCyclesRemaining, playCyclesRemaining,
            seekCyclesRemaining, pendingEvents.size(), nextEventCycles,
            pendingDataBlocks.size(), Math.max(0, activeDataBlock.length - activeDataOffset),
            xaCurrentFile, xaCurrentChannel, xaPcm.length / 2, queuedAudioFrames.getAsInt()
        );
    }

    public record Diagnostic(
        int currentLba, int targetLba, int mode, int interruptFlags, int interruptEnable,
        boolean reading, boolean playing, boolean seeking,
        int readCyclesRemaining, int playCyclesRemaining, int seekCyclesRemaining,
        int pendingEvents, long nextEventCycles, int pendingDataBlocks, int activeDataBytes,
        int xaFile, int xaChannel, int decodedXaFrames, int queuedAudioFrames
    ) {
    }

    public State copyState() {
        State state = new State();
        state.imagePath = mountedImage == null ? null : mountedImage.path().toString();
        state.parameterFifo = queueToArray(parameterFifo);
        state.responseFifo = queueToArray(responseFifo);
        state.lastResponseWindow = lastResponseWindow.clone();
        state.responseReadOffset = responseReadOffset;
        state.responseWindowValid = responseWindowValid;
        state.pendingEvents = pendingEvents.stream()
            .map(event -> event.copyState(eventClockCycles))
            .toArray(PendingEventState[]::new);
        state.soundMapWriteFifo = queueToArray(soundMapWriteFifo);
        state.xaDecoder = xaDecoder.copyState();
        state.registerIndex = registerIndex;
        state.interruptEnable = interruptEnable;
        state.interruptFlags = interruptFlags;
        state.queuedCommand = queuedCommand;
        state.mode = mode;
        state.sectorSizeLatch = sectorSizeLatch;
        state.currentLba = currentLba;
        state.targetLba = targetLba;
        state.targetPending = targetPending;
        state.filterFile = filterFile;
        state.filterChannel = filterChannel;
        state.commandBusyCycles = commandBusyCycles;
        state.currentCommandResponseCycles = currentCommandResponseCycles;
        state.firmwareControllerTicks = firmwareControllerTicks;
        state.firmwareClockRemainder = firmwareClockRemainder;
        state.rotationalPhaseCycles = rotationalPhaseCycles;
        state.readCyclesRemaining = readCyclesRemaining;
        state.playCyclesRemaining = playCyclesRemaining;
        state.playStepSectors = playStepSectors;
        state.playReportCountdown = playReportCountdown;
        state.seekCyclesRemaining = seekCyclesRemaining;
        state.reading = reading;
        state.playing = playing;
        state.seeking = seeking;
        state.seekError = seekError;
        state.seekFailurePending = seekFailurePending;
        state.pendingReadStart = pendingReadStart;
        state.pendingPlayStart = pendingPlayStart;
        state.motorOn = motorOn;
        state.streamMuted = streamMuted;
        state.bufferWriteRequested = bufferWriteRequested;
        state.bufferReadRequested = bufferReadRequested;
        state.soundMapEnabled = soundMapEnabled;
        state.xaMuted = xaMuted;
        state.initInProgress = initInProgress;
        state.shellOpen = shellOpen;
        state.shellOpenSticky = shellOpenSticky;
        state.mediaLifecycleInitialized = mediaLifecycleInitialized;
        state.secretUnlockStep = secretUnlockStep;
        state.secretUnlocked = secretUnlocked;
        state.readSessionId = readSessionId;
        state.xaPcm = xaPcm.clone();
        state.xaCurrentFile = xaCurrentFile;
        state.xaCurrentChannel = xaCurrentChannel;
        state.xaCurrentStreamSet = xaCurrentStreamSet;
        state.audioResetPending = audioResetPending;
        state.pendingAtv0 = pendingAtv0;
        state.pendingAtv1 = pendingAtv1;
        state.pendingAtv2 = pendingAtv2;
        state.pendingAtv3 = pendingAtv3;
        state.appliedAtv0 = appliedAtv0;
        state.appliedAtv1 = appliedAtv1;
        state.appliedAtv2 = appliedAtv2;
        state.appliedAtv3 = appliedAtv3;
        state.lastBufferedSector = SectorState.from(lastBufferedSector);
        state.lastBufferedLba = lastBufferedLba;
        state.newestBufferedSector = SectorState.from(newestBufferedSector);
        state.newestBufferedLba = newestBufferedLba;
        state.lastValidSubchannelLocation = lastValidSubchannelLocation == null
            ? null
            : lastValidSubchannelLocation.clone();
        state.activeSectorInterrupt = activeSectorInterrupt;
        state.pendingDataBlocks = copyDataBlockQueue();
        state.activeDataBlock = activeDataBlock.clone();
        state.activeDataOffset = activeDataOffset;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }
        loadImageForState(state.imagePath);
        loadQueue(parameterFifo, state.parameterFifo);
        loadQueue(responseFifo, state.responseFifo);
        lastResponseWindow = state.lastResponseWindow == null
            ? responseWindowFromFifo(responseFifo)
            : Arrays.copyOf(state.lastResponseWindow, RESPONSE_FIFO_CAPACITY);
        responseReadOffset = state.responseReadOffset & (RESPONSE_FIFO_CAPACITY - 1);
        responseWindowValid = state.responseWindowValid || !responseFifo.isEmpty();
        pendingEvents.clear();
        eventClockCycles = 0;
        if (state.pendingEvents != null) {
            for (PendingEventState eventState : state.pendingEvents) {
                PendingEvent event = PendingEvent.fromState(eventState);
                if (event != null) {
                    event.dueCycle = Math.max(0, eventState.cyclesRemaining);
                    pendingEvents.add(event);
                }
            }
        }
        loadQueue(soundMapWriteFifo, state.soundMapWriteFifo);
        xaDecoder.loadState(state.xaDecoder);
        registerIndex = state.registerIndex;
        interruptEnable = state.interruptEnable;
        interruptFlags = state.interruptFlags;
        queuedCommand = state.queuedCommand;
        mode = state.mode;
        sectorSizeLatch = state.sectorSizeLatch;
        currentLba = state.currentLba;
        targetLba = state.targetLba;
        targetPending = state.targetPending;
        filterFile = state.filterFile;
        filterChannel = state.filterChannel;
        commandBusyCycles = state.commandBusyCycles;
        currentCommandResponseCycles = state.currentCommandResponseCycles;
        firmwareControllerTicks = state.firmwareControllerTicks;
        firmwareClockRemainder = state.firmwareClockRemainder;
        rotationalPhaseCycles = state.rotationalPhaseCycles;
        readCyclesRemaining = state.readCyclesRemaining;
        playCyclesRemaining = state.playCyclesRemaining;
        playStepSectors = state.playStepSectors == 0 ? 1 : state.playStepSectors;
        playReportCountdown = state.playReportCountdown <= 0 ? CDDA_REPORT_INTERVAL : state.playReportCountdown;
        seekCyclesRemaining = state.seekCyclesRemaining;
        reading = state.reading;
        playing = state.playing;
        seeking = state.seeking;
        seekError = state.seekError;
        seekFailurePending = state.seekFailurePending;
        pendingReadStart = state.pendingReadStart;
        pendingPlayStart = state.pendingPlayStart;
        motorOn = state.motorOn;
        streamMuted = state.streamMuted;
        bufferWriteRequested = state.bufferWriteRequested;
        bufferReadRequested = state.bufferReadRequested;
        soundMapEnabled = state.soundMapEnabled;
        xaMuted = state.xaMuted;
        initInProgress = state.initInProgress;
        shellOpen = state.shellOpen != null ? state.shellOpen : mountedImage == null;
        shellOpenSticky = state.shellOpenSticky != null ? state.shellOpenSticky : shellOpen;
        mediaLifecycleInitialized = state.mediaLifecycleInitialized != null
            ? state.mediaLifecycleInitialized
            : mountedImage != null;
        secretUnlockStep = Math.clamp(state.secretUnlockStep, 0, 6);
        secretUnlocked = state.secretUnlocked;
        readSessionId = state.readSessionId;
        xaPcm = state.xaPcm == null ? new short[0] : state.xaPcm.clone();
        xaCurrentFile = state.xaCurrentFile & 0xFF;
        xaCurrentChannel = state.xaCurrentChannel & 0xFF;
        xaCurrentStreamSet = state.xaCurrentStreamSet;
        audioResetPending = state.audioResetPending;
        pendingAtv0 = state.pendingAtv0;
        pendingAtv1 = state.pendingAtv1;
        pendingAtv2 = state.pendingAtv2;
        pendingAtv3 = state.pendingAtv3;
        appliedAtv0 = state.appliedAtv0;
        appliedAtv1 = state.appliedAtv1;
        appliedAtv2 = state.appliedAtv2;
        appliedAtv3 = state.appliedAtv3;
        lastBufferedSector = SectorState.toSector(state.lastBufferedSector);
        if (lastBufferedSector == null && state.lastBufferedLba >= 0 && mountedImage != null) {
            lastBufferedSector = mountedImage.readSector(state.lastBufferedLba);
        }
        lastBufferedLba = state.lastBufferedLba;
        newestBufferedSector = SectorState.toSector(state.newestBufferedSector);
        int restoredNewestLba = state.newestBufferedLba == null
            ? state.lastBufferedLba
            : state.newestBufferedLba;
        if (newestBufferedSector == null && restoredNewestLba >= 0 && mountedImage != null) {
            newestBufferedSector = mountedImage.readSector(restoredNewestLba);
        }
        if (newestBufferedSector == null && state.newestBufferedLba == null) {
            newestBufferedSector = lastBufferedSector;
        }
        newestBufferedLba = restoredNewestLba;
        lastValidSubchannelLocation = state.lastValidSubchannelLocation == null
            ? null
            : Arrays.copyOf(state.lastValidSubchannelLocation, 8);
        activeSectorInterrupt = state.activeSectorInterrupt != null
            ? state.activeSectorInterrupt
            : (interruptFlags & 1) != 0;
        loadDataBlockQueue(state.pendingDataBlocks);
        activeDataBlock = state.activeDataBlock == null ? new byte[0] : state.activeDataBlock.clone();
        activeDataOffset = Math.clamp(state.activeDataOffset, 0, activeDataBlock.length);
        updateIrqLine();
    }

    private void loadImageForState(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            if (mountedImage != null) {
                mountedImage.close();
            }
            mountedImage = null;
            return;
        }
        Path path = Path.of(imagePath);
        if (mountedImage != null && mountedImage.path().equals(path)) {
            return;
        }
        try {
            CdImage restoredImage = CdImage.open(path);
            CdImage previousImage = mountedImage;
            mountedImage = restoredImage;
            if (previousImage != null) {
                previousImage.close();
            }
        } catch (IOException e) {
            if (mountedImage != null) {
                mountedImage.close();
            }
            mountedImage = null;
            Log.error("Failed to restore CD image " + imagePath, e);
        }
    }

    @Override
    public int read() {
        int b0 = readDataByte();
        int b1 = readDataByte();
        int b2 = readDataByte();
        int b3 = readDataByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    @Override
    public void write(int value) { }

    @Override
    public boolean dmaRequest() {
        return bufferReadRequested && activeDataOffset < activeDataBlock.length;
    }

    @Override
    public boolean dmaRequest(boolean fromRam) {
        return !fromRam && bufferReadRequested && activeDataOffset < activeDataBlock.length;
    }

    private void write1801(int value) {
        switch (registerIndex) {
            case 0 -> queueCommand(value);
            case 1 -> writeSoundMapData(value);
            case 2 -> { }                           // CI register (sound map config)
            case 3 -> pendingAtv2 = value & 0xFF;   // ATV2  R→R
        }
    }

    private void write1802(int value) {
        switch (registerIndex) {
            case 0 -> {
                if (parameterFifo.size() < PARAM_FIFO_CAPACITY) {
                    parameterFifo.add(value & 0xFF);
                }
            }
            case 1 -> interruptEnable = value & 0x1F;
            case 2 -> pendingAtv0 = value & 0xFF;   // ATV0  L→L
            case 3 -> pendingAtv3 = value & 0xFF;   // ATV3  R→L
        }
        updateIrqLine();
    }

    private void write1803(int value) {
        switch (registerIndex) {
            case 0 -> writeHostChipControl(value);
            case 1 -> writeHostClearControl(value);
            case 2 -> pendingAtv1 = value & 0xFF;   // ATV1  L→R
            case 3 -> {
                if ((value & 0x20) != 0) applyAudioMatrix();
                xaMuted = (value & 0x01) != 0;
            }
        }
    }

    // HCHPCTL (0x1F801803 write bank 0)
    private void writeHostChipControl(int value) {
        soundMapEnabled      = (value & 0x20) != 0;
        bufferWriteRequested = (value & 0x40) != 0;
        bufferReadRequested  = (value & 0x80) != 0;
        if (bufferReadRequested) {
            activatePendingDataBlock();
        } else {
            // Clearing BFRD rewinds the currently selected decoder buffer.
            activeDataOffset = 0;
        }
        updateIrqLine();
    }

    // HCLRCTL (0x1F801803 write bank 1)
    private void writeHostClearControl(int value) {
        int ackMask = value & HCLR_ACK_MASK;
        int oldInterruptFlags = interruptFlags;
        if (ackMask != 0) interruptFlags &= ~ackMask;
        if (activeSectorInterrupt
            && (oldInterruptFlags & 0x01) != 0
            && (interruptFlags & 0x01) == 0) {
            activeSectorInterrupt = false;
        }

        if (oldInterruptFlags != 0 && interruptFlags == 0) {
            updateIrqLine();
        }

        if ((value & 0x20) != 0) {
            // SMADPCLR clears only the manually-fed sound-map decoder buffer.
            soundMapWriteFifo.clear();
        }
        if ((value & 0x40) != 0) parameterFifo.clear();
        if ((value & 0x80) != 0) {
            resetSecretUnlock();
            mode            = MODE_SECTOR_SIZE;
            sectorSizeLatch = MODE_SECTOR_SIZE;
            resetTransportState();
        }
        dispatchPendingEventsIfPossible();
        dispatchQueuedCommandIfPossible();
        updateIrqLine();
    }

    private void writeSoundMapData(int value) {
        if (!bufferWriteRequested) return;
        if (soundMapWriteFifo.size() < SOUND_MAP_FIFO_CAPACITY) {
            soundMapWriteFifo.add(value & 0xFF);
        }
        updateIrqLine();
    }

    private int readDataByte() {
        if (activeDataBlock.length == 0) return 0;
        if (activeDataOffset >= activeDataBlock.length) {
            return 0;
        }
        if (!bufferReadRequested) return 0;
        int value = activeDataBlock[activeDataOffset++] & 0xFF;
        if (activeDataOffset >= activeDataBlock.length) {
            bufferReadRequested = false;
        }
        updateIrqLine();
        return value;
    }

    private int readResultByte() {
        int value;
        if (!responseFifo.isEmpty()) {
            value = responseFifo.remove();
        } else if (responseWindowValid) {
            value = lastResponseWindow[responseReadOffset & (RESPONSE_FIFO_CAPACITY - 1)];
        } else {
            value = 0;
        }
        responseReadOffset = (responseReadOffset + 1) & (RESPONSE_FIFO_CAPACITY - 1);
        return value & 0xFF;
    }

    private void queueCommand(int command) {
        // The host interface has a single command mailbox, not a FIFO.
        queuedCommand = command & 0xFF;
        dispatchQueuedCommandIfPossible();
    }

    private void dispatchQueuedCommandIfPossible() {
        if (queuedCommand < 0 || interruptFlags != 0 || commandBusyCycles > 0) return;
        int command   = queuedCommand;
        queuedCommand = -1;
        executeCommand(command);
    }

    private void executeCommand(int command) {
        currentCommandResponseCycles = firstResponseCycles(command);
        commandBusyCycles = Math.max(1,
            currentCommandResponseCycles - busyToIrqGapCycles());
        if (Log.isDebugEnabled()) {
            Log.debug("CD cmd 0x" + Integer.toHexString(command)
                + " params=" + parameterFifo.size()
                + " lba=" + currentLba + " target=" + targetLba
                + " mode=0x" + Integer.toHexString(mode)
                + " filter=" + filterFile + ":" + filterChannel);
        }

        if (!hasValidParameterCount(command, parameterFifo.size())) {
            queueErrorResponse(commandResponseCycles(),
                commandStatus() | STATUS_ERROR, 0x20, 5);
            parameterFifo.clear();
            return;
        }

        boolean clearParameters = true;
        switch (command) {
            case 0x00 -> queueErrorResponse(commandResponseCycles(),
                (commandStatus() + 1) & 0xFF, 0x40, 5);
            case 0x01 -> handleNop();
            case 0x02 -> handleSetloc();
            case 0x03 -> handlePlay();
            case 0x06, 0x1B -> handleRead();
            case 0x07 -> handleMotorOn();
            case 0x08 -> handleStop();
            case 0x09 -> handlePause();
            case 0x0A -> handleInit();
            case 0x0B -> handleMute(true);
            case 0x0C -> handleMute(false);
            case 0x0D -> handleSetfilter();
            case 0x0E -> handleSetmode();
            case 0x0F -> queueResponse(commandResponseCycles(), 3,
                commandStatus(), mode, 0x00, filterFile, filterChannel);
            case 0x10 -> handleGetlocL();
            case 0x11 -> handleGetlocP();
            case 0x12 -> handleSetSession();
            case 0x13 -> handleGettn();
            case 0x14 -> handleGettd();
            case 0x15 -> handleSeek(true);
            case 0x16 -> handleSeek(false);
            case 0x19 -> handleTest();
            case 0x1A -> handleGetId();
            case 0x1C -> handleReset();
            case 0x1D -> handleGetQ();
            case 0x1F -> {
                queueErrorResponse(commandResponseCycles(), 0x11, 0x40, 5);
                clearParameters = false;
            }
            case 0x1E -> handleReadToc();
            case 0x04 -> handleScan(true);
            case 0x05 -> handleScan(false);
            case 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57 ->
                handleSecretUnlock(command);
            case 0x58, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F -> {
                // These opcodes make the real HC05 execute data as code.
            }
            default -> queueErrorResponse(commandResponseCycles(),
                commandStatus() | STATUS_ERROR, 0x40, 5);
        }
        if (clearParameters) {
            parameterFifo.clear();
        }
    }

    private static boolean hasValidParameterCount(int command, int count) {
        return switch (command & 0xFF) {
            case 0x00, 0x01, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
                 0x0A, 0x0B, 0x0C, 0x0F, 0x10, 0x11, 0x13, 0x15,
                 0x16, 0x17, 0x18, 0x1A, 0x1B, 0x1E -> count == 0;
            case 0x02 -> count == 3;
            case 0x03 -> count <= 1;
            case 0x0D -> count == 2;
            case 0x0E, 0x12, 0x14 -> count == 1;
            case 0x19 -> count >= 1 && count <= 16;
            // C2 accepts Reset with any FIFO length (measured with 0..7).
            case 0x1C -> true;
            case 0x1D -> count == 2;
            case 0x1F -> count >= 6 && count <= 16;
            default -> true;
        };
    }


    // 0x01 Nop
    private void handleNop() {
        queueResponse(commandResponseCycles(), 3, commandStatus());
        if (!shellOpen) {
            shellOpenSticky = false;
        }
    }

    // 0x02 Setloc — set target LBA in BCD MSF
    private void handleSetloc() {
        int minuteBcd = parameterOrZero();
        int secondBcd = parameterOrZero();
        int frameBcd  = parameterOrZero();
        if (!isPackedBcd(minuteBcd) || !isBcdBelow(secondBcd, 60) || !isBcdBelow(frameBcd, 75)) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x10, 5);
            return;
        }
        int minute = fromBcd(minuteBcd);
        int second = fromBcd(secondBcd);
        int frame  = fromBcd(frameBcd);
        targetLba     = msfToLba(minute, second, frame);
        targetPending = true;
        queueResponse(commandResponseCycles(), 3, commandStatus());
    }

    // 0x03 Play
    private void handlePlay() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        int trackParameter = parameterOrZero();
        if (trackParameter != 0) {
            if (!isPackedBcd(trackParameter)) {
                queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x10, 5);
                return;
            }
            int track = fromBcd(trackParameter);
            int startLba = mountedImage.trackStartLba(track);
            if (startLba < 0) {
                CdImage.TrackPosition position = mountedImage.locateLba(Math.max(0, currentLba));
                int currentTrack = position == null ? mountedImage.firstTrackNumber() : position.trackNumber();
                startLba = mountedImage.trackStartLba(currentTrack);
            }
            targetLba     = startLba;
            targetPending = true;
        }
        stopReadPipeline(true);
        queueResponse(commandResponseCycles(), 3, commandStatus());
        boolean spindleWasRunning = motorOn;
        ensureMotorStarted();
        if (targetPending) {
            boolean needsSeek = targetLba != currentLba;
            targetPending = false;
            if (needsSeek) {
                startSeek(false, false, true, spindleWasRunning);
                return;
            }
        }
        startPlayAtCurrentLocation();
    }

    // 0x04 Forward / 0x05 Backward
    private void handleScan(boolean forward) {
        if (!hasDisc() || !playing || seeking || pendingPlayStart) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        playStepSectors = forward ? CDDA_SCAN_STEP_SECTORS : -CDDA_SCAN_STEP_SECTORS;
        if (playCyclesRemaining <= 0) {
            playCyclesRemaining = sectorCycles();
        }
        queueResponse(commandResponseCycles(), 3, commandStatus());
    }

    // 0x06 ReadN / 0x1B ReadS
    private void handleRead() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        if (!isReadAuthorized()) {
            // SCEx rejection happens before the normal command acknowledge.
            ensureMotorStarted();
            queueErrorResponse(commandResponseCycles(),
                commandStatus() | STATUS_ERROR, 0x40, 5);
            return;
        }
        int readLba = targetPending ? targetLba : currentLba;
        if (!targetPending && !reading && lastBufferedLba >= 0) {
            readLba = lastBufferedLba;
        }
        if (!mountedImage.isDataTrackLba(readLba) && (mode & MODE_CDDA) == 0) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x40, 5);
            return;
        }
        boolean hadTarget = targetPending;
        if (reading && !hadTarget) {
            queueResponse(commandResponseCycles(), 3, commandStatus());
            return;
        }
        boolean spindleWasRunning = motorOn;
        ensureMotorStarted();
        stopReadPipeline(true);
        if (!hadTarget) {
            currentLba = readLba;
        }
        queueResponse(commandResponseCycles(), 3, commandStatus());
        if (targetPending) {
            boolean needsSeek = targetLba != currentLba;
            targetPending = false;
            if (needsSeek) {
                startSeek(true, false, false, spindleWasRunning);
                return;
            }
        }
        if (!reading) {
            reading               = true;
            readCyclesRemaining   = sectorCycles();
        }
    }

    // 0x07 MotorOn — error if motor already on
    private void handleMotorOn() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        if (motorOn) {
            queueErrorResponse(commandResponseCycles(),
                commandStatus() | STATUS_ERROR, 0x20, 5);
            return;
        }
        int initialStatus = commandStatus();
        queueResponse(commandResponseCycles(), 3, initialStatus);
        motorOn = true;
        queueResponse(sampleSpinUpCycles(), 2, commandStatus());
    }

    // 0x08 Stop
    private void handleStop() {
        boolean wasActive = motorOn || reading || playing || seeking;
        stopReadPipeline(true);
        seeking              = false;
        seekError            = false;
        seekFailurePending   = false;
        seekCyclesRemaining  = 0;
        currentLba           = 0;
        targetLba            = 0;
        targetPending        = false;
        queueResponse(commandResponseCycles(), 3, commandStatus());
        motorOn = false;
        rotationalPhaseCycles = 0;
        queueResponse(stopDelayCycles(wasActive), 2, commandStatus());
    }

    // 0x09 Pause
    private void handlePause() {
        if (seeking) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        int firstStatus = commandStatus();
        boolean wasActive = reading || playing;
        stopReadPipeline(false);
        queueResponse(commandResponseCycles(), 3, firstStatus);
        queueResponse(pauseDelayCycles(wasActive), 2, commandStatus());
    }

    // 0x0A Init
    private void handleInit() {
        if (initInProgress) {
            return;
        }
        boolean spindleWasRunning = motorOn;
        stopReadPipeline(true);
        seeking         = false;
        seekError       = false;
        seekFailurePending = false;
        motorOn         = true;
        mode            = MODE_SECTOR_SIZE;
        sectorSizeLatch = MODE_SECTOR_SIZE;
        filterFile      = 0;
        filterChannel   = 0;
        streamMuted     = false;
        xaMuted         = false;
        initInProgress  = true;
        queueResponse(commandResponseCycles(), 3, commandStatus());
        int initSecond = sampleFirmwareTiming(driveProfile.initSecondResponse(), 72);
        if (!spindleWasRunning) {
            initSecond = Math.max(initSecond, sampleSpinUpCycles());
        }
        queueResponse(initSecond, 2, commandStatus());
    }

    // 0x0B Mute / 0x0C Demute
    private void handleMute(boolean mute) {
        streamMuted = mute;
        queueResponse(commandResponseCycles(), 3, commandStatus());
    }

    // 0x0D Setfilter
    private void handleSetfilter() {
        int newFile    = parameterOrZero();
        int newChannel = parameterOrZero();
        // Setfilter only releases the automatic stream lock.
        if (newFile != filterFile || newChannel != filterChannel) {
            resetCurrentXaStream();
        }
        filterFile    = newFile;
        filterChannel = newChannel;
        queueResponse(commandResponseCycles(), 3, commandStatus());
    }

    // 0x0E Setmode
    private void handleSetmode() {
        mode = parameterOrZero();
        if ((mode & MODE_IGNORE) == 0) {
            sectorSizeLatch = mode & MODE_SECTOR_SIZE;
        }
        queueResponse(commandResponseCycles(), 3, commandStatus());
    }

    private void handleGetlocL() {
        if (seekError) {
            queueErrorResponse(commandResponseCycles(), commandStatus(), 0x80, 5);
            return;
        }
        if (!hasDisc() || newestBufferedSector == null || seeking || playing
            || !mountedImage.isDataTrackLba(newestBufferedLba)) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        byte[] raw      = newestBufferedSector.raw2352();
        int[]  response = new int[8];
        for (int i = 0; i < response.length; i++) {
            int offset = 12 + i;
            response[i] = offset < raw.length ? Byte.toUnsignedInt(raw[offset]) : 0;
        }
        queueResponse(commandResponseCycles(), 3, response);
    }

    // 0x11 GetlocP — returns track/index + relative/absolute MSF
    private void handleGetlocP() {
        if (seekError) {
            queueErrorResponse(commandResponseCycles(), commandStatus(), 0x80, 5);
            return;
        }
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        queueResponse(commandResponseCycles(), 3, buildGetlocPResponse());
    }

    // 0x12 SetSession
    private void handleSetSession() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        if (reading || playing) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        int session = parameterOrZero();
        if (session == 0) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x10, 5);
            return;
        }

        stopReadPipeline(true);
        boolean spindleWasRunning = motorOn;
        ensureMotorStarted();
        targetLba            = 0;
        targetPending        = false;
        seeking              = true;
        int seekCycles = seekDurationCycles(targetLba, spindleWasRunning);
        seekCyclesRemaining  = seekCycles;
        queueResponse(commandResponseCycles(), 3, commandStatus());
        if (session == 1) {
            queueEvent(new PendingEvent(seekCycles, EventType.SEEK_COMPLETE,
                2, 0, readSessionId, new int[]{ seekCompletionStatus(false) }));
            return;
        }

        int[] error = { STATUS_MOTOR_ON | STATUS_SEEK_ERROR, 0x40 };
        queueEvent(new PendingEvent(seekCycles, EventType.SESSION_ERROR_COMPLETE,
            5, -1, readSessionId, error));
        queueEvent(new PendingEvent(seekCycles, EventType.RESPONSE,
            5, -1, readSessionId, error));
    }

    // 0x13 GetTN
    private void handleGettn() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        queueResponse(commandResponseCycles(), 3,
            commandStatus(),
            toBcd(mountedImage.firstTrackNumber()),
            toBcd(mountedImage.lastTrackNumber()));
    }

    // 0x14 GetTD
    private void handleGettd() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        int trackParam = parameterOrZero();
        if (trackParam != 0 && !isPackedBcd(trackParam)) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x10, 5);
            return;
        }
        int track = fromBcd(trackParam);
        int lba;
        if (track == 0) {
            lba = mountedImage.leadOutLba();
        } else {
            lba = mountedImage.trackStartLba(track);
            if (lba < 0) {
                queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x10, 5);
                return;
            }
        }
        int absolute = lba + LBA_MSF_OFFSET;
        queueResponse(commandResponseCycles(), 3,
            commandStatus(),
            toBcd((absolute / (75 * 60)) % 100),
            toBcd((absolute / 75) % 60));
    }

    // 0x15 SeekL / 0x16 SeekP
    private void handleSeek(boolean logical) {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        stopReadPipeline(true);
        boolean spindleWasRunning = motorOn;
        ensureMotorStarted();
        queueResponse(commandResponseCycles(), 3, commandStatus());
        // Clear the Setloc position after SeekL/SeekP.
        targetPending = false;
        seekError = false;
        boolean outOfRange = targetLba < 0 || targetLba >= mountedImage.leadOutLba();
        int seekCycles = seekDurationCycles(targetLba, spindleWasRunning);
        if (outOfRange || (logical && !mountedImage.isDataTrackLba(targetLba))) {
            seeking             = true;
            seekCyclesRemaining = seekCycles;
            seekFailurePending  = true;
            queueEvent(new PendingEvent(seekCycles, EventType.SEEK_ERROR_COMPLETE,
                5, targetLba, readSessionId,
                new int[]{ STATUS_SEEK_ERROR, 0x04 }));
            return;
        }
        startSeek(logical, true, false, spindleWasRunning);
    }

    // 0x19 Test
    private void handleTest() {
        int sub = parameterOrZero();
        switch (sub) {
            // 19h,20h returns the drive firmware identity.
            case 0x20 -> queueResponse(commandResponseCycles(), 3,
                driveProfile.firmwareYearBcd(),
                driveProfile.firmwareMonthBcd(),
                driveProfile.firmwareDayBcd(),
                driveProfile.controllerFirmwareRevisionByte());
            // POS0 is the home switch; DOOR is the lid switch.
            case 0x21 -> queueResponse(commandResponseCycles(), 3,
                (currentLba == 0 && !seeking ? 0x01 : 0)
                    | (shellOpen ? 0x02 : 0));
            // 19h,22h → region string
            case 0x22 -> queueAsciiResponse(driveProfile.testRegionIdentity());
            case 0x23 -> queueAsciiResponse(driveProfile.testServoIdentity());
            case 0x24 -> queueAsciiResponse(driveProfile.testDspIdentity());
            case 0x25 -> queueAsciiResponse(driveProfile.testDecoderIdentity());
            default -> queueErrorResponse(commandResponseCycles(), 0x11, 0x10, 5);
        }
    }

    private void handleGetId() {
        boolean spindleWasRunning = motorOn;
        int secondResponseCycles = sampleGetIdSecondResponseCycles(spindleWasRunning);
        if (shellOpen) {
            queueErrorResponse(commandResponseCycles(),
                STATUS_SHELL_OPEN | STATUS_ERROR, 0x80, 5);
            return;
        }
        if (!hasDisc()) {
            queueResponse(commandResponseCycles(), 3, commandStatus());
            queueEvent(new PendingEvent(secondResponseCycles, EventType.RESPONSE, 5, -1, readSessionId,
                new int[]{ 0x08, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 }));
            return;
        }
        queueResponse(commandResponseCycles(), 3, commandStatus());
        // Disc identification requires the spindle to be running.
        motorOn = true;

        if (mountedImage.isAudioOnly()) {
            // Audio-only disc → INT5(0x0A, 0x90)
            queueEvent(new PendingEvent(secondResponseCycles, EventType.RESPONSE, 5, -1, readSessionId,
                new int[]{ 0x0A, 0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 }));
            return;
        }

        int discType = mountedImage.discTypeCode();
        boolean licensedForConsole = mountedImage.isLicensedPlayStationDisc()
            && driveProfile.acceptsLicenseRegion(mountedImage.regionCode());
        if (!licensedForConsole) {
            int flags = 0x80 | (mountedImage.hasAudioTracks() ? 0x10 : 0);
            queueEvent(new PendingEvent(secondResponseCycles, EventType.RESPONSE, 5, -1, readSessionId,
                new int[]{ commandStatus() | STATUS_ID_ERROR, flags, discType, 0,
                    0, 0, 0, 0 }));
            return;
        }

        // Licensed data disc → INT2(stat, flags=0, type, atip=0, "SCEx")
        String region = mountedImage.regionCode();
        queueResponse(secondResponseCycles, 2,
            commandStatus(),
            0x00,
            discType,
            0x00,
            region.charAt(0), region.charAt(1), region.charAt(2), region.charAt(3));
    }

    // 0x1C Reset — INT3 then ~1/8-second delay before controller is ready again
    private void handleReset() {
        resetSecretUnlock();
        queueResponse(commandResponseCycles(), 3, commandStatus());
        queueEvent(new PendingEvent(RESET_CYCLES, EventType.RESET_COMPLETE,
            0, -1, readSessionId, null));
    }

    // C1+ Europe/USA firmware accepts commands 50h..56h in order.
    private void handleSecretUnlock(int command) {
        if (command == 0x57) {
            resetSecretUnlock();
        } else if (command == 0x50) {
            secretUnlocked = false;
            secretUnlockStep = parameterFifo.isEmpty() ? 1 : 0;
        } else {
            int expectedStep = command - 0x50;
            if (secretUnlockStep == expectedStep
                && secretParameterMatches(expectedSecretParameter(expectedStep))) {
                if (command == 0x56) {
                    secretUnlocked = true;
                    secretUnlockStep = 0;
                } else {
                    secretUnlockStep++;
                }
            } else {
                resetSecretUnlock();
            }
        }
        queueErrorResponse(commandResponseCycles(), 0x11, 0x40, 5);
    }

    private String expectedSecretParameter(int step) {
        return switch (step) {
            case 1 -> "Licensed by";
            case 2 -> "Sony";
            case 3 -> "Computer";
            case 4 -> "Entertainment";
            case 5 -> "of America";
            case 6 -> "";
            default -> throw new IllegalArgumentException("Invalid SecretUnlock step " + step);
        };
    }

    private boolean secretParameterMatches(String expected) {
        if (parameterFifo.size() != expected.length()) {
            return false;
        }
        int index = 0;
        for (int value : parameterFifo) {
            if ((value & 0xFF) != expected.charAt(index++)) {
                return false;
            }
        }
        return true;
    }

    private void resetSecretUnlock() {
        secretUnlockStep = 0;
        secretUnlocked = false;
    }

    private void handleGetQ() {
        int adr = parameterOrZero();
        int point = parameterOrZero();
        if (!hasDisc() || !motorOn) {
            queueErrorResponse(commandResponseCycles(),
                commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }

        byte[] subQ = mountedImage.tocSubchannelQ(adr, point);
        queueResponse(commandResponseCycles(), 3, commandStatus());
        if (subQ == null) {
            int timeout = 6 * hardwareProfile.cpuClockHz();
            queueErrorResponse(timeout, commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }

        int[] response = new int[11];
        for (int i = 0; i < subQ.length; i++) response[i] = subQ[i] & 0xFF;
        response[10] = 0;
        queueResponse(sampleReadTocSecondResponseCycles(true), 2, response);
    }

    // 0x1E ReadTOC
    private void handleReadToc() {
        if (!hasDisc()) {
            queueErrorResponse(commandResponseCycles(), commandStatus() | STATUS_ERROR, 0x80, 5);
            return;
        }
        boolean spindleWasRunning = motorOn;
        ensureMotorStarted();
        queueResponse(commandResponseCycles(), 3, commandStatus());
        queueResponse(sampleReadTocSecondResponseCycles(spindleWasRunning), 2, commandStatus());
    }

    private void startSeek(
        boolean logical,
        boolean issueCompletionInterrupt,
        boolean enterPlayState,
        boolean spindleWasRunning
    ) {
        if (mountedImage != null) {
            mountedImage.prefetch(targetLba, HOST_READ_AHEAD_SECTORS);
        }
        int seekCycles = seekDurationCycles(targetLba, spindleWasRunning);
        seeking             = true;
        seekError           = false;
        seekFailurePending  = false;
        seekCyclesRemaining = seekCycles;
        pendingReadStart    = !issueCompletionInterrupt && !enterPlayState;
        pendingPlayStart    = !issueCompletionInterrupt && enterPlayState;
        if (issueCompletionInterrupt) {
            queueEvent(new PendingEvent(seekCycles, EventType.SEEK_COMPLETE,
                2, targetLba, readSessionId,
                new int[]{ seekCompletionStatus(logical) }));
        }
    }

    private int seekCompletionStatus(boolean logical) {
        int status = commandStatus() & ~STATUS_SEEKING;
        if (!logical) status &= ~STATUS_SEEK_ERROR;
        return status;
    }

    private void startPlayAtCurrentLocation() {
        reading             = false;
        playing             = true;
        pendingPlayStart    = false;
        playCyclesRemaining = sectorCycles();
        playStepSectors     = 1;
        playReportCountdown = CDDA_REPORT_INTERVAL;
    }

    private void advancePlayPosition() {
        deliverCddaAudio(currentLba);
        int nextLba = currentLba + playStepSectors;
        if (playStepSectors < 0) {
            currentLba = Math.max(0, nextLba);
            if (currentLba == 0) {
                playStepSectors = 1;
            }
            queuePlayReportIfNeeded(currentLba);
            return;
        }

        int leadOut = mountedImage.leadOutLba();
        int trackEnd = currentTrackEndLba(currentLba);
        if ((mode & MODE_AUTOPAUSE) != 0 && trackEnd >= 0 && nextLba >= trackEnd) {
            currentLba = trackEnd;
            pausePlayWithInterrupt();
            return;
        }
        if (nextLba >= leadOut) {
            currentLba = leadOut;
            stopPlayAtDiscEnd();
            return;
        }

        currentLba = nextLba;
        queuePlayReportIfNeeded(currentLba);
    }

    private void deliverCddaAudio(int lba) {
        if (mountedImage == null) {
            return;
        }
        short[] pcm = decodeCddaSector(lba);
        if (pcm.length == 0) {
            return;
        }
        if ((mode & MODE_SPEED) != 0) {
            short[] decimated = new short[(pcm.length / 4) * 2];
            for (int source = 0, target = 0; target < decimated.length; source += 4) {
                decimated[target++] = pcm[source];
                decimated[target++] = pcm[source + 1];
            }
            pcm = decimated;
        }
        if (streamMuted) {
            pcm = new short[pcm.length];
        } else {
            pcm = applyAudioMix(pcm);
        }
        xaPcm = appendXaPcm(pcm);
    }

    private short[] decodeCddaSector(int lba) {
        short[] silence = new short[588 * 2];
        if (mountedImage.isDataTrackLba(lba)) {
            return silence;
        }
        CdSector sector = mountedImage.readSector(lba);
        if (sector == null) {
            return new short[0];
        }
        byte[] raw = sector.raw2352();
        if (raw.length < 2352) {
            return silence;
        }
        short[] pcm = new short[588 * 2];
        int out = 0;
        for (int i = 0; i + 3 < 2352; i += 4) {
            pcm[out++] = (short) ((raw[i] & 0xFF) | (raw[i + 1] << 8));
            pcm[out++] = (short) ((raw[i + 2] & 0xFF) | (raw[i + 3] << 8));
        }
        return pcm;
    }

    private int currentTrackEndLba(int lba) {
        if (mountedImage == null) return -1;
        CdImage.TrackPosition position = mountedImage.locateLba(Math.max(0, lba));
        if (position == null) return mountedImage.leadOutLba();
        int end = mountedImage.trackEndLba(position.trackNumber());
        return end >= 0 ? end : mountedImage.leadOutLba();
    }

    private void pausePlayWithInterrupt() {
        playing             = false;
        playCyclesRemaining = 0;
        playStepSectors     = 1;
        playReportCountdown = CDDA_REPORT_INTERVAL;
        queueResponse(0, 4, commandStatus());
    }

    private void stopPlayAtDiscEnd() {
        playing             = false;
        motorOn             = false;
        playCyclesRemaining = 0;
        playStepSectors     = 1;
        playReportCountdown = CDDA_REPORT_INTERVAL;
        queueResponse(0, 4, commandStatus());
    }

    private void queuePlayReportIfNeeded(int lba) {
        if ((mode & MODE_REPORT) == 0) {
            return;
        }
        if (--playReportCountdown > 0) {
            return;
        }
        playReportCountdown = CDDA_REPORT_INTERVAL;
        queueEvent(new PendingEvent(0, EventType.PLAY_REPORT,
            1, -1, readSessionId, buildPlayReportResponse(lba)));
    }

    private int[] buildPlayReportResponse(int lba) {
        int[] loc = buildGetlocPResponseAt(lba);
        int absoluteFrame = fromBcd(loc[7]);
        boolean absolute = absoluteFrame % 20 == 0;
        if (absolute) {
            return new int[]{ commandStatus(), loc[0], loc[1], loc[5], loc[6], loc[7], 0x00, 0x00 };
        }
        return new int[]{ commandStatus(), loc[0], loc[1], loc[2], loc[3] | 0x80, loc[4], 0x00, 0x00 };
    }

    private void serviceMechanicalState(int cycles) {
        int remainingCycles = Math.max(0, cycles);
        if (seeking) {
            int seekElapsed = Math.min(remainingCycles, Math.max(0, seekCyclesRemaining));
            seekCyclesRemaining -= seekElapsed;
            remainingCycles -= seekElapsed;
            if (seekCyclesRemaining <= 0) {
                if (!seekFailurePending) {
                    currentLba = targetLba;
                }
                seeking    = false;
                if (pendingReadStart) {
                    pendingReadStart     = false;
                    reading             = true;
                    readCyclesRemaining = sectorCycles();
                }
                if (pendingPlayStart) {
                    pendingPlayStart = false;
                    startPlayAtCurrentLocation();
                }
            }
        }
        if (remainingCycles <= 0) {
            return;
        }
        if (reading && mountedImage != null && !seeking) {
            readCyclesRemaining -= remainingCycles;
            while (readCyclesRemaining <= 0) {
                receiveSector(currentLba++);
                readCyclesRemaining += sectorCycles();
                // At 1x speed only one sector fires per tick window.
                if ((mode & MODE_SPEED) == 0) break;
            }
        }
        if (playing && mountedImage != null && !seeking) {
            playCyclesRemaining -= remainingCycles;
            while (playCyclesRemaining <= 0 && playing) {
                advancePlayPosition();
                playCyclesRemaining += sectorCycles();
                if ((mode & MODE_SPEED) == 0) break;
            }
        }
    }

    private void queueResponse(int delayCycles, int irqType, int... bytes) {
        int[] payload = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) payload[i] = bytes[i] & 0xFF;
        queueEvent(new PendingEvent(Math.max(0, delayCycles),
            EventType.RESPONSE, irqType & 0x1F, -1, readSessionId, payload));
    }

    private void queueErrorResponse(int delayCycles, int status, int errorCode, int irqType) {
        queueEvent(new PendingEvent(Math.max(0, delayCycles),
            EventType.RESPONSE, irqType & 0x1F, -1, readSessionId,
            new int[]{ status & 0xFF, errorCode & 0xFF }));
    }

    private void queueAsciiResponse(String text) {
        queueResponse(commandResponseCycles(), 3,
            text.chars().map(c -> c & 0xFF).toArray());
    }

    private void queueEvent(PendingEvent event) {
        event.dueCycle = eventClockCycles + Math.max(0, event.cyclesRemaining);
        pendingEvents.add(event);
    }

    private void receiveSector(int lba) {
        if (mountedImage != null) {
            mountedImage.prefetch(lba + 1, HOST_READ_AHEAD_SECTORS);
        }
        CdSector sector = latchNewestSector(lba);
        // XA decoding is part of the drive-side sector pipeline.
        if (sector != null && consumeXaAudioSector(sector)) {
            return;
        }
        queueSectorReadyEvent(lba);
    }

    private void queueSectorReadyEvent(int lba) {
        PendingEvent first = null;
        PendingEvent newest = null;
        for (PendingEvent event : pendingEvents) {
            if (event.type != EventType.SECTOR_READY || event.sessionId != readSessionId) {
                continue;
            }
            if (first == null) {
                first = event;
            } else {
                newest = event;
            }
        }

        if (first == null) {
            queueEvent(new PendingEvent(0, EventType.SECTOR_READY, 1,
                lba, readSessionId, null));
        } else if (newest == null) {
            queueEvent(new PendingEvent(0, EventType.SECTOR_READY, 1,
                lba, readSessionId, null));
        } else {
            newest.lba = lba;
        }
    }

    private void dispatchPendingEventsIfPossible() {
        while (!pendingEvents.isEmpty()) {
            PendingEvent head = pendingEvents.getFirst();
            if (head.dueCycle > eventClockCycles || interruptFlags != 0) return;
            pendingEvents.removeFirst();
            deliverEvent(head);
            if (interruptFlags != 0) return;
        }
    }

    private void deliverEvent(PendingEvent event) {
        // Stale mechanical events from an old session are silently dropped.
        if (event.type != EventType.RESPONSE
            && event.type != EventType.RESET_COMPLETE
            && event.sessionId != readSessionId) {
            return;
        }
        switch (event.type) {
            case RESPONSE, PLAY_REPORT -> {
                loadResponse(event.bytes);
                interruptFlags = event.irqType & 0x1F;
                if (initInProgress && event.irqType == 2) {
                    initInProgress = false;
                }
            }
            case SEEK_COMPLETE -> {
                seeking    = false;
                seekError  = false;
                seekFailurePending = false;
                currentLba = event.lba;
                latchSectorHeader(event.lba);
                loadResponse(event.bytes);
                interruptFlags = event.irqType & 0x1F;
            }
            case SEEK_ERROR_COMPLETE -> {
                seeking             = false;
                seekFailurePending  = false;
                seekError           = true;
                seekCyclesRemaining = 0;
                motorOn             = false;
                loadResponse(event.bytes);
                interruptFlags = event.irqType & 0x1F;
            }
            case SESSION_ERROR_COMPLETE -> {
                seeking             = false;
                seekCyclesRemaining = 0;
                motorOn             = false;
                loadResponse(event.bytes);
                interruptFlags = event.irqType & 0x1F;
            }
            case SECTOR_READY -> {
                if (pushSectorData(event.lba)) {
                    loadResponse(new int[]{ commandStatus() });
                    interruptFlags = event.irqType & 0x1F;
                    activeSectorInterrupt = true;
                }
            }
            case RESET_COMPLETE -> {
                // The controller finishes its internal reset; motor stays off.
                resetTransportState();
                motorOn = false;
            }
        }
        updateIrqLine();
    }

    private void loadResponse(int[] bytes) {
        responseFifo.clear();
        Arrays.fill(lastResponseWindow, 0);
        responseReadOffset = 0;
        responseWindowValid = bytes != null;
        if (bytes == null) return;
        for (int value : bytes) {
            if (responseFifo.size() >= RESPONSE_FIFO_CAPACITY) break;
            int byteValue = value & 0xFF;
            lastResponseWindow[responseFifo.size()] = byteValue;
            responseFifo.add(byteValue);
        }
    }

    private boolean pushSectorData(int lba) {
        if (mountedImage == null) return false;
        CdSector sector = mountedImage.readSector(lba);
        if (sector == null) {
            reading = false;
            queueErrorResponse(sampleFirmwareTiming(driveProfile.firstResponse(motorOn), 36),
                commandStatus() | STATUS_ERROR, 0x80, 5);
            return false;
        }
        lastBufferedSector = sector;
        lastBufferedLba    = lba;

        if (!shouldDeliverDataSector(sector)) return false;

        pendingDataBlocks.clear();
        pendingDataBlocks.addLast(sectorPayload(sector));
        return true;
    }

    private void latchSectorHeader(int lba) {
        if (mountedImage == null || lba < 0 || lba >= mountedImage.leadOutLba()) {
            return;
        }
        CdSector sector = mountedImage.readSector(lba);
        if (sector != null) {
            lastBufferedSector = sector;
            lastBufferedLba = lba;
            newestBufferedSector = sector;
            newestBufferedLba = lba;
            latchSubchannelPosition(lba);
        }
    }

    private CdSector latchNewestSector(int lba) {
        if (mountedImage == null || lba < 0 || lba >= mountedImage.leadOutLba()) {
            return null;
        }
        CdSector sector = mountedImage.readSector(lba);
        if (sector != null) {
            newestBufferedSector = sector;
            newestBufferedLba = lba;
            latchSubchannelPosition(lba);
        }
        return sector;
    }

    // The decoder publishes a new position only after a valid Q checksum.
    private void latchSubchannelPosition(int lba) {
        CdImage.SubchannelQ subchannel = mountedImage == null ? null : mountedImage.subchannelQ(lba);
        if (subchannel == null || !subchannel.checksumValid()) {
            return;
        }
        byte[] q = subchannel.data();
        lastValidSubchannelLocation = new int[]{
            Byte.toUnsignedInt(q[1]),
            Byte.toUnsignedInt(q[2]),
            Byte.toUnsignedInt(q[3]),
            Byte.toUnsignedInt(q[4]),
            Byte.toUnsignedInt(q[5]),
            Byte.toUnsignedInt(q[7]),
            Byte.toUnsignedInt(q[8]),
            Byte.toUnsignedInt(q[9])
        };
    }

    private boolean consumeXaAudioSector(CdSector sector) {
        if ((mode & MODE_XA_ADPCM) == 0 || !isXaAudioSector(sector)) return false;
        if (xaFilterEnabled() && !xaFilterMatches(sector)) return true;

        byte[] raw = sector.raw2352();
        int file = Byte.toUnsignedInt(raw[16]);
        int channel = Byte.toUnsignedInt(raw[17]);
        int subMode = Byte.toUnsignedInt(raw[18]);

        if (!xaCurrentStreamSet) {
            if (channel == 0xFF && (!xaFilterEnabled() || filterChannel != 0xFF)) {
                return true;
            }
            xaCurrentFile = file;
            xaCurrentChannel = channel;
            xaCurrentStreamSet = true;
        } else if (file != xaCurrentFile || channel != xaCurrentChannel) {
            return true;
        }
        if ((subMode & 0x80) != 0) {
            xaCurrentFile = 0;
            xaCurrentChannel = 0;
            xaCurrentStreamSet = false;
        }

        // The CD decoder hands sectors to a small streaming FIFO.
        int sectorFrames = xaSectorOutputFrames(raw[19] & 0xFF);
        int pendingFrames = queuedAudioFrames.getAsInt() + xaPcm.length / 2;
        if (pendingFrames + sectorFrames > sectorFrames * XA_AUDIO_DECODER_SLOTS) {
            return true;
        }

        short[] pcm = xaDecoder.decodeSector(sector);
        if (streamMuted || xaMuted) {
            pcm = new short[pcm.length];
        } else {
            pcm = applyAudioMix(pcm);
        }
        xaPcm = appendXaPcm(pcm);
        return true;
    }

    private static int xaSectorOutputFrames(int codingInfo) {
        boolean stereo = (codingInfo & 0x01) != 0;
        boolean halfRate = (codingInfo & 0x04) != 0;
        boolean eightBit = (codingInfo & 0x10) != 0;
        int soundUnitsPerGroup = eightBit ? 4 : 8;
        int sourceFrames = 18 * 28 * soundUnitsPerGroup / (stereo ? 2 : 1);
        return halfRate ? sourceFrames * 7 / 3 : sourceFrames * 7 / 6;
    }

    private boolean shouldDeliverDataSector(CdSector sector) {
        return !(isXaAudioSector(sector) && xaFilterEnabled() && !xaFilterMatches(sector));
    }

    private byte[] sectorPayload(CdSector sector) {
        boolean wholeSector =
            ((mode & MODE_IGNORE) != 0 ? sectorSizeLatch : (mode & MODE_SECTOR_SIZE)) != 0;
        if (!wholeSector) {
            return Arrays.copyOf(sector.userData(), 0x800);
        }
        byte[] raw = sector.raw2352();
        // Whole-sector mode: deliver bytes 12-2351 (skip sync header, keep rest)
        return raw.length <= 12 ? new byte[0] : Arrays.copyOfRange(raw, 12, raw.length);
    }

    private void activatePendingDataBlock() {
        byte[] next = pendingDataBlocks.pollFirst();
        if (next == null || next.length == 0) {
            return;
        }
        activeDataBlock  = next;
        activeDataOffset = 0;
    }

    private int hostStatus() {
        int result = registerIndex & HSTS_INDEX_MASK;
        if (xaPcm.length > 0)                   result |= HSTS_ADPBUSY;
        if (parameterFifo.isEmpty())             result |= HSTS_PRMEMPT;
        if (parameterFifo.size() < PARAM_FIFO_CAPACITY) result |= HSTS_PRMWRDY;
        if (!responseFifo.isEmpty())             result |= HSTS_RSLRRDY;
        if (bufferReadRequested && activeDataOffset < activeDataBlock.length
            || (bufferWriteRequested && !soundMapWriteFifo.isEmpty()))
            result |= HSTS_DRQSTS;
        if (queuedCommand >= 0 || commandBusyCycles > 0)
            result |= HSTS_BUSYSTS;
        return result;
    }

    private int commandStatus() {
        int result = 0;
        if (motorOn)  result |= STATUS_MOTOR_ON;
        if (reading)  result |= STATUS_READING;
        else if (seeking) result |= STATUS_SEEKING;
        else if (playing) result |= STATUS_PLAYING;
        if (seekError) result |= STATUS_SEEK_ERROR;
        if ((mode & MODE_IGNORE) != 0) result |= STATUS_ID_ERROR;
        if (shellOpen || shellOpenSticky) result |= STATUS_SHELL_OPEN;
        return result;
    }

    private int[] buildGetlocPResponse() {
        if (lastValidSubchannelLocation != null) {
            return lastValidSubchannelLocation.clone();
        }
        int lba = (playing || seeking || lastBufferedLba < 0) ? currentLba : lastBufferedLba;
        return buildGetlocPResponseAt(lba);
    }

    private int[] buildGetlocPResponseAt(int lba) {
        boolean leadOut = mountedImage != null && lba >= mountedImage.leadOutLba();
        CdImage.TrackPosition pos = mountedImage == null ? null
            : mountedImage.locateLba(Math.max(0, lba));
        int track    = pos == null ? 1 : pos.trackNumber();
        int index    = pos == null ? 1 : pos.indexNumber();
        int relative = leadOut
            ? Math.max(0, lba - mountedImage.leadOutLba())
            : pos == null ? Math.max(0, lba) : pos.relativeLba();
        int absolute = Math.max(0, lba) + LBA_MSF_OFFSET;
        return new int[]{
            leadOut ? 0xAA : toBcd(track),
            toBcd(index),
            toBcd((relative / (75 * 60)) % 100),
            toBcd((relative / 75) % 60),
            toBcd(relative % 75),
            toBcd((absolute / (75 * 60)) % 100),
            toBcd((absolute / 75) % 60),
            toBcd(absolute % 75)
        };
    }

    private boolean xaFilterEnabled() { return (mode & MODE_FILTER) != 0; }

    private boolean xaFilterMatches(CdSector sector) {
        byte[] raw = sector.raw2352();
        if (raw.length < 20) return false;
        return Byte.toUnsignedInt(raw[16]) == filterFile
            && Byte.toUnsignedInt(raw[17]) == filterChannel;
    }

    private boolean isXaAudioSector(CdSector sector) {
        byte[] raw = sector.raw2352();
        if (raw.length < 20) return false;
        int modeByte = raw[15] & 0xFF;
        int subMode  = raw[18] & 0xFF;
        // Mode 2, submode audio bit (bit 2) and real-time bit (bit 6) both set.
        return modeByte == 2 && (subMode & 0x44) == 0x44;
    }

    private void applyAudioMatrix() {
        appliedAtv0 = pendingAtv0 & 0xFF;
        appliedAtv1 = pendingAtv1 & 0xFF;
        appliedAtv2 = pendingAtv2 & 0xFF;
        appliedAtv3 = pendingAtv3 & 0xFF;
    }

    private short[] applyAudioMix(short[] pcm) {
        if (pcm.length < 2) return pcm;
        short[] mixed = new short[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int left  = pcm[i];
            int right = pcm[i + 1];
            int mixedLeft  = ((left * appliedAtv0) + (right * appliedAtv3)) >> 7;
            int mixedRight = ((left * appliedAtv1) + (right * appliedAtv2)) >> 7;
            mixed[i]     = (short) Math.clamp(mixedLeft,  Short.MIN_VALUE, Short.MAX_VALUE);
            mixed[i + 1] = (short) Math.clamp(mixedRight, Short.MIN_VALUE, Short.MAX_VALUE);
        }
        return mixed;
    }

    private short[] appendXaPcm(short[] pcm) {
        if (pcm.length == 0) return xaPcm;
        if (xaPcm.length == 0) return pcm;
        short[] combined = new short[xaPcm.length + pcm.length];
        System.arraycopy(xaPcm, 0, combined, 0, xaPcm.length);
        System.arraycopy(pcm,   0, combined, xaPcm.length, pcm.length);
        return combined;
    }

    private int parameterOrZero() {
        return parameterFifo.isEmpty() ? 0 : parameterFifo.remove();
    }

    private void ensureMotorStarted() { motorOn = true; }

    // Advance the HC05 controller clock without rounding each scheduler slice.
    private void advanceControllerClock(int cpuCycles) {
        if (cpuCycles == 1) {
            firmwareClockRemainder += hardwareProfile.cdControllerSystemClockHz();
            if (firmwareClockRemainder >= hardwareProfile.cpuClockHz()) {
                firmwareClockRemainder -= hardwareProfile.cpuClockHz();
                firmwareControllerTicks++;
            }
            return;
        }
        long scaled = firmwareClockRemainder
            + (long) cpuCycles * hardwareProfile.cdControllerSystemClockHz();
        int cpuClock = hardwareProfile.cpuClockHz();
        if (scaled < cpuClock) {
            firmwareClockRemainder = scaled;
            return;
        }
        firmwareControllerTicks += scaled / cpuClock;
        firmwareClockRemainder = scaled % cpuClock;
    }

    private void advanceRotationalPhase(int cpuCycles) {
        if (!motorOn) {
            return;
        }
        int period = sectorCycles();
        long next = rotationalPhaseCycles + (long) cpuCycles;
        rotationalPhaseCycles = next < period ? (int) next : (int) (next % period);
    }

    private int commandResponseCycles() {
        return Math.max(1, currentCommandResponseCycles);
    }

    private int firstResponseCycles(int command) {
        CdDriveProfile.TimingRange range = switch (command & 0xFF) {
            case 0x0A -> driveProfile.initFirstResponse();
            case 0x1E -> driveProfile.readTocFirstResponse();
            default -> driveProfile.firstResponse(motorOn);
        };
        return sampleFirmwareTiming(range, commandWorkTicks(command));
    }

    private int commandWorkTicks(int command) {
        return switch (command & 0xFF) {
            case 0x02, 0x0D, 0x0E -> 18;
            case 0x10, 0x11, 0x13, 0x14 -> 28;
            case 0x15, 0x16, 0x1A -> 44;
            case 0x0A, 0x1E -> 62;
            default -> 8;
        };
    }

    private int sampleFirmwareTiming(CdDriveProfile.TimingRange range, int workTicks) {
        int pollTicks = driveProfile.firmwarePollPeriodTicks();
        long phaseTicks = firmwareControllerTicks + Math.max(0, workTicks);
        long epoch = Math.floorDiv(phaseTicks, pollTicks);
        int slot = (int) Math.floorMod(epoch, FIRMWARE_MAINTENANCE_SLOTS);
        int distanceFromLightSlot = Math.abs(slot - (FIRMWARE_MAINTENANCE_SLOTS / 2));
        int weight = Math.min(255, distanceFromLightSlot * 16);

        long interpolated;
        if (weight <= 128) {
            interpolated = range.minCycles()
                + (long) (range.typicalCycles() - range.minCycles()) * weight / 128;
        } else {
            interpolated = range.typicalCycles()
                + (long) (range.maxCycles() - range.typicalCycles()) * (weight - 128) / 127;
        }

        int withinPoll = (int) Math.floorMod(phaseTicks, pollTicks);
        int waitTicks = withinPoll == 0 ? 0 : pollTicks - withinPoll;
        long pollWaitCpuCycles = ((long) waitTicks * hardwareProfile.cpuClockHz()
            + hardwareProfile.cdControllerSystemClockHz() - 1)
            / hardwareProfile.cdControllerSystemClockHz();
        return (int) Math.clamp(interpolated + pollWaitCpuCycles,
            range.minCycles(), range.maxCycles());
    }

    private int busyToIrqGapCycles() {
        int pollTicks = driveProfile.firmwarePollPeriodTicks();
        int phase = (int) Math.floorMod(firmwareControllerTicks, pollTicks);
        return BUSY_TO_IRQ_MIN_CYCLES
            + (BUSY_TO_IRQ_MAX_CYCLES - BUSY_TO_IRQ_MIN_CYCLES) * phase / pollTicks;
    }

    private int sampleSpinUpCycles() {
        return sampleFirmwareTiming(driveProfile.spinUp(), 96);
    }

    private int sampleGetIdSecondResponseCycles(boolean spindleWasRunning) {
        int identify = sampleFirmwareTiming(driveProfile.getIdSecondResponse(), 52);
        if (spindleWasRunning) {
            return identify;
        }
        return saturatedAdd(sampleSpinUpCycles(), identify);
    }

    private int sampleReadTocSecondResponseCycles(boolean spindleWasRunning) {
        int readToc = sampleFirmwareTiming(driveProfile.readTocSecondResponse(), 112);
        return spindleWasRunning ? readToc : Math.max(readToc, sampleSpinUpCycles());
    }

    private int seekDurationCycles(int destinationLba, boolean spindleWasRunning) {
        int boundedDestination = mountedImage == null
            ? Math.max(0, destinationLba)
            : Math.clamp(destinationLba, 0, Math.max(0, mountedImage.leadOutLba() - 1));
        long distance = Math.abs((long) boundedDestination - currentLba);
        long movement = driveProfile.seekSpinningMinCycles();
        long fineSectors = Math.min(distance, 75);
        movement += fineSectors * 1_024L;
        distance -= fineSectors;
        long mediumSectors = Math.min(distance, 4_500 - 75);
        movement += mediumSectors * 384L;
        distance -= mediumSectors;
        movement += distance * 96L;

        int period = sectorCycles();
        int rotationalWait = rotationalPhaseCycles == 0 ? 0 : period - rotationalPhaseCycles;
        movement += rotationalWait;
        if (!spindleWasRunning) {
            movement += sampleSpinUpCycles();
        }
        int max = spindleWasRunning
            ? driveProfile.seekSpinningMaxCycles()
            : driveProfile.seekColdMaxCycles();
        int minimum = Math.max(driveProfile.seekSpinningMinCycles(), sectorCycles());
        return Math.clamp(movement, minimum, max);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private int sectorCycles() {
        return (mode & MODE_SPEED) != 0 ? sectorCycles2x : sectorCycles1x;
    }

    private int pauseDelayCycles(boolean wasActive) {
        CdDriveProfile.TimingRange range;
        if (!wasActive) {
            range = driveProfile.pauseIdle();
        } else {
            range = (mode & MODE_SPEED) != 0
                ? driveProfile.pauseDoubleSpeed()
                : driveProfile.pauseSingleSpeed();
        }
        return sampleFirmwareTiming(range, 48);
    }

    private int stopDelayCycles(boolean wasActive) {
        CdDriveProfile.TimingRange range;
        if (!wasActive) {
            range = driveProfile.stopIdle();
        } else {
            range = (mode & MODE_SPEED) != 0
                ? driveProfile.stopDoubleSpeed()
                : driveProfile.stopSingleSpeed();
        }
        return sampleFirmwareTiming(range, 64);
    }

    private boolean hasDisc() { return mountedImage != null; }

    private boolean isReadAuthorized() {
        return secretUnlocked
            || (mode & MODE_CDDA) != 0
            || (mountedImage.isLicensedPlayStationDisc()
                && driveProfile.acceptsLicenseRegion(mountedImage.regionCode()));
    }

    // Opening the lid is an unsolicited INT5.
    private void deliverShellOpenInterrupt() {
        loadResponse(new int[]{ STATUS_SHELL_OPEN | STATUS_ERROR, 0x08 });
        interruptFlags = 5;
        updateIrqLine();
    }

    private int fromBcd(int bcd) { return ((bcd >>> 4) * 10) + (bcd & 0xF); }
    private int toBcd(int value) { return ((value / 10) << 4) | (value % 10); }

    private boolean isPackedBcd(int value) {
        return ((value >>> 4) & 0x0F) <= 9 && (value & 0x0F) <= 9;
    }

    private boolean isBcdBelow(int value, int exclusiveMax) {
        return isPackedBcd(value) && fromBcd(value) < exclusiveMax;
    }

    private int msfToLba(int minute, int second, int frame) {
        return Math.max(0, (minute * 60 * 75) + (second * 75) + frame - LBA_MSF_OFFSET);
    }

    private void updateIrqLine() {
        boolean asserted = ((interruptEnable & 0x1F) & (interruptFlags & 0x1F)) != 0;
        if (asserted == irqLineAsserted) {
            return;
        }
        irqLineAsserted = asserted;
        if (asserted) {
            interruptController.raise(2);
        } else {
            interruptController.clear(2);
        }
    }

    private void resetTransportState() {
        currentLba          = 0;
        targetLba           = 0;
        targetPending       = false;
        filterFile          = 0;
        filterChannel       = 0;
        commandBusyCycles   = 0;
        readCyclesRemaining = 0;
        playCyclesRemaining = 0;
        playStepSectors     = 1;
        playReportCountdown = CDDA_REPORT_INTERVAL;
        seekCyclesRemaining = 0;
        reading             = false;
        playing             = false;
        seeking             = false;
        seekError           = false;
        seekFailurePending  = false;
        pendingReadStart    = false;
        pendingPlayStart    = false;
        motorOn             = false;
        streamMuted         = false;
        bufferWriteRequested = false;
        soundMapEnabled     = false;
        xaMuted             = false;
        initInProgress      = false;
        resetAudioDecoder();
        Arrays.fill(lastResponseWindow, 0);
        responseReadOffset = 0;
        responseWindowValid = false;
        pendingAtv0 = 0x80; pendingAtv1 = 0x00;
        pendingAtv2 = 0x80; pendingAtv3 = 0x00;
        applyAudioMatrix();
        clearSectorBuffers();
        responseFifo.clear();
        parameterFifo.clear();
        pendingEvents.clear();
        soundMapWriteFifo.clear();
        interruptFlags  = 0;
        queuedCommand   = -1;
        lastBufferedSector = null;
        lastBufferedLba    = -1;
        newestBufferedSector = null;
        newestBufferedLba = -1;
        lastValidSubchannelLocation = null;
        activeSectorInterrupt = false;
        irqLineAsserted = true;
        updateIrqLine();
    }

    private void clearSectorBuffers() {
        activeDataBlock  = new byte[0];
        activeDataOffset = 0;
        bufferReadRequested = false;
        pendingDataBlocks.clear();
    }

    private void stopReadPipeline(boolean clearBuffers) {
        reading         = false;
        playing         = false;
        seeking         = false;
        seekFailurePending = false;
        pendingReadStart = false;
        pendingPlayStart = false;
        readCyclesRemaining = 0;
        playCyclesRemaining = 0;
        playStepSectors = 1;
        playReportCountdown = CDDA_REPORT_INTERVAL;
        seekCyclesRemaining = 0;
        readSessionId++;
        discardPendingMechanicalEvents();
        resetAudioDecoder();
        if (clearBuffers) clearSectorBuffers();
    }

    private void resetAudioDecoder() {
        xaDecoder.resetHistory();
        resetCurrentXaStream();
        xaPcm = EMPTY_PCM;
        audioResetPending = true;
    }

    private void resetCurrentXaStream() {
        xaCurrentFile = 0;
        xaCurrentChannel = 0;
        xaCurrentStreamSet = false;
    }

    private void discardPendingMechanicalEvents() {
        pendingEvents.removeIf(event -> event.type != EventType.RESPONSE);
        activeSectorInterrupt = false;
    }

    private static int[] queueToArray(Queue<Integer> queue) {
        return queue.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void loadQueue(Queue<Integer> queue, int[] values) {
        queue.clear();
        if (values == null) {
            return;
        }
        for (int value : values) {
            queue.add(value & 0xFF);
        }
    }

    private static int[] responseWindowFromFifo(Queue<Integer> queue) {
        int[] window = new int[RESPONSE_FIFO_CAPACITY];
        int index = 0;
        for (int value : queue) {
            if (index >= window.length) {
                break;
            }
            window[index++] = value & 0xFF;
        }
        return window;
    }

    private byte[][] copyDataBlockQueue() {
        byte[][] copy = new byte[pendingDataBlocks.size()][];
        int index = 0;
        for (byte[] block : pendingDataBlocks) {
            copy[index++] = block.clone();
        }
        return copy;
    }

    private void loadDataBlockQueue(byte[][] blocks) {
        pendingDataBlocks.clear();
        if (blocks != null) {
            for (byte[] block : blocks) {
                if (block != null && block.length > 0) {
                    pendingDataBlocks.clear();
                    pendingDataBlocks.add(block.clone());
                }
            }
        }
    }

    private enum EventType {
        RESPONSE,
        PLAY_REPORT,
        SEEK_COMPLETE,
        SESSION_ERROR_COMPLETE,
        SECTOR_READY,
        RESET_COMPLETE,  // delayed effect of the Reset (0x1C) command
        SEEK_ERROR_COMPLETE
    }

    private static final class PendingEvent {
        int cyclesRemaining;
        long dueCycle;
        final EventType type;
        final int irqType;
        int lba;
        final int sessionId;
        final int[] bytes;

        PendingEvent(int cyclesRemaining, EventType type, int irqType,
                     int lba, int sessionId, int[] bytes) {
            this.cyclesRemaining = cyclesRemaining;
            this.type      = type;
            this.irqType   = irqType;
            this.lba       = lba;
            this.sessionId = sessionId;
            this.bytes     = bytes;
        }

        PendingEventState copyState(long eventClockCycles) {
            PendingEventState state = new PendingEventState();
            state.cyclesRemaining = (int) Math.clamp(dueCycle - eventClockCycles, 0L, Integer.MAX_VALUE);
            state.type = type.ordinal();
            state.irqType = irqType;
            state.lba = lba;
            state.sessionId = sessionId;
            state.bytes = bytes == null ? null : bytes.clone();
            return state;
        }

        static PendingEvent fromState(PendingEventState state) {
            if (state == null) {
                return null;
            }
            EventType[] values = EventType.values();
            if (state.type < 0 || state.type >= values.length) {
                return null;
            }
            return new PendingEvent(
                state.cyclesRemaining,
                values[state.type],
                state.irqType,
                state.lba,
                state.sessionId,
                state.bytes == null ? null : state.bytes.clone());
        }
    }

    public static final class State {
        String imagePath;
        int[] parameterFifo;
        int[] responseFifo;
        int[] lastResponseWindow;
        int responseReadOffset;
        boolean responseWindowValid;
        PendingEventState[] pendingEvents;
        int[] soundMapWriteFifo;
        XaAdpcmDecoder.State xaDecoder;
        int registerIndex;
        int interruptEnable;
        int interruptFlags;
        int queuedCommand;
        int mode;
        int sectorSizeLatch;
        int currentLba;
        int targetLba;
        boolean targetPending;
        int filterFile;
        int filterChannel;
        int commandBusyCycles;
        int currentCommandResponseCycles;
        long firmwareControllerTicks;
        long firmwareClockRemainder;
        int rotationalPhaseCycles;
        int readCyclesRemaining;
        int playCyclesRemaining;
        int playStepSectors;
        int playReportCountdown;
        int seekCyclesRemaining;
        boolean reading;
        boolean playing;
        boolean seeking;
        boolean seekError;
        boolean seekFailurePending;
        boolean pendingReadStart;
        boolean pendingPlayStart;
        boolean motorOn;
        boolean streamMuted;
        boolean bufferWriteRequested;
        boolean bufferReadRequested;
        boolean soundMapEnabled;
        boolean xaMuted;
        boolean initInProgress;
        Boolean shellOpen;
        Boolean shellOpenSticky;
        Boolean mediaLifecycleInitialized;
        int secretUnlockStep;
        boolean secretUnlocked;
        int readSessionId;
        short[] xaPcm;
        int xaCurrentFile;
        int xaCurrentChannel;
        boolean xaCurrentStreamSet;
        boolean audioResetPending;
        int pendingAtv0;
        int pendingAtv1;
        int pendingAtv2;
        int pendingAtv3;
        int appliedAtv0;
        int appliedAtv1;
        int appliedAtv2;
        int appliedAtv3;
        SectorState lastBufferedSector;
        int lastBufferedLba;
        SectorState newestBufferedSector;
        Integer newestBufferedLba;
        int[] lastValidSubchannelLocation;
        Boolean activeSectorInterrupt;
        byte[][] pendingDataBlocks;
        byte[] activeDataBlock;
        int activeDataOffset;
    }

    public static final class PendingEventState {
        int cyclesRemaining;
        int type;
        int irqType;
        int lba;
        int sessionId;
        int[] bytes;
    }

    public static final class SectorState {
        int minute;
        int second;
        int frame;
        byte[] raw2352;
        byte[] userData;

        static SectorState from(CdSector sector) {
            if (sector == null) {
                return null;
            }
            SectorState state = new SectorState();
            state.minute = sector.minute();
            state.second = sector.second();
            state.frame = sector.frame();
            state.raw2352 = sector.raw2352() == null ? null : sector.raw2352().clone();
            state.userData = sector.userData() == null ? null : sector.userData().clone();
            return state;
        }

        static CdSector toSector(SectorState state) {
            if (state == null) {
                return null;
            }
            byte[] raw = state.raw2352 == null ? new byte[0] : state.raw2352.clone();
            byte[] userData = state.userData == null ? new byte[0] : state.userData.clone();
            return new CdSector(state.minute, state.second, state.frame, raw, userData);
        }
    }
}
