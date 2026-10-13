package android.hardware.camera2;
import android.util.Range;
public final class CaptureRequest {
    public static final Object CONTROL_AE_TARGET_FPS_RANGE = new Object();
    public final boolean selected;
    public Range<Integer> fps;
    public CaptureRequest(boolean selected) { this.selected = selected; }
    public static final class Builder {
        private final CaptureRequest request = new CaptureRequest(false);
        public void set(Object key, Range<Integer> value) {
            if (key == CONTROL_AE_TARGET_FPS_RANGE) request.fps = value;
        }
        public CaptureRequest build() { return request; }
    }
}
