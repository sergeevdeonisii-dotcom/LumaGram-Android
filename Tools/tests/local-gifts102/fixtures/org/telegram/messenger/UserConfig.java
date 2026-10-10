package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;
    public static final long[] ids = new long[4];
    private final int account;
    private UserConfig(int account) { this.account = account; }
    public static UserConfig getInstance(int account) { return new UserConfig(account); }
    public long getClientUserId() { return ids[account]; }
}
