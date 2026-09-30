package org.telegram.messenger;

/** Tracks presence requests on the stage queue, including cancellation and network retries. */
final class LumaPresenceRequestState {
    private static final long RETRY_DELAY_MS = 5000;

    private int generation;
    private int pendingState;
    private long retryAt;

    boolean canSend(boolean offline, long now) {
        return pendingState != (offline ? 2 : 1) && now >= retryAt;
    }

    int begin(boolean offline) {
        pendingState = offline ? 2 : 1;
        retryAt = 0;
        return ++generation;
    }

    boolean complete(int requestGeneration, long now, boolean success) {
        if (requestGeneration != generation || pendingState == 0) {
            return false;
        }
        pendingState = 0;
        retryAt = success ? 0 : now + RETRY_DELAY_MS;
        return true;
    }

    void cancel() {
        generation++;
        pendingState = 0;
        retryAt = 0;
    }
}
