package org.telegram.messenger;
import org.telegram.tgnet.TLObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Negative control: exact shipped 1.0.2 cloneGraph from release source 8084f2f4. */
public final class LegacyClone103Probe {
    private static final int MAX_GIFT_BYTES = 256 * 1024;
    public static Object copy(Object value) throws Exception {
        return cloneGraph(value,new IdentityHashMap<>(),new int[] {0},0);
    }
    private static Object cloneGraph(Object value, IdentityHashMap<Object, Object> copied,
                                     int[] objects, int depth) throws Exception {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character) return value;
        if (value instanceof String) {
            if (((String) value).length() > 65536) throw new IllegalArgumentException("Oversized TL string");
            return value;
        }
        if (depth > 32 || ++objects[0] > 10000) throw new IllegalArgumentException("Oversized TL graph");
        if (copied.containsKey(value)) return copied.get(value);
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > MAX_GIFT_BYTES) throw new IllegalArgumentException("Oversized TL bytes");
            byte[] clone = bytes.clone();
            copied.put(value, clone);
            return clone;
        }
        if (value instanceof ArrayList) {
            ArrayList<?> list = (ArrayList<?>) value;
            if (list.size() > 256) throw new IllegalArgumentException("Oversized TL list");
            ArrayList<Object> clone = new ArrayList<>(list.size());
            copied.put(value, clone);
            for (Object item : list) clone.add(cloneGraph(item, copied, objects, depth + 1));
            return clone;
        }
        if (!(value instanceof TLObject)) throw new IllegalArgumentException("Unsupported mutable TL field");
        Object clone = value.getClass().getDeclaredConstructor().newInstance();
        copied.put(value, clone);
        for (Field field : value.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            field.set(clone, cloneGraph(field.get(value), copied, objects, depth + 1));
        }
        return clone;
    }
}
