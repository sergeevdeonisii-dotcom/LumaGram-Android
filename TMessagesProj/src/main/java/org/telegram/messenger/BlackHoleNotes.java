package org.telegram.messenger;

import java.util.HashSet;
import java.util.Set;

public final class BlackHoleNotes {
    public static final int MAX_LENGTH = 4000;
    private static final String INDEX = "bhg_notes_index";
    private BlackHoleNotes() {}
    private static String key(long id) { return "bhg_note_" + id; }
    public static String get(int account, long dialogId) throws Exception {
        return BlackHolePrivateData.read(account, key(dialogId), "");
    }
    public static synchronized void save(int account, long dialogId, String note) throws Exception {
        if (dialogId == 0 || note == null || note.length() > MAX_LENGTH) throw new IllegalArgumentException("note");
        long owner = UserConfig.getInstance(account).getClientUserId();
        android.content.SharedPreferences prefs = LumaAccountData.preferences(account);
        requireOwner(account, owner);
        Set<String> index = new HashSet<>(prefs.getStringSet(INDEX, new HashSet<>()));
        if (note.trim().isEmpty()) {
            BlackHolePrivateData.remove(account, owner, key(dialogId)); index.remove(Long.toString(dialogId));
        } else {
            if (index.size() >= 500 && !index.contains(Long.toString(dialogId))) throw new IllegalArgumentException("too many notes");
            BlackHolePrivateData.write(account, owner, key(dialogId), note); index.add(Long.toString(dialogId));
        }
        requireOwner(account, owner);
        prefs.edit().putStringSet(INDEX, index).apply();
    }
    public static synchronized Set<Long> dialogs(int account) {
        Set<Long> ids = new HashSet<>();
        for (String value : LumaAccountData.preferences(account).getStringSet(INDEX, new HashSet<>())) {
            try { long id = Long.parseLong(value); if (id != 0) ids.add(id); } catch (NumberFormatException ignored) {}
        }
        return ids;
    }
    private static void requireOwner(int account, long owner) {
        if (owner <= 0 || owner != UserConfig.getInstance(account).getClientUserId()) throw new IllegalStateException("account changed");
    }
}
