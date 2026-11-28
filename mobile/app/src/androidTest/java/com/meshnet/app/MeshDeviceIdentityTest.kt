package com.meshnet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class MeshDeviceIdentityTest {
    @Test fun signsAndVerifiesPayloadAndStableOriginFingerprint() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val frame = MeshFrame(
            origin = identity.nodeId,
            ttl = MeshFrame.MAX_TTL,
            hops = listOf(identity.nodeId),
            nonce = "nonce",
            ciphertext = "ciphertext",
            id = "signed_message_01"
        )
        val signed = identity.sign(identity.prepare(frame, "trusted payload"))
        assertTrue(MeshDeviceIdentity.verify(signed, "trusted payload"))
        assertFalse(MeshDeviceIdentity.verify(signed, "modified payload"))
        assertFalse(MeshDeviceIdentity.verify(signed.copy(origin = "spoofed_node"), "trusted payload"))
    }

    @Test fun rejectsMissingOrMalformedIdentityMaterial() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val frame = MeshFrame(
            origin = identity.nodeId,
            ttl = MeshFrame.MAX_TTL,
            hops = listOf(identity.nodeId),
            nonce = "nonce",
            ciphertext = "ciphertext",
            id = "signed_message_02"
        )
        val signed = identity.sign(identity.prepare(frame, "payload"))
        assertFalse(MeshDeviceIdentity.verify(signed.copy(signature = null), "payload"))
        assertFalse(MeshDeviceIdentity.verify(signed.copy(deviceKey = "invalid"), "payload"))
    }

    @Test fun rejectsAValidSignatureFromANonP256Curve() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp384r1"))
        val pair = generator.generateKeyPair()
        val publicBytes = pair.public.encoded
        val nodeId = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicBytes).copyOfRange(0, 16),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val plaintext = "payload signed with an unsupported curve"
        val payloadHash = MessageDigest.getInstance("SHA-256").digest(plaintext.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val unsigned = MeshFrame(
            origin = nodeId,
            ttl = MeshFrame.MAX_TTL,
            hops = listOf(nodeId),
            nonce = "nonce",
            ciphertext = "ciphertext",
            id = "p384_identity_01",
            deviceKey = Base64.encodeToString(
                publicBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            ),
            payloadHash = payloadHash
        )
        val signingBytes = "meshnet-device-v1|${unsigned.kind}|${unsigned.id}|${unsigned.origin}|${unsigned.destination.orEmpty()}|${unsigned.createdAt}|${unsigned.ackFor.orEmpty()}|${unsigned.deviceKey.orEmpty()}|${unsigned.payloadHash.orEmpty()}"
            .toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(signingBytes)
        val signed = unsigned.copy(signature = Base64.encodeToString(
            signer.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ))

        assertFalse(MeshDeviceIdentity.verify(signed, plaintext))
    }

    @Test fun rejectsNonCanonicalBase64IdentityMaterial() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val frame = MeshFrame(
            origin = identity.nodeId,
            ttl = MeshFrame.MAX_TTL,
            hops = listOf(identity.nodeId),
            nonce = "nonce",
            ciphertext = "ciphertext",
            id = "canonical_identity_01"
        )
        val signed = identity.sign(identity.prepare(frame, "payload"))
        assertTrue(MeshDeviceIdentity.verify(signed, "payload"))
        assertFalse(MeshDeviceIdentity.verify(signed.copy(deviceKey = signed.deviceKey + "="), "payload"))
    }

    @Test fun signedFrameFitsTheBoundedTransportEnvelope() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val hops = listOf(identity.nodeId) + (1 until MeshFrame.MAX_TTL).map { "node_" + it.toString().padStart(18, '0') }
        val frame = MeshFrame(
            origin = identity.nodeId,
            ttl = 1,
            hops = hops,
            nonce = "A".repeat(16),
            ciphertext = "A".repeat(184),
            id = "signed_mtu_frame_01",
            destination = "destination_node_000000000"
        )
        val plaintext = "A".repeat(120)
        val signed = identity.sign(identity.prepare(frame, plaintext))
        assertTrue("Signed maximum-route frame size was ${signed.encode().size} bytes", signed.encode().size <= MeshFrame.MAX_FRAME_BYTES)
        assertTrue(MeshDeviceIdentity.verify(MeshFrame.decode(signed.encode()), plaintext))
    }


    @Test fun loadOrCreateRefusesToReplaceAnInitializedIdentityAfterKeystoreKeyLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearStoredIdentityForTest(context)
        try {
            val original = MeshDeviceIdentity.loadOrCreate(context)
            val preferences = context.getSharedPreferences(
                "meshnet-device-identity-state", Context.MODE_PRIVATE
            )
            assertTrue(preferences.getBoolean("identity_initialized", false))
            assertEquals(original.nodeId, preferences.getString("node_id", null))

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("meshnet-device-identity-p256-v1")
            assertFalse(
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .containsAlias("meshnet-device-identity-p256-v1")
            )

            val failure = runCatching { MeshDeviceIdentity.loadOrCreate(context) }.exceptionOrNull()
            assertTrue("Missing initialized identity must fail closed", failure is IllegalStateException)
            assertFalse(
                "A replacement key must not be generated after identity loss",
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .containsAlias("meshnet-device-identity-p256-v1")
            )
        } finally {
            clearStoredIdentityForTest(context)
        }
    }

    @Test fun legacyMembershipRecordsPreventSilentIdentityReplacementWithoutNewMarker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearStoredIdentityForTest(context)
        try {
            val legacyIdentity = MeshDeviceIdentity.generateEphemeral()
            val membershipStore = MeshGroupMembershipStore(context)
            val event = MeshMembershipEvent.create(
                identity = legacyIdentity,
                groupId = "a".repeat(64),
                counter = 1,
                type = MeshMembershipEvent.JOIN,
                eventId = "legacy-membership-001"
            )
            assertTrue(membershipStore.append(event))
            membershipStore.close()

            val preferences = context.getSharedPreferences(
                "meshnet-device-identity-state", Context.MODE_PRIVATE
            )
            assertFalse(preferences.getBoolean("identity_initialized", false))
            assertTrue(context.getDatabasePath("meshnet-group-membership.db").exists())

            val failure = runCatching { MeshDeviceIdentity.loadOrCreate(context) }.exceptionOrNull()
            assertTrue("Legacy signed membership must prevent silent identity replacement", failure is IllegalStateException)
            assertFalse(
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .containsAlias("meshnet-device-identity-p256-v1")
            )
        } finally {
            clearStoredIdentityForTest(context)
        }
    }

    @Test fun existingLegacyKeystoreIdentityIsReusedAndItsFingerprintIsRecorded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearStoredIdentityForTest(context)
        try {
            val original = MeshDeviceIdentity.loadOrCreate(context)
            context.getSharedPreferences(
                "meshnet-device-identity-state", Context.MODE_PRIVATE
            ).edit().clear().commit()

            val restored = MeshDeviceIdentity.loadOrCreate(context)
            assertEquals(original.nodeId, restored.nodeId)
            val preferences = context.getSharedPreferences(
                "meshnet-device-identity-state", Context.MODE_PRIVATE
            )
            assertTrue(preferences.getBoolean("identity_initialized", false))
            assertEquals(original.nodeId, preferences.getString("node_id", null))
        } finally {
            clearStoredIdentityForTest(context)
        }
    }

    private fun clearStoredIdentityForTest(context: Context) {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias("meshnet-device-identity-p256-v1")) {
                keyStore.deleteEntry("meshnet-device-identity-p256-v1")
            }
        }
        context.getSharedPreferences("meshnet-device-identity-state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteDatabase("meshnet-group-membership.db")
        context.deleteDatabase("meshnet-group-delivery.db")
    }


}
