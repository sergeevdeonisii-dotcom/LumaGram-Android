package org.telegram.messenger;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MessagesController {
    public static final Prefs prefs = new Prefs();
    public static SharedPreferences getGlobalMainSettings() { return prefs; }
    public static final class Prefs implements SharedPreferences {
        public final HashMap<String, Object> values = new HashMap<>();
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public boolean contains(String key) { return values.containsKey(key); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
        public Editor edit() {
            return new Editor() {
                private final HashMap<String, Object> pending = new HashMap<>();
                private final HashSet<String> removed = new HashSet<>();
                private boolean clear;
                public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                public Editor putLong(String key, long value) { pending.put(key, value); return this; }
                public Editor putString(String key, String value) { pending.put(key, value); return this; }
                public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
                public Editor remove(String key) { removed.add(key); return this; }
                public Editor clear() { clear = true; return this; }
                public void apply() {
                    if (clear) values.clear();
                    for (String key : removed) values.remove(key);
                    values.putAll(pending);
                }
            };
        }
    }
}
