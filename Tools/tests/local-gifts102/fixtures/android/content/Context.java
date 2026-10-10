package android.content;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> stores = new HashMap<>();
    public SharedPreferences getSharedPreferences(String name, int mode) { return stores.computeIfAbsent(name, ignored -> new Memory()); }
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> data = new HashMap<>();
        public Map<String,?> getAll() { return new HashMap<>(data); }
        public boolean contains(String key) { return data.containsKey(key); }
        public boolean getBoolean(String key,boolean fallback) { return (Boolean)data.getOrDefault(key,fallback); }
        public int getInt(String key,int fallback) { return (Integer)data.getOrDefault(key,fallback); }
        public long getLong(String key,long fallback) { return (Long)data.getOrDefault(key,fallback); }
        public String getString(String key,String fallback) { return (String)data.getOrDefault(key,fallback); }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback) { return (Set<String>)data.getOrDefault(key,fallback); }
        public Editor edit() { return new Editor() {
            boolean clear;
            final Map<String,Object> changes = new HashMap<>();
            public Editor putBoolean(String key,boolean value) { changes.put(key,value); return this; }
            public Editor putInt(String key,int value) { changes.put(key,value); return this; }
            public Editor putLong(String key,long value) { changes.put(key,value); return this; }
            public Editor putString(String key,String value) { changes.put(key,value); return this; }
            public Editor putStringSet(String key,Set<String> value) { changes.put(key,value); return this; }
            public Editor remove(String key) { changes.put(key,null); return this; }
            public Editor clear() { clear = true; return this; }
            public void apply() { if (clear) data.clear(); changes.forEach((key,value) -> { if(value == null) data.remove(key); else data.put(key,value); }); }
        }; }
    }
}
