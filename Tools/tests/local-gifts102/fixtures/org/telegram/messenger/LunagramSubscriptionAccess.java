package org.telegram.messenger;
/** Controllable paid access; no network or subscription entitlement implementation. */
public final class LunagramSubscriptionAccess {
    public static boolean allowed = true;
    public static boolean canUse(int account, LunagramFeatureCatalog.Feature feature) {
        return allowed;
    }
}
