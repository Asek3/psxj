package nanolive.psxj.retroachievements;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import nanolive.psxj.config.RetroAchievementsConfig;
import nanolive.psxj.emu.PsxEmulator;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.platform.render.GameOverlayHost;
import nanolive.psxj.util.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RetroAchievementsService implements AutoCloseable {

    private static final int LOGIN_OK = 1;
    private static final int LOGIN_FAILED = 2;
    private static final int GAME_LOADED = 3;
    private static final int GAME_FAILED = 4;
    private static final int ACHIEVEMENT_UNLOCKED = 5;
    private static final int SERVER_ERROR = 6;
    private static final int DISCONNECTED = 7;
    private static final int RECONNECTED = 8;

    private final RetroAchievementsConfig config;
    private volatile HttpClient httpClient;
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final Object nativeLock = new Object();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicBoolean nativeDestroyed = new AtomicBoolean();
    private final AtomicBoolean frameQueued = new AtomicBoolean();
    private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon().name("psxj-retroachievements").factory());
    private final byte[][] frameSnapshots = {
        new byte[PsxEmulator.ACHIEVEMENT_MEMORY_SIZE],
        new byte[PsxEmulator.ACHIEVEMENT_MEMORY_SIZE]
    };
    private final ThreadLocal<byte[]> memoryBuffer = ThreadLocal.withInitial(() -> new byte[8]);
    private final NativeApi nativeApi;
    private final Pointer context;
    private final String unavailableReason;

    // Keep callbacks alive while native code uses them.
    private final ReadCallback readCallback = this::readMemory;
    private final HttpCallback httpCallback = this::sendHttp;
    private final EventCallback eventCallback = this::handleEvent;
    private final LogCallback logCallback = message -> Log.debug("RetroAchievements: " + string(message));
    private final AchievementCallback achievementCallback = this::collectAchievement;

    private volatile boolean closeRequested;
    private volatile boolean loggedIn;
    private volatile PsxEmulator emulator;
    private volatile GameOverlayHost overlay;
    private volatile Path pendingGame;
    private volatile boolean gameActive;
    private volatile CompletableFuture<LoginResult> pendingLogin;
    private volatile long lastIdleNanos;
    private volatile byte[] frameReadSnapshot;
    private int nextFrameSnapshot;
    private volatile List<GameOverlayHost.AchievementInfo> achievements = List.of();
    private List<GameOverlayHost.AchievementInfo> collectingAchievements;
    private final java.util.concurrent.ConcurrentMap<Integer, String> achievementBadgeUrls =
        new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Integer, String> unlockedAchievementBadgeUrls =
        new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, CompletableFuture<BufferedImage>> badgeLoads =
        new ConcurrentHashMap<>();

    public RetroAchievementsService(RetroAchievementsConfig config) {
        this.config = config;
        NativeApi loadedApi = null;
        Pointer loadedContext = null;
        String failure = "";
        try {
            loadedApi = Native.load("psxj_ra", NativeApi.class);
            loadedContext = loadedApi.psxj_ra_create(
                readCallback, httpCallback, eventCallback, logCallback);
            if (loadedContext == null) {
                throw new IllegalStateException("rcheevos could not create a client");
            }
        } catch (Throwable ex) {
            failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            Log.warn("RetroAchievements is unavailable: " + failure);
        }
        nativeApi = loadedApi;
        context = loadedContext;
        unavailableReason = failure;
        if (isAvailable() && config.enabled() && config.hasStoredLogin()) {
            loginWithToken(config.username(), config.token());
        }
    }

    public boolean isAvailable() {
        return nativeApi != null && context != null && !nativeDestroyed.get();
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    List<GameOverlayHost.AchievementInfo> achievementsForTesting() {
        return achievements;
    }

    public CompletableFuture<LoginResult> login(String username, char[] password) {
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(I18n.tr("retroachievements.unavailable", unavailableReason)));
        }
        if (username == null || username.isBlank() || password == null || password.length == 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException(I18n.tr("retroachievements.credentialsRequired")));
        }
        CompletableFuture<LoginResult> result = new CompletableFuture<>();
        pendingLogin = result;
        String secret = new String(password);
        java.util.Arrays.fill(password, '\0');
        synchronized (nativeLock) {
            nativeApi.psxj_ra_login_password(context, username.trim(), secret);
        }
        return result;
    }

    public void logout() {
        loggedIn = false;
        if (isAvailable()) {
            synchronized (nativeLock) {
                nativeApi.psxj_ra_logout(context);
            }
        }
    }

    public void startGame(PsxEmulator target, Path gamePath, GameOverlayHost targetOverlay) {
        stopGame();
        emulator = target;
        overlay = targetOverlay;
        if (targetOverlay != null) {
            targetOverlay.setRetroAchievementsEnabled(config.enabled());
            targetOverlay.setAchievements(achievements);
        }
        pendingGame = gamePath;
        if (target != null && isAvailable() && config.enabled()) {
            activateCurrentGame();
        }
    }

    public void stopGame() {
        deactivateCurrentGame();
        emulator = null;
        overlay = null;
        pendingGame = null;
        achievements = List.of();
        achievementBadgeUrls.clear();
        unlockedAchievementBadgeUrls.clear();
        badgeLoads.clear();
    }

    public void configurationChanged() {
        GameOverlayHost currentOverlay = overlay;
        if (currentOverlay != null) {
            currentOverlay.setRetroAchievementsEnabled(config.enabled());
        }
        if (config.enabled()) {
            activateCurrentGame();
        } else {
            deactivateCurrentGame();
        }
    }

    private void activateCurrentGame() {
        PsxEmulator current = emulator;
        if (current == null || !isAvailable() || gameActive) {
            return;
        }
        gameActive = true;
        current.setFrameListener(this::doFrame);
        current.setIdleListener(this::idle);
        if (loggedIn && pendingGame != null) {
            loadNativeGame(pendingGame);
        }
    }

    private void deactivateCurrentGame() {
        PsxEmulator current = emulator;
        if (current != null) {
            current.setFrameListener(null);
            current.setIdleListener(null);
        }
        if (gameActive && isAvailable() && loggedIn) {
            synchronized (nativeLock) {
                nativeApi.psxj_ra_unload_game(context);
            }
        }
        gameActive = false;
    }

    private void loginWithToken(String username, String token) {
        CompletableFuture<LoginResult> result = new CompletableFuture<>();
        pendingLogin = result;
        synchronized (nativeLock) {
            nativeApi.psxj_ra_login_token(context, username, token);
        }
        result.exceptionally(failure -> {
            Log.warn("RetroAchievements remembered login failed: " + failure.getMessage());
            return null;
        });
    }

    private int readMemory(int address, Pointer buffer, int numBytes) {
        PsxEmulator current = emulator;
        if (current == null || numBytes <= 0 || numBytes > 65_536) {
            return 0;
        }
        byte[] snapshot = frameReadSnapshot;
        if (snapshot != null) {
            return readSnapshotMemory(snapshot, address, buffer, numBytes);
        }
        byte[] bytes = memoryBuffer.get();
        if (bytes.length < numBytes) {
            bytes = new byte[Integer.highestOneBit(numBytes - 1) << 1];
            memoryBuffer.set(bytes);
        }
        int read = current.readAchievementMemory(address, bytes, 0, numBytes);
        if (read > 0) {
            buffer.write(0, bytes, 0, read);
        }
        return read;
    }

    private void doFrame() {
        PsxEmulator current = emulator;
        if (!isAvailable() || !loggedIn || closeRequested || current == null
            || !frameQueued.compareAndSet(false, true)) {
            return;
        }
        byte[] snapshot = frameSnapshots[nextFrameSnapshot];
        nextFrameSnapshot ^= 1;
        current.copyAchievementMemory(snapshot);
        try {
            frameExecutor.execute(() -> processFrame(snapshot));
        } catch (RejectedExecutionException ignored) {
            frameQueued.set(false);
        }
    }

    private void processFrame(byte[] snapshot) {
        try {
            synchronized (nativeLock) {
                if (!isAvailable() || !loggedIn || closeRequested || !gameActive) {
                    return;
                }
                frameReadSnapshot = snapshot;
                try {
                    nativeApi.psxj_ra_do_frame(context);
                } finally {
                    frameReadSnapshot = null;
                }
            }
        } finally {
            frameQueued.set(false);
        }
    }

    private static int readSnapshotMemory(byte[] snapshot, int address, Pointer buffer,
                                          int numBytes) {
        if (address < 0 || numBytes <= 0 || numBytes > 65_536) {
            return 0;
        }
        long unsignedAddress = Integer.toUnsignedLong(address);
        int source;
        int available;
        if (unsignedAddress <= 0x1F_FFFFL) {
            source = (int) unsignedAddress;
            available = PsxEmulator.ACHIEVEMENT_MEMORY_SIZE - source;
        } else if (unsignedAddress >= 0x20_0000L && unsignedAddress <= 0x20_03FFL) {
            source = PsxEmulator.ACHIEVEMENT_MEMORY_SIZE - 1024
                + (int) (unsignedAddress - 0x20_0000L);
            available = PsxEmulator.ACHIEVEMENT_MEMORY_SIZE - source;
        } else {
            return 0;
        }
        int read = Math.min(numBytes, Math.max(0, available));
        if (read > 0) {
            buffer.write(0, snapshot, source, read);
        }
        return read;
    }

    private void idle() {
        long now = System.nanoTime();
        if (isAvailable() && loggedIn && !closeRequested
            && now - lastIdleNanos >= 250_000_000L) {
            lastIdleNanos = now;
            synchronized (nativeLock) {
                nativeApi.psxj_ra_idle(context);
            }
        }
    }

    public void resetAfterStateLoad() {
        if (isAvailable() && loggedIn && !closeRequested) {
            synchronized (nativeLock) {
                nativeApi.psxj_ra_reset(context);
            }
        }
    }

    private void sendHttp(Pointer urlPointer, Pointer postPointer,
                          Pointer contentTypePointer, long requestId) {
        activeRequests.incrementAndGet();
        CompletableFuture<HttpResponse<byte[]>> request;
        try {
            String url = string(urlPointer);
            String post = nullableString(postPointer);
            String contentType = nullableString(contentTypePointer);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent());
            if (post == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", contentType == null
                    ? "application/x-www-form-urlencoded" : contentType);
                builder.POST(HttpRequest.BodyPublishers.ofString(post, StandardCharsets.UTF_8));
            }
            request = httpClient().sendAsync(
                builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException failure) {
            completeHttp(requestId, -1,
                String.valueOf(failure.getMessage()).getBytes(StandardCharsets.UTF_8));
            if (activeRequests.decrementAndGet() == 0) {
                destroyNativeIfReady();
            }
            return;
        }
        requests.add(request);
        request.whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    completeHttp(requestId, -2,
                        String.valueOf(failure.getMessage()).getBytes(StandardCharsets.UTF_8));
                } else {
                    completeHttp(requestId, response.statusCode(), response.body());
                }
            } finally {
                requests.remove(request);
                if (activeRequests.decrementAndGet() == 0) {
                    destroyNativeIfReady();
                }
            }
        });
    }

    private void completeHttp(long requestId, int status, byte[] body) {
        if (!isAvailable() || nativeDestroyed.get()) {
            return;
        }
        byte[] data = body == null ? new byte[0] : body;
        Memory memory = data.length == 0 ? null : new Memory(data.length);
        if (memory != null) {
            memory.write(0, data, 0, data.length);
        }
        synchronized (nativeLock) {
            nativeApi.psxj_ra_complete_http(context, requestId, status,
                memory == null ? Pointer.NULL : memory, data.length);
        }
    }

    private void handleEvent(int type, Pointer titlePointer, Pointer descriptionPointer,
                             Pointer detailPointer, int value) {
        String title = string(titlePointer);
        String description = string(descriptionPointer);
        String detail = string(detailPointer);
        switch (type) {
            case LOGIN_OK -> {
                loggedIn = true;
                LoginResult result = new LoginResult(title, description, detail, value);
                CompletableFuture<LoginResult> login = pendingLogin;
                pendingLogin = null;
                if (login != null) {
                    login.complete(result);
                }
                Path game = pendingGame;
                if (game != null && gameActive) {
                    CompletableFuture.runAsync(() -> loadNativeGame(game));
                }
            }
            case LOGIN_FAILED -> {
                loggedIn = false;
                CompletableFuture<LoginResult> login = pendingLogin;
                pendingLogin = null;
                if (login != null) {
                    login.completeExceptionally(new IllegalStateException(description));
                }
            }
            case GAME_LOADED -> {
                showToast(I18n.tr("overlay.retroAchievementsActive", title));
                CompletableFuture.runAsync(this::refreshAchievements);
            }
            case GAME_FAILED -> {
                Log.warn("RetroAchievements game identification failed: " + description);
                showToast(I18n.tr("overlay.retroAchievementsUnavailableForGame"));
            }
            case ACHIEVEMENT_UNLOCKED -> {
                showAchievement(title, description, value, detail);
                CompletableFuture.runAsync(this::refreshAchievements);
            }
            case SERVER_ERROR -> Log.warn("RetroAchievements " + title + ": " + description);
            case DISCONNECTED -> {
                Log.warn("RetroAchievements connection lost; unlock submissions are queued");
                showToast(I18n.tr("overlay.retroAchievementsDisconnected"));
            }
            case RECONNECTED -> showToast(I18n.tr("overlay.retroAchievementsReconnected"));
            default -> {
            }
        }
    }

    private void showToast(String message) {
        GameOverlayHost current = overlay;
        if (current != null) {
            current.showOverlayToast(message);
        }
    }

    private void showAchievement(String title, String description, int points, String badgeUrl) {
        GameOverlayHost target = overlay;
        if (target == null) return;
        GameOverlayHost.AchievementInfo info = achievements.stream()
            .filter(item -> item.title().equals(title)).findFirst().orElse(null);
        CompletableFuture<BufferedImage> badgeFuture;
        if (info != null) {
            String url = unlockedAchievementBadgeUrls.getOrDefault(info.id(), badgeUrl);
            badgeFuture = loadBadge(info.id(), true, url);
        } else {
            badgeFuture = loadBadge("toast-" + Integer.toUnsignedString(badgeUrl.hashCode()), badgeUrl);
        }
        badgeFuture.exceptionally(failure -> null)
            .thenAccept(badge -> {
                if (overlay == target && gameActive) {
                    target.showAchievement(title, description, points, badge);
                    AchievementSoundPlayer.play();
                }
            });
    }

    private void refreshAchievements() {
        if (!isAvailable() || closeRequested || !loggedIn || !gameActive) {
            return;
        }
        ArrayList<GameOverlayHost.AchievementInfo> collected = new ArrayList<>();
        int count;
        synchronized (nativeLock) {
            if (closeRequested || !loggedIn || !gameActive) {
                return;
            }
            collectingAchievements = collected;
            try {
                count = nativeApi.psxj_ra_enumerate_achievements(context, achievementCallback);
            } finally {
                collectingAchievements = null;
            }
        }
        collected.sort(Comparator
            .comparing(GameOverlayHost.AchievementInfo::unlocked)
            .thenComparing(GameOverlayHost.AchievementInfo::supported, Comparator.reverseOrder())
            .thenComparing(GameOverlayHost.AchievementInfo::title, String.CASE_INSENSITIVE_ORDER));
        achievements = List.copyOf(collected);
        GameOverlayHost current = overlay;
        if (current != null) {
            current.setAchievements(achievements);
        }
        long active = collected.stream().filter(GameOverlayHost.AchievementInfo::supported).count();
        long unlocked = collected.stream().filter(GameOverlayHost.AchievementInfo::unlocked).count();
        Log.info("RetroAchievements runtime: achievements=" + count
            + ", supported=" + active + ", unlocked=" + unlocked);
        if (count > 0 && active == 0) {
            Log.warn("RetroAchievements disabled every achievement for this game; "
                + "check the runtime log for unsupported conditions");
        }
        for (GameOverlayHost.AchievementInfo achievement : collected) {
            loadAchievementBadge(achievement);
        }
    }

    private void collectAchievement(int id, Pointer title, Pointer description, int points,
                                    byte state, byte unlocked, long unlockTime,
                                    Pointer badgeUrl, Pointer lockedBadgeUrl) {
        List<GameOverlayHost.AchievementInfo> destination = collectingAchievements;
        if (destination == null) {
            return;
        }
        boolean earned = Byte.toUnsignedInt(unlocked) != 0;
        boolean supported = Byte.toUnsignedInt(state) != 3;
        destination.add(new GameOverlayHost.AchievementInfo(
            id, string(title), string(description), Math.max(0, points), earned, supported,
            unlockTime > 0 ? Instant.ofEpochSecond(unlockTime) : null, null));
        achievementBadgeUrls.put(id, earned ? string(badgeUrl) : string(lockedBadgeUrl));
        unlockedAchievementBadgeUrls.put(id, string(badgeUrl));
    }

    private void loadAchievementBadge(GameOverlayHost.AchievementInfo achievement) {
        String url = achievementBadgeUrls.getOrDefault(achievement.id(), "");
        loadBadge(achievement.id(), achievement.unlocked(), url)
            .thenAccept(image -> {
                if (image != null) updateAchievementListBadge(achievement.id(), image);
            });
        if (!achievement.unlocked()) {
            loadBadge(achievement.id(), true,
                unlockedAchievementBadgeUrls.getOrDefault(achievement.id(), ""));
        }
    }

    private CompletableFuture<BufferedImage> loadBadge(int id, boolean unlocked, String url) {
        return loadBadge(id + (unlocked ? "-u" : "-l"), url);
    }

    private CompletableFuture<BufferedImage> loadBadge(String cacheName, String url) {
        if (url == null || url.isBlank()) return CompletableFuture.completedFuture(null);
        return badgeLoads.computeIfAbsent(cacheName, ignored -> {
            Path cache = Path.of(System.getProperty("user.home"), ".psxj", "cache",
                "retroachievements", cacheName + ".png");
            try {
                if (Files.isRegularFile(cache)) {
                    BufferedImage image = ImageIO.read(cache.toFile());
                    if (image != null) return CompletableFuture.completedFuture(image);
                }
            } catch (Exception ignoredRead) {
            }
            CompletableFuture<HttpResponse<byte[]>> request;
            try {
                request = httpClient().sendAsync(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(null);
            }
            requests.add(request);
            return request.handle((response, failure) -> {
                requests.remove(request);
                if (failure != null || response.statusCode() != 200) return null;
                try {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                    if (image != null) {
                        Files.createDirectories(cache.getParent());
                        ImageIO.write(image, "png", cache.toFile());
                    }
                    return image;
                } catch (Exception ignoredDecode) {
                    return null;
                }
            });
        });
    }

    private void updateAchievementListBadge(int id, BufferedImage badge) {
        GameOverlayHost current = overlay;
        if (current != null) {
            current.updateAchievementBadge(id, badge);
        }
    }

    private String userAgent() {
        return "PSXJ/0.1.0 (" + System.getProperty("os.name") + " "
            + System.getProperty("os.arch") + ") rcheevos/12.4.0";
    }

    private HttpClient httpClient() {
        HttpClient current = httpClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            }
            return httpClient;
        }
    }

    private void loadNativeGame(Path game) {
        if (!isAvailable() || closeRequested || game == null || !loggedIn
            || !gameActive || !game.equals(pendingGame)) {
            return;
        }
        synchronized (nativeLock) {
            if (closeRequested || !loggedIn || !gameActive || !game.equals(pendingGame)) {
                return;
            }
            nativeApi.psxj_ra_load_game(context, game.toAbsolutePath().toString());
        }
    }

    @Override
    public void close() {
        stopGame();
        closeRequested = true;
        frameExecutor.shutdownNow();
        destroyNativeIfReady();
    }

    private void destroyNativeIfReady() {
        if (closeRequested && activeRequests.get() == 0 && isAvailable()
            && nativeDestroyed.compareAndSet(false, true)) {
            synchronized (nativeLock) {
                nativeApi.psxj_ra_destroy(context);
            }
        }
    }

    private static String nullableString(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0L
            ? null : pointer.getString(0, StandardCharsets.UTF_8.name());
    }

    private static String string(Pointer pointer) {
        String value = nullableString(pointer);
        return value == null ? "" : value;
    }

    public record LoginResult(String displayName, String username, String token, int score) {
    }

    private interface NativeApi extends Library {
        Pointer psxj_ra_create(ReadCallback read, HttpCallback http,
                               EventCallback event, LogCallback log);
        void psxj_ra_destroy(Pointer context);
        void psxj_ra_login_password(Pointer context, String username, String password);
        void psxj_ra_login_token(Pointer context, String username, String token);
        void psxj_ra_logout(Pointer context);
        void psxj_ra_load_game(Pointer context, String path);
        void psxj_ra_unload_game(Pointer context);
        void psxj_ra_do_frame(Pointer context);
        void psxj_ra_idle(Pointer context);
        void psxj_ra_reset(Pointer context);
        int psxj_ra_enumerate_achievements(Pointer context, AchievementCallback callback);
        void psxj_ra_complete_http(Pointer context, long requestId, int status,
                                   Pointer body, long bodyLength);
    }

    private interface ReadCallback extends Callback {
        int invoke(int address, Pointer buffer, int numBytes);
    }

    private interface HttpCallback extends Callback {
        void invoke(Pointer url, Pointer postData, Pointer contentType, long requestId);
    }

    private interface EventCallback extends Callback {
        void invoke(int type, Pointer title, Pointer description, Pointer detail, int value);
    }

    private interface LogCallback extends Callback {
        void invoke(Pointer message);
    }

    private interface AchievementCallback extends Callback {
        void invoke(int id, Pointer title, Pointer description, int points,
                    byte state, byte unlocked, long unlockTime,
                    Pointer badgeUrl, Pointer lockedBadgeUrl);
    }
}
