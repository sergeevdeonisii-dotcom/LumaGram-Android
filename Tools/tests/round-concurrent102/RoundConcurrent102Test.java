package org.telegram.utils.camera.roundvideo;
import android.hardware.camera2.*;
import android.os.Handler;
import android.util.*;
import android.view.Surface;

public final class RoundConcurrent102Test {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++; if (!value) throw new AssertionError(description);
    }
    static final class Scenario {
        final Handler handler = new Handler();
        final CameraManager manager = new CameraManager();
        final RoundVideoConcurrentCamera cameras;
        int ready, failures, stabilizationRetries;
        boolean rejectStabilization;
        Scenario() {
            cameras = new RoundVideoConcurrentCamera(manager, handler, spec("front", RoundVideoSession.CameraFacing.FRONT),
                    new Surface(), spec("back", RoundVideoSession.CameraFacing.BACK), new Surface(),
                    (device, spec, surface, selected, stabilization) -> {
                        if (rejectStabilization && stabilization) { stabilizationRetries++; throw new IllegalArgumentException("HAL stabilization"); }
                        return new CaptureRequest(selected);
                    }, new CameraCaptureSession.CaptureCallback(), new RoundVideoConcurrentCamera.Callback() {
                        public void onReady(RoundVideoConcurrentCamera.Spec spec, CameraDevice device, CameraCaptureSession session) { ready++; }
                        public void onFailure(Exception error) { failures++; }
                    });
        }
        RoundVideoConcurrentCamera.Spec spec(String id, RoundVideoSession.CameraFacing facing) {
            return new RoundVideoConcurrentCamera.Spec(id, new CameraCharacteristics(), facing,
                    RoundVideoSession.CameraResolution.MEDIUM, new Size(720, 1280), new Size(720, 1280), 720, new Range<>(30, 30));
        }
        CameraDevice device(String id) { return manager.devices.get(id); }
        void open(String id) { CameraDevice d = device(id); d.callback.onOpened(d); }
        CameraCaptureSession configure(String id) {
            CameraCaptureSession session = new CameraCaptureSession();
            device(id).configuration.callback.onConfigured(session); return session;
        }
        void closed(String id) { device(id).closed(); handler.drain(); }
    }
    public static void main(String[] args) throws Exception {
        Scenario s = new Scenario();
        check(s.cameras.isSupported(), "Advertised pair and real stream configuration accepted");
        check(s.manager.checkedConfigurations == 2, "Both stream configurations checked");
        s.manager.advertised = false;
        check(!s.cameras.isSupported(), "Unadvertised pair rejected");
        s.manager.advertised = true; s.manager.supported = false;
        check(!s.cameras.isSupported(), "Unsupported actual surfaces rejected");
        s = new Scenario(); s.cameras.start();
        check(s.manager.devices.size() == 2, "Open both camera devices");
        s.open("front"); check(s.device("front").configs == 0, "Do not configure before second device opens");
        s.open("back"); check(s.device("front").configs == 1 && s.device("back").configs == 1, "Configure once after both open");
        CameraCaptureSession front = s.configure("front");
        check(!s.cameras.isReady() && s.ready == 0, "Wait for both sessions");
        CameraCaptureSession back = s.configure("back");
        check(s.cameras.isReady() && s.ready == 1, "Ready callback once");
        check(front.request.selected && !back.request.selected, "Only front is selected initially");
        check(s.cameras.select(RoundVideoSession.CameraFacing.BACK), "Select warm rear camera");
        check(!front.request.selected && back.request.selected, "Standby loses torch/zoom entitlement");
        check(s.cameras.activeDevice() == s.device("back") && s.cameras.activeSession() == back, "Active aliases follow selected input");
        check(s.device("front").configs == 1 && s.device("back").configs == 1, "Switch does not reopen or recreate sessions");
        s.handler.timeout(); check(s.failures == 0, "Ready removes startup timeout");
        s.cameras.close(null); check(front.closed && back.closed, "Close both sessions");
        check(s.device("front").closeRequested && s.device("back").closeRequested, "Close both devices");
        check(!s.cameras.select(RoundVideoSession.CameraFacing.FRONT), "Closed actor ignores new switches");
        s.closed("front"); s.closed("back"); check(s.failures == 0, "Normal release never recovers cameras");
        s = new Scenario(); s.rejectStabilization = true; s.cameras.start(); s.open("front"); s.open("back");
        s.configure("front"); s.configure("back");
        check(s.cameras.isReady() && s.stabilizationRetries == 1, "Rejected camera stabilization retries once without aborting recording");
        s = new Scenario(); s.cameras.start(); s.open("front");
        s.handler.timeout(); check(s.failures == 0, "Wait for close, not just a startup timeout");
        s.open("back"); check(s.device("back").closeRequested && s.device("back").configs == 0, "Late opening camera closes without configuration");
        s.closed("front"); check(s.failures == 0, "Do not reopen while other device is still closing");
        s.closed("back"); check(s.failures == 1, "Fallback fires exactly once after both close");
        s.closed("back"); check(s.failures == 1, "Duplicate close callback cannot repeat recovery");
        s = new Scenario(); s.cameras.start(); s.open("front"); s.open("back");
        s.device("front").configuration.callback.onConfigureFailed(new CameraCaptureSession());
        CameraCaptureSession late = s.configure("back"); check(late.closed, "Late configured session after failure is closed");
        s.closed("front"); s.closed("back"); check(s.failures == 1 && s.ready == 0, "Configuration failure falls back, never announces ready");
        s = new Scenario(); s.manager.failOpen = "back"; s.cameras.start();
        s.open("front"); check(s.device("front").closeRequested, "Partial open failure releases the already pending device");
        s.closed("front"); check(s.failures == 1, "Partial opening failure safely recovers");
        s = new Scenario(); s.cameras.start(); s.open("front"); s.handler.timeout();
        s.cameras.close(null); s.open("back"); s.closed("front"); s.closed("back");
        check(s.failures == 0, "Explicit stop cancels recovery queued by timeout");
        s = new Scenario(); s.cameras.start(); s.open("front"); s.open("back");
        s.device("front").configuration.callback.onConfigureFailed(new CameraCaptureSession());
        s.device("front").closed(); s.device("back").closed();
        check(s.failures == 0 && s.handler.immediate.size() == 1, "Recovery queued only after both device-close callbacks");
        s.cameras.close(null); s.handler.drain();
        check(s.failures == 0, "Stop cancels recovery even after it was posted to Handler");
        s = new Scenario(); s.cameras.start(); s.open("front"); s.open("back");
        s.configure("front"); s.configure("back");
        s.device("front").closed();
        check(s.device("back").closeRequested && !s.cameras.isReady(), "Unexpected device close tears down remaining warm camera");
        s.closed("back"); check(s.failures == 1, "Unexpected close triggers exactly one sequential recovery");
        s = new Scenario(); s.cameras.start(); s.open("front"); s.open("back");
        CameraCaptureSession lost = s.configure("front"); s.configure("back");
        s.device("front").configuration.callback.onClosed(lost);
        check(!s.cameras.isReady() && s.device("front").closeRequested && s.device("back").closeRequested,
                "Unexpected capture session close cannot leave a ready actor with a dead stream");
        s.closed("front"); s.closed("back"); check(s.failures == 1, "Unexpected session close safely recovers after device close");
        s = new Scenario(); s.cameras.start(); s.cameras.start();
        CameraDevice originalFront = s.device("front");
        s.open("front"); s.open("back"); s.open("back");
        check(s.device("front") == originalFront && originalFront.configs == 1 && s.device("back").configs == 1,
                "Duplicate start/open callbacks never duplicate live devices or capture sessions");
        front = s.configure("front"); back = s.configure("back");
        back.reject = true;
        check(!s.cameras.select(RoundVideoSession.CameraFacing.BACK), "Repeating request failure rejects switch completion");
        check(front.closed && back.closed, "Request failure closes both sessions before fallback");
        s.closed("front"); s.closed("back"); check(s.failures == 1, "Request failure has one deferred recovery callback");
        s = new Scenario(); s.cameras.start(); s.open("front"); s.open("back");
        s.configure("front"); s.configure("back");
        s.device("front").callback.onDisconnected(s.device("front"));
        check(!s.cameras.isReady() && s.device("back").closeRequested, "Disconnect stops both warm streams");
        s.closed("front"); s.closed("back"); check(s.failures == 1, "Disconnect fallback waits for closure of both devices");
        s = new Scenario(); s.cameras.close(null); s.cameras.start();
        check(s.manager.devices.isEmpty() && s.handler.delayed.isEmpty(), "Closed actor never opens cameras again");
        System.out.println("PASS: " + checks + " production concurrent-camera lifecycle checks (simulated Camera2, not a hardware FPS claim).");
    }
}
