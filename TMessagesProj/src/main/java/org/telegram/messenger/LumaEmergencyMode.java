package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * Keeps the emergency text-only mode separate for every signed-in account.
 */
public final class LumaEmergencyMode {

    private static final String KEY_ENABLED = "luma_emergency_mode_enabled_";
    private static final String KEY_DIALOG_ID = "luma_emergency_mode_dialog_id_";

    private LumaEmergencyMode() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled(int account) {
        return getDialogId(account) != 0 && preferences().getBoolean(KEY_ENABLED + account, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED + account, enabled && getDialogId(account) != 0).apply();
        apply(account);
    }

    public static long getDialogId(int account) {
        return preferences().getLong(KEY_DIALOG_ID + account, 0);
    }

    public static void selectDialog(int account, long dialogId) {
        preferences().edit()
                .putLong(KEY_DIALOG_ID + account, dialogId)
                .putBoolean(KEY_ENABLED + account, dialogId != 0)
                .apply();
        apply(account);
    }

    public static boolean isSelectedDialog(int account, long dialogId) {
        return isEnabled(account) && dialogId != 0 && dialogId == getDialogId(account);
    }

    private static void apply(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            DownloadController.getInstance(account).checkAutodownloadSettings();
            if (isEnabled(account)) {
                LumaUpdaterController.getInstance().cancelDownloadingUpdate();
            }
        });
    }
}
