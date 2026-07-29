package org.telegram.messenger;

import android.content.SharedPreferences;

public final class LumaTextAnimation {

    private static final String KEY_ENABLED = "luma_text_animation_enabled";
    private static final String KEY_SPEED_LEVEL = "luma_text_animation_speed_level";
    private static final String KEY_BLUR_LEVEL = "luma_text_animation_blur_level";
    private static final String KEY_HEIGHT_LEVEL = "luma_text_animation_height_level";
    private static final String KEY_SWIPE_MODE = "luma_text_animation_swipe_mode";

    public static final int SPEED_FAST = 0;
    public static final int SPEED_BALANCED = 1;
    public static final int SPEED_SMOOTH = 2;

    public static final int BLUR_LIGHT = 0;
    public static final int BLUR_BALANCED = 1;
    public static final int BLUR_STRONG = 2;

    public static final int HEIGHT_LOW = 0;
    public static final int HEIGHT_MEDIUM = 1;
    public static final int HEIGHT_HIGH = 2;

    public static final int SWIPE_WHOLE_WORD = 0;
    public static final int SWIPE_BY_LETTER = 1;
    public static final int SWIPE_NO_ANIMATION = 2;

    private LumaTextAnimation() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled() {
        return preferences().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getSpeedLevel() {
        return clampLevel(preferences().getInt(KEY_SPEED_LEVEL, SPEED_BALANCED));
    }

    public static void setSpeedLevel(int level) {
        preferences().edit().putInt(KEY_SPEED_LEVEL, clampLevel(level)).apply();
    }

    public static long getCharacterDurationMs() {
        switch (getSpeedLevel()) {
            case SPEED_FAST:
                return 220L;
            case SPEED_SMOOTH:
                return 420L;
            default:
                return 300L;
        }
    }

    public static long getWordDurationMs() {
        return getCharacterDurationMs() + 40L;
    }

    public static int getBlurLevel() {
        return clampLevel(preferences().getInt(KEY_BLUR_LEVEL, BLUR_BALANCED));
    }

    public static void setBlurLevel(int level) {
        preferences().edit().putInt(KEY_BLUR_LEVEL, clampLevel(level)).apply();
    }

    public static float getBlurRadiusPx() {
        return getBlurRadiusPx(getBlurLevel());
    }

    public static float getBlurRadiusPx(int level) {
        switch (clampLevel(level)) {
            case BLUR_LIGHT:
                return 4.0f;
            case BLUR_STRONG:
                return 16.0f;
            default:
                return 10.0f;
        }
    }

    public static int getHeightLevel() {
        return clampLevel(preferences().getInt(KEY_HEIGHT_LEVEL, HEIGHT_MEDIUM));
    }

    public static void setHeightLevel(int level) {
        preferences().edit().putInt(KEY_HEIGHT_LEVEL, clampLevel(level)).apply();
    }

    public static float getCharacterSlideDistanceDp() {
        switch (getHeightLevel()) {
            case HEIGHT_LOW:
                return 8.0f;
            case HEIGHT_MEDIUM:
                return 14.0f;
            default:
                return 20.0f;
        }
    }

    public static float getWordSlideDistanceDp() {
        return getCharacterSlideDistanceDp() * 0.5f;
    }

    public static int getSwipeMode() {
        return clampLevel(preferences().getInt(KEY_SWIPE_MODE, SWIPE_WHOLE_WORD));
    }

    public static void setSwipeMode(int mode) {
        preferences().edit().putInt(KEY_SWIPE_MODE, clampLevel(mode)).apply();
    }

    public static void resetTuningSettings() {
        preferences().edit()
            .remove(KEY_SPEED_LEVEL)
            .remove(KEY_BLUR_LEVEL)
            .remove(KEY_HEIGHT_LEVEL)
            .remove(KEY_SWIPE_MODE)
            .apply();
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(2, level));
    }
}
