package android.content;

import java.util.HashMap;
import java.util.Map;

/** Small, intentionally strict preference model: incorrect stored types throw. */
public final class Context {
    public final Preferences preferences = new Preferences();
    public String requestedName;
    public int requestedMode;

    public SharedPreferences getSharedPreferences(String name, int mode) {
        requestedName = name;
        requestedMode = mode;
        return preferences;
    }

    public static final class Preferences implements SharedPreferences {
        public final Map<String, Object> values = new HashMap<>();
        public int writes;

        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value == null ? fallback : (Integer) value;
        }

        public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                public Editor putInt(String key, int value) {
                    pending.put(key, value);
                    return this;
                }
                public void apply() {
                    values.putAll(pending);
                    writes++;
                }
            };
        }
    }
}
