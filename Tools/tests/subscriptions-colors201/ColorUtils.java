/** JVM fixture for AndroidX color math; production still links the real AndroidX class. */
final class ColorUtils {
    private static double component(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    static double calculateLuminance(int color) {
        return 0.2126 * component((color >> 16) & 255)
                + 0.7152 * component((color >> 8) & 255) + 0.0722 * component(color & 255);
    }
    static double calculateContrast(int foreground, int background) {
        double a = calculateLuminance(foreground) + 0.05, b = calculateLuminance(background) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }
    static int blendARGB(int start, int end, float ratio) {
        float inverse = 1 - ratio;
        return ((int) (((start >>> 24) & 255) * inverse + ((end >>> 24) & 255) * ratio) << 24)
                | ((int) (((start >>> 16) & 255) * inverse + ((end >>> 16) & 255) * ratio) << 16)
                | ((int) (((start >>> 8) & 255) * inverse + ((end >>> 8) & 255) * ratio) << 8)
                | (int) ((start & 255) * inverse + (end & 255) * ratio);
    }
}
