package org.telegram.messenger;
public final class AccountInstance {
    private final int account;
    private AccountInstance(int account) { this.account = account; }
    public static AccountInstance getInstance(int account) { return new AccountInstance(account); }
    public UserConfig getUserConfig() { return UserConfig.getInstance(account); }
}
