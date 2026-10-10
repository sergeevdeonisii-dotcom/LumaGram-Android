package org.telegram.messenger;
public final class NotificationCenter {
    public static final int lumaLocalGiftsChanged = 1;
    public static int events;
    public static int lastAccount;
    public static long lastOwner;
    private final int account;
    private NotificationCenter(int account) { this.account = account; }
    public static NotificationCenter getInstance(int account) { return new NotificationCenter(account); }
    public void postNotificationName(int event,Object... arguments) {
        if (event != lumaLocalGiftsChanged || arguments.length != 1) throw new AssertionError("unexpected local event");
        events++; lastAccount = account; lastOwner = (Long)arguments[0];
    }
}
