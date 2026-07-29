package org.telegram.messenger;

import android.content.SharedPreferences;

public final class LumaTextAnimation {

    private static final String KEY_ENABLED = "luma_text_animation_enabled";

    private LumaTextAnimation() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled() {
        return preferences().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
