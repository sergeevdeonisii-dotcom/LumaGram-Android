package org.telegram.ui.Components;

/** Shared, pure geometry for the existing three-island composer; values are dp unless stated. */
public final class LumaComposerGeometry {
    private LumaComposerGeometry() {}

    public static int recordDiameterDp(int configuredHeight) {
        return Math.max(32, Math.min(44, configuredHeight));
    }

    public static int cameraControlsHeightDp(int configuredHeight) {
        return Math.max(48, recordDiameterDp(configuredHeight) + 12);
    }

    public static int cameraControlsLeftMarginDp(int configuredHeight) {
        // Match the compact side-island inset, including the drawable's six-dp bleed.
        return 1 + (44 - recordDiameterDp(configuredHeight)) / 2;
    }

    public static int recordTimerInsetDp(boolean compact) {
        return compact ? 4 : 0;
    }

    public static float recordAuxiliaryCenterY(float sendCenterY, float sendDiameter,
            float minimumTouchSize, float touchGap, int position) {
        if (!finite(sendCenterY) || !finite(sendDiameter) || !finite(minimumTouchSize)
                || !finite(touchGap) || sendDiameter < 0 || minimumTouchSize <= 0
                || touchGap < 0 || position < 1 || position > 2) return finite(sendCenterY) ? sendCenterY : 0f;
        return sendCenterY - Math.max(sendDiameter, minimumTouchSize) / 2f
                - minimumTouchSize / 2f - touchGap
                - (position - 1) * (minimumTouchSize + touchGap);
    }

    public static float localCenter(int controlPositionPx, float controlSizePx, int overlayPositionPx) {
        return controlPositionPx + controlSizePx / 2f - overlayPositionPx;
    }

    public static float boundedRadius(float diameterPx, float centerX, float centerY, float widthPx, float heightPx) {
        if (!finite(diameterPx) || !finite(centerX) || !finite(centerY)
                || !finite(widthPx) || !finite(heightPx)) return 0f;
        return Math.max(0f, Math.min(diameterPx / 2f,
                Math.min(Math.min(centerX, widthPx - centerX), Math.min(centerY, heightPx - centerY))));
    }

    public static int sendAsTextInsetDp(boolean liquid, int avatarWidthDp) {
        return liquid ? 64 : 68 + avatarWidthDp;
    }

    public static float sendAsTextTranslation(boolean liquid, float translationPx) {
        return liquid ? 0f : translationPx;
    }

    public static int replyTextInsetDp(boolean liquid, boolean withImage) {
        return (liquid ? 64 : 52) + (withImage ? 44 : 0);
    }

    public static int replyIconInsetDp(boolean liquid) {
        // Align the 52dp header icon with the edit emoji's 44dp control at 12dp.
        return liquid ? 12 + (44 - 52) / 2 : 0;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
