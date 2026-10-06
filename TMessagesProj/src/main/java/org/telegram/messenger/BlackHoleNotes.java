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
    public static void save(int account, long dialogId, String note) throws Exception {
        if (dialogId == 0 || note == null || note.length() > MAX_LENGTH) throw new IllegalArgumentException("note");
        Set<String> index = new HashSet<>(LumaAccountData.preferences(account).getStringSet(INDEX, new HashSet<>()));
        if (note.trim().isEmpty()) {
            BlackHolePrivateData.remove(account, key(dialogId)); index.remove(Long.toString(dialogId));
        } else {
            if (index.size() >= 500 && !index.contains(Long.toString(dialogId))) throw new IllegalArgumentException("too many notes");
            BlackHolePrivateData.write(account, key(dialogId), note); index.add(Long.toString(dialogId));
        }
        LumaAccountData.preferences(account).edit().putStringSet(INDEX, index).apply();
    }
    public static Set<Long> dialogs(int account) {
        Set<Long> ids = new HashSet<>();
        for (String value : LumaAccountData.preferences(account).getStringSet(INDEX, new HashSet<>())) {
            try { ids.add(Long.parseLong(value)); } catch (NumberFormatException ignored) {}
        }
        return ids;
    }
}
