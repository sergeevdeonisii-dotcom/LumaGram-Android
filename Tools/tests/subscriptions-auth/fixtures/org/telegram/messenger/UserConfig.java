package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;
    public static int selectedAccount;
    public static final long[] ids = {101,202,0,0};
    private final int account;
    private UserConfig(int account) { this.account = account; }
    public static UserConfig getInstance(int account) { return new UserConfig(account); }
    public long getClientUserId() { return ids[account]; }
    /** Model the production identity-first barrier/cleanup contract, not Android IO. */
    public static void logout(int account) {
        long owner = ids[account];
        ids[account] = 0;
        LunagramSubscriptionClient.onAccountLogout(account);
        BlackHolePrivateData.stored.keySet().removeIf(key -> key.startsWith(owner+":"));
    }
}
