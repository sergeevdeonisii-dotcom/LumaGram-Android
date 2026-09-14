package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.tl.TL_stars;

/**
 * Local-only Stars profile level override. It never sends a changed rating to Telegram.
 */
public final class LumaStarRating {

    private static final String KEY_ENABLED = "luma_star_rating_enabled_";
    private static final String KEY_LEVEL = "luma_star_rating_level_";
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 999;

    private LumaStarRating() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled(int account) {
        return preferences().getBoolean(KEY_ENABLED + account, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED + account, enabled).apply();
    }

    public static int getLevel(int account) {
        return clampLevel(preferences().getInt(KEY_LEVEL + account, MIN_LEVEL));
    }

    public static void setLevel(int account, int level) {
        preferences().edit().putInt(KEY_LEVEL + account, clampLevel(level)).apply();
    }

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    public static TL_stars.Tl_starsRating getDisplayRating(int account, long userId, TL_stars.Tl_starsRating original) {
        if (original == null || !isEnabled(account) || userId != UserConfig.getInstance(account).getClientUserId()) {
            return original;
        }
        int level = getLevel(account);
        long current = (long) (level - 1) * 1000L;
        TL_stars.Tl_starsRating local = new TL_stars.Tl_starsRating();
        local.flags = original.flags;
        local.level = level;
        local.current_level_stars = current;
        local.stars = current + 500L;
        local.next_level_stars = current + 1000L;
        return local;
    }
}
