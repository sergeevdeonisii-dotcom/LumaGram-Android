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
    public static boolean isEnabled(int account) { return LumaAccountData.preferences(account).getBoolean(ENABLED, false); }
    public static void setEnabled(int account, boolean enabled) {
        LumaAccountData.preferences(account).edit().putBoolean(ENABLED, enabled).apply();
    }
    private static String limit(String text, int max) { return text == null ? "" : text.substring(0, Math.min(max, text.length())); }
    public static synchronized ArrayList<Entry> entries(int account) throws Exception {
        JSONArray data = new JSONArray(BlackHolePrivateData.read(account, DATA, "[]"));
        ArrayList<Entry> result = new ArrayList<>();
        long oldest = System.currentTimeMillis() - RETENTION_MS;
        for (int n = 0; n < data.length() && result.size() < MAX_ENTRIES; n++) {
            JSONObject e = data.getJSONObject(n);
            long dialogId = e.getLong("dialog"), date = e.getLong("date");
            if (date >= oldest && !BlackHoleVault.contains(account, dialogId))
                result.add(new Entry(dialogId, e.getInt("message"), date, e.getString("title"), e.getString("text")));
        }
        if (result.size() != data.length()) write(account, result);
        return result;
    }
    public static synchronized void record(int account, ArrayList<Entry> batch) {
        if (!isEnabled(account) || !BlackHolePrivateData.isAvailable() || batch.isEmpty()) return;
        long owner = UserConfig.getInstance(account).getClientUserId();
        try {
            ArrayList<Entry> old = entries(account), merged = new ArrayList<>(); HashSet<String> keys = new HashSet<>();
            long oldest = System.currentTimeMillis() - RETENTION_MS;
            for (Entry e : old) keys.add(e.key());
            for (Entry e : batch) {
                if (e.date >= oldest && !BlackHoleVault.contains(account, e.dialogId) && keys.add(e.key())) merged.add(e);
            }
            if (merged.isEmpty()) return; // Repeated notify/update does not rewrite or duplicate history.
            merged.addAll(old); merged.sort((a, b) -> Long.compare(b.date, a.date));
            if (owner <= 0 || owner != UserConfig.getInstance(account).getClientUserId()) return;
            write(account, merged);
        } catch (Exception e) { FileLog.e(e); } // Keystore errors never produce plaintext copies.
    }
    private static void write(int account, ArrayList<Entry> entries) throws Exception {
        JSONArray data = new JSONArray();
        for (int n = 0; n < entries.size() && n < MAX_ENTRIES; n++) {
            Entry e = entries.get(n);
            data.put(new JSONObject().put("dialog", e.dialogId).put("message", e.messageId).put("date", e.date)
                    .put("title", e.title).put("text", e.text));
        }
        BlackHolePrivateData.write(account, DATA, data.toString());
    }
    public static synchronized void removeDialog(int account, long dialogId) throws Exception {
        ArrayList<Entry> values = entries(account); values.removeIf(e -> e.dialogId == dialogId); write(account, values);
    }
    public static synchronized void clear(int account) { BlackHolePrivateData.remove(account, DATA); }
}
