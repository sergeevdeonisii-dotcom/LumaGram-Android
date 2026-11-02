package android.hardware.camera2.params;
import android.view.Surface;
public final class OutputConfiguration {
    public final Surface surface;
    public OutputConfiguration(Surface s) { surface = s; }
    public <T> OutputConfiguration(android.util.Size size, Class<T> type) { surface = null; }
}
