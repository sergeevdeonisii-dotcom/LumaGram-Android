package org.json;
import java.util.*;
/** Controlled object adapter: contracts are tested, not Android's JSON parser. */
public final class JSONObject {
    public static final Object NULL = new Object();
    private static final Map<String,Map<String,Object>> responses = new HashMap<>();
    private static int sequence;
    private final Map<String,Object> values;
    public JSONObject() { values = new HashMap<>(); }
    public JSONObject(String input) {
        if (!responses.containsKey(input)) throw new IllegalArgumentException("Invalid fixture JSON");
        values = new HashMap<>(responses.get(input));
    }
    public JSONObject put(String key,Object value) { values.put(key,value == null ? NULL : value); return this; }
    public Object get(String key) { if (!values.containsKey(key)) throw new IllegalArgumentException("Missing field"); return values.get(key); }
    public boolean has(String key) { return values.containsKey(key); }
    public Iterator<String> keys() { return values.keySet().iterator(); }
    @Override public String toString() {
        String reference = "fixture-object-" + (++sequence); responses.put(reference,new HashMap<>(values)); return reference;
    }
}
