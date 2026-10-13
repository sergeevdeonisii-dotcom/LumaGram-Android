package org.telegram.messenger;
public final class LunagramSubscriptionAccess {
    static final boolean[] allowed={true,true,true,true};
    public static boolean canUse(int account,LunagramFeatureCatalog.Feature feature){return allowed[account];}
}
