package org.telegram.messenger;

/** Test-only login generation seam, not a subscription authorization authority. */
public final class LunagramSubscriptionClient {
    static final long[] epochs = new long[UserConfig.MAX_ACCOUNT_COUNT];
    static long accountEpoch(int account) { return epochs[account]; }
}
