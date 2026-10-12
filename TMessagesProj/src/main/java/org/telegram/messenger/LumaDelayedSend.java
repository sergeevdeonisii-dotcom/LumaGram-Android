package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.ArrayList;

public final class LumaDelayedSend {

    private static final String KEY_ENABLED = "luma_delayed_send_enabled";
    private static final String KEY_DELAY_STEP = "luma_delayed_send_step";

    public static final int MIN_STEP = 2;
    public static final int MAX_STEP = 25;
    public static final int DEFAULT_STEP = 5;

    private LumaDelayedSend() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled() {
        return isEnabled(UserConfig.selectedAccount);
    }

    public static boolean isEnabled(int account) {
        return LumaBuildPolicy.allowsPrivacyTools() && LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.SEND_UNDO)
                && preferences().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        if (enabled && !LunagramSubscriptionAccess.canUse(UserConfig.selectedAccount, LunagramFeatureCatalog.Feature.SEND_UNDO)) return;
        if (!LumaBuildPolicy.allowsPrivacyTools()) return;
        preferences().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getDelayStep() {
        return clampStep(preferences().getInt(KEY_DELAY_STEP, DEFAULT_STEP));
    }

    public static void setDelayStep(int step) {
        if (!LunagramSubscriptionAccess.canUse(UserConfig.selectedAccount, LunagramFeatureCatalog.Feature.SEND_UNDO)) return;
        if (!LumaBuildPolicy.allowsPrivacyTools()) return;
        preferences().edit().putInt(KEY_DELAY_STEP, clampStep(step)).apply();
    }

    public static long getDelayMs() {
        return getDelayMs(UserConfig.selectedAccount);
    }

    public static long getDelayMs(int account) {
        return isEnabled(account) ? getDelayStep() * 200L : 0L;
    }

    /** Complete an already accepted send after closing a chat, without losing it on later expiry. */
    public static void sendDetached(int account, long ownerId,
                                    ArrayList<SendMessagesHelper.SendMessageParams> messages, long sendAt) {
        AndroidUtilities.runOnUIThread(() -> {
            if (ownerId <= 0 || UserConfig.getInstance(account).getClientUserId() != ownerId) return;
            SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
            for (SendMessagesHelper.SendMessageParams message : messages) helper.sendMessage(message);
        }, LumaBuildPolicy.allowsPrivacyTools() ? remainingDelay(sendAt, SystemClock.uptimeMillis()) : 0L);
    }

    static long remainingDelay(long sendAt, long now) {
        return Math.max(0L, sendAt - now);
    }

    private static int clampStep(int step) {
        return Math.max(MIN_STEP, Math.min(MAX_STEP, step));
    }
}
