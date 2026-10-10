package android.view;
import android.graphics.SurfaceTexture;
public class Surface {
    public final SurfaceTexture texture;
    public boolean released;
    public Surface() { texture = null; }
    public Surface(SurfaceTexture texture) { this.texture = texture; }
    public void release() { released = true; }
}
