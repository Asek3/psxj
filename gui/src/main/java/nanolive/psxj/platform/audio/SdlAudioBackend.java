package nanolive.psxj.platform.audio;

import nanolive.psxj.emu.api.AudioBackend;
import nanolive.psxj.util.Log;
import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDL_AudioSpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.sdl.SDLAudio.SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK;
import static org.lwjgl.sdl.SDLAudio.SDL_AUDIO_S16;
import static org.lwjgl.sdl.SDLAudio.SDL_DestroyAudioStream;
import static org.lwjgl.sdl.SDLAudio.SDL_GetAudioStreamQueued;
import static org.lwjgl.sdl.SDLAudio.SDL_OpenAudioDeviceStream;
import static org.lwjgl.sdl.SDLAudio.SDL_PutAudioStreamData;
import static org.lwjgl.sdl.SDLAudio.SDL_ResumeAudioStreamDevice;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_InitSubSystem;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

public final class SdlAudioBackend implements AudioBackend {

    private static final int SAMPLE_RATE = 44_100;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = CHANNELS * Short.BYTES;
    private static final int MIN_LATENCY_MS = 20;

    private final int latencyMs;
    private final int targetQueuedBytes;
    private boolean initialized;
    private boolean playbackStarted;
    private long stream;
    private long submittedFrames;
    private ByteBuffer transferBuffer;

    public SdlAudioBackend(int latencyMs) {
        this.latencyMs = Math.max(MIN_LATENCY_MS, latencyMs);
        this.targetQueuedBytes = Math.max(BYTES_PER_FRAME, SAMPLE_RATE * BYTES_PER_FRAME * this.latencyMs / 1000);
    }

    @Override
    public synchronized void open() {
        if (initialized) {
            return;
        }
        if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
            throw new IllegalStateException("SDL audio init failed: " + SDL_GetError());
        }
        SDL_AudioSpec spec = SDL_AudioSpec.calloc();
        try {
            spec.format(SDL_AUDIO_S16);
            spec.channels(CHANNELS);
            spec.freq(SAMPLE_RATE);
            stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, spec, null, 0L);
            if (stream == 0L) {
                throw new IllegalStateException("SDL_OpenAudioDeviceStream failed: " + SDL_GetError());
            }
            initialized = true;
            playbackStarted = false;
            Log.info("SDL audio backend initialized using default playback device stream"
                + ": latencyMs=" + latencyMs
                + ", targetQueuedBytes=" + targetQueuedBytes);
        } finally {
            spec.free();
        }
    }

    @Override
    public synchronized void submitSamples(short[] interleavedStereo) {
        if (!initialized || stream == 0L || interleavedStereo == null || interleavedStereo.length == 0) {
            return;
        }
        int queued = getQueuedBytes();
        // SDL's queued count excludes samples already moved to the device.
        while (playbackStarted && queued > targetQueuedBytes) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            queued = getQueuedBytes();
        }
        ByteBuffer pcm = ensureTransferBuffer(interleavedStereo.length * Short.BYTES);
        pcm.clear();
        pcm.order(ByteOrder.nativeOrder());
        for (short sample : interleavedStereo) {
            pcm.putShort(sample);
        }
        pcm.flip();
        if (!SDL_PutAudioStreamData(stream, pcm)) {
            throw new IllegalStateException("SDL_PutAudioStreamData failed: " + SDL_GetError());
        }
        int queuedAfterSubmit = getQueuedBytes();
        if (!playbackStarted && queuedAfterSubmit >= targetQueuedBytes) {
            if (!SDL_ResumeAudioStreamDevice(stream)) {
                throw new IllegalStateException("SDL_ResumeAudioStreamDevice failed: " + SDL_GetError());
            }
            playbackStarted = true;
        }
        submittedFrames += interleavedStereo.length / CHANNELS;
        if (submittedFrames % (SAMPLE_RATE * 5L) < CHANNELS && Log.isDebugEnabled()) {
            Log.debug("SDL audio stream queued=" + queuedAfterSubmit + " bytes");
        }
    }

    @Override
    public synchronized void close() {
        if (stream != 0L) {
            SDL_DestroyAudioStream(stream);
            stream = 0L;
        }
        playbackStarted = false;
        transferBuffer = null;
        if (initialized) {
            SDL_QuitSubSystem(SDL_INIT_AUDIO);
            initialized = false;
        }
    }

    private int getQueuedBytes() {
        int queued = SDL_GetAudioStreamQueued(stream);
        if (queued < 0) {
            throw new IllegalStateException("SDL_GetAudioStreamQueued failed: " + SDL_GetError());
        }
        return queued;
    }

    private ByteBuffer ensureTransferBuffer(int byteCount) {
        if (transferBuffer == null || transferBuffer.capacity() < byteCount) {
            transferBuffer = BufferUtils.createByteBuffer(byteCount);
        }
        return transferBuffer;
    }
}
