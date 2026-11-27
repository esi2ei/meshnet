package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MeshFrameTest {
    @Test
    fun messageFrameRoundTrips() {
        val frame = MeshFrame(
            id = "message_01",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_01"),
            nonce = "nonce",
            ciphertext = "ciphertext"
        )
        val decoded = MeshFrame.decode(frame.encode())
        assertEquals(frame.id, decoded.id)
        assertEquals(frame.origin, decoded.origin)
        assertEquals(MeshFrame.KIND_MESSAGE, decoded.kind)
        assertEquals(frame.hops, decoded.hops)
    }

    @Test
    fun receiptFrameRequiresTargetAndRoundTrips() {
        val frame = MeshFrame(
            id = "receipt_01",
            origin = "node_02",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_02"),
            nonce = "nonce",
            ciphertext = "ciphertext",
            kind = MeshFrame.KIND_ACK,
            ackFor = "message_01"
        )
        val decoded = MeshFrame.decode(frame.encode())
        assertEquals(MeshFrame.KIND_ACK, decoded.kind)
        assertEquals("message_01", decoded.ackFor)
    }

    @Test
    fun rejectsReceiptWithoutTarget() {
        val frame = MeshFrame(
            id = "receipt_01",
            origin = "node_02",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_02"),
            nonce = "nonce",
            ciphertext = "ciphertext",
            kind = MeshFrame.KIND_ACK
        )
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(frame.encode())
        }
    }

    @Test
    fun rejectsLegacyProtocolVersion() {
        val frame = MeshFrame(
            id = "message_legacy",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_01"),
            nonce = "nonce",
            ciphertext = "ciphertext"
        )
        val legacyBytes = frame.encode().toString(Charsets.UTF_8)
            .replace(""" "v":3 """.trim(), """ "v":1 """.trim())
            .toByteArray(Charsets.UTF_8)
        val protocolV2Bytes = frame.encode().toString(Charsets.UTF_8)
            .replace(""" "v":3 """.trim(), """ "v":2 """.trim())
            .toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(legacyBytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(protocolV2Bytes)
        }
    }

    @Test
    fun routeMetadataIsAuthenticatedAndRelaysMustReencrypt() {
        val crypto = MeshCrypto("a sufficiently long group passphrase".toCharArray())
        val original = MeshFrame(
            id = "message_route",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_01"),
            nonce = "",
            ciphertext = ""
        )
        val (nonce, ciphertext) = crypto.encrypt("route-bound payload", MeshFrame.aad(original))
        val authenticated = original.copy(nonce = nonce, ciphertext = ciphertext)
        val modifiedRoute = authenticated.copy(
            ttl = MeshFrame.MAX_TTL - 1,
            hops = listOf("node_01", "node_02")
        )

        assertThrows(Exception::class.java) {
            crypto.decrypt(modifiedRoute.nonce, modifiedRoute.ciphertext, MeshFrame.aad(modifiedRoute))
        }

        val (relayNonce, relayCiphertext) = crypto.encrypt(
            "route-bound payload", MeshFrame.aad(modifiedRoute)
        )
        assertEquals(
            "route-bound payload",
            crypto.decrypt(relayNonce, relayCiphertext, MeshFrame.aad(modifiedRoute))
        )
    }

    @Test
    fun rejectsRouteLoop() {
        val frame = MeshFrame(
            id = "message_02",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL - 1,
            hops = listOf("node_01", "node_01"),
            nonce = "nonce",
            ciphertext = "ciphertext"
        )
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(frame.encode())
        }
    }
}
