package org.json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Token-based local persistence fixture, not a JSON parser. */
public class JSONArray {
    private static final Map<String, ArrayList<Object>> saved = new HashMap<>();
    private static int sequence;
    private final ArrayList<Object> values;
    public JSONArray() { values = new ArrayList<>(); }
    public JSONArray(String token) throws JSONException {
        if ("[]".equals(token)) { values = new ArrayList<>(); return; }
        ArrayList<Object> previous = saved.get(token);
        if (previous == null) throw new JSONException("unknown fixture token");
        values = new ArrayList<>(previous);
    }
    public int length() { return values.size(); }
    public JSONArray put(Object value) { values.add(value); return this; }
    public JSONObject getJSONObject(int index) throws JSONException {
        if (index < 0 || index >= values.size() || !(values.get(index) instanceof JSONObject)) {
            throw new JSONException("object index");
        }
        return (JSONObject) values.get(index);
    }
    public String optString(int index) { return index >= 0 && index < values.size() ? (String) values.get(index) : ""; }
    @Override public String toString() {
        String token = "array-token-" + (++sequence);
        saved.put(token, new ArrayList<>(values));
        return token;
    }
}
