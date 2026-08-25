package nanolive.psxj.platform.render;

import static org.lwjgl.sdl.SDLRender.SDL_SOFTWARE_RENDERER;

public final class SdlSoftwareRenderer extends AbstractSdlRenderer {

    public SdlSoftwareRenderer(String title, int width, int height) {
        super(title, width, height, 0L, SDL_SOFTWARE_RENDERER);
    }
}
