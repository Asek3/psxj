package nanolive.psxj.platform.render;

import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_VULKAN;

public final class SdlVulkanRenderer extends AbstractSdlRenderer {

    public SdlVulkanRenderer(String title, int width, int height) {
        super(title, width, height, SDL_WINDOW_VULKAN, "vulkan");
    }
}
