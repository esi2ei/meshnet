package com.meshnet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the group passphrase at rest with a non-exportable Android Keystore key. */
class MeshSecretStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("meshnet-secrets", Context.MODE_PRIVATE)

    @Synchronized
    fun save(passphrase: String) {
        require(passphrase.length >= 24) { "Use an out-of-band passphrase of at least 24 characters" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "Unable to persist encrypted group credentials" }
    }

    @Synchronized
    fun load(): String? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(KEY_IV, null)
            ?: throw IllegalStateException("Saved group credential IV is missing; enter the passphrase again")
        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES) { "Saved group credential IV is invalid; enter the passphrase again" }

        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Saved group credential key is missing; enter the passphrase again")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear saved group credentials" }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) {
            store.deleteEntry(KEY_ALIAS)
            check(!store.containsAlias(KEY_ALIAS)) { "Unable to delete the saved group credential key" }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "meshnet-group-passphrase-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
