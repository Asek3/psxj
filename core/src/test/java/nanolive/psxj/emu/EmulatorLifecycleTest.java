package nanolive.psxj.emu;

import nanolive.psxj.emu.api.AudioBackend;
import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.emu.video.GpuFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmulatorLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void requestStopDoesNotWaitForBackendShutdown() throws Exception {
        Path bios = temporaryDirectory.resolve("bios.bin");
        Files.write(bios, new byte[512 * 1024]);
        BlockingCloseRenderer renderer = new BlockingCloseRenderer();
        PsxEmulator emulator = new PsxEmulator(bios, 100);
        emulator.setBackends(renderer, null);
        emulator.start();

        assertTrue(renderer.opened.await(2, TimeUnit.SECONDS));
        var termination = emulator.terminationFuture();
        assertTimeoutPreemptively(Duration.ofMillis(100), emulator::requestStop);
        assertTrue(renderer.closeEntered.await(2, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class,
            () -> termination.get(100, TimeUnit.MILLISECONDS));
        assertFalse(renderer.interruptedOnEntry);

        emulator.start();
        assertSame(termination, emulator.terminationFuture());

        renderer.allowClose.countDown();
        termination.get(2, TimeUnit.SECONDS);
        assertTrue(renderer.closed);
        emulator.close();
    }

    @Test
    void backendFailureCannotSkipRemainingShutdown() throws Exception {
        Path bios = temporaryDirectory.resolve("failure-bios.bin");
        Files.write(bios, new byte[512 * 1024]);
        RecordingRenderer renderer = new RecordingRenderer();
        PsxEmulator emulator = new PsxEmulator(bios, 100);
        emulator.setBackends(renderer, new ThrowingAudioBackend());
        emulator.start();

        assertTrue(renderer.opened.await(2, TimeUnit.SECONDS));
        emulator.requestStop();
        emulator.terminationFuture().get(2, TimeUnit.SECONDS);

        assertTrue(renderer.closed);
        emulator.close();
    }

    @Test
    void emulatorPauseAlsoPausesAndResumesHostAudio() throws Exception {
        Path bios = temporaryDirectory.resolve("pause-bios.bin");
        Files.write(bios, new byte[512 * 1024]);
        RecordingAudioBackend audio = new RecordingAudioBackend();
        PsxEmulator emulator = new PsxEmulator(bios, 100);
        emulator.setBackends(null, audio);
        emulator.start();

        assertTrue(audio.opened.await(2, TimeUnit.SECONDS));
        emulator.pause();
        assertTrue(audio.paused.get());
        emulator.resume();
        assertTrue(audio.resumed.get());

        emulator.requestStop();
        emulator.terminationFuture().get(2, TimeUnit.SECONDS);
        emulator.close();
    }

    @Test
    void frameObserverDoesNotHoldMachineLock() throws Exception {
        Path bios = temporaryDirectory.resolve("observer-bios.bin");
        Files.write(bios, new byte[512 * 1024]);
        PsxEmulator emulator = new PsxEmulator(bios, 100);
        AtomicBoolean firstFrame = new AtomicBoolean();
        AtomicBoolean memoryReadCompleted = new AtomicBoolean();
        CountDownLatch observerFinished = new CountDownLatch(1);
        emulator.setFrameListener(() -> {
            if (!firstFrame.compareAndSet(false, true)) {
                return;
            }
            Thread reader = Thread.ofPlatform().start(() -> {
                byte[] value = new byte[4];
                memoryReadCompleted.set(
                    emulator.readAchievementMemory(0, value, 0, value.length) == value.length);
            });
            try {
                reader.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            observerFinished.countDown();
        });
        emulator.start();

        assertTrue(observerFinished.await(2, TimeUnit.SECONDS));
        assertTrue(memoryReadCompleted.get());

        emulator.requestStop();
        emulator.terminationFuture().get(2, TimeUnit.SECONDS);
        emulator.close();
    }

    private static final class BlockingCloseRenderer implements RenderBackend {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private volatile boolean closed;
        private volatile boolean interruptedOnEntry;

        @Override
        public void open() {
            opened.countDown();
        }

        @Override
        public void presentFrame(GpuFrame frame) {
        }

        @Override
        public void close() {
            interruptedOnEntry = Thread.currentThread().isInterrupted();
            closeEntered.countDown();
            try {
                allowClose.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            closed = true;
        }
    }

    private static final class RecordingRenderer implements RenderBackend {
        private final CountDownLatch opened = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public void open() {
            opened.countDown();
        }

        @Override
        public void presentFrame(GpuFrame frame) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class ThrowingAudioBackend implements AudioBackend {
        @Override
        public void open() {
        }

        @Override
        public void submitSamples(short[] interleavedStereo) {
        }

        @Override
        public void close() {
            throw new IllegalStateException("expected test failure");
        }
    }

    private static final class RecordingAudioBackend implements AudioBackend {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicBoolean resumed = new AtomicBoolean();

        @Override
        public void open() {
            opened.countDown();
        }

        @Override
        public void pause() {
            paused.set(true);
        }

        @Override
        public void resume() {
            resumed.set(true);
        }

        @Override
        public void close() {
        }
    }
}
