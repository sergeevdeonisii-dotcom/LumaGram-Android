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

    private static SharedPreferences preferences(int account) {
        return LumaAccountData.preferences(account);
    }

    public static boolean isEnabled(int account) {
        return LumaBuildPolicy.allowsPrivacyTools() && preferences(account).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        if (!LumaBuildPolicy.allowsPrivacyTools()) return;
        preferences(account).edit().putBoolean(KEY_ENABLED, enabled).apply();
        MessagesController.getInstance(account).setLumaGhostModeEnabled(enabled);
    }

    public static boolean isScheduledSendEnabled(int account) {
        return LumaBuildPolicy.allowsPrivacyTools() && preferences(account).getBoolean(KEY_SCHEDULE_SEND, false);
    }

    public static void setScheduledSendEnabled(int account, boolean enabled) {
        if (!LumaBuildPolicy.allowsPrivacyTools()) return;
        preferences(account).edit().putBoolean(KEY_SCHEDULE_SEND, enabled).apply();
    }

    public static boolean isAutomaticScheduledSendEnabled(int account) {
        return isEnabled(account) && isScheduledSendEnabled(account);
    }

    /** Viewing ephemeral cloud media requires a server receipt. Do not bypass its TTL. */
    public static boolean shouldBlockEphemeralMedia(int account, long dialogId, boolean outgoing, boolean ephemeral) {
        return ephemeral && !outgoing && dialogId > 0 && !DialogObject.isEncryptedDialog(dialogId) && isEnabled(account);
    }

    public static int getAutomaticScheduleDate(int account, long dialogId, int requestedScheduleDate) {
        if (requestedScheduleDate != 0 || !isAutomaticScheduledSendEnabled(account) || DialogObject.isEncryptedDialog(dialogId)) {
            return requestedScheduleDate;
        }
        int serverTime = ConnectionsManager.getInstance(account).getCurrentTime();
        int deviceTime = (int) (System.currentTimeMillis() / 1000L);
        return (serverTime > 0 ? serverTime : deviceTime) + SCHEDULE_SEND_DELAY_SECONDS;
    }
}
