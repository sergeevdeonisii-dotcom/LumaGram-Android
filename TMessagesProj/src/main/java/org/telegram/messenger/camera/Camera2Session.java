package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LumaRoundVideoQuality;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private volatile boolean isError;
    private volatile boolean isSuccess;
    private volatile boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private float currentZoom = 1f;

    private final Size previewSize;

    private ImageReader imageReader;
    private final boolean roundRecording;
    private int requestedRecordingFrameRate;
    private volatile int recordingFrameRate = LumaRoundVideoQuality.FRAME_RATE;
    private Runnable errorCallback;

    private long lastTime;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        return create(front, viewWidth, viewHeight, 0);
    }

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight, int frameRate) {
        CameraChoice choice = chooseCamera(front, viewWidth, viewHeight, frameRate);
        return choice == null ? null : new Camera2Session(ApplicationLoader.applicationContext, front,
            choice.id, choice.size, frameRate);
    }

    private static final class CameraChoice {
        String id;
        Size size;
        CameraCharacteristics characteristics;
    }

    private static CameraChoice chooseCamera(boolean front, int viewWidth, int viewHeight, int frameRate) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        CameraCharacteristics bestCharacteristics = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Size[] previews = confMap.getOutputSizes(SurfaceTexture.class);
                        Size size = previews == null || previews.length == 0 ? null : chooseOptimalSize(previews, viewWidth, viewHeight, false);
                        if (frameRate == LumaRoundVideoQuality.HIGH_FRAME_RATE && previews != null) {
                            Size fastSize = null;
                            for (Size candidate : previews) {
                                if (candidate.getWidth() >= viewWidth && candidate.getHeight() >= viewHeight
                                    && chooseRecordingFpsRange(characteristics, candidate, frameRate) != null
                                    && (fastSize == null || (long) candidate.getWidth() * candidate.getHeight() < (long) fastSize.getWidth() * fastSize.getHeight())) {
                                    fastSize = candidate;
                                }
                            }
                            if (fastSize != null) size = fastSize;
                        }
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                            bestCharacteristics = characteristics;
                        }
                    }
                } else {

                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        CameraChoice choice = new CameraChoice();
        choice.id = cameraId;
        choice.size = bestSize;
        choice.characteristics = bestCharacteristics;
        return choice;
    }

    public static boolean supportsRoundFrameRate(boolean front, int width, int height, int frameRate) {
        try {
            CameraChoice choice = chooseCamera(front, width, height, frameRate);
            if (choice == null || choice.size.getWidth() < width || choice.size.getHeight() < height) return false;
            Range<Integer> range = chooseRecordingFpsRange(choice.characteristics, choice.size, frameRate);
            return range != null && range.getLower() >= Math.min(frameRate, LumaRoundVideoQuality.FRAME_RATE)
                && range.getUpper() == frameRate;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static Range<Integer> chooseRecordingFpsRange(CameraCharacteristics characteristics, Size size, int target) {
        if (characteristics == null) return null;
        Range<Integer>[] supported = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        ArrayList<int[]> ranges = new ArrayList<>();
        if (supported != null) {
            for (Range<Integer> range : supported) {
                if (range != null) ranges.add(new int[] {range.getLower(), range.getUpper()});
            }
        }
        int[] selected = LumaRoundVideoQuality.chooseFpsRange(ranges, target, 1);
        if (target == LumaRoundVideoQuality.HIGH_FRAME_RATE) {
            if (!LumaRoundVideoQuality.supportsTargetFrameRate(ranges, target, 1)) return null;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            // Round sessions configure only SurfaceTexture, never a slow JPEG
            // stream. High-speed-only modes do not qualify as normal sessions.
            long duration = map == null ? 0 : map.getOutputMinFrameDuration(SurfaceTexture.class, size);
            if (!LumaRoundVideoQuality.supportsFrameDuration(duration, target)) return null;
        }
        return selected == null ? null : new Range<>(selected[0], selected[1]);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size, int frameRate) {
        roundRecording = frameRate > 0;
        requestedRecordingFrameRate = frameRate;
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
                publishError();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                publishError();
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    if (!updateCaptureRequest()) {
                        publishError();
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                    publishError();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                publishError();
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        // A JPEG output can limit a normal capture session to 30fps even
        // when only the preview surface is used by the repeating request.
        this.imageReader = roundRecording ? null : ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            publishError();
        }
    }

    private void publishError() {
        AndroidUtilities.runOnUIThread(() -> {
            if (isClosed) return;
            isError = true;
            Runnable callback = errorCallback;
            errorCallback = null;
            if (callback != null) callback.run();
        });
    }

    public void whenError(Runnable callback) {
        if (isError && !isClosed) callback.run();
        else errorCallback = callback;
    }

    public int getRecordingFrameRate() { return recordingFrameRate; }

    public void setRecordingFrameRate(int frameRate) {
        if (!roundRecording || isClosed) return;
        requestedRecordingFrameRate = frameRate;
        if (isInitiated()) updateCaptureRequest();
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        surface = new Surface(surfaceTexture);

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            if (imageReader != null) surfaces.add(imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, null);
        } catch (Exception e) {
            FileLog.e(e);
            publishError();
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; // compensate the mirror
            } else { // back-facing
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; // compensate the mirror
            } else { // back-facing
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        currentZoom = Utilities.clamp(value, maxZoom, 1f);
        updateCaptureRequest();

        try {
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        // TODO: support wide zoom camera switching
        return 1f;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        if (async) {
            handler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                thread.quitSafely();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        thread.join();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (afterCallback != null) {
                        afterCallback.run();
                    }
                });
            });
        } else {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            thread.quitSafely();
            try {
                thread.join();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        }
    }

    private boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    private boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private boolean updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                int template;
                if (recordingVideo) {
                    template = CameraDevice.TEMPLATE_RECORD;
                } else if (scanningBarcode) {
                    template = CameraDevice.TEMPLATE_STILL_CAPTURE;
                } else {
                    template = CameraDevice.TEMPLATE_PREVIEW;
                }
                captureRequestBuilder = cameraDevice.createCaptureRequest(template);

                if (scanningBarcode) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
                } else if (nightMode) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
                }

                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

                if (recordingVideo) {
                    Range<Integer> fps = roundRecording
                    ? chooseRecordingFpsRange(cameraCharacteristics, previewSize, requestedRecordingFrameRate)
                    : new Range<Integer>(30, 60);
                    if (roundRecording && fps == null && requestedRecordingFrameRate == LumaRoundVideoQuality.HIGH_FRAME_RATE) {
                        requestedRecordingFrameRate = LumaRoundVideoQuality.FRAME_RATE;
                        fps = chooseRecordingFpsRange(cameraCharacteristics, previewSize, requestedRecordingFrameRate);
                    }
                    if (fps != null) captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                    captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
                }

                if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
                    final int centerX = sensorSize.width() / 2;
                    final int centerY = sensorSize.height() / 2;
                    final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
                    final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
                    cropRegion.set(
                    centerX - deltaX,
                    centerY - deltaY,
                    centerX + deltaX,
                    centerY + deltaY
                    );
                    captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                }

                captureRequestBuilder.addTarget(surface);
                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
                if (roundRecording) recordingFrameRate = requestedRecordingFrameRate;
                return true;
            } catch (Exception e) {
                FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
                if (!roundRecording || requestedRecordingFrameRate != LumaRoundVideoQuality.HIGH_FRAME_RATE || attempt != 0) return false;
                requestedRecordingFrameRate = LumaRoundVideoQuality.FRAME_RATE;
            }
        }
        return false;
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (imageReader == null) return false;
        if (cameraDevice == null || captureSession == null) return false;
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (whenDone != null) {
                            whenDone.run(orientation);
                        }
                    });
                }
            }, null);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {}, null);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }


    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}
