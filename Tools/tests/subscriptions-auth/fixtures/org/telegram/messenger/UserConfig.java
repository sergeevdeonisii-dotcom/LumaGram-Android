package org.telegram.messenger;
public final class UserConfig {
    public static int selectedAccount;
    public static final long[] ids = {101,202,0,0};
    private final int account;
    private UserConfig(int account) { this.account = account; }
    public static UserConfig getInstance(int account) { return new UserConfig(account); }
    public long getClientUserId() { return ids[account]; }
}
