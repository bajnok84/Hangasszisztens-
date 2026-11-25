package com.sajat.hangasszisztens

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object ApiKeyStore {
    private const val KEY_ALIAS = "jarvis_api_key"
    private const val SECURE_PREFERENCES = "secure_jarvis_prefs"
    private const val ENCRYPTED_KEY = "encrypted_api_key"
    private const val LEGACY_PREFERENCES = "jarvis_prefs"
    private const val LEGACY_KEY = "gemini_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    @Synchronized
    fun read(context: Context): String? {
        val securePreferences = context.getSharedPreferences(SECURE_PREFERENCES, Context.MODE_PRIVATE)
        val encryptedKey = securePreferences.getString(ENCRYPTED_KEY, null)
        val legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        if (encryptedKey != null) {
            val decryptedKey = decrypt(encryptedKey)
            if (legacyPreferences.contains(LEGACY_KEY) &&
                !legacyPreferences.edit().remove(LEGACY_KEY).commit()
            ) {
                throw IOException("Could not remove the legacy API key")
            }
            return decryptedKey
        }

        val legacyKey = legacyPreferences.getString(LEGACY_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null
        write(context, legacyKey)
        if (!legacyPreferences.edit().remove(LEGACY_KEY).commit()) {
            throw IOException("Could not remove the legacy API key")
        }
        return legacyKey
    }

    @Synchronized
    fun write(context: Context, apiKey: String) {
        val encryptedKey = encrypt(apiKey)
        val saved = context.getSharedPreferences(SECURE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENCRYPTED_KEY, encryptedKey)
            .commit()
        if (!saved) throw IOException("Could not save the encrypted API key")
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            ByteBuffer.allocate(cipher.iv.size + encrypted.size)
                .put(cipher.iv)
                .put(encrypted)
                .array(),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > IV_LENGTH) { "Stored API key is invalid" }
        val buffer = ByteBuffer.wrap(combined)
        val iv = ByteArray(IV_LENGTH).also { buffer.get(it) }
        val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }
}
