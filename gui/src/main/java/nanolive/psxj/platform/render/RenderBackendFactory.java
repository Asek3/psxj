package nanolive.psxj.platform.render;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.RendererType;
import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.util.Log;

public final class RenderBackendFactory {

    private RenderBackendFactory() {
    }

    public static RenderBackend create(AppConfig config) {
        RendererType renderer = config.video().renderer();
        if (!RendererCapabilities.isAvailable(renderer)) {
            Log.warn("Renderer " + renderer + " is unavailable on this host; falling back to AWT.");
            renderer = RendererType.AWT;
        }
        RenderBackend backend = switch (renderer) {
            case OPENGL -> new SdlOpenGlRenderer("PSXJ - OpenGL", 1280, 720);
            case D3D11 -> new SdlDirect3D11Renderer("PSXJ - Direct3D 11", 1280, 720);
            case D3D12 -> new SdlDirect3D12Renderer("PSXJ - Direct3D 12", 1280, 720);
            case VULKAN -> new SdlVulkanRenderer("PSXJ - Vulkan", 1280, 720);
            case METAL -> new SdlMetalRenderer("PSXJ - Metal", 1280, 720);
            case SOFTWARE -> new SdlSoftwareRenderer("PSXJ - Software", 1280, 720);
            case AWT -> new AwtRenderer("PSXJ - AWT", 1280, 720);
        };
        return new ThreadedRenderBackend(backend);
    }
}
