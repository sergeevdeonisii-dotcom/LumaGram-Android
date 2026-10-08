package org.json;

import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;

/** Controlled JSON tokens only; this fixture does not test a JSON parser. */
public class JSONObject {
    public static final Map<String, Map<String, Object>> responses = new HashMap<>();
    private static int sequence;
    private final Map<String, Object> values;
    public JSONObject() { values = new HashMap<>(); }
    public JSONObject(Map<String, ?> data) { values = new HashMap<>(data); }
    public JSONObject(String token) throws JSONException {
        Map<String, Object> saved = responses.get(token);
        if (saved == null) throw new JSONException("unknown fixture token");
        values = new HashMap<>(saved);
    }
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public String getString(String key) { return (String) values.get(key); }
    public int getInt(String key) { return ((Number) values.get(key)).intValue(); }
    public Object get(String key) { return values.get(key); }
    public JSONObject getJSONObject(String key) { return (JSONObject) values.get(key); }
    public Iterator<String> keys() { return values.keySet().iterator(); }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    public int optInt(String key) { return ((Number) values.getOrDefault(key, 0)).intValue(); }
    @Override public String toString() {
        String token = "object-token-" + (++sequence);
        responses.put(token, new HashMap<>(values));
        return token;
    }
    public String toString(int indent) { return toString(); }
}
