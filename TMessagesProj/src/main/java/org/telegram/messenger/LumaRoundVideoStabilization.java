package org.telegram.messenger;

/** Public Camera2 modes plus personal-only horizon lock, not Samsung's proprietary Super Steady. */
public final class LumaRoundVideoStabilization {
    public static final int OFF = 0, STANDARD = 1, ENHANCED = 2;
    public static final String PREFERENCE_KEY = "lunagram_round_video_stabilization";

    private LumaRoundVideoStabilization() {}

    public static int getMode() {
        try {
            return clamp(MessagesController.getGlobalMainSettings().getInt(PREFERENCE_KEY, OFF));
        } catch (ClassCastException ignored) {
            // A malformed restored setting must not crash camera opening or enable a feature.
            return OFF;
        }
    }

    public static void setMode(int mode) {
        MessagesController.getGlobalMainSettings().edit().putInt(PREFERENCE_KEY, clamp(mode)).apply();
    }

    private static int clamp(int mode) {
        int maximum = LumaBuildPolicy.allowsEnhancedRoundVideoStabilization() ? ENHANCED : STANDARD;
        // An imported personal setting gracefully keeps ordinary stabilization in public builds.
        return Math.max(OFF, Math.min(maximum, mode));
    }

    // CameraMetadata's public OFF/ON/PREVIEW_STABILIZATION values are 0/1/2.
    // Prefer OIS for ordinary stabilization of the GL preview. EIS ON is not
    // guaranteed for a SurfaceTexture, so consumers must inspect capture results.
    public static int videoMode(int preference, boolean eis, boolean preview, boolean ois, boolean highSpeed) {
        preference = clamp(preference);
        if (preference <= OFF || highSpeed) return OFF;
        if (preference == ENHANCED && preview) return ENHANCED;
        if (preference == STANDARD && ois) return OFF;
        return eis ? STANDARD : OFF;
    }

    public static boolean opticalMode(int preference, int videoMode, boolean supported) {
        // Never force competing optical and digital stabilization together.
        return clamp(preference) > OFF && videoMode == OFF && supported;
    }
}
