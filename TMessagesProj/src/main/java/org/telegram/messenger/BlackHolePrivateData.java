package org.telegram.messenger;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Never falls back to plaintext if Android Keystore is unavailable. */
public final class BlackHolePrivateData {
    private BlackHolePrivateData() {}
    private static synchronized SecretKey key(long owner) throws Exception {
        if (owner <= 0 || Build.VERSION.SDK_INT < 23) throw new IllegalStateException("Keystore unavailable");
        String alias = "bhg_private_data_" + owner;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    public static String read(int account, String name, String fallback) throws Exception {
        long owner = UserConfig.getInstance(account).getClientUserId();
        String value = LumaAccountData.preferences(account).getString(name, null);
        if (value == null) return fallback;
        return BlackHoleSealedData.open(key(owner), owner + ":" + name, Base64.decode(value, Base64.NO_WRAP));
    }
    public static void write(int account, String name, String text) throws Exception {
        long owner = UserConfig.getInstance(account).getClientUserId();
        android.content.SharedPreferences target = LumaAccountData.preferences(account);
        if (UserConfig.getInstance(account).getClientUserId() != owner) throw new IllegalStateException("account changed");
        byte[] value = BlackHoleSealedData.seal(key(owner), owner + ":" + name, text);
        if (UserConfig.getInstance(account).getClientUserId() != owner) throw new IllegalStateException("account changed");
        target.edit().putString(name, Base64.encodeToString(value, Base64.NO_WRAP)).apply();
    }
    public static void remove(int account, String name) { LumaAccountData.preferences(account).edit().remove(name).apply(); }
    public static boolean isAvailable() { return Build.VERSION.SDK_INT >= 23; }
}
