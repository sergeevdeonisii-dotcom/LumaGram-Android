package org.telegram.messenger;
public final class BlackHoleVault {
    public static int locks;
    public static synchronized void lockAll() { locks++; }
    public static boolean contains(int account,long dialog) { return false; }
}
