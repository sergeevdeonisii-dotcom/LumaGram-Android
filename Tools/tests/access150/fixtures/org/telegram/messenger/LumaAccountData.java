package org.telegram.messenger;
public final class LumaAccountData {
    public static android.content.SharedPreferences preferences(int account) {
        return MessagesController.getMainSettings(1000+(int)UserConfig.ids[account]);
    }
}
