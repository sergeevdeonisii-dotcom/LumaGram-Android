package org.telegram.utils.camera.roundvideo;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** API 30+ warm front/back streams. Only the selected GL input enters the MP4. */
final class RoundVideoConcurrentCamera {
    static final class Spec {
        final String id;
        final CameraCharacteristics characteristics;
        final RoundVideoSession.CameraFacing facing;
        final RoundVideoSession.CameraResolution resolution;
        final Size previewSize, size;
        final int crop;
        final Range<Integer> fps;

        Spec(String id, CameraCharacteristics characteristics, RoundVideoSession.CameraFacing facing,
             RoundVideoSession.CameraResolution resolution, Size previewSize, Size size, int crop, Range<Integer> fps) {
            this.id = id; this.characteristics = characteristics; this.facing = facing;
            this.resolution = resolution; this.previewSize = previewSize; this.size = size;
            this.crop = crop; this.fps = fps;
        }

        boolean realtime() {
            Integer value = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            return value != null && value == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        }
    }

    interface Requests {
        CaptureRequest create(CameraDevice device, Spec spec, Surface surface, boolean selected,
                              boolean stabilization) throws CameraAccessException;
    }

    interface Callback {
        void onReady(Spec spec, CameraDevice device, CameraCaptureSession session);
        void onFailure(Exception error);
    }

    private final CameraManager manager;
    private final Handler handler;
    private final Executor executor;
    private final Requests requests;
    private final Callback callback;
    private final CameraCaptureSession.CaptureCallback captureCallback;
    private final Slot[] slots;
    private int selected;
    private boolean started, configuring, closed, ready, stabilization = true;
    private int closeGeneration;
    private Runnable afterClose;
    private final Runnable startupTimeout = () -> fail(new IllegalStateException("Concurrent camera startup timed out"));

    private final class Slot {
        final Spec spec;
        final Surface surface;
        CameraDevice device;
        CameraCaptureSession session;
        boolean opening;

        Slot(Spec spec, Surface surface) { this.spec = spec; this.surface = surface; }

        SessionConfiguration configuration() {
            return new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(surface)), executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (closed) { session.close(); return; }
                            Slot.this.session = session;
                            try {
                                repeat(Slot.this);
                                if (!ready && slots[0].session != null && slots[1].session != null) {
                                    ready = true;
                                    handler.removeCallbacks(startupTimeout);
                                    Slot active = slots[selected];
                                    callback.onReady(active.spec, active.device, active.session);
                                }
                            } catch (Exception error) { fail(error); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            if (!closed) fail(new IllegalStateException("Concurrent camera configuration rejected"));
                        }
                        @Override public void onClosed(CameraCaptureSession session) {
                            if (Slot.this.session != session) return;
                            Slot.this.session = null;
                            if (!closed) fail(new IllegalStateException("Concurrent camera session closed unexpectedly"));
                        }
                    });
        }

        final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) {
                opening = false;
                device = camera;
                if (closed) { camera.close(); return; }
                // Both devices must be open before either capture session is configured.
                if (!configuring && slots[0].device != null && slots[1].device != null) {
                    configuring = true;
                    try {
                        for (Slot slot : slots) slot.device.createCaptureSession(slot.configuration());
                    } catch (Exception error) { fail(error); }
                }
            }
            @Override public void onDisconnected(CameraDevice camera) {
                opening = false; device = camera;
                if (closed) camera.close();
                else fail(new IllegalStateException("Concurrent camera disconnected"));
            }
            @Override public void onError(CameraDevice camera, int error) {
                opening = false; device = camera;
                if (closed) camera.close();
                else fail(new IllegalStateException("Concurrent camera rejected: " + error));
            }
            @Override public void onClosed(CameraDevice camera) {
                if (device == camera) device = null;
                opening = false;
                if (!closed) fail(new IllegalStateException("Concurrent camera closed unexpectedly"));
                finishClose();
            }
        };
    }

    RoundVideoConcurrentCamera(CameraManager manager, Handler handler, Spec first, Surface firstSurface,
                               Spec second, Surface secondSurface, Requests requests,
                               CameraCaptureSession.CaptureCallback captureCallback, Callback callback) {
        this.manager = manager; this.handler = handler; this.executor = command -> handler.post(command);
        this.requests = requests; this.captureCallback = captureCallback; this.callback = callback;
        slots = new Slot[]{new Slot(first, firstSurface), new Slot(second, secondSurface)};
    }

    boolean isSupported() throws CameraAccessException {
        boolean pairAdvertised = manager.getConcurrentCameraIds().stream()
                .anyMatch(ids -> ids.contains(slots[0].spec.id) && ids.contains(slots[1].spec.id));
        if (!pairAdvertised) return false;
        Map<String, SessionConfiguration> configurations = new LinkedHashMap<>();
        for (Slot slot : slots) configurations.put(slot.spec.id, slot.configuration());
        return manager.isConcurrentSessionConfigurationSupported(configurations);
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (started || closed) return;
        started = true;
        handler.postDelayed(startupTimeout, 3_000L);
        try {
            for (Slot slot : slots) {
                slot.opening = true;
                try { manager.openCamera(slot.spec.id, slot.deviceCallback, handler); }
                catch (Exception error) { slot.opening = false; throw error; }
            }
        } catch (Exception error) { fail(error); }
    }

    boolean select(RoundVideoSession.CameraFacing facing) {
        if (!ready || closed) return false;
        selected = slots[0].spec.facing == facing ? 0 : 1;
        updateRequests();
        return !closed;
    }

    boolean isReady() { return ready && !closed; }
    Spec activeSpec() { return slots[selected].spec; }
    CameraDevice activeDevice() { return slots[selected].device; }
    CameraCaptureSession activeSession() { return slots[selected].session; }

    void updateRequests() {
        if (!ready || closed) return;
        try { for (Slot slot : slots) repeat(slot); }
        catch (Exception error) { fail(error); }
    }

    private void repeat(Slot slot) throws CameraAccessException {
        try {
            slot.session.setRepeatingRequest(requests.create(slot.device, slot.spec, slot.surface,
                    slot == slots[selected], stabilization), captureCallback, handler);
        } catch (IllegalArgumentException | CameraAccessException error) {
            if (!stabilization) throw error;
            stabilization = false;
            slot.session.setRepeatingRequest(requests.create(slot.device, slot.spec, slot.surface,
                    slot == slots[selected], false), captureCallback, handler);
        }
    }

    private void fail(Exception error) {
        if (!closed) close(() -> callback.onFailure(error));
    }

    void close(Runnable completion) {
        if (closed) {
            // Explicit stop/release cancels a queued recovery instead of reopening cameras later.
            if (completion == null) {
                afterClose = null;
                closeGeneration++;
            }
            return;
        }
        closed = true; ready = false; afterClose = completion;
        handler.removeCallbacks(startupTimeout);
        for (Slot slot : slots) {
            if (slot.session != null) { slot.session.close(); slot.session = null; }
            if (slot.device != null) slot.device.close();
        }
        finishClose();
    }

    private void finishClose() {
        if (!closed) return;
        for (Slot slot : slots) if (slot.opening || slot.device != null) return;
        Runnable completion = afterClose;
        afterClose = null;
        if (completion != null) {
            int generation = closeGeneration;
            handler.post(() -> {
                // close(null) may arrive after the devices close but before this task runs.
                if (generation == closeGeneration) completion.run();
            });
        }
    }
}
