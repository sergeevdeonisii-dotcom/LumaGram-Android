package android.content;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe SharedPreferences model with a deterministic pre-apply pause, no Android disk IO. */
public final class Context {
    public static final int MODE_PRIVATE = 0;
    public interface ApplyHook { void before(String store,Map<String,Object> changes,boolean clear); }
    public static volatile ApplyHook applyHook;
    private final Map<String,SharedPreferences> stores = new ConcurrentHashMap<>();
    public SharedPreferences getSharedPreferences(String name,int mode) { return stores.computeIfAbsent(name, Memory::new); }
    private static final class Memory implements SharedPreferences {
        private final String name;
        private final Map<String,Object> data = new HashMap<>();
        Memory(String name) { this.name = name; }
        public synchronized Map<String,?> getAll() { return new HashMap<>(data); }
        public synchronized boolean contains(String key) { return data.containsKey(key); }
        public synchronized boolean getBoolean(String key,boolean fallback) { return (Boolean)data.getOrDefault(key,fallback); }
        public synchronized int getInt(String key,int fallback) { return (Integer)data.getOrDefault(key,fallback); }
        public synchronized long getLong(String key,long fallback) { return (Long)data.getOrDefault(key,fallback); }
        public synchronized String getString(String key,String fallback) { return (String)data.getOrDefault(key,fallback); }
        @SuppressWarnings("unchecked") public synchronized Set<String> getStringSet(String key,Set<String> fallback) {
            return new HashSet<>((Set<String>)data.getOrDefault(key,fallback));
        }
        public Editor edit() { return new Editor() {
            boolean clear;
            final Map<String,Object> changes = new HashMap<>();
            public Editor putBoolean(String key,boolean value) { changes.put(key,value); return this; }
            public Editor putInt(String key,int value) { changes.put(key,value); return this; }
            public Editor putLong(String key,long value) { changes.put(key,value); return this; }
            public Editor putString(String key,String value) { changes.put(key,value); return this; }
            public Editor putStringSet(String key,Set<String> value) { changes.put(key,new HashSet<>(value)); return this; }
            public Editor remove(String key) { changes.put(key,null); return this; }
            public Editor clear() { clear = true; return this; }
            public void apply() {
                ApplyHook hook = applyHook;
                if (hook != null) hook.before(name,new HashMap<>(changes),clear);
                synchronized (Memory.this) {
                    if (clear) data.clear();
                    changes.forEach((key,value) -> { if (value == null) data.remove(key); else data.put(key,value); });
                }
            }
        }; }
    }
}
