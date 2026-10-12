package org.telegram.tgnet;
public final class ConnectionsManager {
    public static boolean test;
    public static ConnectionsManager getInstance(int account) { return new ConnectionsManager(); }
    public boolean isTestBackend() { return test; }
    public int getCurrentTime() { return (int)(System.currentTimeMillis()/1000L); }
}
