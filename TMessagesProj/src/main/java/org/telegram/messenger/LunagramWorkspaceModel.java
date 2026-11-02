package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Versioned, bounded data only. It never fetches messages or records drafts automatically. */
public final class LunagramWorkspaceModel {
    public static final int MAX_ITEMS = 128, MAX_TEXT = 16384, MAX_QUERY = 512, MAX_NAME = 80, MAX_BYTES = 262144;
    public enum Kind { TEMPLATE, BOOKMARK, CHECKLIST, DRAFT, SEARCH, MARKER }

    public static final class Entry {
        public final String id, name, text, collection;
        public final Kind kind;
        public final int messageId;
        public final boolean checked;
        public final long created;
        public Entry(String id, Kind kind, String name, String text, String collection,
                int messageId, boolean checked, long created) {
            this.id = id; this.kind = kind; this.name = name; this.text = text;
            this.collection = collection; this.messageId = messageId;
            this.checked = checked; this.created = created;
            validate();
        }
        public static Entry create(Kind kind, String name, String text, String collection, int messageId) {
            return new Entry(UUID.randomUUID().toString(), kind, name, text, collection, messageId, false, System.currentTimeMillis());
        }
        public Entry toggled() { return new Entry(id, kind, name, text, collection, messageId, !checked, created); }
        private void validate() {
            if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") || kind == null || created <= 0)
                throw new IllegalArgumentException("entry identity");
            if (name == null || name.trim().isEmpty() || name.length() > MAX_NAME || text == null || text.length() > MAX_TEXT
                    || collection == null || collection.length() > MAX_NAME || messageId < 0)
                throw new IllegalArgumentException("entry bounds");
            if (!validUnicode(name) || !validUnicode(text) || !validUnicode(collection))
                throw new IllegalArgumentException("invalid Unicode");
            if ((kind == Kind.BOOKMARK || kind == Kind.MARKER) && (messageId <= 0 || !text.isEmpty()))
                throw new IllegalArgumentException("reference only");
            if ((kind == Kind.TEMPLATE || kind == Kind.DRAFT || kind == Kind.SEARCH || kind == Kind.CHECKLIST) && text.trim().isEmpty())
                throw new IllegalArgumentException("empty text");
            if (kind != Kind.CHECKLIST && checked) throw new IllegalArgumentException("checked kind");
            if (kind == Kind.SEARCH && text.length() > MAX_QUERY) throw new IllegalArgumentException("query bounds");
            if (kind != Kind.BOOKMARK && kind != Kind.CHECKLIST && !collection.isEmpty()) throw new IllegalArgumentException("collection kind");
            if ((kind == Kind.BOOKMARK || kind == Kind.CHECKLIST) && collection.trim().isEmpty()) throw new IllegalArgumentException("collection required");
            if (kind != Kind.BOOKMARK && kind != Kind.MARKER && kind != Kind.CHECKLIST && messageId != 0)
                throw new IllegalArgumentException("unexpected message reference");
        }
    }

    public static final class Document {
        public final long dialogId;
        public final List<Entry> entries;
        public Document(long dialogId, List<Entry> entries) {
            if (dialogId == 0 || entries == null || entries.size() > MAX_ITEMS) throw new IllegalArgumentException("document bounds");
            Set<String> ids = new HashSet<>();
            int markers = 0;
            for (Entry entry : entries) {
                if (entry == null || !ids.add(entry.id)) throw new IllegalArgumentException("duplicate entry");
                if (entry.kind == Kind.MARKER && ++markers > 1) throw new IllegalArgumentException("duplicate marker");
            }
            this.dialogId = dialogId;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }
        public Entry find(String id) { for (Entry entry : entries) if (entry.id.equals(id)) return entry; return null; }
        public Document put(Entry entry) {
            ArrayList<Entry> copy = new ArrayList<>();
            for (Entry old : entries) {
                if (old.id.equals(entry.id) || entry.kind == Kind.MARKER && old.kind == Kind.MARKER) continue;
                copy.add(old);
            }
            copy.add(entry);
            return new Document(dialogId, copy);
        }
        public Document remove(String id) {
            ArrayList<Entry> copy = new ArrayList<>();
            for (Entry old : entries) if (!old.id.equals(id)) copy.add(old);
            return new Document(dialogId, copy);
        }
    }

    public static String encode(Document document) throws Exception {
        JSONArray entries = new JSONArray();
        for (Entry entry : document.entries) entries.put(new JSONObject()
                .put("id", entry.id).put("kind", entry.kind.name()).put("name", entry.name)
                .put("text", entry.text).put("collection", entry.collection).put("message", entry.messageId)
                .put("checked", entry.checked).put("created", entry.created));
        String result = new JSONObject().put("version", 1).put("dialog", document.dialogId).put("entries", entries).toString();
        requireSize(result);
        return result;
    }
    public static Document decode(String raw, long dialogId) throws Exception {
        if (raw == null) return new Document(dialogId, Collections.emptyList());
        requireSize(raw);
        JSONTokener tokenizer = new JSONTokener(raw);
        Object decoded = tokenizer.nextValue();
        if (!(decoded instanceof JSONObject) || tokenizer.nextClean() != 0) throw new IllegalArgumentException("invalid JSON envelope");
        JSONObject object = (JSONObject) decoded;
        exactKeys(object, "version", "dialog", "entries");
        if (integer(object, "version") != 1 || integer(object, "dialog") != dialogId) throw new IllegalArgumentException("schema or dialog mismatch");
        Object items = object.get("entries");
        if (!(items instanceof JSONArray)) throw new IllegalArgumentException("entries type");
        JSONArray array = (JSONArray) items;
        if (array.length() > MAX_ITEMS) throw new IllegalArgumentException("too many entries");
        ArrayList<Entry> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            exactKeys(entry, "id", "kind", "name", "text", "collection", "message", "checked", "created");
            long message = integer(entry, "message");
            if (message < 0 || message > Integer.MAX_VALUE || !(entry.get("checked") instanceof Boolean)) throw new IllegalArgumentException("reference or checkbox type");
            entries.add(new Entry(string(entry, "id"), Kind.valueOf(string(entry, "kind")), string(entry, "name"),
                    string(entry, "text"), string(entry, "collection"), (int) message, (Boolean) entry.get("checked"), integer(entry, "created")));
        }
        return new Document(dialogId, entries);
    }
    private static String string(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("string type");
        return (String) value;
    }
    private static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer type");
        return ((Number) value).longValue();
    }
    private static void exactKeys(JSONObject object, String... allowed) throws Exception {
        if (object.length() != allowed.length) throw new IllegalArgumentException("schema keys");
        Set<String> keys = new HashSet<>(); Collections.addAll(keys, allowed);
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw new IllegalArgumentException("unknown key");
    }
    private static void requireSize(String raw) {
        if (raw.length() > MAX_BYTES || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("workspace too large");
    }
    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private LunagramWorkspaceModel() {}
}
