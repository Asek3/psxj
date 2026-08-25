package nanolive.psxj.platform.render;

import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.emu.video.GpuFrame;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Owns the renderer thread and keeps only the newest completed frame. */
public final class ThreadedRenderBackend implements RenderBackend, GameOverlayHost {

    private static final long IDLE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int MAX_RETAINED_PIXEL_BUFFERS = 3;

    private final RenderBackend delegate;
    private final Object frameLock = new Object();
    private final ArrayDeque<int[]> freePixelBuffers = new ArrayDeque<>();
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private final AtomicBoolean closeNotified = new AtomicBoolean();
    private final GameOverlay gameOverlay = new GameOverlay();

    private volatile boolean open;
    private volatile boolean closing;
    private volatile boolean surfaceAvailable;
    private volatile boolean attentionRequested;
    private volatile CloseRequestHandler closeRequestHandler;
    private volatile KeyEventHandler keyEventHandler;
    private volatile HostKeyEventHandler hostKeyEventHandler;
    private volatile PointerEventHandler pointerEventHandler;
    private GpuFrame pendingFrame;
    private Thread worker;

    public ThreadedRenderBackend(RenderBackend delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public synchronized void open() {
        if (open) {
            return;
        }
        closing = false;
        surfaceAvailable = false;
        attentionRequested = false;
        closeNotified.set(false);
        workerFailure.set(null);
        synchronized (frameLock) {
            pendingFrame = null;
            freePixelBuffers.clear();
        }

        CountDownLatch started = new CountDownLatch(1);
        worker = Thread.ofPlatform()
            .daemon(true)
            .name("psxj-render")
            .start(() -> runWorker(started));
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                closing = true;
                LockSupport.unpark(worker);
                throw new IllegalStateException("Timed out while opening render backend");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closing = true;
            LockSupport.unpark(worker);
            throw new IllegalStateException("Interrupted while opening render backend", ex);
        }
        throwIfWorkerFailed();
        open = true;
    }

    @Override
    public void presentFrame(GpuFrame frame) {
        if (!open || closing || frame == null) {
            return;
        }
        throwIfWorkerFailed();
        int pixelCount = Math.max(1, frame.width()) * Math.max(1, frame.height());
        int copyLength = Math.min(frame.pixels().length, pixelCount);
        int[] pixels = acquirePixelBuffer(pixelCount);
        System.arraycopy(frame.pixels(), 0, pixels, 0, copyLength);
        if (copyLength < pixelCount) {
            java.util.Arrays.fill(pixels, copyLength, pixelCount, 0);
        }
        GpuFrame snapshot = new GpuFrame(frame.width(), frame.height(), pixels, frame.frameId());

        synchronized (frameLock) {
            if (closing) {
                recyclePixelBuffer(pixels);
                return;
            }
            if (pendingFrame != null) {
                recyclePixelBuffer(pendingFrame.pixels());
            }
            pendingFrame = snapshot;
        }
        LockSupport.unpark(worker);
    }

    @Override
    public void processEvents() {
        // Native event polling stays on the renderer thread.
        throwIfWorkerFailed();
    }

    @Override
    public boolean isRenderSurfaceAvailable() {
        return open && !closing && surfaceAvailable;
    }

    @Override
    public void requestAttention() {
        if (!closing) {
            attentionRequested = true;
            LockSupport.unpark(worker);
        }
    }

    @Override
    public void setCloseRequestHandler(CloseRequestHandler handler) {
        closeRequestHandler = handler;
    }

    @Override
    public void setKeyEventHandler(KeyEventHandler handler) {
        keyEventHandler = handler;
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
    public void configureSaveStateOverlay(
                                          java.util.function.Supplier<SaveStateSlot[]> occupiedSlots,
                                          java.util.function.IntConsumer saveAction,
                                          java.util.function.IntConsumer loadAction) {
        gameOverlay.configure(occupiedSlots, saveAction, loadAction);
        requestOverlayRedraw();
    }

    @Override
    public void setAchievements(java.util.List<AchievementInfo> achievements) {
        gameOverlay.setAchievements(achievements);
        requestOverlayRedraw();
    }

    @Override
    public void setRetroAchievementsEnabled(boolean enabled) {
        gameOverlay.setRetroAchievementsEnabled(enabled);
        requestOverlayRedraw();
    }

    @Override
    public void updateAchievementBadge(int id, java.awt.image.BufferedImage badge) {
        gameOverlay.updateAchievementBadge(id, badge);
        requestOverlayRedraw();
    }

    @Override
    public void setOverlayOpenListener(java.util.function.Consumer<Boolean> listener) {
        gameOverlay.setOpenListener(listener);
    }

    @Override
    public void showOverlayToast(String message) {
        gameOverlay.showToast(message);
        requestOverlayRedraw();
    }

    @Override
    public void showAchievement(String title, String description, int points,
                                java.awt.image.BufferedImage badge) {
        gameOverlay.showAchievement(title, description, points, badge);
        requestOverlayRedraw();
    }

    @Override
    public void updateAchievementBadge(String title, java.awt.image.BufferedImage badge) {
        gameOverlay.updateAchievementBadge(title, badge);
        requestOverlayRedraw();
    }

    @Override
    public void refreshOverlaySlots() {
        gameOverlay.refreshSlots();
        requestOverlayRedraw();
    }

    @Override
    public synchronized void close() {
        Thread currentWorker = worker;
        if (currentWorker == null) {
            return;
        }
        closing = true;
        LockSupport.unpark(currentWorker);
        if (currentWorker != Thread.currentThread()) {
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (currentWorker.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedJoin(currentWorker, remaining);
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        open = false;
        surfaceAvailable = false;
        synchronized (frameLock) {
            if (pendingFrame != null) {
                recyclePixelBuffer(pendingFrame.pixels());
                pendingFrame = null;
            }
            freePixelBuffers.clear();
        }
        closeRequestHandler = null;
        keyEventHandler = null;
        hostKeyEventHandler = null;
        pointerEventHandler = null;
    }

    private void runWorker(CountDownLatch started) {
        boolean delegateOpened = false;
        long lastOverlayFrame = 0L;
        boolean overlayWasVisible = false;
        WindowOverlayTarget overlayTarget = delegate instanceof WindowOverlayTarget target
            ? target : null;
        try {
            delegate.setCloseRequestHandler(this::notifyCloseRequested);
            delegate.setKeyEventHandler(this::dispatchKeyEvent);
            delegate.setHostKeyEventHandler(this::dispatchHostKeyEvent);
            delegate.setPointerEventHandler(this::dispatchPointerEvent);
            if (overlayTarget != null) {
                overlayTarget.setGameOverlay(gameOverlay);
            }
            delegate.open();
            delegateOpened = true;
            surfaceAvailable = delegate.isRenderSurfaceAvailable();
            int overlayRefreshRate = overlayTarget == null ? 60 : overlayTarget.overlayRefreshRateHz();
            long overlayFrameNanos = TimeUnit.SECONDS.toNanos(1) / Math.clamp(overlayRefreshRate, 30, 360);
            long lastRefreshRateProbe = System.nanoTime();
            started.countDown();

            while (!closing) {
                delegate.processEvents();
                surfaceAvailable = delegate.isRenderSurfaceAvailable();
                if (attentionRequested) {
                    attentionRequested = false;
                    delegate.requestAttention();
                }

                long now = System.nanoTime();
                if (overlayTarget != null
                    && now - lastRefreshRateProbe >= TimeUnit.SECONDS.toNanos(1)) {
                    overlayRefreshRate = overlayTarget.overlayRefreshRateHz();
                    overlayFrameNanos = TimeUnit.SECONDS.toNanos(1)
                        / Math.clamp(overlayRefreshRate, 30, 360);
                    lastRefreshRateProbe = now;
                }
                boolean overlayVisible = gameOverlay.isVisible();
                long redrawGeneration = gameOverlay.redrawGeneration();
                boolean overlayNeedsFrame = gameOverlay.redrawRequested()
                    || gameOverlay.animationActive(now)
                    || overlayVisible != overlayWasVisible;
                if (surfaceAvailable && overlayTarget != null && overlayNeedsFrame
                    && now - lastOverlayFrame >= overlayFrameNanos) {
                    overlayTarget.redrawOverlay();
                    gameOverlay.markRedrawn(redrawGeneration);
                    lastOverlayFrame = now;
                    overlayWasVisible = overlayVisible;
                }

                GpuFrame frame = takePendingFrame();
                if (frame != null) {
                    try {
                        if (surfaceAvailable) {
                            if (overlayTarget == null) {
                                gameOverlay.render(frame.pixels(), frame.width(), frame.height());
                            }
                            delegate.presentFrame(frame);
                        }
                    } finally {
                        synchronized (frameLock) {
                            recyclePixelBuffer(frame.pixels());
                        }
                    }
                } else {
                    LockSupport.parkNanos(this, IDLE_POLL_NANOS);
                }
            }
        } catch (Throwable failure) {
            if (!closing) {
                workerFailure.compareAndSet(null, failure);
            }
        } finally {
            started.countDown();
            surfaceAvailable = false;
            if (delegateOpened) {
                try {
                    delegate.close();
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            }
        }
    }

    private GpuFrame takePendingFrame() {
        synchronized (frameLock) {
            GpuFrame frame = pendingFrame;
            pendingFrame = null;
            return frame;
        }
    }

    private int[] acquirePixelBuffer(int pixelCount) {
        synchronized (frameLock) {
            if (pendingFrame != null && pendingFrame.pixels().length == pixelCount) {
                int[] pixels = pendingFrame.pixels();
                pendingFrame = null;
                return pixels;
            }
            int candidates = freePixelBuffers.size();
            for (int i = 0; i < candidates; i++) {
                int[] pixels = freePixelBuffers.removeFirst();
                if (pixels.length == pixelCount) {
                    return pixels;
                }
                recyclePixelBuffer(pixels);
            }
        }
        return new int[pixelCount];
    }

    private void recyclePixelBuffer(int[] pixels) {
        if (pixels != null && freePixelBuffers.size() < MAX_RETAINED_PIXEL_BUFFERS) {
            freePixelBuffers.addLast(pixels);
        }
    }

    private boolean notifyCloseRequested() {
        CloseRequestHandler handler = closeRequestHandler;
        if (handler == null || !closeNotified.compareAndSet(false, true)) {
            return handler == null;
        }
        boolean accepted = false;
        try {
            accepted = handler.shouldClose();
            return accepted;
        } finally {
            if (!accepted) {
                closeNotified.set(false);
            }
        }
    }

    private void dispatchKeyEvent(InputKey key, boolean pressed) {
        if (gameOverlay.consumesPadInput()) {
            return;
        }
        KeyEventHandler handler = keyEventHandler;
        if (handler != null) {
            handler.handle(key, pressed);
        }
    }

    private boolean dispatchHostKeyEvent(HostKey key, boolean pressed) {
        if (gameOverlay.handleHostKey(key, pressed)) {
            delegate.setPointerCursor(PointerCursor.DEFAULT);
            requestOverlayRedraw();
            return true;
        }
        HostKeyEventHandler handler = hostKeyEventHandler;
        return handler != null && handler.handle(key, pressed);
    }

    private boolean dispatchPointerEvent(PointerEvent event) {
        if (gameOverlay.handlePointer(event)) {
            delegate.setPointerCursor(gameOverlay.wantsPointingHand()
                ? PointerCursor.POINTING_HAND : PointerCursor.DEFAULT);
            requestOverlayRedraw();
            return true;
        }
        delegate.setPointerCursor(PointerCursor.DEFAULT);
        PointerEventHandler handler = pointerEventHandler;
        return handler != null && handler.handle(event);
    }

    private void requestOverlayRedraw() {
        gameOverlay.requestRedraw();
        Thread currentWorker = worker;
        if (currentWorker != null) {
            LockSupport.unpark(currentWorker);
        }
    }

    private void throwIfWorkerFailed() {
        Throwable failure = workerFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Render backend worker failed", failure);
        }
    }
}
