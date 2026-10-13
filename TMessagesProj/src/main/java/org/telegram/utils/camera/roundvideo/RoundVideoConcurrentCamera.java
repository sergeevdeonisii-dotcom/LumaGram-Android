package org.telegram.utils.camera.roundvideo;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
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
        final int frameRate;
        final Range<Integer> fps;

        Spec(String id, CameraCharacteristics characteristics, RoundVideoSession.CameraFacing facing,
             RoundVideoSession.CameraResolution resolution, Size previewSize, Size size, int crop, int frameRate, Range<Integer> fps) {
            this.id = id; this.characteristics = characteristics; this.facing = facing;
            this.resolution = resolution; this.previewSize = previewSize; this.size = size;
            this.crop = crop; this.frameRate = frameRate; this.fps = fps;
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
    private boolean qualifying;
    private static final long QUALIFICATION_TIMEOUT_MS = 2_500L;
    private static final int WARMUP_FRAMES = 4;
    private static final int QUALIFICATION_INTERVALS = 12;
    private static final int MONITOR_INTERVALS = 30;
    private int closeGeneration;
    private Runnable afterClose;
    private final Runnable startupTimeout = () -> fail(new IllegalStateException("Concurrent camera startup timed out"));
    private final Runnable qualificationTimeout = () -> fail(new IllegalStateException("Concurrent 60 FPS could not be verified on both cameras"));

    private final class Slot {
        final Spec spec;
        final Surface surface;
        CameraDevice device;
        CameraCaptureSession session;
        boolean opening;
        int warmupFrames, intervals;
        long firstTimestamp, lastTimestamp;
        boolean cadenceQualified;
        boolean firstFrameSeen;

        Slot(Spec spec, Surface surface) { this.spec = spec; this.surface = surface; }

        SessionConfiguration configuration() throws CameraAccessException {
            SessionConfiguration configuration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(surface)), executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (closed) { session.close(); return; }
                            Slot.this.session = session;
                            try {
                                repeat(Slot.this);
                                maybeReady();
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
            if (device != null) setSessionFrameRate(configuration, device, spec.fps);
            return configuration;
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

        final CameraCaptureSession.CaptureCallback measuredCallback = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                if (closed || Slot.this.session != session) return;
                Long firstFrame = result.get(CaptureResult.SENSOR_TIMESTAMP);
                if (firstFrame != null && firstFrame > 0) { firstFrameSeen = true; maybeReady(); }
                if (spec.frameRate == 60 && qualifying) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp != null && timestamp > 0 && timestamp > lastTimestamp) {
                        lastTimestamp = timestamp;
                        if (warmupFrames < WARMUP_FRAMES) {
                            warmupFrames++;
                        } else if (firstTimestamp == 0L) {
                            firstTimestamp = timestamp;
                        } else if (++intervals >= (ready ? MONITOR_INTERVALS : QUALIFICATION_INTERVALS)) {
                            long elapsed = timestamp - firstTimestamp;
                            double measured = intervals * 1_000_000_000d / elapsed;
                            // Session support explicitly ignores session parameters,
                            // so advertised 60 FPS alone is not simultaneous-60 proof.
                            // Sensor cadence, never Handler callback arrival, is used.
                            if (measured < 57d || measured > 63d) {
                                cadenceQualified = false;
                                firstTimestamp = timestamp;
                                intervals = 0;
                                if (ready) fail(new IllegalStateException("Concurrent 60 FPS sensor cadence rejected: " + measured));
                                return;
                            }
                            cadenceQualified = true;
                            firstTimestamp = timestamp;
                            intervals = 0;
                            maybeReady();
                        }
                    }
                }
                if (!closed) captureCallback.onCaptureCompleted(session, request, result);
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
        if (!validRates()) return false;
        boolean pairAdvertised = manager.getConcurrentCameraIds().stream()
                .anyMatch(ids -> ids.contains(slots[0].spec.id) && ids.contains(slots[1].spec.id));
        if (!pairAdvertised) return false;
        Map<String, SessionConfiguration> configurations = new LinkedHashMap<>();
        for (Slot slot : slots) configurations.put(slot.spec.id, slot.configuration());
        return manager.isConcurrentSessionConfigurationSupported(configurations);
    }

    static void setSessionFrameRate(SessionConfiguration configuration, CameraDevice device,
                                    Range<Integer> fps) throws CameraAccessException {
        if (fps == null) return;
        // AE FPS is commonly a session parameter. Supplying it only in the
        // first repeating request can make the HAL configure twice at 60 FPS.
        CaptureRequest.Builder parameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        parameters.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        configuration.setSessionParameters(parameters.build());
    }

    /** Check dimensions before creating the encoder/GL pipeline. No camera is opened. */
    static boolean supportsSizes(CameraManager manager, Handler handler, Spec first, Spec second) throws CameraAccessException {
        Map<String, SessionConfiguration> configurations = new LinkedHashMap<>();
        CameraCaptureSession.StateCallback state = new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession session) {}
            @Override public void onConfigureFailed(CameraCaptureSession session) {}
        };
        for (Spec spec : new Spec[]{first, second}) {
            configurations.put(spec.id, new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(spec.size, SurfaceTexture.class)),
                    command -> handler.post(command), state));
        }
        return manager.isConcurrentSessionConfigurationSupported(configurations);
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (started || closed) return;
        started = true;
        if (!validRates()) { fail(new IllegalStateException("Concurrent camera FPS mismatch")); return; }
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
        return select(facing, true);
    }

    boolean select(RoundVideoSession.CameraFacing facing, boolean updateControls) {
        if (!ready || closed) return false;
        selected = slots[0].spec.facing == facing ? 0 : 1;
        if (updateControls) updateRequests();
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
                    slot == slots[selected], stabilization), slot.measuredCallback, handler);
        } catch (IllegalArgumentException | CameraAccessException error) {
            if (!stabilization) throw error;
            stabilization = false;
            slot.session.setRepeatingRequest(requests.create(slot.device, slot.spec, slot.surface,
                    slot == slots[selected], false), slot.measuredCallback, handler);
        }
    }

    private boolean validRates() {
        int target = slots[0].spec.frameRate;
        if (target != 30 && target != 60) return false;
        for (Slot slot : slots) {
            if (slot.spec.frameRate != target || slot.spec.fps == null
                    || slot.spec.fps.getUpper() != target || slot.spec.fps.getLower() <= 0) return false;
        }
        return true;
    }

    static boolean supportsLowLatencyStartup(int frameRate) {
        // A cold dual-60 trial must measure both sensors before it is safe to
        // record. Do not spend that qualification window after the user has
        // already pressed Record; the single-camera path keeps the same FPS.
        return frameRate == 30;
    }

    private void maybeReady() {
        if (ready || closed || slots[0].session == null || slots[1].session == null) return;
        handler.removeCallbacks(startupTimeout); // Configuration timeout must not consume sensor warm-up time.
        if (!qualifying) {
            qualifying = true;
            handler.postDelayed(qualificationTimeout, QUALIFICATION_TIMEOUT_MS);
        }
        if (slots[0].spec.frameRate == 60) {
            if (!slots[0].cadenceQualified || !slots[1].cadenceQualified) return;
        } else if (!slots[0].firstFrameSeen || !slots[1].firstFrameSeen) return;
        ready = true;
        handler.removeCallbacks(startupTimeout);
        handler.removeCallbacks(qualificationTimeout);
        Slot active = slots[selected];
        callback.onReady(active.spec, active.device, active.session);
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
        handler.removeCallbacks(qualificationTimeout);
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
