package nanolive.psxj.platform.render;

import nanolive.psxj.config.RendererType;
import nanolive.psxj.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.sdl.SDLRender.SDL_GetNumRenderDrivers;
import static org.lwjgl.sdl.SDLRender.SDL_GetRenderDriver;

public final class RendererCapabilities {

    private static final List<RendererType> AVAILABLE = detectAvailableRenderers();

    private RendererCapabilities() {
    }

    public static RendererType[] availableRenderers() {
        return AVAILABLE.toArray(RendererType[]::new);
    }

    public static boolean isAvailable(RendererType renderer) {
        return renderer != null && AVAILABLE.contains(renderer);
    }

    private static List<RendererType> detectAvailableRenderers() {
        Set<String> drivers = new HashSet<>();
        try {
            int count = Math.max(0, SDL_GetNumRenderDrivers());
            for (int index = 0; index < count; index++) {
                String driver = SDL_GetRenderDriver(index);
                if (driver != null) {
                    drivers.add(driver.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable error) {
            Log.warn("Could not enumerate SDL render drivers; only AWT will be offered: " + error.getMessage());
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        boolean mac = os.contains("mac");
        List<RendererType> result = new ArrayList<>();
        addWhen(result, RendererType.OPENGL, drivers.contains("opengl"));
        addWhen(result, RendererType.D3D11, windows && drivers.contains("direct3d11"));
        addWhen(result, RendererType.D3D12, windows && drivers.contains("direct3d12"));
        addWhen(result, RendererType.VULKAN, !mac && drivers.contains("vulkan"));
        addWhen(result, RendererType.METAL, mac && drivers.contains("metal"));
        addWhen(result, RendererType.SOFTWARE, drivers.contains("software"));
        result.add(RendererType.AWT);
        return List.copyOf(result);
    }

    private static void addWhen(List<RendererType> result, RendererType renderer, boolean condition) {
        if (condition) {
            result.add(renderer);
        }
    }
}
