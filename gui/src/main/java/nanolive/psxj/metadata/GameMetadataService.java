package nanolive.psxj.metadata;

import nanolive.psxj.library.GameEntry;
import nanolive.psxj.util.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameMetadataService implements AutoCloseable {

    private static final String LIBRETRO =
        "https://raw.githubusercontent.com/libretro-thumbnails/Sony_-_PlayStation/master/";
    private static final String PSX_COVERS =
        "https://raw.githubusercontent.com/xlenore/psx-covers/main/covers/default/";
    private static final Duration MISSING_RETRY_DELAY = Duration.ofDays(1);

    private final Path cacheRoot;
    private final Map<String, Media> media = new ConcurrentHashMap<>();
    private final Map<String, Boolean> scheduled = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "psxj-metadata");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public GameMetadataService() {
        this(Path.of(System.getProperty("user.home"), ".psxj", "cache", "game-media"));
    }

    GameMetadataService(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Media mediaFor(GameEntry game) {
        return game == null ? Media.EMPTY : media.getOrDefault(game.libraryId(), Media.EMPTY);
    }

    public void refresh(List<GameEntry> games, Runnable updated) {
        if (games == null) return;
        for (GameEntry game : games) {
            if (scheduled.putIfAbsent(game.libraryId(), true) == null) {
                worker.execute(() -> load(game, updated));
            }
        }
    }

    private void load(GameEntry game, Runnable updated) {
        try {
            Path directory = cacheRoot.resolve(cacheKey(game.libraryId()));
            BufferedImage cover = readCachedImage(directory, "cover");
            BufferedImage logo = readCachedImage(directory, "logo");
            if (cover != null || logo != null) {
                Log.debug("Artwork cache hit for " + game.title());
                publish(game, cover, logo, updated);
            }
            if (cover != null && logo != null) return;

            Files.createDirectories(directory);
            List<String> names = thumbnailNames(game);
            if (cover == null && shouldRetry(directory, "cover")) {
                ArrayList<String> candidates = new ArrayList<>();
                if (game.hasKnownSerial()) {
                    candidates.add(PSX_COVERS + encodeSegment(game.serial().toUpperCase()) + ".jpg");
                }
                for (String name : names) {
                    candidates.add(LIBRETRO + "Named_Boxarts/" + encodeSegment(name) + ".png");
                }
                DownloadResult result = downloadFirst(candidates, directory.resolve("cover.png"));
                cover = result.image();
                updateMissingMarker(directory, "cover", result);
            }
            if (logo == null && shouldRetry(directory, "logo")) {
                ArrayList<String> candidates = new ArrayList<>();
                for (String name : names) {
                    candidates.add(LIBRETRO + "Named_Logos/" + encodeSegment(name) + ".png");
                }
                DownloadResult result = downloadFirst(candidates, directory.resolve("logo.png"));
                logo = result.image();
                updateMissingMarker(directory, "logo", result);
            }
            if (cover != null || logo != null) publish(game, cover, logo, updated);
        } catch (Exception failure) {
            Log.debug("Artwork lookup failed for " + game.title() + ": " + failure.getMessage());
        }
    }

    private void publish(GameEntry game, BufferedImage cover, BufferedImage logo,
                         Runnable updated) {
        media.put(game.libraryId(), new Media(cover, logo));
        if (updated != null) updated.run();
    }

    private DownloadResult downloadFirst(List<String> urls, Path destination) {
        boolean transportFailure = false;
        for (String url : urls) {
            try {
                HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) continue;
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                if (image == null) continue;
                ImageIO.write(image, "png", destination.toFile());
                return new DownloadResult(image, false);
            } catch (Exception ignored) {
                transportFailure = true;
            }
        }
        return new DownloadResult(null, !transportFailure);
    }

    private static boolean shouldRetry(Path directory, String name) {
        Path marker = directory.resolve(name + ".missing");
        try {
            if (!Files.isRegularFile(marker)) return true;
            Instant retryAt = Files.getLastModifiedTime(marker).toInstant().plus(MISSING_RETRY_DELAY);
            return Instant.now().isAfter(retryAt);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void updateMissingMarker(Path directory, String name, DownloadResult result) {
        Path marker = directory.resolve(name + ".missing");
        try {
            if (result.image() != null) {
                Files.deleteIfExists(marker);
            } else if (result.definitivelyMissing()) {
                Files.writeString(marker, "No image found by the configured providers.\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static List<String> thumbnailNames(GameEntry game) {
        ArrayList<String> names = new ArrayList<>(2);
        addName(names, game.title());
        String fileName = game.path().getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        addName(names, dot < 0 ? fileName : fileName.substring(0, dot));
        return List.copyOf(names);
    }

    private static void addName(List<String> names, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.replaceAll("[&*/:`<>?\\\\|]", "_").trim();
        if (!names.contains(normalized)) names.add(normalized);
    }

    private static BufferedImage readImage(Path path) {
        try { return Files.isRegularFile(path) ? ImageIO.read(path.toFile()) : null; }
        catch (Exception ignored) { return null; }
    }

    private static BufferedImage readCachedImage(Path directory, String name) {
        BufferedImage image = readImage(directory.resolve(name + ".png"));
        return image != null ? image : readImage(directory.resolve(name + ".jpg"));
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String cacheKey(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash, 0, 16);
    }

    @Override
    public void close() { worker.shutdownNow(); }

    public record Media(BufferedImage cover, BufferedImage logo) {
        public static final Media EMPTY = new Media(null, null);
    }

    private record DownloadResult(BufferedImage image, boolean definitivelyMissing) {}
}
