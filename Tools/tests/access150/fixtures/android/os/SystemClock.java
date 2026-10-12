package android.os;
/** Deterministic monotonic time. Wall-clock time is intentionally not changed. */
public final class SystemClock {
    public static long now = 1_000_000L;
    public static long elapsedRealtime() { return now; }
}
