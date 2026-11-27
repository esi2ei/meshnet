package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun directedFrameRoundTripsAndAuthenticatesDestination() {
        val frame = MeshFrame(
            id = "directed_01",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_01"),
            nonce = "nonce",
            ciphertext = "ciphertext",
            destination = "node_02"
        )
        val decoded = MeshFrame.decode(frame.encode())
        assertEquals("node_02", decoded.destination)
        assertEquals(MeshFrame.PROTOCOL_VERSION, org.json.JSONObject(frame.encode().toString(Charsets.UTF_8)).getInt("v"))
    }

    @Test
    fun rejectsInvalidDestinationNodeId() {
        val frame = MeshFrame(
            id = "directed_02",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_01"),
            nonce = "nonce",
            ciphertext = "ciphertext",
            destination = "invalid node"
        )
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(frame.encode())
        }
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
        val encoded = frame.encode().toString(Charsets.UTF_8)
        val legacyBytes = encoded.replace("\"v\":5", "\"v\":1").toByteArray(Charsets.UTF_8)
        val protocolV2Bytes = encoded.replace("\"v\":5", "\"v\":2").toByteArray(Charsets.UTF_8)
        val protocolV3Bytes = encoded.replace("\"v\":5", "\"v\":3").toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(legacyBytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(protocolV2Bytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(protocolV3Bytes)
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
    fun maximumRouteFrameFitsDefaultMtuChunkBudget() {
        val hops = (0 until MeshFrame.MAX_TTL).map { "n" + it.toString().padStart(21, '0') }
        val frame = MeshFrame(
            id = "550e8400-e29b-41d4-a716-446655440000",
            origin = hops.first(),
            ttl = 1,
            hops = hops,
            nonce = "A".repeat(16),
            ciphertext = "A".repeat(184),
            createdAt = System.currentTimeMillis()
        )

        val encoded = frame.encode()
        assertTrue(encoded.size <= MeshFrame.MAX_FRAME_BYTES)
        assertEquals(hops, MeshFrame.decode(encoded).hops)
    }


    @Test
    fun ephemeralFrameRoundTripsWithoutAckTarget() {
        val frame = MeshFrame(
            id = "voice_packet_01",
            origin = "node_01",
            ttl = MeshFrame.MAX_TTL,
            hops = listOf("node_01"),
            nonce = "nonce",
            ciphertext = "ciphertext",
            kind = MeshFrame.KIND_EPHEMERAL
        )
        val decoded = MeshFrame.decode(frame.encode())
        assertEquals(MeshFrame.KIND_EPHEMERAL, decoded.kind)
        assertEquals(null, decoded.ackFor)
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
