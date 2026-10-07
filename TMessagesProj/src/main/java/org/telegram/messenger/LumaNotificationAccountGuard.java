package org.telegram.messenger;

/** Revalidate an asynchronous notification against its original login identity. */
public final class LumaNotificationAccountGuard {
    private LumaNotificationAccountGuard() {
    }

    public static boolean allows(long expectedOwner, long currentOwner, long expectedCall, long currentCall,
                                 boolean hiddenDialog, boolean emergencyExcluded) {
        return expectedOwner > 0 && expectedOwner == currentOwner && expectedCall == currentCall
                && !hiddenDialog && !emergencyExcluded;
    }
}
