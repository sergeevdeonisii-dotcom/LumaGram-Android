package android.hardware.camera2;
public final class TotalCaptureResult extends CaptureResult {
    private final Long timestamp;
    public TotalCaptureResult(Long timestamp) { this.timestamp = timestamp; }
    public Long get(Object key) { return timestamp; }
}
