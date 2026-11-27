package com.meshnet.app

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated group encryption for the first native transport prototype.
 * The same high-entropy passphrase must be exchanged out-of-band on every participating device.
 * This is not a substitute for per-peer Signal sessions or forward secrecy.
 */
class MeshCrypto(passphrase: CharArray) {
    private val key: SecretKeySpec

    init {
        require(passphrase.size >= 24) { "Use an out-of-band passphrase of at least 24 characters" }
        val spec = PBEKeySpec(passphrase, SALT, 210_000, 256)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            passphrase.fill('\u0000')
        }
        key = SecretKeySpec(bytes, "AES")
        bytes.fill(0)
    }

    fun encrypt(plainText: String, aad: ByteArray): Pair<String, String> {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce, Base64.NO_WRAP) to
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(nonce: String, ciphertext: String, aad: ByteArray): String {
        val nonceBytes = Base64.decode(nonce, Base64.NO_WRAP)
        require(nonceBytes.size == NONCE_BYTES) { "Invalid AES-GCM nonce length" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonceBytes))
        cipher.updateAAD(aad)
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    companion object {
        private val random = SecureRandom()
        private val SALT = "MeshNet-native-group-v1".toByteArray(Charsets.UTF_8)
        private const val NONCE_BYTES = 12
    }
}
