package org.telegram.messenger;
public class NotificationCenter {public static final int updateInterfaces=1;public static NotificationCenter getInstance(int account){return new NotificationCenter();}public void postNotificationName(int event,Object... args){}}
