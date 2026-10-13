package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

// Host seams only. Model, store, catalog and AES/GCM envelope are the real production sources.
class UserConfig {
    static final int MAX_ACCOUNT_COUNT = 4;
    static int selectedAccount;
    static final UserConfig[] values = {new UserConfig(), new UserConfig(), new UserConfig(), new UserConfig()};
    long owner;
    static UserConfig getInstance(int account) { return values[account]; }
    long getClientUserId() { return owner; }
}
class LunagramSubscriptionClient {
    static final long[] epochs = new long[4];
    static long accountEpoch(int account) { return epochs[account]; }
}
class LunagramSubscriptionAccess {
    static boolean paid;
    static boolean canUse(int account, LunagramFeatureCatalog.Feature feature) {
        if (feature != LunagramFeatureCatalog.Feature.LOCAL_WORKSPACE) throw new AssertionError("wrong paid feature");
        return paid;
    }
}
class SharedConfig { static boolean appLocked, isWaitingForPasscodeEnter; }
class DialogObject {
    static boolean isEncryptedDialog(long id) { return id == 4294967296L; }
    static boolean isFolderDialogId(long id) { return id == 2305843009213693953L; }
}
class BlackHoleVault { static boolean locked; static boolean blocks(int account, long dialog) { return locked; } }
class MessagesController {
    static final MessagesController[] values = {new MessagesController(), new MessagesController(), new MessagesController(), new MessagesController()};
    final Map<Long, TLRPC.Chat> chats = new HashMap<>();
    static MessagesController getInstance(int account) { return values[account]; }
    TLRPC.Chat getChat(long id) { return chats.get(id); }
}
class BlackHolePrivateData {
    static boolean available = true, failRead, failWrite;
    static int writes;
    static Runnable beforeRead, beforeWrite;
    static final Map<String, byte[]> data = new HashMap<>();
    static final Map<Long, SecretKey> keys = new HashMap<>();
    static boolean isAvailable() { return available; }
    static String binding(int account, long owner, String key) { return account + ":" + owner + ":" + key; }
    static SecretKey key(long owner) throws Exception {
        if (!keys.containsKey(owner)) { KeyGenerator gen = KeyGenerator.getInstance("AES"); gen.init(128); keys.put(owner, gen.generateKey()); }
        return keys.get(owner);
    }
    static String read(int account, String name, String fallback) throws Exception {
        long owner = UserConfig.getInstance(account).owner;
        if (beforeRead != null) beforeRead.run();
        if (failRead) throw new Exception("fixture read error");
        String binding = binding(account, owner, name);
        byte[] bytes = data.get(binding);
        return bytes == null ? fallback : BlackHoleSealedData.open(key(owner), binding, bytes);
    }
    static void write(int account, long owner, String name, String text) throws Exception {
        if (beforeWrite != null) beforeWrite.run();
        if (failWrite || owner != UserConfig.getInstance(account).owner) throw new Exception("fixture write error");
        String binding = binding(account, owner, name);
        data.put(binding, BlackHoleSealedData.seal(key(owner), binding, text)); writes++;
    }
}
