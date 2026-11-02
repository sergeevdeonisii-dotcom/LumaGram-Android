package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.tgnet.TLRPC;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Account-scoped presentation mask. No User/Chat object, formatter or server setting is changed. */
public final class LunagramStreamerMode {
    private static final AtomicLongArray owners = new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);
    private static final AtomicReferenceArray<SharedPreferences> preferences = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    private LunagramStreamerMode() {}
    public static boolean isEnabled(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        long userId = UserConfig.getInstance(account).getClientUserId();
        SharedPreferences prefs = preferences.get(account);
        if (prefs == null || owners.get(account) != userId) synchronized (LunagramStreamerMode.class) {
            if (preferences.get(account) == null || owners.get(account) != userId) {
                preferences.set(account, LumaAccountData.preferences(account));
                owners.set(account, userId);
            }
            prefs = preferences.get(account);
        }
        // Read the cached preferences object, not a cached boolean: logout can
        // clear an identity's preferences even if it logs in again before a draw.
        return prefs.getBoolean("lunagram_streamer_mode", false)
                && (LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.STREAMER_MODE)
                || LunagramSubscriptionAccess.shouldProtectExistingPrivacy(account));
    }
    public static boolean isRenderingEnabled() { return isEnabled(UserConfig.selectedAccount); }
    public static void setEnabled(int account, boolean enabled) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        if (enabled && !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.STREAMER_MODE)) return;
        LumaAccountData.preferences(account).edit().putBoolean("lunagram_streamer_mode", enabled).apply();
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
    }
    public static String hiddenName(boolean chat) {
        return LocaleController.getString(chat ? R.string.LunagramHiddenChat : R.string.LunagramHiddenContact);
    }
    public static String displayName(int account, TLRPC.User user) {
        return isEnabled(account) ? hiddenName(false) : user == null ? "" : UserObject.getUserName(user);
    }
    public static String displayName(int account, TLRPC.Chat chat) {
        return isEnabled(account) ? hiddenName(true) : chat == null || chat.title == null ? "" : chat.title;
    }
    public static CharSequence displayName(int account, CharSequence original, boolean chat) {
        return isEnabled(account) ? hiddenName(chat) : original;
    }
}
