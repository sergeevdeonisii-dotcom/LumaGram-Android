package org.telegram.messenger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** A chronological frontier: never emit an old post while another source's head is unknown. */
public final class LunagramFeedMerger<T> {
    public static final class Entry<T> {
        public final long channel;
        public final int id, date;
        public final T value;

        public Entry(long channel, int id, int date, T value) {
            this.channel = channel;
            this.id = id;
            this.date = date;
            this.value = value;
        }
    }

    public static final class Source<T> {
        public final long channel;
        public int offset;
        public boolean loading, ended, failed;
        private final ArrayDeque<Entry<T>> pending = new ArrayDeque<>();

        private Source(long channel) { this.channel = channel; }
        public boolean needsPage() { return pending.isEmpty() && !ended && !loading && !failed; }
    }

    private final LinkedHashMap<Long, Source<T>> sources = new LinkedHashMap<>();
    private final HashSet<String> seen = new HashSet<>();
    private final HashMap<String, T> edited = new HashMap<>();
    private final Comparator<Entry<T>> newestFirst = (a, b) -> {
        int c = Integer.compare(b.date, a.date);
        if (c == 0) c = Long.compare(b.channel, a.channel);
        return c == 0 ? Integer.compare(b.id, a.id) : c;
    };

    public LunagramFeedMerger(Collection<Long> channels) {
        for (long channel : channels) if (channel > 0) sources.put(channel, new Source<>(channel));
    }

    public Collection<Source<T>> sources() { return sources.values(); }

    public void accept(Source<T> source, List<Entry<T>> entries, int nextOffset, boolean ended) {
        source.loading = false;
        source.failed = false;
        // A server returning the same nonempty page must not cause an infinite request loop.
        if (!ended && (nextOffset <= 0 || source.offset != 0 && nextOffset >= source.offset)) {
            source.failed = true;
            return;
        }
        source.offset = nextOffset;
        source.ended = ended;
        ArrayList<Entry<T>> sorted = new ArrayList<>(entries);
        sorted.sort(newestFirst);
        for (Entry<T> entry : sorted) {
            if (entry.channel == source.channel && entry.id > 0 && seen.add(entry.channel + ":" + entry.id)) {
                T latest = edited.get(entry.channel + ":" + entry.id);
                source.pending.addLast(latest == null ? entry : new Entry<>(entry.channel, entry.id, entry.date, latest));
            }
        }
    }

    public Entry<T> poll() {
        Source<T> winner = null;
        for (Source<T> source : sources.values()) {
            if (source.pending.isEmpty()) {
                if (!source.ended) return null;
            } else if (winner == null || newestFirst.compare(source.pending.peekFirst(), winner.pending.peekFirst()) < 0) {
                winner = source;
            }
        }
        return winner == null ? null : winner.pending.removeFirst();
    }

    public boolean hasError() {
        for (Source<T> source : sources.values()) if (source.failed) return true;
        return false;
    }

    public boolean isEnd() {
        for (Source<T> source : sources.values()) if (!source.ended || !source.pending.isEmpty()) return false;
        return true;
    }

    public void retry() {
        for (Source<T> source : sources.values()) source.failed = false;
    }

    public void remove(long channel, Collection<Integer> ids) {
        // A response already in flight can contain a now-deleted post. Keep a tombstone for this generation.
        for (int id : ids) { seen.add(channel + ":" + id); edited.remove(channel + ":" + id); }
        Source<T> source = sources.get(channel);
        if (source != null) source.pending.removeIf(entry -> ids.contains(entry.id));
    }

    public void replace(long channel, int id, T value) {
        Source<T> source = sources.get(channel);
        if (source == null) return;
        edited.put(channel + ":" + id, value);
        ArrayDeque<Entry<T>> updated = new ArrayDeque<>();
        for (Entry<T> entry : source.pending) updated.add(entry.id == id ? new Entry<>(channel, id, entry.date, value) : entry);
        source.pending.clear();
        source.pending.addAll(updated);
    }
}
