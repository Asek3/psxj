package nanolive.psxj.platform.render;

import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.emu.video.GpuFrame;
import nanolive.psxj.util.Log;
import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Texture;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.EnumSet;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_InitSubSystem;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;
import static org.lwjgl.sdl.SDLPixels.SDL_PIXELFORMAT_XRGB8888;
import static org.lwjgl.sdl.SDLPixels.SDL_PIXELFORMAT_ARGB8888;
import static org.lwjgl.sdl.SDLBlendMode.SDL_BLENDMODE_BLEND;
import static org.lwjgl.sdl.SDLRender.SDL_CreateRenderer;
import static org.lwjgl.sdl.SDLRender.SDL_CreateTexture;
import static org.lwjgl.sdl.SDLRender.SDL_DestroyRenderer;
import static org.lwjgl.sdl.SDLRender.SDL_DestroyTexture;
import static org.lwjgl.sdl.SDLRender.SDL_GetRendererName;
import static org.lwjgl.sdl.SDLRender.SDL_GetCurrentRenderOutputSize;
import static org.lwjgl.sdl.SDLRender.SDL_RenderClear;
import static org.lwjgl.sdl.SDLRender.SDL_RenderPresent;
import static org.lwjgl.sdl.SDLRender.SDL_RenderTexture;
import static org.lwjgl.sdl.SDLRender.SDL_SetRenderVSync;
import static org.lwjgl.sdl.SDLRender.SDL_SetTextureBlendMode;
import static org.lwjgl.sdl.SDLRender.SDL_TEXTUREACCESS_STREAMING;
import static org.lwjgl.sdl.SDLRender.SDL_UpdateTexture;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST;
import static org.lwjgl.sdl.SDLEvents.SDL_PollEvent;
import static org.lwjgl.sdl.SDLEvents.SDL_PumpEvents;
import static org.lwjgl.sdl.SDLKeycode.SDLK_1;
import static org.lwjgl.sdl.SDLKeycode.SDLK_2;
import static org.lwjgl.sdl.SDLKeycode.SDLK_3;
import static org.lwjgl.sdl.SDLKeycode.SDLK_4;
import static org.lwjgl.sdl.SDLKeycode.SDLK_5;
import static org.lwjgl.sdl.SDLKeycode.SDLK_6;
import static org.lwjgl.sdl.SDLKeycode.SDLK_7;
import static org.lwjgl.sdl.SDLKeycode.SDLK_8;
import static org.lwjgl.sdl.SDLKeycode.SDLK_9;
import static org.lwjgl.sdl.SDLKeycode.SDLK_A;
import static org.lwjgl.sdl.SDLKeycode.SDLK_DOWN;
import static org.lwjgl.sdl.SDLKeycode.SDLK_ESCAPE;
import static org.lwjgl.sdl.SDLKeycode.SDLK_F5;
import static org.lwjgl.sdl.SDLKeycode.SDLK_F8;
import static org.lwjgl.sdl.SDLKeycode.SDLK_LEFT;
import static org.lwjgl.sdl.SDLKeycode.SDLK_Q;
import static org.lwjgl.sdl.SDLKeycode.SDLK_RETURN;
import static org.lwjgl.sdl.SDLKeycode.SDLK_RIGHT;
import static org.lwjgl.sdl.SDLKeycode.SDLK_S;
import static org.lwjgl.sdl.SDLKeycode.SDLK_SPACE;
import static org.lwjgl.sdl.SDLKeycode.SDLK_UP;
import static org.lwjgl.sdl.SDLKeycode.SDLK_W;
import static org.lwjgl.sdl.SDLKeycode.SDLK_X;
import static org.lwjgl.sdl.SDLKeycode.SDLK_Z;
import static org.lwjgl.sdl.SDLMouse.SDL_CreateSystemCursor;
import static org.lwjgl.sdl.SDLMouse.SDL_DestroyCursor;
import static org.lwjgl.sdl.SDLMouse.SDL_GetDefaultCursor;
import static org.lwjgl.sdl.SDLMouse.SDL_SetCursor;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_POINTER;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_RESIZABLE;

abstract class AbstractSdlRenderer implements RenderBackend, WindowOverlayTarget {

    private final String title;
    private final int width;
    private final int height;
    private final long windowFlags;
    private final String rendererName;

    private volatile long window;
    private volatile long renderer;
    private volatile SDL_Texture texture;
    private volatile int textureWidth;
    private volatile int textureHeight;
    private volatile CloseRequestHandler closeRequestHandler;
    private volatile KeyEventHandler keyEventHandler;
    private volatile HostKeyEventHandler hostKeyEventHandler;
    private volatile PointerEventHandler pointerEventHandler;
    private volatile int lastFrameId = -1;
    private volatile ByteBuffer uploadBuffer;
    private volatile IntBuffer uploadInts;
    private volatile GameOverlay gameOverlay;
    private SDL_Texture overlayTexture;
    private BufferedImage overlayImage;
    private ByteBuffer overlayUploadBuffer;
    private IntBuffer overlayUploadInts;
    private int overlayWidth;
    private int overlayHeight;
    private boolean overlayVisible;
    private SDL_Event eventBuffer;
    private final EnumSet<InputKey> pressedInputKeys = EnumSet.noneOf(InputKey.class);
    private final EnumSet<HostKey> consumedHostKeys = EnumSet.noneOf(HostKey.class);
    private final IntBuffer windowWidthBuffer = BufferUtils.createIntBuffer(1);
    private final IntBuffer windowHeightBuffer = BufferUtils.createIntBuffer(1);
    private final IntBuffer outputWidthBuffer = BufferUtils.createIntBuffer(1);
    private final IntBuffer outputHeightBuffer = BufferUtils.createIntBuffer(1);
    private boolean videoInitialized;
    private long defaultCursor;
    private long pointingHandCursor;

    protected AbstractSdlRenderer(String title, int width, int height, long windowFlags, String rendererName) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.windowFlags = windowFlags;
        this.rendererName = rendererName;
    }

    @Override
    public synchronized void open() {
        if (window != 0L) {
            return;
        }
        if (!SDL_InitSubSystem(SDL_INIT_VIDEO)) {
            throw new IllegalStateException("SDL video init failed: " + SDL_GetError());
        }
        videoInitialized = true;

        long createdWindow = SDL_CreateWindow(title, width, height, windowFlags | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY);
        if (createdWindow == MemoryUtil.NULL) {
            shutdownVideo();
            throw new IllegalStateException("SDL window creation failed: " + SDL_GetError());
        }

        long createdRenderer = SDL_CreateRenderer(createdWindow, rendererName);
        if (createdRenderer == MemoryUtil.NULL) {
            SDL_DestroyWindow(createdWindow);
            shutdownVideo();
            throw new IllegalStateException("SDL renderer creation failed for '" + rendererName + "': " + SDL_GetError());
        }
        if (!SDL_SetRenderVSync(createdRenderer, 0)) {
            Log.warn("SDL renderer could not disable vertical synchronization: " + SDL_GetError());
        }

        this.window = createdWindow;
        this.renderer = createdRenderer;
        this.eventBuffer = SDL_Event.calloc();
        defaultCursor = SDL_GetDefaultCursor();
        pointingHandCursor = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_POINTER);
        requestAttention();
        Log.info("SDL renderer window created: title=" + title + ", backend=" + safeRendererName(createdRenderer));
    }

    @Override
    public int overlayRefreshRateHz() {
        long currentWindow = window;
        if (currentWindow != 0L) {
            SDL_DisplayMode mode = SDL_GetCurrentDisplayMode(SDL_GetDisplayForWindow(currentWindow));
            if (mode != null && mode.refresh_rate() > 0f) {
                return Math.clamp(Math.round(mode.refresh_rate()), 30, 360);
            }
        }
        return WindowOverlayTarget.super.overlayRefreshRateHz();
    }

    @Override
    public void presentFrame(GpuFrame frame) {
        if (frame == null || frame.frameId() == lastFrameId) {
            return;
        }
        if (window == 0L || renderer == 0L) {
            return;
        }
        int frameWidth = Math.max(1, frame.width());
        int frameHeight = Math.max(1, frame.height());
        ensureTexture(frameWidth, frameHeight);
        IntBuffer ints = uploadInts;
        ints.clear();
        ints.put(frame.pixels(), 0, Math.min(ints.remaining(), frame.pixels().length));
        ByteBuffer bytes = uploadBuffer;
        bytes.position(0);
        bytes.limit(frameWidth * frameHeight * Integer.BYTES);
        if (!SDL_UpdateTexture(texture, null, bytes, frameWidth * Integer.BYTES)) {
            throw new IllegalStateException("SDL_UpdateTexture failed: " + SDL_GetError());
        }
        ensureOverlaySizeCurrent();
        renderScene();
        lastFrameId = frame.frameId();
    }

    private void ensureTexture(int frameWidth, int frameHeight) {
        if (texture != null && textureWidth == frameWidth && textureHeight == frameHeight) {
            return;
        }
        if (texture != null) {
            SDL_DestroyTexture(texture);
            texture = null;
        }
        texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_XRGB8888, SDL_TEXTUREACCESS_STREAMING, frameWidth, frameHeight);
        if (texture == null) {
            throw new IllegalStateException("SDL_CreateTexture failed: " + SDL_GetError());
        }
        textureWidth = frameWidth;
        textureHeight = frameHeight;
        uploadBuffer = BufferUtils.createByteBuffer(frameWidth * frameHeight * Integer.BYTES);
        uploadInts = uploadBuffer.asIntBuffer();
    }

    private String safeRendererName(long rendererHandle) {
        try {
            String name = SDL_GetRendererName(rendererHandle);
            return name != null ? name : rendererName;
        } catch (Throwable t) {
            return rendererName;
        }
    }

    @Override
    public void setCloseRequestHandler(CloseRequestHandler handler) {
        this.closeRequestHandler = handler;
    }

    @Override
    public void setKeyEventHandler(KeyEventHandler handler) {
        this.keyEventHandler = handler;
    }

    @Override
    public void setHostKeyEventHandler(HostKeyEventHandler handler) {
        hostKeyEventHandler = handler;
    }

    @Override
    public void setPointerEventHandler(PointerEventHandler handler) {
        pointerEventHandler = handler;
    }

    @Override
    public synchronized void setPointerCursor(PointerCursor cursor) {
        long selected = cursor == PointerCursor.POINTING_HAND
            ? pointingHandCursor : defaultCursor;
        if (selected != MemoryUtil.NULL) {
            SDL_SetCursor(selected);
        }
    }

    @Override
    public void setGameOverlay(GameOverlay overlay) {
        gameOverlay = overlay;
    }

    @Override
    public synchronized void redrawOverlay() {
        updateOverlayTexture();
        if (renderer != 0L && texture != null) {
            renderScene();
        }
    }

    @Override
    public synchronized void processEvents() {
        if (window == 0L) {
            return;
        }
        SDL_Event event = eventBuffer;
        if (event == null) {
            return;
        }
        SDL_PumpEvents();
        boolean closeRequestHandled = false;
        while (SDL_PollEvent(event)) {
            int type = event.type();
            if (type == SDL_EVENT_QUIT || type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
                if (closeRequestHandled) {
                    continue;
                }
                closeRequestHandled = true;
                CloseRequestHandler handler = closeRequestHandler;
                releasePressedInputKeys();
                boolean accepted = handler == null;
                try {
                    accepted = handler == null || handler.shouldClose();
                } catch (Throwable failure) {
                    Log.warn("Render close handler failed: " + failure.getMessage());
                }
                if (accepted) {
                    close();
                    break;
                }
                continue;
            }
            if (type == SDL_EVENT_WINDOW_FOCUS_LOST) {
                releasePressedInputKeys();
                continue;
            }
            if (type == SDL_EVENT_KEY_DOWN || type == SDL_EVENT_KEY_UP) {
                boolean pressed = type == SDL_EVENT_KEY_DOWN;
                int keycode = event.key().key();
                HostKey hostKey = mapSdlHostKeycode(keycode);
                boolean consumed = hostKey != null && dispatchHostKey(hostKey, pressed);
                InputKey key = mapSdlKeycode(keycode);
                if (!consumed && key != null) {
                    dispatchInputKey(key, pressed);
                }
                continue;
            }
            if (type == SDL_EVENT_MOUSE_MOTION) {
                dispatchPointer(event.motion().x(), event.motion().y(), 0, PointerAction.MOVE);
                continue;
            }
            if (type == SDL_EVENT_MOUSE_BUTTON_DOWN || type == SDL_EVENT_MOUSE_BUTTON_UP) {
                dispatchPointer(event.button().x(), event.button().y(),
                    Byte.toUnsignedInt(event.button().button()),
                    type == SDL_EVENT_MOUSE_BUTTON_DOWN ? PointerAction.DOWN : PointerAction.UP);
            }
        }
    }

    @Override
    public void requestAttention() {
        if (window != 0L) {
            SDL_RaiseWindow(window);
        }
    }

    @Override
    public boolean isRenderSurfaceAvailable() {
        return window != 0L && renderer != 0L;
    }

    @Override
    public synchronized void close() {
        releasePressedInputKeys();
        closeRequestHandler = null;
        keyEventHandler = null;
        hostKeyEventHandler = null;
        pointerEventHandler = null;
        consumedHostKeys.clear();
        if (defaultCursor != MemoryUtil.NULL) {
            SDL_SetCursor(defaultCursor);
        }
        if (pointingHandCursor != MemoryUtil.NULL) {
            SDL_DestroyCursor(pointingHandCursor);
            pointingHandCursor = MemoryUtil.NULL;
        }
        defaultCursor = MemoryUtil.NULL;
        if (texture != null) {
            SDL_DestroyTexture(texture);
            texture = null;
        }
        if (overlayTexture != null) {
            SDL_DestroyTexture(overlayTexture);
            overlayTexture = null;
        }
        textureWidth = 0;
        textureHeight = 0;
        uploadInts = null;
        uploadBuffer = null;
        overlayImage = null;
        overlayUploadInts = null;
        overlayUploadBuffer = null;
        overlayWidth = 0;
        overlayHeight = 0;
        overlayVisible = false;
        SDL_Event currentEventBuffer = eventBuffer;
        eventBuffer = null;
        if (currentEventBuffer != null) {
            currentEventBuffer.free();
        }
        long currentRenderer = renderer;
        renderer = 0L;
        if (currentRenderer != 0L) {
            SDL_DestroyRenderer(currentRenderer);
        }
        long currentWindow = window;
        window = 0L;
        if (currentWindow != 0L) {
            SDL_DestroyWindow(currentWindow);
        }
        shutdownVideo();
    }

    static InputKey mapSdlKeycode(int keycode) {
        return switch (keycode) {
            case SDLK_UP -> InputKey.UP;
            case SDLK_RIGHT -> InputKey.RIGHT;
            case SDLK_DOWN -> InputKey.DOWN;
            case SDLK_LEFT -> InputKey.LEFT;
            case SDLK_RETURN -> InputKey.START;
            case SDLK_SPACE -> InputKey.SELECT;
            case SDLK_X -> InputKey.CROSS;
            case SDLK_Z -> InputKey.SQUARE;
            case SDLK_S -> InputKey.CIRCLE;
            case SDLK_A -> InputKey.TRIANGLE;
            case SDLK_Q -> InputKey.L1;
            case SDLK_W -> InputKey.R1;
            case SDLK_1 -> InputKey.L2;
            case SDLK_2 -> InputKey.R2;
            default -> null;
        };
    }

    static HostKey mapSdlHostKeycode(int keycode) {
        return switch (keycode) {
            case SDLK_F5 -> HostKey.SAVE_STATE;
            case SDLK_F8 -> HostKey.LOAD_STATE;
            case SDLK_ESCAPE -> HostKey.CANCEL;
            case SDLK_RETURN -> HostKey.CONFIRM;
            case SDLK_1 -> HostKey.SLOT_1;
            case SDLK_2 -> HostKey.SLOT_2;
            case SDLK_3 -> HostKey.SLOT_3;
            case SDLK_4 -> HostKey.SLOT_4;
            case SDLK_5 -> HostKey.SLOT_5;
            case SDLK_6 -> HostKey.SLOT_6;
            case SDLK_7 -> HostKey.SLOT_7;
            case SDLK_8 -> HostKey.SLOT_8;
            case SDLK_9 -> HostKey.SLOT_9;
            default -> null;
        };
    }

    private void dispatchInputKey(InputKey key, boolean pressed) {
        boolean changed = pressed ? pressedInputKeys.add(key) : pressedInputKeys.remove(key);
        KeyEventHandler handler = keyEventHandler;
        if (changed && handler != null) {
            handler.handle(key, pressed);
        }
    }

    private boolean dispatchHostKey(HostKey key, boolean pressed) {
        HostKeyEventHandler handler = hostKeyEventHandler;
        if (pressed) {
            if (consumedHostKeys.contains(key)) {
                return true;
            }
            boolean consumed = handler != null && handler.handle(key, true);
            if (consumed) {
                consumedHostKeys.add(key);
            }
            return consumed;
        }
        boolean consumed = consumedHostKeys.remove(key);
        boolean releasedConsumed = handler != null && handler.handle(key, false);
        return consumed || releasedConsumed;
    }

    private void dispatchPointer(float x, float y, int button, PointerAction action) {
        PointerEventHandler handler = pointerEventHandler;
        if (handler == null) {
            return;
        }
        int currentWidth = width;
        int currentHeight = height;
        windowWidthBuffer.clear();
        windowHeightBuffer.clear();
        if (SDL_GetWindowSize(window, windowWidthBuffer, windowHeightBuffer)) {
            currentWidth = Math.max(1, windowWidthBuffer.get(0));
            currentHeight = Math.max(1, windowHeightBuffer.get(0));
        }
        handler.handle(new PointerEvent(x / currentWidth, y / currentHeight, button, action));
    }

    private void releasePressedInputKeys() {
        KeyEventHandler handler = keyEventHandler;
        if (handler != null) {
            for (InputKey key : pressedInputKeys) {
                handler.handle(key, false);
            }
        }
        pressedInputKeys.clear();
        HostKeyEventHandler hostHandler = hostKeyEventHandler;
        if (hostHandler != null) {
            for (HostKey key : consumedHostKeys) {
                hostHandler.handle(key, false);
            }
        }
        consumedHostKeys.clear();
    }

    private void shutdownVideo() {
        if (videoInitialized) {
            SDL_QuitSubSystem(SDL_INIT_VIDEO);
            videoInitialized = false;
        }
    }

    private void ensureOverlaySizeCurrent() {
        if (!overlayVisible || renderer == 0L) {
            return;
        }
        outputWidthBuffer.clear();
        outputHeightBuffer.clear();
        if (SDL_GetCurrentRenderOutputSize(renderer, outputWidthBuffer, outputHeightBuffer)) {
            int width = Math.max(1, outputWidthBuffer.get(0));
            int height = Math.max(1, outputHeightBuffer.get(0));
            if (width != overlayWidth || height != overlayHeight) {
                updateOverlayTexture();
            }
        }
    }

    private void updateOverlayTexture() {
        GameOverlay overlay = gameOverlay;
        if (overlay == null || !overlay.isVisible() || renderer == 0L) {
            overlayVisible = false;
            return;
        }
        outputWidthBuffer.clear();
        outputHeightBuffer.clear();
        int width = this.width;
        int height = this.height;
        if (SDL_GetCurrentRenderOutputSize(renderer, outputWidthBuffer, outputHeightBuffer)) {
            width = Math.max(1, outputWidthBuffer.get(0));
            height = Math.max(1, outputHeightBuffer.get(0));
        }
        ensureOverlayTexture(width, height);
        Graphics2D graphics = overlayImage.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(0, 0, width, height);
            graphics.setComposite(AlphaComposite.SrcOver);
            overlay.render(graphics, width, height);
        } finally {
            graphics.dispose();
        }
        int[] pixels = ((DataBufferInt) overlayImage.getRaster().getDataBuffer()).getData();
        overlayUploadInts.clear();
        overlayUploadInts.put(pixels, 0, Math.min(pixels.length, overlayUploadInts.remaining()));
        overlayUploadBuffer.position(0);
        overlayUploadBuffer.limit(width * height * Integer.BYTES);
        if (!SDL_UpdateTexture(overlayTexture, null, overlayUploadBuffer, width * Integer.BYTES)) {
            throw new IllegalStateException("SDL overlay texture update failed: " + SDL_GetError());
        }
        overlayVisible = true;
    }

    private void ensureOverlayTexture(int width, int height) {
        if (overlayTexture != null && overlayWidth == width && overlayHeight == height) {
            return;
        }
        if (overlayTexture != null) {
            SDL_DestroyTexture(overlayTexture);
        }
        overlayTexture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
            SDL_TEXTUREACCESS_STREAMING, width, height);
        if (overlayTexture == null) {
            throw new IllegalStateException("SDL overlay texture creation failed: " + SDL_GetError());
        }
        if (!SDL_SetTextureBlendMode(overlayTexture, SDL_BLENDMODE_BLEND)) {
            throw new IllegalStateException("SDL overlay blend mode failed: " + SDL_GetError());
        }
        overlayWidth = width;
        overlayHeight = height;
        overlayImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        overlayUploadBuffer = BufferUtils.createByteBuffer(width * height * Integer.BYTES);
        overlayUploadInts = overlayUploadBuffer.asIntBuffer();
    }

    private void renderScene() {
        if (!SDL_RenderClear(renderer)) {
            throw new IllegalStateException("SDL_RenderClear failed: " + SDL_GetError());
        }
        if (texture != null && !SDL_RenderTexture(renderer, texture, null, null)) {
            throw new IllegalStateException("SDL_RenderTexture failed: " + SDL_GetError());
        }
        if (overlayVisible && overlayTexture != null
            && !SDL_RenderTexture(renderer, overlayTexture, null, null)) {
            throw new IllegalStateException("SDL overlay render failed: " + SDL_GetError());
        }
        if (!SDL_RenderPresent(renderer)) {
            throw new IllegalStateException("SDL_RenderPresent failed: " + SDL_GetError());
        }
    }
}
