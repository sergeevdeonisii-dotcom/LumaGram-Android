package android.graphics;
import android.opengl.EGL14;
import android.os.Handler;
public final class SurfaceTexture {
    public static boolean failNextBufferSize;
    public static SurfaceTexture lastCreated;
    public interface OnFrameAvailableListener { void onFrameAvailable(SurfaceTexture texture); }
    public final int id;
    public long timestamp, latest;
    public int width, height, updates;
    public boolean released;
    public OnFrameAvailableListener listener;
    public Handler handler;
    public SurfaceTexture(int id) { this.id = id; lastCreated = this; }
    public void setDefaultBufferSize(int width, int height) {
        if (failNextBufferSize) { failNextBufferSize = false; throw new IllegalStateException("HAL buffer size rejected"); }
        this.width = width; this.height = height;
    }
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) { this.listener = listener; }
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener, Handler handler) { this.listener = listener; this.handler = handler; }
    public void updateTexImage() {
        if (released) throw new IllegalStateException("Released texture");
        if (!EGL14.current) throw new IllegalStateException("No current GL context");
        timestamp = latest; updates++;
    }
    public void getTransformMatrix(float[] matrix) {}
    public long getTimestamp() { return timestamp; }
    public void release() { released = true; }
    public void frame(long value) { latest = value; callback(); }
    public void callback() {
        OnFrameAvailableListener saved = listener;
        if (saved != null) handler.post(() -> saved.onFrameAvailable(this));
    }
}
