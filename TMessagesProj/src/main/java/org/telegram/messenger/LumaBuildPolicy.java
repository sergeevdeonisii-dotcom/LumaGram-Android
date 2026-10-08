package org.telegram.messenger;

/** Compile-time capabilities; no preference, import or preset can override them. */
public final class LumaBuildPolicy {
    private LumaBuildPolicy() {
    }

    public static boolean isFriendsEdition() {
        return BuildConfig.LUMA_FRIENDS_EDITION;
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

    /** The full edition's feed must never replace a restricted Friends APK. */
    public static boolean allowsBuiltInUpdates() {
        return !BuildConfig.LUMA_FRIENDS_EDITION;
    }
}
