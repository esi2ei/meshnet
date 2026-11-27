package com.meshnet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Stable per-install P-256 signing identity. The Android Keystore keeps the private key
 * non-exportable; the public key is carried in each frame and its fingerprint is the node ID.
 * This authenticates a device identity inside a shared-key group; it is not a ratchet or FS.
 */
internal class MeshDeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    publicKeyBytes: ByteArray
) {
    val publicKey: String = Base64.encodeToString(
        publicKeyBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
    val nodeId: String = nodeIdFor(publicKeyBytes)

    fun prepare(frame: MeshFrame, plaintext: String): MeshFrame =
        frame.copy(deviceKey = publicKey, payloadHash = sha256(plaintext.toByteArray(Charsets.UTF_8)), signature = null)

    fun sign(frame: MeshFrame): MeshFrame {
        require(frame.origin == nodeId && frame.deviceKey == publicKey && frame.payloadHash?.matches(Regex("[a-f0-9]{64}")) == true) {
            "Frame identity does not match this device"
        }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(signingBytes(frame))
        val encoded = Base64.encodeToString(
            signer.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return frame.copy(signature = encoded)
    }

    companion object {
        private const val KEY_ALIAS = "meshnet-device-identity-p256-v1"

        fun loadOrCreate(context: Context): MeshDeviceIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) return MeshDeviceIdentity(
                existing.privateKey, existing.certificate.publicKey.encoded
            )

            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setKeySize(256)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            val pair = generator.generateKeyPair()
            return MeshDeviceIdentity(pair.private, pair.public.encoded)
        }

        fun generateEphemeral(): MeshDeviceIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair: KeyPair = generator.generateKeyPair()
            return MeshDeviceIdentity(pair.private, pair.public.encoded)
        }

        fun verify(frame: MeshFrame, plaintext: String): Boolean = runCatching {
            val encodedKey = frame.deviceKey ?: return false
            val encodedSignature = frame.signature ?: return false
            val expectedHash = frame.payloadHash ?: return false
            val publicBytes = Base64.decode(
                encodedKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            if (nodeIdFor(publicBytes) != frame.origin) return false
            if (sha256(plaintext.toByteArray(Charsets.UTF_8)) != expectedHash) return false
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(signingBytes(frame))
            verifier.verify(Base64.decode(
                encodedSignature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            ))
        }.getOrDefault(false)

        private fun signingBytes(frame: MeshFrame): ByteArray =
            "meshnet-device-v1|${frame.kind}|${frame.id}|${frame.origin}|${frame.destination.orEmpty()}|${frame.createdAt}|${frame.ackFor.orEmpty()}|${frame.deviceKey.orEmpty()}|${frame.payloadHash.orEmpty()}"
                .toByteArray(Charsets.UTF_8)

        private fun nodeIdFor(publicKey: ByteArray): String =
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey).copyOfRange(0, 16),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
