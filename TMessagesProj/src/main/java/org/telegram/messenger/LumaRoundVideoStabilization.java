package org.telegram.messenger;

/** Public Camera2 modes only. Enhanced is not Samsung's proprietary Super Steady. */
public final class LumaRoundVideoStabilization {
    public static final int OFF = 0, STANDARD = 1, ENHANCED = 2;
    public static final String PREFERENCE_KEY = "lunagram_round_video_stabilization";

    private LumaRoundVideoStabilization() {}

    public static int getMode() {
        return clamp(MessagesController.getGlobalMainSettings().getInt(PREFERENCE_KEY, OFF));
    }

    public static void setMode(int mode) {
        MessagesController.getGlobalMainSettings().edit().putInt(PREFERENCE_KEY, clamp(mode)).apply();
    }

    private static int clamp(int mode) { return Math.max(OFF, Math.min(ENHANCED, mode)); }

    // CameraMetadata's public OFF/ON/PREVIEW_STABILIZATION values are 0/1/2.
    // Prefer OIS for ordinary stabilization of the GL preview. EIS ON is not
    // guaranteed for a SurfaceTexture, so consumers must inspect capture results.
    public static int videoMode(int preference, boolean eis, boolean preview, boolean ois, boolean highSpeed) {
        if (preference <= OFF || highSpeed) return OFF;
        if (preference == ENHANCED && preview) return ENHANCED;
        if (preference == STANDARD && ois) return OFF;
        return eis ? STANDARD : OFF;
    }

    public static boolean opticalMode(int preference, int videoMode, boolean supported) {
        // Never force competing optical and digital stabilization together.
        return preference > OFF && videoMode == OFF && supported;
    }
}
