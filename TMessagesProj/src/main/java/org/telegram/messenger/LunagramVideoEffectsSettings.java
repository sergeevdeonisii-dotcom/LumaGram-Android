package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/** Local choices, snapshotted when a round-video recording is opened. */
public final class LunagramVideoEffectsSettings {
    public static final int NONE = 0, VHS = 1, GRAIN = 2, MONOCHROME = 3, DIGITAL = 4;
    private LunagramVideoEffectsSettings() {}
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("lunagram_video_effects", Context.MODE_PRIVATE);
    }
    public static int getEffect() { return getEffect(UserConfig.selectedAccount); }
    public static int getEffect(int account) {
        return LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.ROUND_VIDEO_FILTERS)
                ? Math.max(NONE, Math.min(DIGITAL, prefs().getInt("effect", NONE))) : NONE;
    }
    public static void setEffect(int effect) { prefs().edit().putInt("effect", Math.max(NONE, Math.min(DIGITAL, effect))).apply(); }
    public static boolean isDateEnabled() { return isDateEnabled(UserConfig.selectedAccount); }
    public static boolean isDateEnabled(int account) {
        return LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.ROUND_VIDEO_DATE)
                && prefs().getBoolean("date", false);
    }
    public static void setDateEnabled(boolean value) { prefs().edit().putBoolean("date", value).apply(); }
    public static boolean isBokehEnabled() { return isBokehEnabled(UserConfig.selectedAccount); }
    public static boolean isBokehEnabled(int account) {
        return LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.ROUND_VIDEO_PORTRAIT)
                && prefs().getBoolean("bokeh", false);
    }
    public static void setBokehEnabled(boolean value) { prefs().edit().putBoolean("bokeh", value).apply(); }
    public static boolean needsProcessedPreview() { return getEffect() != NONE || isDateEnabled() || isBokehEnabled(); }
    public static boolean needsProcessedPreview(int account) { return getEffect(account) != NONE || isDateEnabled(account) || isBokehEnabled(account); }
}
