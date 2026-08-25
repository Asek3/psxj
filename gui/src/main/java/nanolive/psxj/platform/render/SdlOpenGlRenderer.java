package nanolive.psxj.platform.render;

import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;

public final class SdlOpenGlRenderer extends AbstractSdlRenderer {

    public SdlOpenGlRenderer(String title, int width, int height) {
        super(title, width, height, SDL_WINDOW_OPENGL, "opengl");
    }
}
