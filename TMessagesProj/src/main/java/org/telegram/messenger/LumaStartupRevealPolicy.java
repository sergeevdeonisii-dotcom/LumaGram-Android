package org.telegram.messenger;

/** Pure policy for once-per-installed-release notes, never an authentication or loading gate. */
public final class LumaStartupRevealPolicy {
    public static final int DEFER = 0;
    public static final int SHOW_STATIC = 1;
    public static final int SHOW = 2;

    private LumaStartupRevealPolicy() {}

    public static int decide(int version, int seenVersion, boolean launcherStart,
                             boolean foreground, boolean protectedScreen, boolean animationsEnabled) {
        if (version <= 0 || version <= seenVersion || !launcherStart || !foreground || protectedScreen) {
            return DEFER;
        }
        // Reduced motion changes presentation, not access to the release notes.
        return animationsEnabled ? SHOW : SHOW_STATIC;
    }

    public static boolean canMarkSeen(boolean showing, boolean attached, boolean drawn,
                                      boolean windowFocused, boolean ownerReady) {
        return showing && attached && drawn && windowFocused && ownerReady;
    }

    public static boolean hasRelease15Notes(String versionName) {
        // A future release without authored notes must not recycle the old feature claims.
        return "1.5".equals(versionName);
    }

    public static boolean hasRelease20Notes(String versionName) {
        return "2.0".equals(versionName);
    }
}
