package org.json;
import java.util.ArrayList;
/** Bounded contract fixture, not proof of Android JSON parsing. */
public final class JSONArray {
    private final ArrayList<Object> values = new ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public Object get(int index) { return values.get(index); }
    public int length() { return values.size(); }
}
