package org.telegram.messenger;
public class UserConfig {
 public static int selectedAccount; public static final long[] ids=new long[4];
 private final int account;private UserConfig(int account){this.account=account;}
 public static UserConfig getInstance(int account){return new UserConfig(account);}
 public long getClientUserId(){return ids[account];}
}
