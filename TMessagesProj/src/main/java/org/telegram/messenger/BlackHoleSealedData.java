package org.telegram.messenger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned, authenticated data; the identity and preference name are bound as AAD. */
public final class BlackHoleSealedData {
    private BlackHoleSealedData() {}
    public static byte[] seal(SecretKey key, String binding, String text) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(2 + iv.length + encrypted.length).put((byte) 1)
                .put((byte) iv.length).put(iv).put(encrypted).array();
    }
    public static String open(SecretKey key, String binding, byte[] data) throws GeneralSecurityException {
        if (data == null || data.length < 30 || data[0] != 1 || data[1] != 12)
            throw new GeneralSecurityException("invalid encrypted data");
        byte[] iv = new byte[12]; System.arraycopy(data, 2, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(data, 14, data.length - 14), StandardCharsets.UTF_8);
    }
}
