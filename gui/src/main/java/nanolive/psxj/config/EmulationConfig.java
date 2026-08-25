package nanolive.psxj.config;

public final class EmulationConfig {

    private int overclockPercent = 100;
    private boolean pauseWhenOverlayOpen = true;

    public int overclockPercent() {
        return overclockPercent;
    }

    public void setOverclockPercent(int overclockPercent) {
        this.overclockPercent = Math.max(1, overclockPercent);
    }

    public boolean pauseWhenOverlayOpen() {
        return pauseWhenOverlayOpen;
    }

    public void setPauseWhenOverlayOpen(boolean pauseWhenOverlayOpen) {
        this.pauseWhenOverlayOpen = pauseWhenOverlayOpen;
    }
}
