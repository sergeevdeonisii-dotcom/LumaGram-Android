package android.hardware.camera2;
import android.os.Handler;
public final class CameraCaptureSession {
    public boolean closed, reject;
    public int repeats;
    public CaptureRequest request;
    public CaptureCallback callback;
    public void close() { closed = true; }
    public void setRepeatingRequest(CaptureRequest request, CaptureCallback callback, Handler handler) throws CameraAccessException {
        if (reject) throw new CameraAccessException();
        this.request = request; this.callback = callback; repeats++;
    }
    public void frame(Long timestamp) { callback.onCaptureCompleted(this, request, new TotalCaptureResult(timestamp)); }
    public static class CaptureCallback {
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {}
    }
    public abstract static class StateCallback {
        public abstract void onConfigured(CameraCaptureSession session);
        public abstract void onConfigureFailed(CameraCaptureSession session);
        public void onClosed(CameraCaptureSession session) {}
    }
}
