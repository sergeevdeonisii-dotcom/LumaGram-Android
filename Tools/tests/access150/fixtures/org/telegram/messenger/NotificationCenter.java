package org.telegram.messenger;
public final class NotificationCenter {
    public static final int lunagramSubscriptionChanged=1,updateInterfaces=2;
    public static final int[] changes=new int[4],interfaces=new int[4];
    private final int account;
    private NotificationCenter(int account) { this.account=account; }
    public static NotificationCenter getInstance(int account) { return new NotificationCenter(account); }
    public void postNotificationName(int id,Object...args) { if(id==1) changes[account]++; else interfaces[account]++; }
}
