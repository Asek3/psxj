package nanolive.psxj.config;

public final class InputConfig {

    private boolean enableGamepad = true;
    private boolean enableRumble = true;
    private int deadZonePercent = 18;

    public boolean enableGamepad() {
        return enableGamepad;
    }

    public void setEnableGamepad(boolean enableGamepad) {
        this.enableGamepad = enableGamepad;
    }

    public boolean enableRumble() {
        return enableRumble;
    }

    public void setEnableRumble(boolean enableRumble) {
        this.enableRumble = enableRumble;
    }

    public int deadZonePercent() {
        return Math.clamp(deadZonePercent, 0, 50);
    }

    public void setDeadZonePercent(int deadZonePercent) {
        this.deadZonePercent = Math.clamp(deadZonePercent, 0, 50);
    }
}
