package org.telegram.messenger;

/** Test-only authority input. Production access decisions have their own independent suite. */
public final class LunagramSubscriptionAccess {
    public static final boolean[] allowed = {true, true, true, true};
    public static boolean canUse(int account, LunagramFeatureCatalog.Feature feature) {
        return account >= 0 && account < allowed.length && allowed[account] && feature != null;
    }
}
