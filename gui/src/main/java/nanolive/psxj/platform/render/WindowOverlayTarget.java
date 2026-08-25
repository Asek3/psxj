package nanolive.psxj.platform.render;

interface WindowOverlayTarget {

    void setGameOverlay(GameOverlay overlay);

    void redrawOverlay();

    default int overlayRefreshRateHz() {
        try {
            int rate = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDisplayMode().getRefreshRate();
            return rate > 0 ? Math.clamp(rate, 30, 360) : 60;
        } catch (RuntimeException ignored) {
            return 60;
        }
    }
}
