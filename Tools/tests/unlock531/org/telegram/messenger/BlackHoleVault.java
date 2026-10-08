package org.telegram.messenger;
/** Authentication state model only; no PIN validation or device biometrics are simulated as successful implicitly. */
public final class BlackHoleVault {
    public static long epoch, unlockedOwner;
    public static int acceptCalls;
    public static long authenticationEpoch() { return epoch; }
    public static void acceptAuthentication(int account, long owner, long expectedEpoch) {
        acceptCalls++;
        if (owner > 0 && owner == UserConfig.getInstance(account).getClientUserId() && expectedEpoch == epoch) unlockedOwner = owner;
    }
    public static boolean isUnlocked(int account) { return unlockedOwner > 0 && unlockedOwner == UserConfig.getInstance(account).getClientUserId(); }
    public static void lockAll() { epoch++; unlockedOwner = 0; }
}
