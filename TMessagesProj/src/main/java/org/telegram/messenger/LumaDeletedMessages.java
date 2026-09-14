package org.telegram.messenger;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Stores local tombstones for deleted messages. The Telegram server and other clients are untouched.
 */
public final class LumaDeletedMessages {

    private static final String KEY_ENABLED = "luma_keep_deleted_messages_enabled_";
    private static final String KEY_IDS = "luma_deleted_message_ids_";

    private LumaDeletedMessages() {
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

    private static String key(long dialogId, int messageId) {
        return dialogId + ":" + messageId;
    }

    public static synchronized void rememberDeleted(int account, long dialogId, int messageId) {
        if (!isEnabled(account) || messageId == 0) {
            return;
        }
        SharedPreferences prefs = preferences();
        Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_IDS + account, new HashSet<>()));
        if (saved.add(key(dialogId, messageId))) {
            prefs.edit().putStringSet(KEY_IDS + account, saved).apply();
        }
    }

    public static synchronized boolean isDeleted(int account, long dialogId, int messageId) {
        if (messageId == 0) {
            return false;
        }
        return preferences().getStringSet(KEY_IDS + account, new HashSet<>()).contains(key(dialogId, messageId));
    }
}
