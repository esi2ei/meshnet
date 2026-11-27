package com.meshnet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only an encrypted MWA auth token and public address; the AES key never leaves Android Keystore. */
class MeshWalletAuthorizationStore(context: Context) {
    data class Authorization(val authToken: String, val address: String)

    private val preferences = context.applicationContext
        .getSharedPreferences("meshnet-wallet-auth", Context.MODE_PRIVATE)

    @Synchronized
    fun save(authToken: String, address: String) {
        require(authToken.isNotBlank() && address.isNotBlank()) { "Wallet authorization is incomplete" }
        val payload = JSONObject()
            .put("authToken", authToken)
            .put("address", address)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(payload)
        check(preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "Unable to persist encrypted wallet authorization" }
    }

    @Synchronized
    fun load(): Authorization? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: throw IllegalStateException("Wallet authorization IV is missing")
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Wallet authorization key is missing; reconnect the wallet")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val payload = JSONObject(String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8))
        val authToken = payload.optString("authToken")
        val address = payload.optString("address")
        if (authToken.isBlank() || address.isBlank()) {
            throw IllegalStateException("Stored wallet authorization is malformed")
        }
        return Authorization(authToken, address)
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear wallet authorization preferences" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
        private const val KEY_ALIAS = "meshnet-wallet-auth-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
        private val AAD = "meshnet-wallet-auth-v1".toByteArray(Charsets.UTF_8)
    }
}
