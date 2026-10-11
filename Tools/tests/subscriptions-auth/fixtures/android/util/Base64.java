package android.util;
public final class Base64 {
    public static final int NO_PADDING = 1, NO_WRAP = 2, URL_SAFE = 8;
    public static String encodeToString(byte[] value, int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        return ((flags & NO_PADDING) != 0 ? encoder.withoutPadding() : encoder).encodeToString(value);
    }
}
