package org.telegram.tgnet;
public class ConnectionsManager {
 public static final java.util.ArrayList<Integer> cancelledRequests = new java.util.ArrayList<>();
 public static final int[] serverTime = new int[4];
 private final int account; private ConnectionsManager(int account) { this.account=account; }
 public static ConnectionsManager getInstance(int account) { return new ConnectionsManager(account); }
 public int getCurrentTime() { return serverTime[account]; }
 public void cancelRequest(int requestId, boolean notifyServer) { cancelledRequests.add(requestId); }
}
