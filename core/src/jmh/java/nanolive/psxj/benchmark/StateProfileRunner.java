package nanolive.psxj.benchmark;

import nanolive.psxj.emu.PsxEmulator;
import nanolive.psxj.emu.devices.SioController;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Runs a user-supplied save state headlessly for sampling/JFR profiling. */
public final class StateProfileRunner {

    private StateProfileRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 7 && args.length != 8) {
            throw new IllegalArgumentException(
                "Expected: <bios> <game> <state-json> <warmup-seconds> <duration-seconds> "
                    + "[warmup-system-cycles measured-system-cycles [sample-system-cycles]]");
        }
        Path bios = Path.of(args[0]);
        Path game = Path.of(args[1]);
        Path state = Path.of(args[2]);
        int warmupSeconds = Math.max(0, Integer.parseInt(args[3]));
        int durationSeconds = Math.max(1, Integer.parseInt(args[4]));
        boolean deterministic = args.length >= 7;
        long warmupCycles = deterministic ? Math.max(0L, Long.parseLong(args[5])) : 0L;
        long measuredCycles = deterministic ? Math.max(1L, Long.parseLong(args[6])) : 0L;
        long sampleCycles = args.length == 8 ? Math.max(1L, Long.parseLong(args[7])) : measuredCycles;
        boolean exact = Boolean.getBoolean("psxj.profile.exact");
        boolean profileStalls = Boolean.getBoolean("psxj.profile.stalls");
        boolean reloadAfterWarmup = Boolean.getBoolean("psxj.profile.reloadAfterWarmup");
        boolean captureFrames = Boolean.getBoolean("psxj.profile.captureFrames");
        boolean machineState = Boolean.getBoolean("psxj.profile.machineState");
        boolean printStateHash = Boolean.getBoolean("psxj.profile.stateHash");
        String stateOutput = System.getProperty("psxj.profile.stateOutput", "");
        String recordingOutput = System.getProperty("psxj.profile.recording", "");
        int padMask = parsePadMask(System.getProperty("psxj.profile.buttons", ""));

        try (PsxEmulator emulator = new PsxEmulator(bios, 100)) {
            emulator.loadGame(game, game.getFileName().toString());
            if (deterministic) {
                emulator.loadBios();
                String stateJson = Files.readString(state, StandardCharsets.UTF_8);
                emulator.loadStateJson(stateJson);
                if (padMask != 0) {
                    emulator.setPadButtonState(padMask, true);
                }
                emulator.runHeadlessCycles(warmupCycles);
                if (reloadAfterWarmup && warmupCycles > 0) {
                    emulator.loadStateJson(stateJson);
                    if (padMask != 0) {
                        emulator.setPadButtonState(padMask, true);
                    }
                }
                PsxEmulator.PerformanceSnapshot before = emulator.performanceSnapshot();
                Recording recording = startRecording(recordingOutput);
                AutoCloseable stallDiagnostics = profileStalls
                    ? emulator.startHeadlessStallDiagnostics() : null;
                long started = System.nanoTime();
                PsxEmulator.PerformanceSnapshot after = before;
                long finished;
                try {
                    long remaining = measuredCycles;
                    int sample = 0;
                    while (remaining > 0) {
                        long interval = Math.min(remaining, sampleCycles);
                        PsxEmulator.PerformanceSnapshot sampleBefore = after;
                        long sampleStarted = System.nanoTime();
                        after = exact
                            ? emulator.runHeadlessCyclesExactly(interval)
                            : captureFrames
                                ? emulator.runHeadlessCyclesWithFrameCapture(interval)
                                : emulator.runHeadlessCycles(interval);
                        long sampleFinished = System.nanoTime();
                        if (sampleCycles < measuredCycles) {
                            printResult(emulator, sampleBefore, after, sampleStarted, sampleFinished,
                                "sample=" + (++sample));
                            if (machineState) {
                                printMachineDiagnostic(emulator.machineDiagnostic());
                            }
                        }
                        remaining -= interval;
                    }
                    finished = System.nanoTime();
                } finally {
                    if (stallDiagnostics != null) {
                        stallDiagnostics.close();
                    }
                    finishRecording(recording);
                }
                printResult(emulator, before, after, started, finished,
                    "warmupCycles=" + warmupCycles);
                if (printStateHash || !stateOutput.isBlank()) {
                    String outputStateJson = emulator.saveStateJson();
                    byte[] json = outputStateJson.getBytes(StandardCharsets.UTF_8);
                    if (!stateOutput.isBlank()) {
                        Files.writeString(Path.of(stateOutput), outputStateJson, StandardCharsets.UTF_8);
                    }
                    if (!printStateHash) {
                        return;
                    }
                    System.out.println("STATE_SHA256 " + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(json)));
                }
                return;
            }
            emulator.start();
            emulator.loadStateJson(Files.readString(state, StandardCharsets.UTF_8));
            if (warmupSeconds > 0) {
                Thread.sleep(warmupSeconds * 1_000L);
            }
            PsxEmulator.PerformanceSnapshot before = emulator.performanceSnapshot();
            Recording recording = startRecording(recordingOutput);
            long started = System.nanoTime();
            long finished;
            try {
                Thread.sleep(durationSeconds * 1_000L);
                finished = System.nanoTime();
            } finally {
                finishRecording(recording);
            }
            PsxEmulator.PerformanceSnapshot after = emulator.performanceSnapshot();

            printResult(emulator, before, after, started, finished,
                "warmupSeconds=" + warmupSeconds);
        }
    }

    private static Recording startRecording(String output) throws Exception {
        if (output == null || output.isBlank()) {
            return null;
        }
        Path destination = Path.of(output).toAbsolutePath();
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setDestination(destination);
        recording.start();
        return recording;
    }

    private static void finishRecording(Recording recording) {
        if (recording == null) return;
        try {
            recording.stop();
        } finally {
            recording.close();
        }
    }

    private static void printResult(
        PsxEmulator emulator,
        PsxEmulator.PerformanceSnapshot before,
        PsxEmulator.PerformanceSnapshot after,
        long started,
        long finished,
        String warmup
    ) {
        double hostSeconds = (finished - started) / 1_000_000_000.0;
        long systemCycles = after.systemCycles() - before.systemCycles();
        long frames = Integer.toUnsignedLong(after.gpuFrames() - before.gpuFrames());
        double cycleRate = systemCycles / hostSeconds;
        double speedPercent = cycleRate * 100.0 / emulator.hardwareProfile().cpuClockHz();
        System.out.printf(Locale.ROOT,
            "PROFILE %s hostSeconds=%.3f systemCycles=%d cycleRate=%.0f speed=%.2f%% frames=%d fps=%.2f%n",
            warmup, hostSeconds, systemCycles, cycleRate, speedPercent, frames,
            frames / hostSeconds);
    }

    private static int parsePadMask(String buttons) {
        int mask = 0;
        for (String value : buttons.split("[,;+]")) {
            mask |= switch (value.trim().toUpperCase(Locale.ROOT)) {
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
                case "L2" -> SioController.PAD_L2;
                case "R2" -> SioController.PAD_R2;
                case "", "NONE" -> 0;
                default -> throw new IllegalArgumentException("Unknown profile button: " + value);
            };
        }
        return mask;
    }

    private static void printMachineDiagnostic(PsxEmulator.MachineDiagnostic value) {
        System.out.printf(Locale.ROOT,
            "MACHINE pc=%08x frame=%d hash=%08x gpu=%08x irq=%03x/%03x timers=%04x,%04x,%04x%n",
            value.pc(), value.gpuFrame(), value.frameHash(), value.gpuStatus(),
            value.irqStatus(), value.irqMask(), value.timer0(), value.timer1(), value.timer2());
        var gpu = value.gpu();
        System.out.printf(Locale.ROOT,
            "  GPU vram=%08x display=%d,%d completed=%d,%d/%dx%d dirty=%d,%d-%d,%d words=%d/%d cmd=%08x rem=%d busy=%d journal=%d%n",
            gpu.vramHash(), gpu.displayStartX(), gpu.displayStartY(),
            gpu.completedStartX(), gpu.completedStartY(), gpu.completedWidth(), gpu.completedHeight(),
            gpu.dirtyMinX(), gpu.dirtyMinY(), gpu.dirtyMaxX(), gpu.dirtyMaxY(),
            gpu.totalGp0Words(), gpu.totalImageWords(), gpu.command(), gpu.wordsRemaining(),
            gpu.renderBusyCycles(), gpu.renderJournalCount());
        var cd = value.cdrom();
        System.out.printf(Locale.ROOT,
            "  CD lba=%d->%d mode=%02x irq=%02x/%02x state=%s%s%s cycles=%d,%d,%d events=%d/%d data=%d/%d xa=%d:%d/%d queued=%d%n",
            cd.currentLba(), cd.targetLba(), cd.mode(), cd.interruptFlags(), cd.interruptEnable(),
            cd.reading() ? "R" : "-", cd.playing() ? "P" : "-", cd.seeking() ? "S" : "-",
            cd.readCyclesRemaining(), cd.playCyclesRemaining(), cd.seekCyclesRemaining(),
            cd.pendingEvents(), cd.nextEventCycles(), cd.pendingDataBlocks(), cd.activeDataBytes(),
            cd.xaFile(), cd.xaChannel(), cd.decodedXaFrames(), cd.queuedAudioFrames());
        var dma = value.dma();
        var spu = value.spu();
        System.out.printf(Locale.ROOT,
            "  DMA dpcr=%08x dicr=%08x active=%02x cpu=%02x gpu=%08x/%d/%d cd=%08x/%d/%d SPU cnt=%04x stat=%04x irq=%04x/%s cdq=%d%n",
            dma.dpcr(), dma.dicr(), dma.enabledChannels(), dma.cpuWindowChannels(),
            dma.gpuControl(), dma.gpuRemainingWords(), dma.gpuRemainingBlocks(),
            dma.cdControl(), dma.cdRemainingWords(), dma.cdRemainingBlocks(),
            spu.control(), spu.status(), spu.irqAddress(), spu.irqFlag(), spu.queuedCdAudioFrames());
    }
}
