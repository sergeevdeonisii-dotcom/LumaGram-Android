package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

/** Opt-in history of this client's posted notifications, not a listener for other apps. */
public final class BlackHoleNotificationJournal {
    public static final int MAX_ENTRIES = 200;
    public static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String ENABLED = "bhg_notification_journal_enabled", DATA = "bhg_notification_journal";
    public static final class Entry {
        public final long dialogId, date;
        public final int messageId;
        public final String title, text;
        public Entry(long dialogId, int messageId, long date, String title, String text) {
            this.dialogId = dialogId; this.messageId = messageId; this.date = date;
            this.title = limit(title, 200); this.text = limit(text, 2000);
        }
        String key() { return dialogId + ":" + messageId; }
    }
    private BlackHoleNotificationJournal() {}
    public static boolean isEnabled(int account) {
        // History is opt-in. A legacy/corrupt preference with another type must not
        // crash the settings UI (SharedPreferences.getBoolean throws ClassCastException).
        return Boolean.TRUE.equals(LumaAccountData.preferences(account).getAll().get(ENABLED));
    }
    public static synchronized void setEnabled(int account, boolean enabled) {
        LumaAccountData.preferences(account).edit().putBoolean(ENABLED, enabled).apply();
    }
    private static String limit(String text, int max) {
        if (text == null) return "";
        int end = Math.min(max, text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
    public static synchronized ArrayList<Entry> entries(int account) throws Exception {
        long owner = UserConfig.getInstance(account).getClientUserId();
        ArrayList<Entry> result = new ArrayList<>();
        if (owner <= 0) return result;
        // A decryption failure remains an error; it must never be mistaken for an
        // empty journal and overwritten. Only malformed authenticated JSON recovers.
        String text = BlackHolePrivateData.read(account, DATA, "[]");
        JSONArray data;
        try {
            data = new JSONArray(text);
        } catch (Exception e) {
            requireOwner(account, owner);
            // JSONObject errors can include the decrypted value in their message.
            FileLog.e(new IllegalStateException("Invalid local notification journal data"));
            return result;
        }
        long oldest = System.currentTimeMillis() - RETENTION_MS;
        for (int n = 0; n < data.length() && result.size() < MAX_ENTRIES; n++) {
            try {
                JSONObject e = data.getJSONObject(n);
                long dialogId = e.getLong("dialog"), date = e.getLong("date");
                int messageId = e.getInt("message");
                if (dialogId != 0 && messageId != 0 && date >= oldest && !BlackHoleVault.contains(account, dialogId))
                    result.add(new Entry(dialogId, messageId, date, e.getString("title"), e.getString("text")));
            } catch (Exception e) {
                FileLog.e(new IllegalStateException("Invalid local notification journal entry"));
            }
        }
        requireOwner(account, owner);
        if (result.size() != data.length()) write(account, owner, result);
        return result;
    }
    public static synchronized void record(int account, ArrayList<Entry> batch) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (owner <= 0 || !isEnabled(account) || !BlackHolePrivateData.isAvailable() || batch == null || batch.isEmpty()
                || owner != UserConfig.getInstance(account).getClientUserId()) return;
        try {
            ArrayList<Entry> old = entries(account), merged = new ArrayList<>(); HashSet<String> keys = new HashSet<>();
            long oldest = System.currentTimeMillis() - RETENTION_MS;
            for (Entry e : old) keys.add(e.key());
            for (Entry e : batch) {
                if (e != null && e.dialogId != 0 && e.messageId != 0 && e.date >= oldest && !BlackHoleVault.contains(account, e.dialogId) && keys.add(e.key())) merged.add(e);
            }
            if (merged.isEmpty()) return; // Repeated notify/update does not rewrite or duplicate history.
            merged.addAll(old); merged.sort((a, b) -> Long.compare(b.date, a.date));
            if (owner <= 0 || owner != UserConfig.getInstance(account).getClientUserId()) return;
            write(account, owner, merged);
        } catch (Exception e) { FileLog.e(e); } // Keystore errors never produce plaintext copies.
    }
    private static void write(int account, long owner, ArrayList<Entry> entries) throws Exception {
        JSONArray data = new JSONArray();
        for (int n = 0; n < entries.size() && n < MAX_ENTRIES; n++) {
            Entry e = entries.get(n);
            data.put(new JSONObject().put("dialog", e.dialogId).put("message", e.messageId).put("date", e.date)
                    .put("title", e.title).put("text", e.text));
        }
        BlackHolePrivateData.write(account, owner, DATA, data.toString());
    }
    public static synchronized void removeDialog(int account, long dialogId) throws Exception {
        long owner = UserConfig.getInstance(account).getClientUserId();
        ArrayList<Entry> values = entries(account); values.removeIf(e -> e.dialogId == dialogId); write(account, owner, values);
    }
    public static synchronized void clear(int account) { BlackHolePrivateData.remove(account, DATA); }
    private static void requireOwner(int account, long owner) {
        if (owner <= 0 || owner != UserConfig.getInstance(account).getClientUserId()) throw new IllegalStateException("account changed");
    }
}
