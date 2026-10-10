package org.telegram.utils.camera.roundvideo;
import android.util.Size;
final class RoundVideoOverlayRenderer {
    RoundVideoOverlayRenderer(int output, Size size, int crop, boolean composition) {}
    void updateInputConfiguration(Size size, int crop) {}
    void setHorizon(float degrees, boolean horizon) {}
    void renderRawCameraToOutput(int texture, float[] matrix, int width, int height) {}
    void render(int texture, float[] matrix, long time, int width, int height) {}
    void captureSwitchFrame(int texture, float[] matrix, boolean fresh) {}
    void renderSwitch(float oldBlur, float newBlur, float mix, int width, int height) {}
    void finishCameraSwitch(long now) {}
    float getSwitchTargetBlurRadiusPx() { return 1f; }
    void release() {}
}
