package com.meshnet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
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
        val encoded = encode(signer.sign())
        return frame.copy(signature = encoded)
    }

    /** Signs a domain-separated, canonical application record without exporting the private key. */
    fun signDetached(domain: String, payload: ByteArray): String {
        require(domain.matches(Regex("[a-z0-9.-]{1,80}"))) { "Invalid signature domain" }
        require(payload.size in 1..MAX_DETACHED_PAYLOAD_BYTES) { "Invalid signed payload size" }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(detachedSigningBytes(domain, payload))
        return encode(signer.sign())
    }

    companion object {
        private const val KEY_ALIAS = "meshnet-device-identity-p256-v1"
        private const val MAX_DETACHED_PAYLOAD_BYTES = 16 * 1024

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

        fun verify(frame: MeshFrame, plaintext: String): Boolean {
            return runCatching {
                val encodedKey = frame.deviceKey ?: return@runCatching false
                val encodedSignature = frame.signature ?: return@runCatching false
                val expectedHash = frame.payloadHash ?: return@runCatching false
                val publicBytes = decodeCanonicalBase64(encodedKey) ?: return@runCatching false
                val signatureBytes = decodeCanonicalBase64(encodedSignature) ?: return@runCatching false
                if (nodeIdFor(publicBytes) != frame.origin) return@runCatching false
                if (sha256(plaintext.toByteArray(Charsets.UTF_8)) != expectedHash) return@runCatching false
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(publicBytes)) as? ECPublicKey
                    ?: return@runCatching false
                if (!isP256(publicKey)) return@runCatching false
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(signingBytes(frame))
                verifier.verify(signatureBytes)
            }.getOrDefault(false)
        }

        /** Checks the node-ID/public-key binding without requiring a signed payload. */
        internal fun verifyPublicKeyBinding(publicKey: String, expectedNodeId: String): Boolean = runCatching {
            val publicBytes = decodeCanonicalBase64(publicKey) ?: return@runCatching false
            if (nodeIdFor(publicBytes) != expectedNodeId) return@runCatching false
            val decoded = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicBytes)) as? ECPublicKey
                ?: return@runCatching false
            isP256(decoded)
        }.getOrDefault(false)

        /** Verifies detached application records against the same P-256 device identity. */
        internal fun verifyDetached(
            publicKey: String,
            expectedNodeId: String,
            domain: String,
            payload: ByteArray,
            signature: String
        ): Boolean = runCatching {
            if (!domain.matches(Regex("[a-z0-9.-]{1,80}"))) return@runCatching false
            if (payload.size !in 1..MAX_DETACHED_PAYLOAD_BYTES) return@runCatching false
            val publicBytes = decodeCanonicalBase64(publicKey) ?: return@runCatching false
            val signatureBytes = decodeCanonicalBase64(signature) ?: return@runCatching false
            if (nodeIdFor(publicBytes) != expectedNodeId) return@runCatching false
            val decoded = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicBytes)) as? ECPublicKey
                ?: return@runCatching false
            if (!isP256(decoded)) return@runCatching false
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(decoded)
                update(detachedSigningBytes(domain, payload))
                verify(signatureBytes)
            }
        }.getOrDefault(false)

        private fun detachedSigningBytes(domain: String, payload: ByteArray): ByteArray =
            ("meshnet-detached-v1|$domain|".toByteArray(Charsets.UTF_8) + payload)

        private fun decodeCanonicalBase64(value: String): ByteArray? {
            val decoded = runCatching {
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }.getOrNull() ?: return null
            return decoded.takeIf { encode(it) == value }
        }

        private fun encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun isP256(publicKey: ECPublicKey): Boolean = runCatching {
            val expected = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val actual = publicKey.params
            val actualField = actual.curve.field as? ECFieldFp ?: return@runCatching false
            val expectedField = expected.curve.field as? ECFieldFp ?: return@runCatching false
            actual.curve.field.fieldSize == 256 &&
                actualField.p == expectedField.p &&
                actual.curve.a == expected.curve.a &&
                actual.curve.b == expected.curve.b &&
                actual.generator.affineX == expected.generator.affineX &&
                actual.generator.affineY == expected.generator.affineY &&
                actual.order == expected.order &&
                actual.cofactor == expected.cofactor
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
