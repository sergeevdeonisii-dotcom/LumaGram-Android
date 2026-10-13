package org.telegram.messenger;

/** Binds queued media work to its original account owner, never to a later selected account. */
public final class LunagramMediaAccess {
    private LunagramMediaAccess() {}

    public static Session forAccount(int account) { return new Session(account); }

    public static final class Session {
        private final int account;
        private final long owner, epoch;
        private Session(int account) {
            this.account = account;
            boolean valid = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
            owner = valid ? UserConfig.getInstance(account).getClientUserId() : 0;
            epoch = valid ? LunagramSubscriptionClient.accountEpoch(account) : -1;
        }
        public boolean canUse(LunagramFeatureCatalog.Feature feature) {
            return isCurrent() && LunagramSubscriptionAccess.canUse(account, feature) && isCurrent();
        }
        /** Identity is separate from expiry: an expired owner can still record ordinary mono. */
        public boolean isCurrent() {
            return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && owner > 0
                    && UserConfig.getInstance(account).getClientUserId() == owner
                    && LunagramSubscriptionClient.accountEpoch(account) == epoch;
        }
    }
}
