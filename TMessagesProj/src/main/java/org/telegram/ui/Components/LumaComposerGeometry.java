package org.telegram.ui.Components;

/** Shared, pure geometry for the existing three-island composer; values are dp unless stated. */
public final class LumaComposerGeometry {
    private LumaComposerGeometry() {}

    public static int recordDiameterDp(int configuredHeight) {
        return Math.max(32, Math.min(44, configuredHeight));
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

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
