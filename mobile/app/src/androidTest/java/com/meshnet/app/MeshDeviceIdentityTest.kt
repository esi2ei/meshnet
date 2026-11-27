package com.meshnet.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

}
