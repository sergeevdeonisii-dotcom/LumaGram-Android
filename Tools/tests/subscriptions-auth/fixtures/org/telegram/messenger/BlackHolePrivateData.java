package org.telegram.messenger;
import java.util.HashMap;
import java.util.Map;
/** In-memory storage adapter only; production BlackHolePrivateData uses Android Keystore AES-GCM. */
public final class BlackHolePrivateData {
    public static boolean available = true, fail;
    public static String ioReadFailureRole;
    public static int writes, removes;
    public static final Map<String,String> stored = new HashMap<>();
    private static String role(int account,String name) { return UserConfig.getInstance(account).getClientUserId()+":"+name; }
    public static boolean isAvailable() { return available; }
    public static String read(int account,String name,String fallback) throws Exception {
        if (name.equals(ioReadFailureRole)) throw new java.io.IOException("Fixture secure-storage read fault");
        if (fail) throw new Exception("Fixture storage fault");
        return stored.getOrDefault(role(account,name),fallback);
    }
    public static void write(int account,long owner,String name,String payload) throws Exception {
        if (fail || !available || owner != UserConfig.getInstance(account).getClientUserId()) throw new Exception("Fixture storage fault");
        writes++; stored.put(role(account,name),payload);
    }
    public static void remove(int account,long owner,String name) throws Exception {
        if (owner != UserConfig.getInstance(account).getClientUserId()) throw new Exception("Fixture changed owner");
        removes++; stored.remove(role(account,name));
    }
    /** Independent test identity reset, NOT a process restart (which must preserve stored snapshots). */
    public static void reset() {
        for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++) {
            UserConfig.ids[account]=0;
            LunagramSubscriptionClient.onAccountLogout(account);
        }
        available = true; fail = false; ioReadFailureRole = null; writes = removes = 0; stored.clear(); UserConfig.selectedAccount = 0;
        UserConfig.ids[0] = 101; UserConfig.ids[1] = 202; org.telegram.tgnet.ConnectionsManager.test = false;
    }
}
