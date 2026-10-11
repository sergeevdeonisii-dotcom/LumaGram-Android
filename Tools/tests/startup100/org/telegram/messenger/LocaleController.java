package org.telegram.messenger;
/** Compile-only localization bridge. Resource copy is validated separately by the runner. */
public final class LocaleController {
    public static boolean isRTL;
    public static String getString(int id) { return Integer.toString(id); }
    public static String formatString(int id, Object... arguments) { return Integer.toString(id); }
}
