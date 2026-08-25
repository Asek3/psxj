package nanolive.psxj.config;

public final class VideoConfig {

    private RendererType renderer = RendererType.VULKAN;

    public RendererType renderer() {
        return renderer;
    }

    public void setRenderer(RendererType renderer) {
        this.renderer = renderer;
    }
}
