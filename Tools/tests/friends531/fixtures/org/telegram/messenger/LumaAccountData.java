package org.telegram.messenger;

/** Identity migration is covered separately; these tests exercise edition gates. */
public final class LumaAccountData {
    public static android.content.SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "luma_user_" + UserConfig.getInstance(account).getClientUserId(), 0);
    }
}
