package org.telegram.messenger;

/** Bounded, Android-independent contrast policy for light chat glass, not message bubbles. */
public final class LumaGlassContrast {
    public static final int MIN_LIGHT_CHANNEL = 238;
    public static final int MIN_LIGHT_ALPHA = 204;
    private static final int DARKEST_SURFACE_CHANNEL = MIN_LIGHT_CHANNEL * MIN_LIGHT_ALPHA / 255;
    private static final int DARKEST_SURFACE = 0xff000000 | DARKEST_SURFACE_CHANNEL * 0x010101;
    private static final double TEXT_CONTRAST = 4.5;

    private LumaGlassContrast() {}

    /** Preserve the glass tint/opacity controls, but prevent a grey-on-grey daytime surface. */
    public static int panel(int color, boolean dark, boolean liquidGlass) {
        if (dark || !liquidGlass) return color;
        int red = color >>> 16 & 255, green = color >>> 8 & 255, blue = color & 255;
        int lowest = Math.min(red, Math.min(green, blue));
        if (lowest < MIN_LIGHT_CHANNEL) {
            int amount = MIN_LIGHT_CHANNEL - lowest, range = 255 - lowest;
            red += (255 - red) * amount / range;
            green += (255 - green) * amount / range;
            blue += (255 - blue) * amount / range;
        }
        // Keep all opacity levels ordered rather than flattening them to one clamped value.
        int alpha = MIN_LIGHT_ALPHA + ((color >>> 24) * (255 - MIN_LIGHT_ALPHA) + 127) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /** Match the darkest possible protected panel, including translucent theme text. */
    public static int foreground(int color, boolean dark, boolean liquidGlass) {
        if (dark || !liquidGlass || color >>> 24 == 0) {
            return color;
        }
        // Saturated translucent text has no single worst backdrop: checking grey alone
        // can miss a contrast dip over blue/pink wallpaper. Resolve its visible shade
        // once, then use opaque text whose contrast increases with panel luminance.
        if (color >>> 24 != 255) color = composite(color, DARKEST_SURFACE);
        if (contrast(color, DARKEST_SURFACE) >= TEXT_CONTRAST) return color;
        float low = 0f, high = 1f;
        int result = 0xff000000;
        for (int step = 0; step < 12; step++) {
            float amount = (low + high) * .5f;
            int candidate = blendToBlack(color, amount);
            if (contrast(candidate, DARKEST_SURFACE) >= TEXT_CONTRAST) {
                result = candidate;
                high = amount;
            } else {
                low = amount;
            }
        }
        return result;
    }

    private static int blendToBlack(int color, float amount) {
        int alpha = Math.round((color >>> 24) + (255 - (color >>> 24)) * amount);
        int red = Math.round((color >>> 16 & 255) * (1f - amount));
        int green = Math.round((color >>> 8 & 255) * (1f - amount));
        int blue = Math.round((color & 255) * (1f - amount));
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /** Straight-alpha composition onto an opaque surface, for policy/regression checks. */
    public static int composite(int foreground, int background) {
        int alpha = foreground >>> 24, inverse = 255 - alpha;
        int red = ((foreground >>> 16 & 255) * alpha + (background >>> 16 & 255) * inverse) / 255;
        int green = ((foreground >>> 8 & 255) * alpha + (background >>> 8 & 255) * inverse) / 255;
        int blue = ((foreground & 255) * alpha + (background & 255) * inverse) / 255;
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    public static double contrast(int foreground, int opaqueBackground) {
        double front = luminance(composite(foreground, opaqueBackground));
        double back = luminance(opaqueBackground);
        return (Math.max(front, back) + .05) / (Math.min(front, back) + .05);
    }

    private static double luminance(int color) {
        return .2126 * linear(color >>> 16 & 255) + .7152 * linear(color >>> 8 & 255)
                + .0722 * linear(color & 255);
    }

    private static double linear(int value) {
        double component = value / 255.0;
        return component <= .04045 ? component / 12.92 : Math.pow((component + .055) / 1.055, 2.4);
    }
}
