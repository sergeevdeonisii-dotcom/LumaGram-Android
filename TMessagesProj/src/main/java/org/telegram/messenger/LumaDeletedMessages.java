package org.telegram.messenger;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores local tombstones for deleted messages. The Telegram server and other clients are untouched.
 */
public final class LumaDeletedMessages {

    private static final String KEY_ENABLED = "luma_keep_deleted_messages_enabled_";
    private static final String KEY_IDS = "luma_deleted_message_ids_";

    private LumaDeletedMessages() {
    }

    private static SharedPreferences preferences(int account) {
        return LumaAccountData.preferences(account);
    }

    public static boolean isEnabled(int account) {
        return preferences(account).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        preferences(account).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean shouldRetain(int account, boolean forceLocalRemoval, boolean scheduled, boolean template) {
        return !forceLocalRemoval && !scheduled && !template && isEnabled(account);
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + ":" + messageId;
    }

    public static synchronized void rememberDeleted(int account, long dialogId, int messageId) {
        if (!isEnabled(account) || messageId == 0) {
            return;
        }
        SharedPreferences prefs = preferences(account);
        Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_IDS, new HashSet<>()));
        if (saved.add(key(dialogId, messageId))) {
            prefs.edit().putStringSet(KEY_IDS, saved).apply();
        }
    }

    public static synchronized boolean isDeleted(int account, long dialogId, int messageId) {
        if (messageId == 0) {
            return false;
        }
        return preferences(account).getStringSet(KEY_IDS, new HashSet<>()).contains(key(dialogId, messageId));
    }

    /** Explicit local removal must not turn into another retained tombstone. */
    public static synchronized void forgetDeleted(int account, long dialogId, List<Integer> messageIds) {
        SharedPreferences prefs = preferences(account);
        Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_IDS, new HashSet<>()));
        boolean changed = false;
        for (Integer id : messageIds) {
            if (id != null) changed |= saved.remove(key(dialogId, id));
        }
        if (changed) prefs.edit().putStringSet(KEY_IDS, saved).apply();
    }
}
