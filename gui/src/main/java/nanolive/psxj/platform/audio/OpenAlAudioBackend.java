package nanolive.psxj.platform.audio;

import nanolive.psxj.emu.api.AudioBackend;
import nanolive.psxj.util.Log;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.ALC11.ALC_ALL_DEVICES_SPECIFIER;
import static org.lwjgl.openal.ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER;

public final class OpenAlAudioBackend implements AudioBackend, PumpedAudioBackend {

    private static final int SAMPLE_RATE = 44_100;
    private static final int CHANNELS = 2;
    private static final int BUFFER_COUNT = 8;
    private static final int MIN_LATENCY_MS = 20;
    private static final int MAX_LATENCY_MS = 250;
    private static final int CONTINUITY_LOW_WATER_BUFFERS = 2;
    private static final int CONTINUITY_CROSSFADE_FRAMES = 64;
    private static final long UNDERRUN_WARNING_INTERVAL_NANOS = 5_000_000_000L;

    private final int latencyMs;
    private final int prebufferTargetSamples;
    private final int chunkSamples;
    private long device;
    private long context;
    private ALCCapabilities deviceCapabilities;
    private ALCapabilities alCapabilities;
    private int source;
    private IntBuffer buffers;
    private ByteBuffer uploadBytes;
    private ShortBuffer uploadSamples;
    private final Queue<Integer> freeBuffers = new ArrayDeque<>();
    private final short[] stagingSamples;
    private final short[] continuitySamples;
    private final short[] lastQueuedSamples;
    private int stagingSampleCount;
    private boolean haveContinuitySeed;
    private boolean recoveringFromUnderrun;
    private boolean playbackStarted;
    private boolean sourcePaused;
    private long lastUnderrunWarningNanos;
    private int suppressedUnderrunWarnings;

    public OpenAlAudioBackend() {
        this(80);
    }

    public OpenAlAudioBackend(int latencyMs) {
        this.latencyMs = Math.clamp(latencyMs, MIN_LATENCY_MS, MAX_LATENCY_MS);
        this.prebufferTargetSamples = Math.max(
            CHANNELS,
            SAMPLE_RATE * CHANNELS * this.latencyMs / 1000
        );
        int samplesPerBuffer = (prebufferTargetSamples + BUFFER_COUNT - 1) / BUFFER_COUNT;
        this.chunkSamples = (samplesPerBuffer + (CHANNELS - 1)) & ~(CHANNELS - 1);
        this.stagingSamples = new short[chunkSamples];
        this.continuitySamples = new short[chunkSamples];
        this.lastQueuedSamples = new short[chunkSamples];
    }

    @Override
    public void open() {
        if (device != 0L) {
            return;
        }
        String requestedDevice = null;
        if (alcIsExtensionPresent(MemoryUtil.NULL, "ALC_ENUMERATE_ALL_EXT")) {
            requestedDevice = alcGetString(MemoryUtil.NULL, ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
        }
        device = requestedDevice == null || requestedDevice.isBlank()
            ? alcOpenDevice((java.nio.ByteBuffer) null)
            : alcOpenDevice(requestedDevice);
        if (device == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to open OpenAL device.");
        }
        deviceCapabilities = ALC.createCapabilities(device);
        context = alcCreateContext(device, (java.nio.IntBuffer) null);
        if (context == MemoryUtil.NULL) {
            alcCloseDevice(device);
            device = 0L;
            throw new IllegalStateException("Failed to create OpenAL context.");
        }
        if (!alcMakeContextCurrent(context)) {
            alcDestroyContext(context);
            alcCloseDevice(device);
            context = 0L;
            device = 0L;
            throw new IllegalStateException("Failed to make the OpenAL context current.");
        }
        alCapabilities = AL.createCapabilities(deviceCapabilities);
        source = alGenSources();
        checkAlError("creating source");
        buffers = MemoryUtil.memAllocInt(BUFFER_COUNT);
        alGenBuffers(buffers);
        checkAlError("creating buffers");
        freeBuffers.clear();
        for (int i = 0; i < buffers.capacity(); i++) {
            freeBuffers.add(buffers.get(i));
        }
        uploadBytes = MemoryUtil.memAlloc(chunkSamples * Short.BYTES)
            .order(ByteOrder.nativeOrder());
        uploadSamples = uploadBytes.asShortBuffer();
        stagingSampleCount = 0;
        playbackStarted = false;
        sourcePaused = false;
        haveContinuitySeed = false;
        recoveringFromUnderrun = false;
        lastUnderrunWarningNanos = 0L;
        suppressedUnderrunWarnings = 0;
        String activeDevice = deviceCapabilities.ALC_ENUMERATE_ALL_EXT
            ? alcGetString(device, ALC_ALL_DEVICES_SPECIFIER)
            : alcGetString(device, ALC_DEVICE_SPECIFIER);
        Log.info("OpenAL backend initialized: device=" + activeDevice
            + ", latencyMs=" + latencyMs
            + ", prebufferFrames=" + (chunkSamples * BUFFER_COUNT / CHANNELS)
            + ", bufferFrames=" + (chunkSamples / CHANNELS));
    }

    @Override
    public synchronized void submitSamples(short[] interleavedStereo) {
        if (source == 0 || interleavedStereo == null || interleavedStereo.length == 0) {
            return;
        }
        ensureContextCurrent();
        reclaimProcessedBuffers();

        if (playbackStarted && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
            // Rewind AL_STOPPED before rebuilding the queue.
            alSourceRewind(source);
            checkAlError("rewinding source after underrun");
            playbackStarted = false;
            recoveringFromUnderrun = true;
            logUnderrun();
        }

        int sourceOffset = 0;
        while (sourceOffset < interleavedStereo.length) {
            int copied = Math.min(
                chunkSamples - stagingSampleCount,
                interleavedStereo.length - sourceOffset
            );
            System.arraycopy(interleavedStereo, sourceOffset, stagingSamples, stagingSampleCount, copied);
            sourceOffset += copied;
            stagingSampleCount += copied;
            if (stagingSampleCount < chunkSamples) {
                continue;
            }
            if (!queueSamples(stagingSamples, 0, chunkSamples)) {
                return;
            }
            stagingSampleCount = 0;
            startPlaybackWhenReady();
        }
    }

    @Override
    public synchronized void pause() {
        if (source == 0) {
            return;
        }
        ensureContextCurrent();
        if (alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING) {
            alSourcePause(source);
            checkAlError("pausing playback");
            sourcePaused = true;
        }
    }

    @Override
    public synchronized void resume() {
        if (source == 0 || !sourcePaused) {
            return;
        }
        ensureContextCurrent();
        if (alGetSourcei(source, AL_BUFFERS_QUEUED) > 0) {
            alSourcePlay(source);
            checkAlError("resuming playback");
            playbackStarted = true;
        }
        sourcePaused = false;
    }

    private boolean queueSamples(short[] samples, int offset, int length) {
        while (freeBuffers.isEmpty()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
            reclaimProcessedBuffers();
        }

        int bufferId = freeBuffers.remove();
        uploadSamples.clear();
        uploadSamples.put(samples, offset, length);
        uploadSamples.flip();
        alBufferData(bufferId, AL_FORMAT_STEREO16, uploadSamples, SAMPLE_RATE);
        checkAlError("uploading PCM");
        alSourceQueueBuffers(source, bufferId);
        checkAlError("queueing PCM");
        if (length == chunkSamples) {
            System.arraycopy(samples, offset, lastQueuedSamples, 0, chunkSamples);
            haveContinuitySeed = true;
        }
        return true;
    }

    // Bridges the final queued buffer when the producer is briefly late.
    @Override
    public synchronized void pump() {
        if (source == 0) {
            return;
        }
        ensureContextCurrent();
        reclaimProcessedBuffers();
        int state = alGetSourcei(source, AL_SOURCE_STATE);
        if (playbackStarted && state != AL_PLAYING) {
            alSourceRewind(source);
            checkAlError("rewinding source after underrun");
            playbackStarted = false;
            recoveringFromUnderrun = true;
            logUnderrun();
        }
        if (!haveContinuitySeed || freeBuffers.isEmpty()) {
            return;
        }
        int queued = alGetSourcei(source, AL_BUFFERS_QUEUED);
        boolean needsSafetyBlock = needsContinuityBlock(playbackStarted, queued);
        boolean needsRecoveryBlock = recoveringFromUnderrun && queued < BUFFER_COUNT;
        if (!needsSafetyBlock && !needsRecoveryBlock) {
            return;
        }
        do {
            buildContinuityChunk(lastQueuedSamples, stagingSamples,
                stagingSampleCount, continuitySamples);
            stagingSampleCount = 0;
            if (!queueSamples(continuitySamples, 0, continuitySamples.length)) {
                return;
            }
            queued++;
        } while (recoveringFromUnderrun
            && queued < BUFFER_COUNT
            && !freeBuffers.isEmpty());
        startPlaybackWhenReady();
    }

    @Override
    public synchronized void close() {
        if (source != 0) {
            ensureContextCurrent();
            alSourceStop(source);
            int queued = alGetSourcei(source, AL_BUFFERS_QUEUED);
            while (queued-- > 0) {
                try {
                    alSourceUnqueueBuffers(source);
                } catch (Exception ignored) {
                    break;
                }
            }
            if (buffers != null) {
                alDeleteBuffers(buffers);
                MemoryUtil.memFree(buffers);
                buffers = null;
            }
            freeBuffers.clear();
            uploadSamples = null;
            if (uploadBytes != null) {
                MemoryUtil.memFree(uploadBytes);
                uploadBytes = null;
            }
            alDeleteSources(source);
            source = 0;
        }
        stagingSampleCount = 0;
        playbackStarted = false;
        sourcePaused = false;
        haveContinuitySeed = false;
        recoveringFromUnderrun = false;
        lastUnderrunWarningNanos = 0L;
        suppressedUnderrunWarnings = 0;
        if (context != 0L) {
            alcMakeContextCurrent(MemoryUtil.NULL);
            alcDestroyContext(context);
            context = 0L;
        }
        alCapabilities = null;
        deviceCapabilities = null;
        if (device != 0L) {
            alcCloseDevice(device);
            device = 0L;
        }
    }

    private void reclaimProcessedBuffers() {
        int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            freeBuffers.add(alSourceUnqueueBuffers(source));
        }
    }

    private void startPlaybackWhenReady() {
        if (alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING) {
            playbackStarted = true;
            return;
        }
        int queued = alGetSourcei(source, AL_BUFFERS_QUEUED);
        // Refill the configured prebuffer before restarting after an underrun.
        if (isReadyToStartPlayback(queued)) {
            alSourcePlay(source);
            checkAlError("starting playback");
            playbackStarted = true;
            recoveringFromUnderrun = false;
        }
    }

    static void buildContinuityChunk(short[] previous,
                                     short[] partial,
                                     int partialCount,
                                     short[] output) {
        int length = Math.min(previous.length, output.length) & ~1;
        int prefix = Math.clamp(partialCount & ~1, 0,
            Math.min(partial.length, length));
        if (prefix > 0) {
            System.arraycopy(partial, 0, output, 0, prefix);
        }
        int previousFrames = Math.max(1, length / CHANNELS);
        int generatedFrames = (length - prefix) / CHANNELS;
        int fadeFrames = Math.min(CONTINUITY_CROSSFADE_FRAMES,
            Math.max(1, generatedFrames / 2));
        for (int frame = 0; frame < generatedFrames; frame++) {
            int sourceFrame = frame % previousFrames;
            for (int channel = 0; channel < CHANNELS; channel++) {
                int source = previous[sourceFrame * CHANNELS + channel];
                if (frame < fadeFrames) {
                    int boundary = prefix > 0
                        ? output[prefix - CHANNELS + channel]
                        : previous[(previousFrames - 1) * CHANNELS + channel];
                    source = (boundary * (fadeFrames - frame)
                        + source * (frame + 1)) / (fadeFrames + 1);
                }
                output[prefix + frame * CHANNELS + channel] = (short) source;
            }
        }
    }

    static boolean isReadyToStartPlayback(int queuedBuffers) {
        return queuedBuffers >= BUFFER_COUNT;
    }

    static boolean needsContinuityBlock(boolean playbackStarted, int queuedBuffers) {
        // Keep enough PCM to survive a short scheduler or GC pause.
        return playbackStarted && queuedBuffers <= CONTINUITY_LOW_WATER_BUFFERS;
    }

    private void logUnderrun() {
        long now = System.nanoTime();
        if (lastUnderrunWarningNanos == 0L
            || now - lastUnderrunWarningNanos >= UNDERRUN_WARNING_INTERVAL_NANOS) {
            String suppressed = suppressedUnderrunWarnings == 0
                ? ""
                : "; suppressed=" + suppressedUnderrunWarnings;
            Log.warn("OpenAL stream underrun; refilling full prebuffer before restart" + suppressed);
            lastUnderrunWarningNanos = now;
            suppressedUnderrunWarnings = 0;
        } else {
            suppressedUnderrunWarnings++;
        }
    }

    private void ensureContextCurrent() {
        if (context == 0L) {
            return;
        }
        if (alcGetCurrentContext() != context && !alcMakeContextCurrent(context)) {
            throw new IllegalStateException("Failed to make the OpenAL context current.");
        }
        if (alCapabilities != null) {
            AL.setCurrentThread(alCapabilities);
        }
    }

    private static void checkAlError(String operation) {
        int error = alGetError();
        if (error != AL_NO_ERROR) {
            throw new IllegalStateException("OpenAL error 0x"
                + Integer.toHexString(error) + " while " + operation);
        }
    }
}
