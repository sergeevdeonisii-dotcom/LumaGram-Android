package org.telegram.messenger;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
public final class LumaAccountData {
    private static final Map<Integer, Memory> accounts = new HashMap<>();
    public static SharedPreferences preferences(int account) {
        return accounts.computeIfAbsent(account, key -> new Memory());
    }
    private static final class Memory implements SharedPreferences, SharedPreferences.Editor {
        private final Map<String, Boolean> values = new HashMap<>();
        public boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
        public Editor edit() { return this; }
        public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        public void apply() { }
    }
}
