package org.telegram.messenger;

import android.content.SharedPreferences;

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
        return preferences().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getDelayStep() {
        return clampStep(preferences().getInt(KEY_DELAY_STEP, DEFAULT_STEP));
    }

    public static void setDelayStep(int step) {
        preferences().edit().putInt(KEY_DELAY_STEP, clampStep(step)).apply();
    }

    public static long getDelayMs() {
        return getDelayStep() * 200L;
    }

    private static int clampStep(int step) {
        return Math.max(MIN_STEP, Math.min(MAX_STEP, step));
    }
}
