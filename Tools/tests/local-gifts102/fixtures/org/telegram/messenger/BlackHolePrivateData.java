package org.telegram.messenger;
import android.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** JVM key adapter ONLY. Real SealedData AES-GCM; does not test Android Keystore availability. */
public final class BlackHolePrivateData {
    private static final Map<Long,SecretKey> keys = new HashMap<>();
    public static boolean fail;
    public static int reads;
    public static Runnable beforeWrite;
    private static SecretKey key(long owner) throws Exception {
        if (fail) throw new IllegalStateException("fixture Keystore unavailable");
        if (!keys.containsKey(owner)) { KeyGenerator g = KeyGenerator.getInstance("AES"); g.init(128); keys.put(owner,g.generateKey()); }
        return keys.get(owner);
    }
    public static String read(int account,String name,String fallback) throws Exception {
        reads++;
        long owner = UserConfig.getInstance(account).getClientUserId();
        String stored = LumaAccountData.preferences(account).getString(name,null);
        if (stored == null) return fallback;
        return BlackHoleSealedData.open(key(owner), owner+":"+name, Base64.decode(stored,Base64.NO_WRAP));
    }
    public static void write(int account,long owner,String name,String text) throws Exception {
        if (beforeWrite != null) { Runnable hook = beforeWrite; beforeWrite = null; hook.run(); }
        if (owner <= 0 || owner != UserConfig.getInstance(account).getClientUserId()) throw new IllegalStateException("account changed");
        byte[] encrypted = BlackHoleSealedData.seal(key(owner),owner+":"+name,text);
        LumaAccountData.preferences(account).edit().putString(name,Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
    }
}
