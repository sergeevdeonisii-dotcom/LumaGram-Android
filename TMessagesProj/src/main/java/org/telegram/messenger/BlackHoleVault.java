package org.telegram.messenger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** UI privacy lock, not an encryption layer for Telegram's existing message database. */
public final class BlackHoleVault {
    private static final String KEY = "bhg_vault_dialogs";
    private static final HashMap<Integer, Long> unlockedOwners = new HashMap<>();
    private static long authenticationEpoch;
    private BlackHoleVault() {}
    public static Set<Long> dialogs(int account) {
        Set<Long> result = new HashSet<>();
        for (String value : LumaAccountData.preferences(account).getStringSet(KEY, new HashSet<>())) {
            try { long id = Long.parseLong(value); if (id != 0) result.add(id); } catch (NumberFormatException ignored) {}
        }
        return result;
    }
    public static boolean contains(int account, long id) {
        return id != 0 && LumaAccountData.preferences(account).getStringSet(KEY, new HashSet<>()).contains(Long.toString(id));
    }
    public static boolean hasDialogs(int account) { return !dialogs(account).isEmpty(); }
    public static void setProtected(int account, long id, boolean protect) {
        if (id == 0 || UserConfig.getInstance(account).getClientUserId() <= 0) return;
        Set<String> copy = new HashSet<>(LumaAccountData.preferences(account).getStringSet(KEY, new HashSet<>()));
        if (protect) copy.add(Long.toString(id)); else copy.remove(Long.toString(id));
        LumaAccountData.preferences(account).edit().putStringSet(KEY, copy).apply();
        TelegramMediaSession.refreshPrivacyIfCreated();
    }
    public static synchronized boolean isUnlocked(int account) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        return owner > 0 && Long.valueOf(owner).equals(unlockedOwners.get(account));
    }
    /** Called only by the successful native PasscodeView delegate, never from saved preferences. */
    public static synchronized long authenticationEpoch() { return authenticationEpoch; }
    public static synchronized void acceptAuthentication(int account, long expectedOwner, long expectedEpoch) {
        if (expectedEpoch == authenticationEpoch && expectedOwner > 0 && expectedOwner == UserConfig.getInstance(account).getClientUserId())
            unlockedOwners.put(account, expectedOwner);
    }
    public static synchronized void lockAll() { authenticationEpoch++; unlockedOwners.clear(); }
    public static boolean blocks(int account, long dialogId) { return contains(account, dialogId) && !isUnlocked(account); }
}
