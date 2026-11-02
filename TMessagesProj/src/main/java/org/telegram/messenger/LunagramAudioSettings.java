package org.telegram.messenger;

/** Recording preference; actual route/AudioRecord support decides the channel count. */
public final class LunagramAudioSettings {
    private LunagramAudioSettings() {}
    public static boolean isStereoEnabled() {
        return isStereoEnabled(UserConfig.selectedAccount);
    }
    public static boolean isStereoEnabled(int account) {
        return LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.VOICE_STEREO)
                && MessagesController.getGlobalMainSettings().getBoolean("lunagramStereoVoice", false);
    }
    public static void setStereoEnabled(boolean enabled) {
        setStereoEnabled(UserConfig.selectedAccount, enabled);
    }
    public static void setStereoEnabled(int account, boolean enabled) {
        if (enabled && !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.VOICE_STEREO)) return;
        MessagesController.getGlobalMainSettings().edit().putBoolean("lunagramStereoVoice", enabled).apply();
    }
}
