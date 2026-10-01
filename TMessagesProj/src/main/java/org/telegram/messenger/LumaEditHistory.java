package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

/** Local snapshots of message text and captions seen before an edit arrives. */
public final class LumaEditHistory {
    private static final String PREFIX = "luma_edit_history_";
    private static final String INDEX_PREFIX = "luma_edit_history_index_";
    private static final int MAX_MESSAGES = 300;
    private static final int MAX_VERSIONS = 20;
    private static final int MAX_TEXT_LENGTH = 4096;

    private LumaEditHistory() {
    }

    private static SharedPreferences preferences(int account) {
        return LumaAccountData.preferences(account);
    }

    private static String key(int account, long dialogId, int messageId) {
        return PREFIX + dialogId + "_" + messageId;
    }

    private static String text(TLRPC.Message message) {
        if (message == null || message.message == null) {
            return "";
        }
        String value = message.message;
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }

    private static JSONObject version(TLRPC.Message message) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("date", message.edit_date != 0 ? message.edit_date : message.date);
        value.put("text", text(message));
        return value;
    }

    public static synchronized void rememberEdit(int account, long dialogId, TLRPC.Message before, TLRPC.Message after) {
        if (before == null || after == null || before.id <= 0 || before.id != after.id
                || (after.flags & TLRPC.MESSAGE_FLAG_EDITED) == 0
                || after.edit_date == 0 || after.edit_date < before.edit_date
                || TextUtils.equals(text(before), text(after))) {
            return;
        }
        String storageKey = key(account, dialogId, before.id);
        SharedPreferences prefs = preferences(account);
        try {
            JSONArray previous = new JSONArray(prefs.getString(storageKey, "[]"));
            JSONArray versions = new JSONArray();
            for (int i = Math.max(0, previous.length() - MAX_VERSIONS + 2); i < previous.length(); i++) {
                versions.put(previous.getJSONObject(i));
            }
            if (versions.length() == 0 || !TextUtils.equals(versions.getJSONObject(versions.length() - 1).optString("text"), text(before))) {
                versions.put(version(before));
            }
            versions.put(version(after));

            String indexKey = INDEX_PREFIX;
            JSONArray oldIndex = new JSONArray(prefs.getString(indexKey, "[]"));
            JSONArray index = new JSONArray();
            for (int i = 0; i < oldIndex.length(); i++) {
                String item = oldIndex.optString(i);
                if (!storageKey.equals(item)) {
                    index.put(item);
                }
            }
            index.put(storageKey);
            SharedPreferences.Editor editor = prefs.edit().putString(storageKey, versions.toString());
            if (index.length() > MAX_MESSAGES) {
                editor.remove(index.optString(0));
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < index.length(); i++) {
                    trimmed.put(index.optString(i));
                }
                index = trimmed;
            }
            editor.putString(indexKey, index.toString()).apply();
        } catch (JSONException exception) {
            FileLog.e(exception);
        }
    }

    public static synchronized JSONArray getVersions(int account, long dialogId, int messageId) {
        try {
            return new JSONArray(preferences(account).getString(key(account, dialogId, messageId), "[]"));
        } catch (JSONException exception) {
            FileLog.e(exception);
            return new JSONArray();
        }
    }
}
