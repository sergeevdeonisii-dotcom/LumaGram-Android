package org.telegram.messenger;

/** Device presentation preferences belong to the logged-in Telegram identity. */
public final class LunagramExperienceSettings {
    private LunagramExperienceSettings() { }
    public static boolean showPlanInDialogsHeader(int account) { return LumaAccountData.preferences(account).getBoolean("experience.showPlanInDialogsHeader", true); }
    public static void setShowPlanInDialogsHeader(int account, boolean value) {
        LumaAccountData.preferences(account).edit().putBoolean("experience.showPlanInDialogsHeader", value).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }
    public static String planLabel(int account) { return normalizePlanLabel(LumaAccountData.preferences(account).getString("experience.planLabel", "Ultra")); }
    public static void setPlanLabel(int account, String value) {
        LumaAccountData.preferences(account).edit().putString("experience.planLabel", normalizePlanLabel(value)).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }
    public static int planLabelColor(int account) {
        return readableOnWhite(LumaAccountData.preferences(account).getInt("experience.planLabelColor", 0xff000000));
    }
    public static void setPlanLabelColor(int account, int value) {
        LumaAccountData.preferences(account).edit().putInt("experience.planLabelColor", readableOnWhite(value)).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }
    public static boolean searchButtonOnRight(int account) { return LumaAccountData.preferences(account).getBoolean("experience.searchButtonOnRight", false); }
    public static void setSearchButtonOnRight(int account, boolean value) {
        LumaAccountData.preferences(account).edit().putBoolean("experience.searchButtonOnRight", value).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
    }
    /** Legacy LunaPet renderer is retained as dormant source, but can no longer be enabled. */
    @Deprecated
    public static int pet(int account) { return 0; }
    private static int readableOnWhite(int color) {
        int red = (color >>> 16) & 0xff;
        int green = (color >>> 8) & 0xff;
        int blue = color & 0xff;
        for (int i = 0; i < 32 && contrastOnWhite(red, green, blue) < 4.5; i++) {
            red = (int) (red * 0.9f);
            green = (int) (green * 0.9f);
            blue = (int) (blue * 0.9f);
        }
        return 0xff000000 | red << 16 | green << 8 | blue;
    }
    private static double contrastOnWhite(int red, int green, int blue) {
        double luminance = 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
        return 1.05 / (luminance + 0.05);
    }
    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
    private static String normalizePlanLabel(String value) {
        if (value == null || value.isEmpty()) return "Ultra";
        StringBuilder label = new StringBuilder(8);
        int count = 0;
        boolean previousWasSpace = true;
        for (int offset = 0; offset < value.length() && count < 8;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (!previousWasSpace) {
                    label.append(' ');
                    previousWasSpace = true;
                    count++;
                }
            } else if (Character.isLetterOrDigit(codePoint) || codePoint == '+' || codePoint == '.' || codePoint == '-') {
                label.appendCodePoint(codePoint);
                previousWasSpace = false;
                count++;
            }
        }
        while (label.length() > 0 && label.charAt(label.length() - 1) == ' ') label.setLength(label.length() - 1);
        return label.length() == 0 ? "Ultra" : label.toString();
    }
    public static boolean showDialogQuickActions(int account) { return LumaAccountData.preferences(account).getBoolean("experience.showDialogQuickActions", true); }
    public static void setShowDialogQuickActions(int account, boolean value) { LumaAccountData.preferences(account).edit().putBoolean("experience.showDialogQuickActions", value).apply(); }
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
    public static int incomingEffect(int account) { return allowed(account, LunagramFeatureCatalog.Feature.SEND_ANIMATIONS) ? clamp(LumaAccountData.preferences(account).getInt("experience.incomingEffect", 0), 0, 3) : 0; }
    public static void setIncomingEffect(int account, int value) { if (value != 0 && !allowed(account, LunagramFeatureCatalog.Feature.SEND_ANIMATIONS)) return; LumaAccountData.preferences(account).edit().putInt("experience.incomingEffect", clamp(value, 0, 3)).apply(); }
    public static int atmosphere(int account, long dialogId) { return allowed(account, LunagramFeatureCatalog.Feature.CHAT_ATMOSPHERE) ? clamp(LumaAccountData.preferences(account).getInt("experience.atmosphere." + dialogId, 0), 0, 4) : 0; }
    public static void setAtmosphere(int account, long dialogId, int value) { if (value != 0 && !allowed(account, LunagramFeatureCatalog.Feature.CHAT_ATMOSPHERE)) return; LumaAccountData.preferences(account).edit().putInt("experience.atmosphere." + dialogId, clamp(value, 0, 4)).apply(); }
    public static int atmosphereIntensity(int account, long dialogId) { return clamp(LumaAccountData.preferences(account).getInt("experience.intensity." + dialogId, 1), 0, 2); }
    public static void setAtmosphereIntensity(int account, long dialogId, int value) { if (!allowed(account, LunagramFeatureCatalog.Feature.CHAT_ATMOSPHERE)) return; LumaAccountData.preferences(account).edit().putInt("experience.intensity." + dialogId, clamp(value, 0, 2)).apply(); }
    private static boolean allowed(int account, LunagramFeatureCatalog.Feature feature) { return LunagramSubscriptionAccess.canUse(account, feature); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
