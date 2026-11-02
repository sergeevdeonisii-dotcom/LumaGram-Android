package org.telegram.messenger;
public final class LunagramSubscriptionAccess {
    public static boolean paid=true;
    public static boolean canUse(int account,LunagramFeatureCatalog.Feature feature){return paid;}
}
