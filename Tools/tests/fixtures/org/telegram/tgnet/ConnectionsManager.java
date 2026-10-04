package org.telegram.tgnet;
public class ConnectionsManager {
 public static final int[] serverTime = new int[4];
 private final int account; private ConnectionsManager(int account) { this.account=account; }
 public static ConnectionsManager getInstance(int account) { return new ConnectionsManager(account); }
 public int getCurrentTime() { return serverTime[account]; }
}
