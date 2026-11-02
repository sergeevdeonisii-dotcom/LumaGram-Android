package android.hardware.camera2;
import android.hardware.camera2.params.SessionConfiguration;
public final class CameraDevice {
    public static final int TEMPLATE_RECORD = 3;
    public final String id;
    public final StateCallback callback;
    public boolean closeRequested;
    public int configs;
    public SessionConfiguration configuration;
    public CameraDevice(String id, StateCallback callback) { this.id = id; this.callback = callback; }
    public void createCaptureSession(SessionConfiguration configuration) { this.configuration = configuration; configs++; }
    public CaptureRequest.Builder createCaptureRequest(int template) { return new CaptureRequest.Builder(); }
    public void close() { closeRequested = true; }
    public void closed() { callback.onClosed(this); }
    public abstract static class StateCallback {
        public abstract void onOpened(CameraDevice device);
        public abstract void onDisconnected(CameraDevice device);
        public abstract void onError(CameraDevice device, int error);
        public void onClosed(CameraDevice device) {}
    }
}
