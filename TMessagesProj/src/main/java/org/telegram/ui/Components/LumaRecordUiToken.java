package org.telegram.ui.Components;

/** Main-thread transition ownership; cancellation invalidates callbacks before Animator.cancel(). */
public final class LumaRecordUiToken {
    private long epoch;
    public long current() { return epoch; }
    public long begin() { return ++epoch; }
    public void invalidate() { ++epoch; }
    public boolean isCurrent(long candidate) { return candidate == epoch; }
}
