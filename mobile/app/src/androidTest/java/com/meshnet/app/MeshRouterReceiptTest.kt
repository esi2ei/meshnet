package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterReceiptTest {
    @Test
    fun applicationPayloadHandlerReceivesRawAuthenticatedPlaintext() {
        val passphrase = "a sufficiently long group passphrase"
        val frames = mutableListOf<ByteArray>()
        val handled = mutableListOf<String>()
        val displayed = mutableListOf<String>()
        val senderSeen = mutableSetOf<String>()
        val receiverSeen = mutableSetOf<String>()
        val sender = MeshRouter(
            nodeId = "sender_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { frames.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { 0 }
        )
        val receiver = MeshRouter(
            nodeId = "receiver1",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { true },
            onMessage = { displayed.add(it) },
            hasReceived = { it in receiverSeen },
            markReceived = { id, _ -> !receiverSeen.add(id) },
            acknowledge = { 0 },
            onPayload = { handled.add(it); true }
        )
        sender.sendMessage("~F1M|transfer_01|10|1|0123456789abcdef|dGVzdA")
        val frame = frames.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE }
        receiver.receive(frame.encode())

        assertEquals(listOf("~F1M|transfer_01|10|1|0123456789abcdef|dGVzdA"), handled)
        assertTrue(displayed.isEmpty())
    }

    @Test
    fun relayReencryptsPayloadForUpdatedAuthenticatedRoute() {
        val passphrase = "a sufficiently long group passphrase"
        val senderQueue = mutableListOf<ByteArray>()
        val relayQueue = mutableListOf<ByteArray>()
        val destinationEvents = mutableListOf<String>()
        val relayRoutes = mutableListOf<List<String>>()
        val destinationRoutes = mutableListOf<List<String>>()
        val senderSeen = mutableSetOf<String>()
        val relaySeen = mutableSetOf<String>()
        val destinationSeen = mutableSetOf<String>()

        val sender = MeshRouter(
            nodeId = "sender_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { senderQueue.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { 0 }
        )
        val relay = MeshRouter(
            nodeId = "relay_001",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { relayQueue.add(it); true },
            onMessage = {},
            hasReceived = { it in relaySeen },
            markReceived = { id, _ -> !relaySeen.add(id) },
            acknowledge = { 0 },
            onRouteObserved = { relayRoutes.add(it) }
        )
        val destination = MeshRouter(
            nodeId = "dest_0001",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { true },
            onMessage = { destinationEvents.add(it) },
            hasReceived = { it in destinationSeen },
            markReceived = { id, _ -> !destinationSeen.add(id) },
            acknowledge = { 0 },
            onRouteObserved = { destinationRoutes.add(it) }
        )

        sender.sendMessage("route-bound hello")
        val original = senderQueue.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_MESSAGE }
        relay.receive(original.encode(), "ble-peer-address")

        val relayed = relayQueue.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_MESSAGE }
        assertEquals(original.id, relayed.id)
        assertEquals(MeshFrame.MAX_TTL - 1, relayed.ttl)
        assertEquals(listOf("sender_01", "relay_001"), relayed.hops)
        assertEquals(
            "route-bound hello",
            MeshCrypto(passphrase.toCharArray()).decrypt(
                relayed.nonce, relayed.ciphertext, MeshFrame.aad(relayed)
            )
        )

        destination.receive(relayed.encode(), "wifi-peer-address")
        assertTrue(destinationEvents.any { it.contains("route-bound hello") })
        assertEquals(listOf(listOf("sender_01", "relay_001")), relayRoutes)
        assertEquals(listOf(listOf("sender_01", "relay_001", "dest_0001")), destinationRoutes)
    }

    @Test
    fun unauthenticatedFrameCannotCreateRouteObservation() {
        val outgoing = mutableListOf<ByteArray>()
        val senderSeen = mutableSetOf<String>()
        val receiverSeen = mutableSetOf<String>()
        val observedRoutes = mutableListOf<List<String>>()
        val sender = MeshRouter(
            nodeId = "sender_01",
            crypto = MeshCrypto("sender group passphrase is long enough".toCharArray()),
            send = { outgoing.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { 0 }
        )
        val receiver = MeshRouter(
            nodeId = "receiver1",
            crypto = MeshCrypto("different receiver group passphrase".toCharArray()),
            send = { true },
            onMessage = {},
            hasReceived = { it in receiverSeen },
            markReceived = { id, _ -> !receiverSeen.add(id) },
            acknowledge = { 0 },
            onRouteObserved = { observedRoutes.add(it) }
        )

        assertTrue(sender.sendMessage("not for this group"))
        val frame = outgoing.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE }
        receiver.receive(frame.encode(), "untrusted-peer")

        assertTrue(observedRoutes.isEmpty())
    }

    @Test
    fun relayRebroadcastsOnlyWhenANewerPathIsShorter() {
        val passphrase = "a sufficiently long group passphrase"
        val senderFrames = mutableListOf<ByteArray>()
        val relayFrames = mutableListOf<ByteArray>()
        val senderSeen = mutableSetOf<String>()
        val relaySeen = mutableSetOf<String>()
        val sender = MeshRouter(
            nodeId = "sender_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { senderFrames.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { 0 }
        )
        val relay = MeshRouter(
            nodeId = "relay_001",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { relayFrames.add(it); true },
            onMessage = {},
            hasReceived = { it in relaySeen },
            markReceived = { id, _ -> !relaySeen.add(id) },
            acknowledge = { 0 }
        )
        sender.sendMessage("shortest route")
        val original = senderFrames.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_MESSAGE }
        val plaintext = MeshCrypto(passphrase.toCharArray()).decrypt(
            original.nonce, original.ciphertext, MeshFrame.aad(original)
        )
        val longer = original.copy(
            ttl = original.ttl - 1,
            hops = original.hops + "other_hop"
        )
        val encryptedLonger = MeshCrypto(passphrase.toCharArray()).encrypt(
            plaintext, MeshFrame.aad(longer)
        )
        relay.receive(longer.copy(nonce = encryptedLonger.first, ciphertext = encryptedLonger.second).encode())
        assertEquals(1, relayFrames.count { MeshFrame.decode(it).kind == MeshFrame.KIND_MESSAGE })

        relay.receive(original.encode())
        assertEquals(2, relayFrames.count { MeshFrame.decode(it).kind == MeshFrame.KIND_MESSAGE })

        relay.receive(longer.copy(nonce = encryptedLonger.first, ciphertext = encryptedLonger.second).encode())
        assertEquals(2, relayFrames.count { MeshFrame.decode(it).kind == MeshFrame.KIND_MESSAGE })
    }

    @Test
    fun receiverReturnsAuthenticatedGroupReceiptAndSenderRetiresMessage() {
        val senderQueue = mutableListOf<ByteArray>()
        val receiverQueue = mutableListOf<ByteArray>()
        val senderSeen = mutableSetOf<String>()
        val receiverSeen = mutableSetOf<String>()
        val acknowledged = mutableListOf<String>()

        val sender = MeshRouter(
            nodeId = "sender_01",
            crypto = MeshCrypto("a sufficiently long group passphrase".toCharArray()),
            send = { senderQueue.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { id -> acknowledged.add(id); 1 }
        )
        val receiver = MeshRouter(
            nodeId = "receiver_01",
            crypto = MeshCrypto("a sufficiently long group passphrase".toCharArray()),
            send = { receiverQueue.add(it); true },
            onMessage = {},
            hasReceived = { it in receiverSeen },
            markReceived = { id, _ -> !receiverSeen.add(id) },
            acknowledge = { 0 }
        )

        sender.sendMessage("hello over the mesh")
        val message = senderQueue.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_MESSAGE }

        receiver.receive(message.encode())
        val receipt = receiverQueue.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_ACK }

        sender.receive(receipt.encode())

        assertEquals(message.id, receipt.ackFor)
        assertTrue(message.id in acknowledged)
    }
    @Test
    fun everyMeshNodeCanOriginateAndReceiveMessagesInBothDirections() {
        val passphrase = "a sufficiently long group passphrase"
        val aToB = mutableListOf<ByteArray>()
        val bToA = mutableListOf<ByteArray>()
        val eventsA = mutableListOf<String>()
        val eventsB = mutableListOf<String>()
        val seenA = mutableSetOf<String>()
        val seenB = mutableSetOf<String>()
        val nodeA = MeshRouter(
            nodeId = "node_A001",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { aToB.add(it); true },
            onMessage = { eventsA.add(it) },
            hasReceived = { it in seenA },
            markReceived = { id, _ -> !seenA.add(id) },
            acknowledge = { 0 }
        )
        val nodeB = MeshRouter(
            nodeId = "node_B001",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { bToA.add(it); true },
            onMessage = { eventsB.add(it) },
            hasReceived = { it in seenB },
            markReceived = { id, _ -> !seenB.add(id) },
            acknowledge = { 0 }
        )

        assertTrue(nodeA.sendMessage("from A to B"))
        val aMessage = aToB.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE }
        nodeB.receive(aMessage.encode())
        assertTrue(eventsB.any { it.contains("from A to B") })

        assertTrue(nodeB.sendMessage("from B to A"))
        val bMessage = bToA.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE }
        nodeA.receive(bMessage.encode())
        assertTrue(eventsA.any { it.contains("from B to A") })
    }

}
