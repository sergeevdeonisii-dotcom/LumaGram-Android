package org.telegram.messenger;

/** Test-only authority seam. Existing unit suites model unrestricted access by default. */
public final class LunagramSubscriptionAccess {
    private static final java.util.Map<Integer, LunagramFeatureCatalog.Tier> tiers = new java.util.HashMap<>();
    private static final java.util.Set<Integer> unknown = new java.util.HashSet<>();
    public static boolean canUse(int account, LunagramFeatureCatalog.Feature feature) {
        return account >= 0 && LunagramFeatureCatalog.includes(tiers.getOrDefault(account, LunagramFeatureCatalog.Tier.ULTRA), feature);
    }
    public static void setTier(int account, LunagramFeatureCatalog.Tier tier) { tiers.put(account, tier); }
    public static void reset() { tiers.clear(); unknown.clear(); }
    public static void refresh(int account) {}
    public static boolean isChecking(int account) { return false; }
    public static boolean shouldProtectExistingPrivacy(int account) { return unknown.contains(account); }
    public static void setPrivacyUnknown(int account, boolean value) { if (value) unknown.add(account); else unknown.remove(account); }
}
