package com.tempoverlay.mangohud;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a user-supplied, read-only GitHub token encrypted with an Android Keystore key. */
final class SecureTokenStore {
    private static final String PREFS = "secure_update_credentials";
    private static final String TOKEN_KEY = "github_token_v1";
    private static final String KEY_ALIAS = "temp_hud_update_token_aes";

    private SecureTokenStore() {}

    static void save(Context context, String token) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        buffer.putInt(iv.length).put(iv).put(encrypted);
        String value = Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
        prefs(context).edit().putString(TOKEN_KEY, value).apply();
    }

    static String read(Context context) throws Exception {
        String value = prefs(context).getString(TOKEN_KEY, null);
        if (value == null || value.isEmpty()) return null;
        byte[] all = Base64.decode(value, Base64.NO_WRAP);
        ByteBuffer buffer = ByteBuffer.wrap(all);
        int ivLength = buffer.getInt();
        if (ivLength < 12 || ivLength > 16 || ivLength > buffer.remaining()) throw new IllegalStateException("Invalid encrypted token");
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    }

    static void clear(Context context) {
        prefs(context).edit().remove(TOKEN_KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
