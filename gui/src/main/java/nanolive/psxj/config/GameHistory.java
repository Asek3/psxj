package nanolive.psxj.config;

public final class GameHistory {

    private String key;
    private String title;
    private String serial;
    private long totalPlayTimeSeconds;
    private long lastPlayedEpochSecond;

    public GameHistory() {
    }

    public GameHistory(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String serial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public long totalPlayTimeSeconds() {
        return totalPlayTimeSeconds;
    }

    public void setTotalPlayTimeSeconds(long totalPlayTimeSeconds) {
        this.totalPlayTimeSeconds = totalPlayTimeSeconds;
    }

    public long lastPlayedEpochSecond() {
        return lastPlayedEpochSecond;
    }

    public void setLastPlayedEpochSecond(long lastPlayedEpochSecond) {
        this.lastPlayedEpochSecond = lastPlayedEpochSecond;
    }
}
