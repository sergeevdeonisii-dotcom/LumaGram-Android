package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * Local-only visual verification marker for the profile. It never changes the
 * server-side verification state or sends a request to Telegram.
 */
public final class LumaProfileVerification {

    private static final String KEY_ENABLED = "luma_profile_verification_enabled_";

    private LumaProfileVerification() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled(int account) {
        return preferences().getBoolean(KEY_ENABLED + account, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED + account, enabled).apply();
    }
}
