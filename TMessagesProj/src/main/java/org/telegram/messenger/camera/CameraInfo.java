/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.camera;

import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;

import java.util.ArrayList;

public class CameraInfo {

    public int cameraId;
    protected Camera camera;
    protected ArrayList<Size> pictureSizes = new ArrayList<>();
    protected ArrayList<Size> previewSizes = new ArrayList<>();
    private final ArrayList<int[]> previewFpsRanges = new ArrayList<>();
    public final int frontCamera;

    protected CameraDevice cameraDevice;
    CameraCharacteristics cameraCharacteristics;
    CaptureRequest.Builder captureRequestBuilder;
    public CameraCaptureSession cameraCaptureSession;

    public CameraInfo(int id, int frontFace) {
        cameraId = id;
        frontCamera = frontFace;
    }

    public int getCameraId() {
        return cameraId;
    }

    private Camera getCamera() {
        return camera;
    }

    public ArrayList<Size> getPreviewSizes() {
        return previewSizes;
    }

    public ArrayList<Size> getPictureSizes() {
        return pictureSizes;
    }

    public ArrayList<int[]> getPreviewFpsRanges() {
        ArrayList<int[]> result = new ArrayList<>();
        for (int[] range : previewFpsRanges) result.add(range.clone());
        return result;
    }

    void setPreviewFpsRanges(java.util.List<int[]> ranges) {
        previewFpsRanges.clear();
        if (ranges == null) return;
        for (int[] range : ranges) {
            if (range != null && range.length >= 2 && range[0] > 0 && range[0] <= range[1]) {
                previewFpsRanges.add(new int[] {range[0], range[1]});
            }
        }
    }

    public boolean isFrontface() {
        return frontCamera != 0;
    }
}
