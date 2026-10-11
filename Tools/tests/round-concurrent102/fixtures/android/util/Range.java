package android.util;
public final class Range<T> {
    private final T low, high;
    public Range(T low, T high) { this.low = low; this.high = high; }
    public T getLower() { return low; }
    public T getUpper() { return high; }
}
