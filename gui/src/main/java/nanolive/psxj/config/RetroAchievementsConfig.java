package nanolive.psxj.config;

public final class RetroAchievementsConfig {

    private boolean enabled;
    private String username = "";
    private String token = "";

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String username() {
        return username == null ? "" : username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username.trim();
    }

    public String token() {
        return token == null ? "" : token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public boolean hasStoredLogin() {
        return !username().isBlank() && !token().isBlank();
    }
}
