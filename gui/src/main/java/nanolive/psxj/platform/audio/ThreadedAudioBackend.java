package nanolive.psxj.platform.audio;

import nanolive.psxj.emu.api.AudioBackend;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the host audio backend on its own platform thread. */
public final class ThreadedAudioBackend implements AudioBackend {

    private static final int DEFAULT_QUEUE_PACKETS = 12;
    private static final long PUMP_INTERVAL_MILLIS = 1L;

    private final AudioBackend delegate;
    private final ArrayBlockingQueue<short[]> queue;
    private final AtomicReference<RuntimeException> workerFailure = new AtomicReference<>();
    private volatile boolean open;
    private volatile boolean closing;
    private volatile boolean paused;
    private Thread worker;

    public ThreadedAudioBackend(AudioBackend delegate) {
        this(delegate, DEFAULT_QUEUE_PACKETS);
    }

    ThreadedAudioBackend(AudioBackend delegate, int queuePackets) {
        this.delegate = Objects.requireNonNull(delegate);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queuePackets));
    }

    @Override
    public synchronized void open() {
        if (open) {
            return;
        }
        queue.clear();
        workerFailure.set(null);
        closing = false;
        paused = false;
        CountDownLatch started = new CountDownLatch(1);
        worker = new Thread(() -> runWorker(started), "psxj-audio");
        worker.setDaemon(true);
        worker.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
        worker.start();
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                closing = true;
                worker.interrupt();
                throw new IllegalStateException("Timed out while opening audio backend");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closing = true;
            worker.interrupt();
            throw new IllegalStateException("Interrupted while opening audio backend", ex);
        }
        throwIfWorkerFailed();
        open = true;
    }

    @Override
    public void submitSamples(short[] interleavedStereo) {
        if (!open || paused || interleavedStereo == null || interleavedStereo.length == 0) {
            return;
        }
        throwIfWorkerFailed();
        try {
            while (open && !closing && !paused
                && !queue.offer(interleavedStereo, 10, TimeUnit.MILLISECONDS)) {
                // Check for worker errors while waiting for queue space.
                throwIfWorkerFailed();
            }
            if (paused) {
                queue.remove(interleavedStereo);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        throwIfWorkerFailed();
    }

    @Override
    public void pause() {
        if (!open || closing) {
            return;
        }
        paused = true;
    }

    @Override
    public void resume() {
        if (!open || closing) {
            return;
        }
        paused = false;
    }

    @Override
    public synchronized void close() {
        Thread currentWorker = worker;
        if (currentWorker == null) {
            return;
        }
        closing = true;
        currentWorker.interrupt();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (currentWorker.isAlive() && currentWorker != Thread.currentThread()) {
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
        worker = null;
        open = false;
        queue.clear();
    }

    private void runWorker(CountDownLatch started) {
        boolean delegateOpened = false;
        try {
            delegate.open();
            delegateOpened = true;
            started.countDown();
            boolean delegatePaused = false;
            while (!closing) {
                if (paused != delegatePaused) {
                    if (paused) {
                        delegate.pause();
                    } else {
                        delegate.resume();
                    }
                    delegatePaused = paused;
                }
                if (delegatePaused) {
                    TimeUnit.MILLISECONDS.sleep(PUMP_INTERVAL_MILLIS);
                    continue;
                }
                short[] samples = queue.poll(PUMP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (samples != null) {
                    delegate.submitSamples(samples);
                    // Drain real PCM before asking the backend to bridge a gap.
                    while ((samples = queue.poll()) != null) {
                        delegate.submitSamples(samples);
                    }
                }
                if (delegate instanceof PumpedAudioBackend pumped) {
                    pumped.pump();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            workerFailure.compareAndSet(null, ex);
        } finally {
            started.countDown();
            if (delegateOpened) {
                try {
                    delegate.close();
                } catch (RuntimeException ex) {
                    workerFailure.compareAndSet(null, ex);
                }
            }
        }
    }

    private void throwIfWorkerFailed() {
        RuntimeException failure = workerFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Audio backend worker failed", failure);
        }
    }
}
