package org.telegram.messenger;

/** Metadata policy only: it does not pause or otherwise control playback. */
final class LumaPlaybackPrivacy {
    private LumaPlaybackPrivacy() { }

    static boolean passcodeLocked(boolean enabled, boolean appLocked, boolean waiting,
            int autoLockSeconds, int lastPauseSeconds, long uptimeSeconds) {
        return enabled && (appLocked || waiting
                || autoLockSeconds != 0 && lastPauseSeconds != 0
                && (long) lastPauseSeconds + autoLockSeconds <= uptimeSeconds
                || uptimeSeconds + 5 < lastPauseSeconds);
    }

    static boolean shouldRedact(long capturedOwner, long liveOwner, long dialogId,
            boolean encrypted, boolean vaultProtected, boolean passcodeLocked, boolean forceRedact) {
        return forceRedact || capturedOwner <= 0 || capturedOwner != liveOwner || dialogId == 0
                || encrypted || vaultProtected || passcodeLocked;
    }
}
