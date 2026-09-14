package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * Keeps the optional local ghost mode separate for every signed-in account.
 * When enabled, this client does not publish an online status while active.
 */
public final class LumaGhostMode {

    private static final String KEY_ENABLED = "luma_ghost_mode_enabled_";

    private LumaGhostMode() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled(int account) {
        return preferences().getBoolean(KEY_ENABLED + account, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED + account, enabled).apply();
        MessagesController.getInstance(account).setLumaGhostModeEnabled(enabled);
    }
}
