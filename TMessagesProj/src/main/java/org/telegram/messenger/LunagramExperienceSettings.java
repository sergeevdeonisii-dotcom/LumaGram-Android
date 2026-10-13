package org.telegram.messenger;

/** Device presentation preferences belong to the logged-in Telegram identity. */
public final class LunagramExperienceSettings {
    private LunagramExperienceSettings() { }
    public static boolean swipeEnabled(int account) { return allowed(account, LunagramFeatureCatalog.Feature.SWIPE_ACTIONS) && LumaAccountData.preferences(account).getBoolean("experience.swipe", true); }
    public static void setSwipeEnabled(int account, boolean value) { if (value && !allowed(account, LunagramFeatureCatalog.Feature.SWIPE_ACTIONS)) return; LumaAccountData.preferences(account).edit().putBoolean("experience.swipe", value).apply(); }
    public static int[] swipeOrder(int account) {
        return LunagramSwipeGesture.decodeOrder(LumaAccountData.preferences(account).getString("experience.swipeOrder", "1,2,3,4"));
    }
    public static void setSwipeOrder(int account, int[] order) {
        if (!allowed(account, LunagramFeatureCatalog.Feature.SWIPE_ACTIONS)) return;
        if (!LunagramSwipeGesture.validOrder(order)) throw new IllegalArgumentException("swipe order");
        StringBuilder value = new StringBuilder();
        for (int action : order) { if (value.length() > 0) value.append(','); value.append(action); }
        LumaAccountData.preferences(account).edit().putString("experience.swipeOrder", value.toString()).apply();
    }
    public static int pet(int account) { return allowed(account, LunagramFeatureCatalog.Feature.LUNA_PET) ? clamp(LumaAccountData.preferences(account).getInt("experience.pet", 0), 0, 1) : 0; }
    public static void setPet(int account, int value) { if (value != 0 && !allowed(account, LunagramFeatureCatalog.Feature.LUNA_PET)) return; LumaAccountData.preferences(account).edit().putInt("experience.pet", clamp(value, 0, 1)).apply(); }
    public static boolean feedVisible(int account) { return allowed(account, LunagramFeatureCatalog.Feature.CHANNEL_FEED) && LumaAccountData.preferences(account).getBoolean("experience.feedVisible", true); }
    public static void setFeedVisible(int account, boolean value) {
        if (value && !allowed(account, LunagramFeatureCatalog.Feature.CHANNEL_FEED)) return;
        LumaAccountData.preferences(account).edit().putBoolean("experience.feedVisible", value).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
    }
    public static int soundPack(int account) { return allowed(account, LunagramFeatureCatalog.Feature.INTERFACE_SOUNDS) ? clamp(LumaAccountData.preferences(account).getInt("experience.sounds", 0), 0, 4) : 0; }
    public static void setSoundPack(int account, int value) { if (value != 0 && !allowed(account, LunagramFeatureCatalog.Feature.INTERFACE_SOUNDS)) return; LumaAccountData.preferences(account).edit().putInt("experience.sounds", clamp(value, 0, 4)).apply(); }
    public static int sendEffect(int account) { return allowed(account, LunagramFeatureCatalog.Feature.SEND_ANIMATIONS) ? clamp(LumaAccountData.preferences(account).getInt("experience.sendEffect", 0), 0, 3) : 0; }
    public static void setSendEffect(int account, int value) { if (value != 0 && !allowed(account, LunagramFeatureCatalog.Feature.SEND_ANIMATIONS)) return; LumaAccountData.preferences(account).edit().putInt("experience.sendEffect", clamp(value, 0, 3)).apply(); }
    public static int atmosphere(int account, long dialogId) { return allowed(account, LunagramFeatureCatalog.Feature.CHAT_ATMOSPHERE) ? clamp(LumaAccountData.preferences(account).getInt("experience.atmosphere." + dialogId, 0), 0, 4) : 0; }
    public static void setAtmosphere(int account, long dialogId, int value) { if (value != 0 && !allowed(account, LunagramFeatureCatalog.Feature.CHAT_ATMOSPHERE)) return; LumaAccountData.preferences(account).edit().putInt("experience.atmosphere." + dialogId, clamp(value, 0, 4)).apply(); }
    public static int atmosphereIntensity(int account, long dialogId) { return clamp(LumaAccountData.preferences(account).getInt("experience.intensity." + dialogId, 1), 0, 2); }
    public static void setAtmosphereIntensity(int account, long dialogId, int value) { if (!allowed(account, LunagramFeatureCatalog.Feature.CHAT_ATMOSPHERE)) return; LumaAccountData.preferences(account).edit().putInt("experience.intensity." + dialogId, clamp(value, 0, 2)).apply(); }
    private static boolean allowed(int account, LunagramFeatureCatalog.Feature feature) { return LunagramSubscriptionAccess.canUse(account, feature); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
