package org.telegram.utils.camera.roundvideo;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

public final class RoundConcurrentGl102Test {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++; if (!value) throw new AssertionError(description);
    }
    static final class Scenario {
        int firstFrames, selections, failures, errors;
        final RoundVideoDiagnostics diagnostics = new RoundVideoDiagnostics();
        final RoundVideoGlProcessor gl = new RoundVideoGlProcessor(new Size(720, 1280), new Surface(),
                360, 720, false, false, diagnostics, 0, null, error -> errors++, new Surface(), null);
        Surface first, second;
        Handler handler;
        EGLSurface encoder;
        Scenario() {
            int initial = EGL14.surfaces.size();
            gl.setCameraPolicy(true, 30, 30);
            gl.setFirstFrameListener(() -> firstFrames++);
            first = gl.start();
            encoder = EGL14.surfaces.get(initial);
            EGL14.current = false;
            second = gl.addConcurrentInput(new Size(1080, 1920), 1080, false, false);
            handler = Handler.lastAutomatic;
        }
        void select(boolean rear) { gl.selectConcurrentInput(rear, () -> selections++, () -> failures++); }
    }
    public static void main(String[] args) {
        Scenario s = new Scenario();
        check(s.first.texture.id != s.second.texture.id, "Each camera owns a distinct OES texture");
        check(s.second.texture.width == 1080 && s.second.texture.height == 1920, "Standby has its own negotiated camera size");
        s.second.texture.frame(100L);
        check(s.firstFrames == 0 && s.encoder.swaps == 0, "Standby cannot announce startup or encode");
        s.first.texture.frame(200L);
        check(s.firstFrames == 1 && s.gl.hasFirstFrame(), "Initial selected frame announces A/V readiness exactly once");
        s.first.texture.callback();
        check(s.firstFrames == 1, "Duplicate callback does not announce another first frame");
        s.gl.startRecording(1L);
        s.first.texture.frame(300L);
        check(s.encoder.swaps == 1, "Selected stream enters encoder");
        s.second.texture.frame(400L);
        check(s.encoder.swaps == 1 && s.second.texture.timestamp == 400L, "Standby is consumed but never written to MP4");
        EGL14.current = false;
        s.select(true);
        s.second.texture.callback();
        check(s.selections == 0 && s.encoder.swaps == 1, "Queued stale target callback cannot finish a switch");
        s.first.texture.frame(500L);
        check(s.encoder.swaps == 2, "Old live stream continues until new target frame arrives");
        s.second.texture.frame(600L);
        check(s.selections == 1 && s.encoder.swaps == 3, "Fresh target frame is rendered before announcing switch completion");
        s.second.texture.callback();
        check(s.encoder.swaps == 3 && s.selections == 1, "Duplicate target frame is not encoded twice");
        s.first.texture.frame(700L);
        check(s.encoder.swaps == 3, "Previously active stream is now standby");
        s.select(false);
        s.first.texture.callback();
        check(s.selections == 1, "Reverse switch also rejects queued stale frames");
        s.second.texture.frame(800L);
        s.first.texture.frame(900L);
        check(s.selections == 2 && s.encoder.swaps == 5, "Repeated flip reuses the same encoder and warm surfaces");
        boolean monotonic = true;
        for (int i = 1; i < EGLExt.times.size(); i++) monotonic &= EGLExt.times.get(i) > EGLExt.times.get(i - 1);
        check(monotonic, "MP4 presentation timestamps remain monotonic across both switches");
        s.select(true);
        s.handler.timeout();
        check(s.failures == 1 && s.selections == 2, "Absent fresh frame yields a bounded failure instead of a frozen switch");
        s.second.texture.frame(1000L);
        check(s.encoder.swaps == 5, "Late target frame after timeout does not silently change active camera");
        SurfaceTexture.OnFrameAvailableListener staleListener = s.second.texture.listener;
        EGL14.current = false;
        s.gl.resetToPrimaryInput();
        check(s.second.released && s.second.texture.released, "Sequential recovery releases standby producer and texture");
        check(GLES20.bound == s.first.texture.id, "Recovery rebinds primary texture under a current GL context");
        staleListener.onFrameAvailable(s.second.texture);
        check(s.errors == 0, "Queued callback from released standby is ignored");
        s.first.texture.frame(1100L);
        check(s.encoder.swaps == 6, "Recovery continues the existing encoder rather than starting a new MP4");
        s.gl.stop();
        check(s.first.released && s.first.texture.released, "Stop releases primary input");
        check(GLES20.deleted.contains(s.first.texture.id) && GLES20.deleted.contains(s.second.texture.id), "Both camera texture IDs are reclaimed");
        s.gl.selectConcurrentInput(false, () -> s.selections++, () -> s.failures++);
        check(s.failures == 2, "Selection after stop fails without scheduling GL work");
        Scenario failedPreview = new Scenario();
        failedPreview.first.texture.frame(1L);
        failedPreview.gl.startRecording(1L);
        failedPreview.select(true);
        EGL14.failSwap = true;
        failedPreview.second.texture.frame(2L);
        check(failedPreview.errors == 1 && failedPreview.selections == 0, "Failed preview rendering cannot announce a successful switch");
        failedPreview.gl.stop();
        Scenario rejectedPost = new Scenario();
        rejectedPost.handler.rejectPosts = true;
        rejectedPost.select(true);
        check(rejectedPost.failures == 1, "Stopped handler rejects asynchronous switch cleanly");
        boolean rejected = false;
        try { rejectedPost.gl.resetToPrimaryInput(); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Stopped GL handler cannot deadlock synchronous reset");
        rejectedPost.handler.rejectPosts = false;
        rejectedPost.gl.stop();
        Scenario partial = new Scenario();
        partial.gl.resetToPrimaryInput();
        SurfaceTexture.failNextBufferSize = true;
        boolean rejectedSize = false;
        try { partial.gl.addConcurrentInput(new Size(1080, 1920), 1080, false, false); }
        catch (IllegalStateException expected) { rejectedSize = true; }
        check(rejectedSize && SurfaceTexture.lastCreated.released && GLES20.deleted.contains(SurfaceTexture.lastCreated.id),
                "Failed secondary construction frees the partially created SurfaceTexture and GL ID");
        partial.gl.stop();
        Scenario lateStop = new Scenario();
        lateStop.handler.rejectPosts = true;
        lateStop.gl.stop();
        check(!lateStop.gl.hasFirstFrame(), "Stop with an already terminated handler returns without an unbounded latch wait");
        System.out.println("PASS: " + checks + " production concurrent-GL checks (simulated EGL/SurfaceTexture; not hardware FPS or A/V measurement).");
    }
}
