package org.telegram.messenger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-shot, short-lived arrivals. Never populated by history loading or view binding. */
public final class LunagramIncomingAnimation {
    public static final int MAX_PENDING = 16;
    public static final long MAX_AGE_MS = 1200;
    private final LinkedHashMap<Integer, Long> pending = new LinkedHashMap<>();

    public void register(int messageId, long now) {
        prune(now);
        if (messageId <= 0 || pending.containsKey(messageId)) return;
        if (pending.size() >= MAX_PENDING) pending.remove(pending.keySet().iterator().next());
        pending.put(messageId, now);
    }

    public boolean consume(int messageId, long now) {
        prune(now);
        return pending.remove(messageId) != null;
    }

    public void clear() { pending.clear(); }

    private void prune(long now) {
        Iterator<Map.Entry<Integer, Long>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            long age = now - iterator.next().getValue();
            if (age < 0 || age >= MAX_AGE_MS) iterator.remove();
        }
    }

    public static long duration(int effect) { return effect == 1 ? 460 : effect == 2 ? 420 : 540; }

    public static float progress(float value) {
        if (Float.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }

    public static float eased(float progress) {
        float remaining = 1 - progress(progress);
        return 1 - remaining * remaining * remaining;
    }

}
