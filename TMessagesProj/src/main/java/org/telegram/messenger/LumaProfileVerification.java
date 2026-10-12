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

    private static SharedPreferences preferences(int account) {
        return LumaAccountData.preferences(account);
    }

    public static boolean isEnabled(int account) {
        return LumaBuildPolicy.allowsProfileVerification() && LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_VERIFICATION)
                && preferences(account).getBoolean(KEY_ENABLED, false);
    }

    /** Only view-local decoration; the caller must keep real server markers and lock icons. */
    public static boolean showsPrefix(int account, long profileUserId, long ownUserId,
                                      boolean encryptedChat, long serverIcon) {
        return !encryptedChat && serverIcon == 0 && showsOwnProfile(account, profileUserId, ownUserId);
    }

    public static boolean showsFooter(int account, long profileUserId, long ownUserId,
                                      boolean encryptedChat, boolean serverVerification, boolean managedBot) {
        return !encryptedChat && !serverVerification && !managedBot
                && showsOwnProfile(account, profileUserId, ownUserId);
    }

    private static boolean showsOwnProfile(int account, long profileUserId, long ownUserId) {
        return profileUserId > 0 && profileUserId == ownUserId && isEnabled(account);
    }

    public static void setEnabled(int account, boolean enabled) {
        if (enabled && !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_VERIFICATION)) return;
        if (!LumaBuildPolicy.allowsProfileVerification()) return;
        preferences(account).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
