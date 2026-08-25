package nanolive.psxj.benchmark;

import nanolive.psxj.emu.PsxEmulator;
import nanolive.psxj.emu.api.GamepadBackend;
import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.video.GpuFrame;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/** Runs a PS-X EXE headlessly and saves its last rendered frame. */
public final class ConformanceRunner {

    private ConformanceRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                "Expected: <bios> <exe> <seconds> <output.png> [input-script]");
        }

        Path bios = Path.of(args[0]);
        Path executable = Path.of(args[1]);
        int seconds = Math.max(1, Integer.parseInt(args[2]));
        Path output = Path.of(args[3]);
        FrameCapture capture = new FrameCapture();
        ScriptedGamepad gamepad = new ScriptedGamepad();
        CountDownLatch executableReady = new CountDownLatch(1);
        StringBuilder ttyLine = new StringBuilder();

        try (PsxEmulator emulator = new PsxEmulator(bios, 100)) {
            emulator.setBackends(capture, null, gamepad);
            emulator.setBiosTtyCharacterSink(value -> {
                char character = (char) value;
                System.out.print(character);
                synchronized (ttyLine) {
                    ttyLine.append(character);
                    if (ttyLine.indexOf("Running tests") >= 0) {
                        executableReady.countDown();
                    }
                    if (character == '\n' && ttyLine.length() > 256) {
                        ttyLine.delete(0, ttyLine.length() - 128);
                    }
                }
            });
            emulator.loadGame(executable, executable.getFileName().toString());
            emulator.start();
            if (args.length == 5 && !args[4].isBlank()) {
                executableReady.await(15, TimeUnit.SECONDS);
                capture.awaitVisible(15, TimeUnit.SECONDS);
                runInputScript(gamepad, args[4]);
            }
            Thread.sleep(seconds * 1_000L);
            PsxEmulator.PerformanceSnapshot snapshot = emulator.performanceSnapshot();
            emulator.stop();
            capture.write(output);
            System.out.printf(Locale.ROOT, "%ncycles=%d frames=%d output=%s%n",
                snapshot.systemCycles(), snapshot.gpuFrames(), output.toAbsolutePath());
        }
    }

    private static void runInputScript(ScriptedGamepad gamepad, String script)
        throws InterruptedException {
        for (String action : script.split(",")) {
            String[] parts = action.trim().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid input action: " + action);
            }
            Thread.sleep(Math.max(0, Long.parseLong(parts[0])));
            int mask = buttonMask(parts[1]);
            gamepad.setButton(mask, true);
            Thread.sleep(300);
            gamepad.setButton(mask, false);
        }
    }

    private static final class ScriptedGamepad implements GamepadBackend {
        private final AtomicInteger pressed = new AtomicInteger();

        @Override
        public void open() {
        }

        @Override
        public void poll(StateSink sink) {
            sink.update(pressed.get(), 0x80, 0x80, 0x80, 0x80);
        }

        @Override
        public void close() {
        }

        private void setButton(int mask, boolean down) {
            pressed.updateAndGet(value -> down ? value | mask : value & ~mask);
        }
    }

    private static int buttonMask(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "UP" -> SioController.PAD_UP;
            case "RIGHT" -> SioController.PAD_RIGHT;
            case "DOWN" -> SioController.PAD_DOWN;
            case "LEFT" -> SioController.PAD_LEFT;
            case "START" -> SioController.PAD_START;
            case "SELECT" -> SioController.PAD_SELECT;
            case "CROSS" -> SioController.PAD_CROSS;
            case "SQUARE" -> SioController.PAD_SQUARE;
            case "CIRCLE" -> SioController.PAD_CIRCLE;
            case "TRIANGLE" -> SioController.PAD_TRIANGLE;
            case "L1" -> SioController.PAD_L1;
            case "R1" -> SioController.PAD_R1;
            default -> throw new IllegalArgumentException("Unknown button: " + name);
        };
    }

    private static final class FrameCapture implements RenderBackend {
        private volatile GpuFrame frame;
        private volatile GpuFrame lastVisibleFrame;
        private int visiblePixels;
        private int lastVisiblePixels;
        private final CountDownLatch visible = new CountDownLatch(1);

        @Override
        public void open() {
        }

        @Override
        public synchronized void presentFrame(GpuFrame frame) {
            int visible = 0;
            for (int pixel : frame.pixels()) {
                if ((pixel & 0x00FF_FFFF) != 0) {
                    visible++;
                }
            }
            if (this.frame == null || visible >= visiblePixels) {
                this.frame = new GpuFrame(
                    frame.width(), frame.height(), frame.pixels().clone(), frame.frameId());
                visiblePixels = visible;
            }
            if (visible > 0) {
                lastVisibleFrame = new GpuFrame(
                    frame.width(), frame.height(), frame.pixels().clone(), frame.frameId());
                lastVisiblePixels = visible;
                if (visible > 2_000) {
                    this.visible.countDown();
                }
            }
        }

        @Override
        public void close() {
        }

        private boolean awaitVisible(long timeout, TimeUnit unit) throws InterruptedException {
            return visible.await(timeout, unit);
        }

        private void write(Path output) throws IOException {
            GpuFrame captured = lastVisibleFrame != null ? lastVisibleFrame : frame;
            if (captured == null) {
                throw new IOException("The executable did not render a frame");
            }
            System.out.println("capturedVisiblePixels=" + lastVisiblePixels
                + " peakVisiblePixels=" + visiblePixels);
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BufferedImage image = new BufferedImage(
                captured.width(), captured.height(), BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, captured.width(), captured.height(),
                captured.pixels(), 0, captured.width());
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IOException("No PNG writer is available");
            }
        }
    }
}
