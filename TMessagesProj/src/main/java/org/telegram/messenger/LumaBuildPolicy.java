package org.telegram.messenger;

/** Compile-time capabilities; no preference, import or preset can override them. */
public final class LumaBuildPolicy {
    private LumaBuildPolicy() {
    }

    public static boolean isFriendsEdition() {
        return BuildConfig.LUMA_FRIENDS_EDITION;
    }

    public static boolean isPublicEdition() {
        return BuildConfig.LUMA_PUBLIC_EDITION;
    }

    public static boolean isPersonalEdition() {
        return !BuildConfig.LUMA_FRIENDS_EDITION && !BuildConfig.LUMA_PUBLIC_EDITION;
    }

    /** Ghost mode, automatic delay, retained deletions and local edit history. */
    public static boolean allowsPrivacyTools() {
        return !BuildConfig.LUMA_FRIENDS_EDITION;
    }

    public static boolean allowsAnonymousNumber() {
        return !BuildConfig.LUMA_FRIENDS_EDITION;
    }

    public static boolean allowsProfileVerification() {
        return !BuildConfig.LUMA_FRIENDS_EDITION;
    }

    /** Sensor-based horizon lock is personal-only; ordinary stabilization and FPS are public. */
    public static boolean allowsEnhancedRoundVideoStabilization() {
        return !BuildConfig.LUMA_FRIENDS_EDITION && !BuildConfig.LUMA_PUBLIC_EDITION;
    }

    /** Public uses its own pinned feed; restricted Friends installs have no updater. */
    public static boolean allowsBuiltInUpdates() {
        return !BuildConfig.LUMA_FRIENDS_EDITION;
    }
}
