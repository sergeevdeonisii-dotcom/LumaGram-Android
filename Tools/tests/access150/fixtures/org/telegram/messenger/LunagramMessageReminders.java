package org.telegram.messenger;
public final class LunagramMessageReminders {
    public static final int[] changes=new int[UserConfig.MAX_ACCOUNT_COUNT];
    public static void onAccessChanged(int account){changes[account]++;}
}
