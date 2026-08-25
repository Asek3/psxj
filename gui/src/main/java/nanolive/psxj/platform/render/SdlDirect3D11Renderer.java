package nanolive.psxj.platform.render;

public final class SdlDirect3D11Renderer extends AbstractSdlRenderer {

    public SdlDirect3D11Renderer(String title, int width, int height) {
        super(title, width, height, 0L, "direct3d11");
    }
}
