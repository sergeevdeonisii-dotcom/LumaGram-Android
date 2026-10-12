package org.telegram.messenger;

import java.util.LinkedHashMap;
import java.util.Map;

import org.telegram.messenger.LunagramFeatureCatalog.Feature;
import org.telegram.messenger.LunagramFeatureCatalog.Tier;

public final class FeatureCatalog150Test {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void immutable(Runnable mutation) {
        boolean rejected = false;
        try { mutation.run(); } catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, "catalog mutation rejected");
    }
    private static void add(Map<String, Tier> expected, Tier tier, String... ids) {
        for (String id : ids) expected.put(id, tier);
    }
    private static int rank(Tier tier) {
        switch (tier) {
            case FREE: return 0;
            case BASED: return 1;
            case STANDARD: return 2;
            case PLUS: return 3;
            case PRO: return 4;
            case ULTRA: return 5;
            default: throw new AssertionError("Unexpected tier");
        }
    }
    public static void main(String[] args) {
        Map<String, Tier> expected = new LinkedHashMap<>();
        add(expected, Tier.FREE, "telegram_baseline", "basic_glass", "usability_fixes", "existing_local_data_access");
        add(expected, Tier.BASED, "advanced_glass", "automatic_message_formatting");
        add(expected, Tier.STANDARD, "typing_motion", "send_undo");
        add(expected, Tier.PLUS, "round_video_controls");
        add(expected, Tier.PRO, "local_number", "local_level", "local_verification", "local_nft", "chat_notes", "settings_profiles", "settings_transfer");
        add(expected, Tier.ULTRA, "ghost", "retain_deleted", "edit_retention", "emergency", "chat_vault", "notification_journal", "chat_export", "chat_pdf");
        Map<String, Tier> actual = LunagramFeatureCatalog.minimumTiers();
        check(actual.equals(expected) && Feature.values().length == expected.size(), "exact agreed matrix without silent extras");
        for (Tier tier : Tier.values()) {
            check(LunagramFeatureCatalog.tierFromId(tier.id) == tier, "stable tier ID");
            for (Feature feature : Feature.values()) {
                Tier required = expected.get(feature.id);
                boolean included = rank(tier) >= rank(required);
                check(LunagramFeatureCatalog.featureFromId(feature.id) == feature, "stable feature ID");
                check(LunagramFeatureCatalog.minimumTier(feature) == required, "typed minimum");
                check(LunagramFeatureCatalog.minimumTier(feature.id) == required, "string minimum");
                check(LunagramFeatureCatalog.includes(tier, feature) == included, "cumulative typed membership");
                check(LunagramFeatureCatalog.includes(tier.id, feature.id) == included, "cumulative string membership");
                check(LunagramFeatureCatalog.isBaselineFree(feature) == (required == Tier.FREE), "free baseline independent of purchase");
            }
        }
        for (String unknown : new String[]{null, "", " ", "ultra ", "ULTRA", "pro_ultra", "ultrа", "paid", "personal", "friends", "unknown", "{\"planId\":\"ultra\"}"}) {
            check(LunagramFeatureCatalog.tierFromId(unknown) == null, "unknown plan not normalized or upgraded");
            for (Feature feature : Feature.values()) check(!LunagramFeatureCatalog.includes(unknown, feature.id), "unknown plan fails closed");
        }
        for (String unknown : new String[]{null, "", " ", "ghost ", "GHOST", "ghost.enabled", "horizon_lock", "personal_updater", "all", "*", "unknown"}) {
            check(LunagramFeatureCatalog.featureFromId(unknown) == null && LunagramFeatureCatalog.minimumTier(unknown) == null, "unknown feature has no grant");
            for (Tier tier : Tier.values()) check(!LunagramFeatureCatalog.includes(tier.id, unknown), "unknown feature fails closed even for Ultra");
        }
        check(!LunagramFeatureCatalog.includes((Tier) null, Feature.GHOST), "missing typed tier rejected");
        check(!LunagramFeatureCatalog.includes(Tier.ULTRA, (Feature) null), "missing typed feature rejected");
        check(!LunagramFeatureCatalog.isBaselineFree(null), "missing feature not free by default");
        check(LunagramFeatureCatalog.includes(Tier.PRO, Feature.SETTINGS_PROFILES)
                && !LunagramFeatureCatalog.includes(Tier.PRO, Feature.GHOST), "Pro profile shell does not grant Ghost content");
        check(LunagramFeatureCatalog.includes(Tier.FREE, Feature.EXISTING_LOCAL_DATA_ACCESS), "existing read/delete/export access is a free baseline, not a runtime policy side effect");
        immutable(() -> actual.put("injected", Tier.FREE));
        immutable(actual::clear);
        immutable(() -> actual.entrySet().iterator().remove());
        immutable(() -> actual.entrySet().iterator().next().setValue(Tier.ULTRA));
        immutable(() -> actual.keySet().remove("ghost"));
        immutable(() -> actual.values().remove(Tier.FREE));
        check(actual.equals(expected), "all mutation attempts leave exact matrix unchanged");
        System.out.println("PASS: " + assertions + " pure feature catalog checks; no entitlement, UI or runtime gates.");
    }
}
