package com.tempoverlay.mangohud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the user-entered, read-only GitHub token encrypted with an Android Keystore key. */
internal object SecureTokenStore {
    private const val PREFS = "secure_update_credentials"
    private const val TOKEN_KEY = "github_token_v1"
    private const val KEY_ALIAS = "temp_hud_update_token_aes"

    fun save(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.allocate(4 + iv.size + encrypted.size)
            .putInt(iv.size)
            .put(iv)
            .put(encrypted)
        prefs(context).edit().putString(TOKEN_KEY, Base64.encodeToString(buffer.array(), Base64.NO_WRAP)).apply()
    }

    fun read(context: Context): String? {
        val value = prefs(context).getString(TOKEN_KEY, null)?.takeIf { it.isNotEmpty() } ?: return null
        val buffer = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val ivLength = buffer.int
        if (ivLength < 12 || ivLength > 16 || ivLength > buffer.remaining()) {
            throw IllegalStateException("Invalid encrypted token")
        }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(TOKEN_KEY).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
