package org.telegram.messenger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure vocabulary for the public feature tiers. Not a runtime access check.
 * Does not read accounts, payments or preferences. The subscription screen may display
 * this distribution; that display never enforces or grants runtime access.
 * The separate owner-bound authority disables paid effects after confirmed expiry while
 * retaining their saved data and preferences. Existing vault locks must never auto-unlock.
 * Personal-only capabilities and compile-time edition limits are not represented here.
 */
public final class LunagramFeatureCatalog {
    private LunagramFeatureCatalog() {}

    public enum Tier {
        FREE("free", 0),
        BASED("based", 1),
        STANDARD("standard", 2),
        PLUS("plus", 3),
        PRO("pro", 4),
        ULTRA("ultra", 5);

        public final String id;
        private final int rank;

        Tier(String id, int rank) {
            this.id = id;
            this.rank = rank;
        }
    }

    public enum Feature {
        TELEGRAM_BASELINE("telegram_baseline", Tier.FREE),
        BASIC_GLASS("basic_glass", Tier.FREE),
        USABILITY_FIXES("usability_fixes", Tier.FREE),
        EXISTING_LOCAL_DATA_ACCESS("existing_local_data_access", Tier.FREE),

        ADVANCED_GLASS("advanced_glass", Tier.BASED),
        AUTOMATIC_MESSAGE_FORMATTING("automatic_message_formatting", Tier.BASED),

        TYPING_MOTION("typing_motion", Tier.STANDARD),
        SEND_UNDO("send_undo", Tier.STANDARD),

        ROUND_VIDEO_CONTROLS("round_video_controls", Tier.PLUS),

        LOCAL_NUMBER("local_number", Tier.PRO),
        LOCAL_LEVEL("local_level", Tier.PRO),
        LOCAL_VERIFICATION("local_verification", Tier.PRO),
        LOCAL_NFT("local_nft", Tier.PRO),
        CHAT_NOTES("chat_notes", Tier.PRO),
        SETTINGS_PROFILES("settings_profiles", Tier.PRO),
        SETTINGS_TRANSFER("settings_transfer", Tier.PRO),

        GHOST("ghost", Tier.ULTRA),
        RETAIN_DELETED("retain_deleted", Tier.ULTRA),
        EDIT_RETENTION("edit_retention", Tier.ULTRA),
        EMERGENCY("emergency", Tier.ULTRA),
        CHAT_VAULT("chat_vault", Tier.ULTRA),
        NOTIFICATION_JOURNAL("notification_journal", Tier.ULTRA),
        CHAT_EXPORT("chat_export", Tier.ULTRA),
        CHAT_PDF("chat_pdf", Tier.ULTRA);

        public final String id;
        private final Tier minimumTier;

        Feature(String id, Tier minimumTier) {
            this.id = id;
            this.minimumTier = minimumTier;
        }
    }

    private static final Map<String, Tier> TIERS;
    private static final Map<String, Feature> FEATURES;
    private static final Map<String, Tier> MINIMUM_TIERS;
    static {
        Map<String, Tier> tiers = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) tiers.put(tier.id, tier);
        TIERS = Collections.unmodifiableMap(tiers);
        Map<String, Feature> features = new LinkedHashMap<>();
        Map<String, Tier> minimums = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            features.put(feature.id, feature);
            minimums.put(feature.id, feature.minimumTier);
        }
        FEATURES = Collections.unmodifiableMap(features);
        MINIMUM_TIERS = Collections.unmodifiableMap(minimums);
    }

    /** Exact catalog IDs only. Missing/unknown API plans are not silently treated as paid. */
    public static Tier tierFromId(String id) {
        return id == null ? null : TIERS.get(id);
    }

    public static Feature featureFromId(String id) {
        return id == null ? null : FEATURES.get(id);
    }

    public static Tier minimumTier(Feature feature) {
        return feature == null ? null : feature.minimumTier;
    }

    public static Tier minimumTier(String featureId) {
        return minimumTier(featureFromId(featureId));
    }

    /** Pure set membership, not proof of a paid account or permission for a runtime action. */
    public static boolean includes(Tier tier, Feature feature) {
        return tier != null && feature != null && tier.rank >= feature.minimumTier.rank;
    }

    public static boolean includes(String tierId, String featureId) {
        return includes(tierFromId(tierId), featureFromId(featureId));
    }

    public static boolean isBaselineFree(Feature feature) {
        return feature != null && feature.minimumTier == Tier.FREE;
    }

    public static Map<String, Tier> minimumTiers() {
        return MINIMUM_TIERS;
    }
}
