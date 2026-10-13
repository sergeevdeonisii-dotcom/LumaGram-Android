package org.telegram.messenger;
public class UserConfig {public static int selectedAccount;public static long[] owners={100,200,300,400};private final int account;private UserConfig(int account){this.account=account;}public static UserConfig getInstance(int account){return new UserConfig(account);}public long getClientUserId(){return owners[account];}}
