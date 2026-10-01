package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/** Personal local data belongs to a Telegram identity, never to a reusable login slot. */
public final class LumaAccountData {
    private LumaAccountData() { }

    public static SharedPreferences preferences(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(
                userId > 0 ? "luma_user_" + userId : "luma_logged_out_" + account, Context.MODE_PRIVATE);
        if (userId > 0) {
            initialize(account, prefs);
        }
        return prefs;
    }

    private static synchronized void initialize(int account, SharedPreferences prefs) {
        if (prefs.getBoolean("identity_initialized", false)) return;
        SharedPreferences legacy = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        String migrationKey = "luma_identity_migrated_" + account;
        SharedPreferences.Editor editor = prefs.edit().putBoolean("identity_initialized", true);
        if (!legacy.getBoolean(migrationKey, false)) {
            // Preserve protective switches once, but never import unattributed message history,
            // deleted IDs, profile overrides or gift pins from a previous login-slot owner.
            editor.putBoolean("luma_ghost_mode_enabled_", legacy.getBoolean("luma_ghost_mode_enabled_" + account, false));
            editor.putBoolean("luma_ghost_mode_schedule_send_", legacy.getBoolean("luma_ghost_mode_schedule_send_" + account, false));
            editor.putBoolean("luma_keep_deleted_messages_enabled_", legacy.getBoolean("luma_keep_deleted_messages_enabled_" + account, false));
            legacy.edit().putBoolean(migrationKey, true).apply();
        }
        editor.apply();
    }

    public static void clearOnLogout(int account, long userId) {
        if (userId > 0) {
            ApplicationLoader.applicationContext.getSharedPreferences("luma_user_" + userId, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        }
        ApplicationLoader.applicationContext.getSharedPreferences("luma_logged_out_" + account, Context.MODE_PRIVATE)
                .edit().clear().apply();
        SharedPreferences legacy = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = legacy.edit().putBoolean("luma_identity_migrated_" + account, true);
        for (String key : legacy.getAll().keySet()) {
            if (isLegacyAccountKey(key, account)) editor.remove(key);
        }
        editor.apply();
    }

    private static boolean isLegacyAccountKey(String key, int account) {
        if (key.startsWith("luma_edit_history_" + account + "_")
                || key.equals("luma_edit_history_index_" + account)
                || key.startsWith("luma_star_rating_progress_" + account + "_")) return true;
        final String[] prefixes = { "luma_ghost_mode_enabled_", "luma_ghost_mode_schedule_send_",
                "luma_keep_deleted_messages_enabled_", "luma_deleted_message_ids_",
                "luma_anonymous_number_enabled_", "luma_anonymous_number_digits_",
                "luma_star_rating_enabled_", "luma_star_rating_level_",
                "luma_profile_verification_enabled_", "luma_emergency_mode_enabled_", "luma_emergency_mode_dialog_id_" };
        for (String prefix : prefixes) if (key.equals(prefix + account)) return true;
        return false;
    }
}
