package nanolive.psxj.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppConfig {

    private UiConfig ui = new UiConfig();
    private VideoConfig video = new VideoConfig();
    private AudioConfig audio = new AudioConfig();
    private InputConfig input = new InputConfig();
    private EmulationConfig emulation = new EmulationConfig();
    private RetroAchievementsConfig retroAchievements = new RetroAchievementsConfig();
    private Path biosPath;
    private List<Path> libraryRoots = new ArrayList<>();
    private Map<String, GameProfile> gameProfiles = new LinkedHashMap<>();
    private Map<String, GameHistory> gameHistory = new LinkedHashMap<>();
    private Path memoryCardDirectory;
    private Path saveStateDirectory;

    public static AppConfig defaults() {
        var config = new AppConfig();
        Path home = Path.of(System.getProperty("user.home"));
        config.libraryRoots.add(home.resolve("Games").resolve("PS1"));
        config.memoryCardDirectory = home.resolve(".psxj").resolve("memcards");
        config.saveStateDirectory = home.resolve(".psxj").resolve("savestates");
        return config;
    }

    public void normalize() {
        if (ui == null) ui = new UiConfig();
        if (video == null) video = new VideoConfig();
        if (audio == null) audio = new AudioConfig();
        if (input == null) input = new InputConfig();
        if (emulation == null) emulation = new EmulationConfig();
        if (retroAchievements == null) retroAchievements = new RetroAchievementsConfig();
        if (libraryRoots == null) libraryRoots = new ArrayList<>();
        if (gameProfiles == null) gameProfiles = new LinkedHashMap<>();
        if (gameHistory == null) gameHistory = new LinkedHashMap<>();
        audio.normalize();
        Path home = Path.of(System.getProperty("user.home"));
        if (libraryRoots.isEmpty()) {
            libraryRoots.add(home.resolve("Games").resolve("PS1"));
        }
        if (memoryCardDirectory == null) {
            memoryCardDirectory = home.resolve(".psxj").resolve("memcards");
        }
        if (saveStateDirectory == null) {
            saveStateDirectory = home.resolve(".psxj").resolve("savestates");
        }
    }

    public UiConfig ui() { return ui; }
    public VideoConfig video() { return video; }
    public AudioConfig audio() { return audio; }
    public InputConfig input() { return input; }
    public EmulationConfig emulation() { return emulation; }
    public RetroAchievementsConfig retroAchievements() { return retroAchievements; }
    public Path biosPath() { return biosPath; }
    public void setBiosPath(Path biosPath) { this.biosPath = biosPath; }
    public List<Path> libraryRoots() { return Collections.unmodifiableList(libraryRoots); }
    public void setLibraryRoots(List<Path> roots) { this.libraryRoots = new ArrayList<>(roots); }
    public Path memoryCardDirectory() { return memoryCardDirectory; }
    public Path saveStateDirectory() { return saveStateDirectory; }
    public GameProfile gameProfile(String serial) {
        return gameProfiles.computeIfAbsent(serial, ignored -> new GameProfile());
    }
    public GameHistory findGameHistory(String key) { return gameHistory.get(key); }
    public GameHistory gameHistory(String key) { return gameHistory.computeIfAbsent(key, GameHistory::new); }
    public void putGameHistory(GameHistory history) {
        if (history != null && history.key() != null && !history.key().isBlank()) {
            gameHistory.put(history.key(), history);
        }
    }
}
