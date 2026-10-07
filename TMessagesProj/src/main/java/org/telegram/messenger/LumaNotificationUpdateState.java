package org.telegram.messenger;

/** Queue-confined state for merging notification refreshes without losing an alert. */
public final class LumaNotificationUpdateState {
    public static final long MIN_UPDATE_INTERVAL_MS = 250;

    private boolean pending;
    private boolean alert;
    private long lastUpdate = -1;

    /** Returns a delay for a new task, or -1 when the existing task will handle it. */
    public long request(boolean notifyAboutLast, long now) {
        alert |= notifyAboutLast;
        if (pending) {
            return -1;
        }
        pending = true;
        if (lastUpdate < 0 || now < lastUpdate) {
            return 0;
        }
        return Math.max(0, MIN_UPDATE_INTERVAL_MS - (now - lastUpdate));
    }

    /** A cancelled/stale task must not post or consume a later account's alert. */
    public Boolean take(long now) {
        if (!pending) {
            return null;
        }
        boolean result = alert;
        pending = false;
        alert = false;
        lastUpdate = now;
        return result;
    }

    public void clear() {
        pending = false;
        alert = false;
        lastUpdate = -1;
    }
}
