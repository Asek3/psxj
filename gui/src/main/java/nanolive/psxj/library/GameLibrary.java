package nanolive.psxj.library;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.GameHistory;
import nanolive.psxj.util.Log;
import nanolive.psxj.util.TaskDispatcher;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GameLibrary {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "cue", "bin", "iso", "img", "exe", "ps-exe", "psx"
    );
    private static final Pattern CUE_FILE_PATTERN = Pattern.compile("^\\s*FILE\\s+\"([^\"]+)\".*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERIAL_PATTERN = Pattern.compile("([A-Z]{4})[-_ ]?(\\d{3})[\\.-]?(\\d{2})");
    private static final List<String> PREFERRED_ORDER = List.of("cue", "iso", "img", "exe", "ps-exe", "psx", "bin");
    private static final int METADATA_SCAN_BYTES = 2 * 1024 * 1024;

    private final TaskDispatcher taskDispatcher;
    private final AppConfig config;
    private final List<GameEntry> entries = new CopyOnWriteArrayList<>();
    private final List<GameLibraryListener> listeners = new CopyOnWriteArrayList<>();

    public GameLibrary(TaskDispatcher taskDispatcher, AppConfig config) {
        this.taskDispatcher = taskDispatcher;
        this.config = config;
    }

    public void addListener(GameLibraryListener listener) {
        listeners.add(listener);
    }

    public List<GameEntry> snapshot() {
        return List.copyOf(entries);
    }

    public void scanAsync(List<Path> roots) {
        taskDispatcher.execute(() -> scan(roots));
    }

    private void scan(List<Path> roots) {
        var collected = new ArrayList<Path>();
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) {
                continue;
            }
            Log.info("Scanning library root: " + root);
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                    .filter(GameLibrary::isSupported)
                    .forEach(collected::add);
            } catch (IOException ex) {
                Log.warn("Failed to scan " + root + ": " + ex.getMessage());
            }
        }

        var cueReferences = buildCueReferenceMap(collected);
        var deduped = new HashMap<String, Path>();
        for (Path path : collected) {
            String key = deriveGameKey(path, cueReferences);
            Path current = deduped.get(key);
            if (current == null || comparePriority(path, current) < 0) {
                deduped.put(key, path);
            }
        }

        var result = deduped.entrySet().stream()
            .map(entry -> toEntry(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(GameEntry::title, String.CASE_INSENSITIVE_ORDER))
            .toList();
        entries.clear();
        entries.addAll(result);
        Log.info("Library scan complete. Collected files=" + collected.size() + ", unique games=" + result.size());
        notifyListeners();
    }

    private Map<Path, Path> buildCueReferenceMap(List<Path> files) {
        Map<Path, Path> references = new HashMap<>();
        Set<Path> normalized = new HashSet<>();
        for (Path file : files) {
            normalized.add(normalize(file));
        }
        for (Path file : files) {
            if (!extension(file).equals("cue")) {
                continue;
            }
            for (Path referenced : parseCueFiles(file)) {
                Path normalizedRef = normalize(file.getParent().resolve(referenced));
                if (normalized.contains(normalizedRef)) {
                    references.put(normalizedRef, normalize(file));
                }
            }
        }
        return references;
    }

    private List<Path> parseCueFiles(Path cuePath) {
        try {
            var lines = Files.readAllLines(cuePath);
            var result = new ArrayList<Path>();
            for (String line : lines) {
                Matcher matcher = CUE_FILE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    result.add(Path.of(matcher.group(1)));
                }
            }
            return result;
        } catch (IOException ex) {
            Log.warn("Failed to parse CUE " + cuePath + ": " + ex.getMessage());
            return List.of();
        }
    }

    private String deriveGameKey(Path path, Map<Path, Path> cueReferences) {
        Path normalized = normalize(path);
        Path cueOwner = cueReferences.get(normalized);
        if (cueOwner != null) {
            return normalizeStem(cueOwner);
        }
        Path siblingCue = normalized.resolveSibling(stem(normalized) + ".cue");
        if (extension(path).equals("bin") && Files.exists(siblingCue)) {
            return normalizeStem(siblingCue);
        }
        return normalizeStem(normalized);
    }

    private int comparePriority(Path left, Path right) {
        return Integer.compare(priority(left), priority(right));
    }

    private int priority(Path path) {
        int idx = PREFERRED_ORDER.indexOf(extension(path));
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    public void refreshGame(GameEntry updatedEntry) {
        for (int i = 0; i < entries.size(); i++) {
            GameEntry current = entries.get(i);
            if (current.libraryId().equals(updatedEntry.libraryId())) {
                entries.set(i, updatedEntry);
                break;
            }
        }
        notifyListeners();
    }

    private GameEntry toEntry(String gameKey, Path path) {
        GameHistory history = config.findGameHistory(gameKey);
        String detectedSerial = inferSerial(path);
        String serial = !"UNKNOWN".equals(detectedSerial)
            ? detectedSerial
            : history != null && history.serial() != null && !history.serial().isBlank()
                ? history.serial()
                : "UNKNOWN";
        String title = history != null && history.title() != null && !history.title().isBlank()
            ? history.title()
            : prettifyTitle(stem(path));
        long totalPlayTimeSeconds = history == null ? 0 : Math.max(0, history.totalPlayTimeSeconds());
        Instant lastPlayed = history == null || history.lastPlayedEpochSecond() <= 0
            ? Instant.EPOCH
            : Instant.ofEpochSecond(history.lastPlayedEpochSecond());
        return new GameEntry(gameKey, title, serial, inferRegion(serial), path, totalPlayTimeSeconds, lastPlayed);
    }

    private String inferSerial(Path path) {
        String fromName = extractSerial(path.getFileName().toString());
        if (fromName != null) {
            return fromName;
        }
        Path metadataSource = resolveMetadataSource(path);
        if (metadataSource == null || !Files.isRegularFile(metadataSource)) {
            return "UNKNOWN";
        }
        try (InputStream inputStream = Files.newInputStream(metadataSource)) {
            byte[] sample = inputStream.readNBytes(METADATA_SCAN_BYTES);
            String fromContent = extractSerial(new String(sample, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT));
            return fromContent == null ? "UNKNOWN" : fromContent;
        } catch (IOException ex) {
            Log.warn("Failed to inspect metadata for " + path + ": " + ex.getMessage());
            return "UNKNOWN";
        }
    }

    private Path resolveMetadataSource(Path path) {
        String ext = extension(path);
        if ("cue".equals(ext)) {
            List<Path> referenced = parseCueFiles(path);
            for (Path relative : referenced) {
                Path candidate = path.getParent().resolve(relative).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return path;
        }
        return path;
    }

    private String extractSerial(String text) {
        Matcher matcher = SERIAL_PATTERN.matcher(text.toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "-" + matcher.group(2) + matcher.group(3);
    }

    private String inferRegion(String serial) {
        if (serial == null) {
            return "Unknown";
        }
        return switch (serial.substring(0, Math.min(4, serial.length())).toUpperCase(Locale.ROOT)) {
            case "SCES", "SLES", "SCED", "SLED" -> "PAL";
            case "SCUS", "SLUS", "SCUD", "SLUD" -> "NTSC-U";
            case "SCPS", "SLPS", "SLPM", "PAPX", "PCPX", "SIPS" -> "NTSC-J";
            default -> "Unknown";
        };
    }

    private static String prettifyTitle(String stem) {
        return stem.replace('_', ' ').replace('.', ' ').trim();
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String normalizeStem(Path path) {
        return normalize(path.getParent().resolve(stem(path))).toString().toLowerCase(Locale.ROOT);
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isSupported(Path path) {
        return SUPPORTED_EXTENSIONS.contains(extension(path));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private void notifyListeners() {
        var snapshot = List.copyOf(entries);
        SwingUtilities.invokeLater(() -> listeners.forEach(listener -> listener.onLibraryUpdated(snapshot)));
    }
}
