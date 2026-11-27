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
}
