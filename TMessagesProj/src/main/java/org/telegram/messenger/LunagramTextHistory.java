package org.telegram.messenger;

import java.util.ArrayList;

/** Session-only bounded snapshots. Caller supplies immutable values, binds identity and clears on exit. */
public final class LunagramTextHistory<T> {
    public static final int MAX_STATES = 24, MAX_UNITS = 262144;
    private final ArrayList<Entry<T>> entries = new ArrayList<>();
    private int cursor = -1, units;
    public static final class Entry<T> {
        public final T value; public final int length, start, end;
        private Entry(T value, int length, int start, int end) {
            this.value = value; this.length = length;
            this.start = Math.max(0, Math.min(length, start)); this.end = Math.max(0, Math.min(length, end));
        }
    }
    public void record(T value, int length, int start, int end) {
        if (value == null || length < 0 || length > LunagramTextTools.MAX_INPUT) { clear(); return; }
        if (cursor >= 0 && entries.get(cursor).value.equals(value)) return;
        while (entries.size() > cursor + 1) units -= entries.remove(entries.size() - 1).length;
        entries.add(new Entry<>(value, length, start, end)); units += length; cursor++;
        while (entries.size() > MAX_STATES || units > MAX_UNITS) { units -= entries.remove(0).length; cursor--; }
    }
    public boolean canUndo() { return cursor > 0; }
    public boolean canRedo() { return cursor >= 0 && cursor + 1 < entries.size(); }
    public Entry<T> undo() { return canUndo() ? entries.get(--cursor) : null; }
    public Entry<T> redo() { return canRedo() ? entries.get(++cursor) : null; }
    public int retainedUnits() { return units; }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); cursor = -1; units = 0; }
}
