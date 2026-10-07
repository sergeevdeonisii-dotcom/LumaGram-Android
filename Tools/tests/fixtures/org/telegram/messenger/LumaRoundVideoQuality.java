package org.telegram.messenger;

// Preference-only fixture. The production recording profile and codec integration
// are compiled separately by run-round-video-regressions.ps1.
public final class LumaRoundVideoQuality {
    public static final String PREFERENCE_KEY = "luma_round_video_high_quality";

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREFERENCE_KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREFERENCE_KEY, enabled).apply();
    }
}
