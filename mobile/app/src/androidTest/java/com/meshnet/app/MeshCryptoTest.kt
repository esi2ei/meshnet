package com.meshnet.app

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class MeshCryptoTest {
    @Test
    fun aesGcmRoundTripAuthenticatesCiphertextAndAdditionalData() {
        val crypto = MeshCrypto("a-high-entropy-meshnet-test-passphrase".toCharArray())
        val aad = "meshnet-test-frame-metadata".toByteArray(Charsets.UTF_8)
        val (nonce, ciphertext) = crypto.encrypt("hello mesh", aad)

        assertEquals("hello mesh", crypto.decrypt(nonce, ciphertext, aad))

        expectFailure("AES-GCM must reject modified additional authenticated data") {
            crypto.decrypt(nonce, ciphertext, "different-metadata".toByteArray(Charsets.UTF_8))
        }

        val modifiedCiphertext = Base64.decode(ciphertext, Base64.NO_WRAP).also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }
        val modifiedEncoded = Base64.encodeToString(modifiedCiphertext, Base64.NO_WRAP)
        expectFailure("AES-GCM must reject modified ciphertext or tag") {
            crypto.decrypt(nonce, modifiedEncoded, aad)
        }
    }

    @Test
    fun eachEncryptionUsesAFreshNonce() {
        val crypto = MeshCrypto("a-high-entropy-meshnet-test-passphrase".toCharArray())
        val aad = byteArrayOf(1, 2, 3)
        val first = crypto.encrypt("same message", aad)
        val second = crypto.encrypt("same message", aad)

        assertFalse("AES-GCM nonce was reused", first.first == second.first)
        assertFalse("Fresh nonces should produce different ciphertext", first.second == second.second)
    }

    @Test
    fun rejectsPassphrasesShorterThanTwentyFourCharacters() {
        expectFailure("Short group passphrases must be rejected") {
            MeshCrypto("too-short".toCharArray())
        }
    }

    private fun expectFailure(message: String, block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail(message)
    }
}
