package android.hardware.camera2.params;
import java.util.List;
import java.util.concurrent.Executor;
import android.hardware.camera2.CameraCaptureSession;
public final class SessionConfiguration {
    public static final int SESSION_REGULAR = 0;
    public final CameraCaptureSession.StateCallback callback;
    public final Executor executor;
    public SessionConfiguration(int mode, List<OutputConfiguration> outputs, Executor executor, CameraCaptureSession.StateCallback callback) {
        this.callback = callback; this.executor = executor;
    }
}
