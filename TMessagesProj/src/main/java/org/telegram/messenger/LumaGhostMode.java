package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Keeps the optional local ghost mode separate for every signed-in account.
 * When enabled, this client does not publish an online status while active.
 */
public final class LumaGhostMode {

    private static final String KEY_ENABLED = "luma_ghost_mode_enabled_";
    private static final String KEY_SCHEDULE_SEND = "luma_ghost_mode_schedule_send_";
    public static final int SCHEDULE_SEND_DELAY_SECONDS = 20;

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

    public static boolean isScheduledSendEnabled(int account) {
        return preferences().getBoolean(KEY_SCHEDULE_SEND + account, false);
    }

    public static void setScheduledSendEnabled(int account, boolean enabled) {
        preferences().edit().putBoolean(KEY_SCHEDULE_SEND + account, enabled).apply();
    }

    public static int getAutomaticScheduleDate(int account, long dialogId, int requestedScheduleDate) {
        if (requestedScheduleDate != 0 || !isEnabled(account) || !isScheduledSendEnabled(account) || DialogObject.isEncryptedDialog(dialogId)) {
            return requestedScheduleDate;
        }
        int serverTime = ConnectionsManager.getInstance(account).getCurrentTime();
        int deviceTime = (int) (System.currentTimeMillis() / 1000L);
        return Math.max(serverTime, deviceTime) + SCHEDULE_SEND_DELAY_SECONDS;
    }
}
