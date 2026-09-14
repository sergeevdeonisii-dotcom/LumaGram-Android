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
    public static final int MAX_LEVEL = 100;

    /**
     * Published Telegram Stars rating thresholds. Index is the profile level;
     * index 0 is kept as a sentinel so the table can be indexed directly.
     * These values are only used for the local display override.
     */
    private static final long[] LEVEL_THRESHOLDS = {
            0L,
            1L, 5000L, 12000L, 19000L, 27000L, 36000L, 46000L, 57000L, 68000L, 81000L,
            94000L, 107000L, 120000L, 133000L, 146000L, 160000L, 173000L, 186000L, 199000L, 212000L,
            225000L, 238000L, 251000L, 265000L, 278000L, 291000L, 304000L, 317000L, 330000L, 343000L,
            356000L, 370000L, 383000L, 396000L, 409000L, 422000L, 435000L, 448000L, 461000L, 475000L,
            488000L, 501000L, 514000L, 527000L, 540000L, 553000L, 566000L, 580000L, 593000L, 606000L,
            619000L, 632000L, 645000L, 658000L, 671000L, 685000L, 698000L, 711000L, 724000L, 737000L,
            750000L, 763000L, 776000L, 790000L, 803000L, 816000L, 829000L, 842000L, 855000L, 868000L,
            881000L, 885000L, 908000L, 921000L, 934000L, 947000L, 960000L, 973000L, 986000L, 1000000L,
            1400000L, 1960000L, 2744000L, 3842000L, 5379000L, 7531000L, 10543000L, 14760000L, 20664000L, 28930000L,
            40502000L, 56703000L, 79384000L, 111138949L, 155596417L, 217837627L, 304976379L, 426972112L, 597768211L, 836885652L
    };

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

    public static long getRequiredStars(int level) {
        return LEVEL_THRESHOLDS[clampLevel(level)];
    }

    public static TL_stars.Tl_starsRating getDisplayRating(int account, long userId, TL_stars.Tl_starsRating original) {
        if (original == null || !isEnabled(account) || userId != UserConfig.getInstance(account).getClientUserId()) {
            return original;
        }
        int level = getLevel(account);
        long current = getRequiredStars(level);
        TL_stars.Tl_starsRating local = new TL_stars.Tl_starsRating();
        local.flags = original.flags;
        local.level = level;
        local.current_level_stars = current;
        local.stars = current;
        if (level < MAX_LEVEL) {
            local.next_level_stars = getRequiredStars(level + 1);
            local.flags |= 1;
        } else {
            local.next_level_stars = 0L;
            local.flags &= ~1;
        }
        return local;
    }
}
