package nanolive.psxj.platform.render;

import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_METAL;

public final class SdlMetalRenderer extends AbstractSdlRenderer {

    public SdlMetalRenderer(String title, int width, int height) {
        super(title, width, height, SDL_WINDOW_METAL, "metal");
    }
}
