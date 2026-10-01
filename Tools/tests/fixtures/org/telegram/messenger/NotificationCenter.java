package org.telegram.messenger;
public class NotificationCenter { public static final int appUpdateAvailable=1,appUpdateLoading=2;public static NotificationCenter getGlobalInstance(){return new NotificationCenter();}public void postNotificationName(int n){} }
