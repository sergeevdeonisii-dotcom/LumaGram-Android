package org.telegram.utils.camera.roundvideo;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Build;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.RequiresApi;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramFeatureCatalog;

import java.util.Collections;
import java.util.concurrent.Executor;

/** Vendor subject-aware BOKEH only. Unsupported outputs stay on the ordinary recording path. */
@RequiresApi(31)
final class RoundVideoBokeh {
    interface Listener { void onReady(); void onUnavailable(); }
    private CameraExtensionSession session;
    private boolean closed;
    private final Executor executor;
    private final CameraDevice device;
    private final Surface surface;
    private final Listener listener;
    private final LunagramMediaAccess.Session mediaAccess;

    static boolean supports(CameraManager manager, String id, Size size) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            CameraExtensionCharacteristics c = manager.getCameraExtensionCharacteristics(id);
            return c.getSupportedExtensions().contains(CameraExtensionCharacteristics.EXTENSION_BOKEH)
                    && c.getExtensionSupportedSizes(CameraExtensionCharacteristics.EXTENSION_BOKEH, SurfaceTexture.class).contains(size);
        } catch (CameraAccessException | RuntimeException e) { return false; }
    }

    RoundVideoBokeh(CameraDevice device, Surface surface, Handler handler, LunagramMediaAccess.Session mediaAccess, Listener listener) {
        this.device = device; this.surface = surface; this.listener = listener;
        this.mediaAccess = mediaAccess;
        executor = runnable -> { handler.post(runnable); };
    }
    void open() throws CameraAccessException {
        if (!mediaAccess.canUse(LunagramFeatureCatalog.Feature.ROUND_VIDEO_PORTRAIT)) { fail(); return; }
        device.createExtensionSession(new ExtensionSessionConfiguration(CameraExtensionCharacteristics.EXTENSION_BOKEH,
                Collections.singletonList(new OutputConfiguration(surface)), executor, new CameraExtensionSession.StateCallback() {
            @Override public void onConfigured(CameraExtensionSession configured) {
                if (closed) { closeSession(configured); return; }
                session = configured;
                try { repeat(); if (!closed) listener.onReady(); }
                catch (CameraAccessException | RuntimeException e) { fail(); }
            }
            @Override public void onConfigureFailed(CameraExtensionSession failed) { closeSession(failed); fail(); }
            @Override public void onClosed(CameraExtensionSession closedSession) {
                if (session == closedSession) {
                    session = null;
                    if (!closed) fail(); // Unexpected vendor teardown must recover the ordinary camera path.
                }
            }
        }));
    }
    // Extensions own AF, FPS and stabilization. Unsupported vendor request keys are never added.
    void repeat() throws CameraAccessException {
        if (closed || session == null) return;
        if (!mediaAccess.canUse(LunagramFeatureCatalog.Feature.ROUND_VIDEO_PORTRAIT)) { fail(); return; }
        CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        request.addTarget(surface);
        session.setRepeatingRequest(request.build(), executor, new CameraExtensionSession.ExtensionCaptureCallback() {
            @Override public void onCaptureStarted(CameraExtensionSession session, CaptureRequest request, long timestamp) {
                if (!mediaAccess.canUse(LunagramFeatureCatalog.Feature.ROUND_VIDEO_PORTRAIT)) fail();
            }
            @Override public void onCaptureFailed(CameraExtensionSession failed, CaptureRequest request) { fail(); }
        });
    }
    private void fail() {
        if (closed) return;
        close(); listener.onUnavailable();
    }
    void close() {
        closed = true;
        if (session != null) { CameraExtensionSession old = session; session = null; closeSession(old); }
    }
    private static void closeSession(CameraExtensionSession session) {
        try { session.close(); } catch (CameraAccessException | RuntimeException ignored) {}
    }
    boolean isReady() { return !closed && session != null; }
}
