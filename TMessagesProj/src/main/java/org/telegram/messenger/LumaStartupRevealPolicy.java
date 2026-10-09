package org.telegram.messenger;

/** Pure policy for a decorative reveal, never an authentication or loading gate. */
public final class LumaStartupRevealPolicy {
    public static final int DEFER = 0;
    public static final int MARK_SEEN = 1;
    public static final int SHOW = 2;

    private LumaStartupRevealPolicy() {}

    public static int decide(int version, int seenVersion, boolean launcherStart,
                             boolean foreground, boolean protectedScreen, boolean animationsEnabled) {
        if (version <= 0 || version <= seenVersion || !launcherStart || !foreground || protectedScreen) {
            return DEFER;
        }
        // Turning animations off must not unexpectedly play the reveal on a later resume.
        return animationsEnabled ? SHOW : MARK_SEEN;
    }
}
