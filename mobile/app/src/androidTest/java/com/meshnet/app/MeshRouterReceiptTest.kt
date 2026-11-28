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
        val senderIdentity = MeshDeviceIdentity.generateEphemeral()
        val relayIdentity = MeshDeviceIdentity.generateEphemeral()
        val destinationIdentity = MeshDeviceIdentity.generateEphemeral()
        val senderQueue = mutableListOf<ByteArray>()
        val relayQueue = mutableListOf<ByteArray>()
        val destinationEvents = mutableListOf<String>()
        val relayRoutes = mutableListOf<List<String>>()
        val destinationRoutes = mutableListOf<List<String>>()
        val learnedRoutes = MeshRouteRegistry()
        val senderSeen = mutableSetOf<String>()
        val relaySeen = mutableSetOf<String>()
        val destinationSeen = mutableSetOf<String>()

        val sender = MeshRouter(
            nodeId = senderIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { senderQueue.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { 0 },
            identity = senderIdentity
        )
        val relay = MeshRouter(
            nodeId = relayIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { relayQueue.add(it); true },
            onMessage = {},
            hasReceived = { it in relaySeen },
            markReceived = { id, _ -> !relaySeen.add(id) },
            acknowledge = { 0 },
            onRouteObserved = { path, peer ->
                relayRoutes.add(path)
                learnedRoutes.observe(path, 100L, peer)
            },
            identity = relayIdentity
        )
        val destination = MeshRouter(
            nodeId = destinationIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { true },
            onMessage = { destinationEvents.add(it) },
            hasReceived = { it in destinationSeen },
            markReceived = { id, _ -> !destinationSeen.add(id) },
            acknowledge = { 0 },
            onRouteObserved = { path, _ -> destinationRoutes.add(path) },
            identity = destinationIdentity
        )

        sender.sendMessage("route-bound hello")
        val original = senderQueue.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_MESSAGE }
        // A group member can re-encrypt a frame with fabricated route metadata.
        // The relay must learn only the verified origin through its actual ingress peer.
        val forgedPath = original.copy(ttl = MeshFrame.MAX_TTL - 2, hops = listOf(senderIdentity.nodeId, relayIdentity.nodeId, "victim_01"))
        val forgedCiphertext = MeshCrypto(passphrase.toCharArray()).encrypt(
            "route-bound hello", MeshFrame.aad(forgedPath)
        )
        relay.receive(
            forgedPath.copy(nonce = forgedCiphertext.first, ciphertext = forgedCiphertext.second).encode(),
            "ble-peer-address"
        )
        assertTrue(learnedRoutes.nextHopsFor("victim_01", 100L).isEmpty())
        assertEquals(listOf("ble-peer-address"), learnedRoutes.nextHopsFor(senderIdentity.nodeId, 100L))

        val relayed = relayQueue.map { MeshFrame.decode(it) }
            .first { it.kind == MeshFrame.KIND_MESSAGE }
        assertEquals(original.id, relayed.id)
        assertEquals(MeshFrame.MAX_TTL - 2, relayed.ttl)
        assertEquals(listOf(senderIdentity.nodeId, "victim_01", relayIdentity.nodeId), relayed.hops)
        assertEquals(
            "route-bound hello",
            MeshCrypto(passphrase.toCharArray()).decrypt(
                relayed.nonce, relayed.ciphertext, MeshFrame.aad(relayed)
            )
        )

        destination.receive(relayed.encode(), "wifi-peer-address")
        assertTrue(destinationEvents.any { it.contains("route-bound hello") })
        assertEquals(listOf(listOf(senderIdentity.nodeId, relayIdentity.nodeId)), relayRoutes)
        assertEquals(listOf(listOf(senderIdentity.nodeId, destinationIdentity.nodeId)), destinationRoutes)
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
            onRouteObserved = { path, _ -> observedRoutes.add(path) }
        )

        assertTrue(sender.sendMessage("not for this group"))
        val frame = outgoing.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE }
        receiver.receive(frame.encode(), "untrusted-peer")

        assertTrue(observedRoutes.isEmpty())
    }

    @Test
    fun relayForwardsDuplicateFrameOnlyOnceRegardlessOfHopMetadata() {
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
        assertEquals(1, relayFrames.count { MeshFrame.decode(it).kind == MeshFrame.KIND_MESSAGE })

        relay.receive(longer.copy(nonce = encryptedLonger.first, ciphertext = encryptedLonger.second).encode())
        assertEquals(1, relayFrames.count { MeshFrame.decode(it).kind == MeshFrame.KIND_MESSAGE })
    }

    @Test
    fun groupReceiptIsGeneratedButCannotRetireBroadcastWithoutRoster() {
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
        assertTrue(acknowledged.isEmpty())
        assertTrue(senderQueue.any { MeshFrame.decode(it).id == message.id })
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
        val bMessage = bToA.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE && it.origin == "node_B001" }
        assertEquals("node_B001", bMessage.origin)
        assertEquals(null, bMessage.destination)
        assertEquals("from B to A", MeshCrypto(passphrase.toCharArray()).decrypt(
            bMessage.nonce, bMessage.ciphertext, MeshFrame.aad(bMessage)
        ))
        assertTrue("B-to-A frame ID unexpectedly already received: ${bMessage.id}", bMessage.id !in seenA)
        nodeA.receive(bMessage.encode())
        assertTrue("B-to-A delivery failed; node A events: $eventsA", eventsA.any { it.contains("from B to A") })
    }

    @Test
    fun directedMessagesLearnReverseRoutesAndUsePeerSpecificTransport() {
        val passphrase = "a sufficiently long group passphrase"
        val sourceFrames = mutableListOf<ByteArray>()
        val relayFloods = mutableListOf<ByteArray>()
        val targeted = mutableListOf<Pair<String, ByteArray>>()
        val sourceSeen = mutableSetOf<String>()
        val relaySeen = mutableSetOf<String>()
        val learned = MeshRouteRegistry()

        val source = MeshRouter(
            nodeId = "source_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { sourceFrames.add(it); true },
            onMessage = {},
            hasReceived = { it in sourceSeen },
            markReceived = { id, _ -> !sourceSeen.add(id) },
            acknowledge = { 0 }
        )
        val relay = MeshRouter(
            nodeId = "relay_001",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { relayFloods.add(it); true },
            onMessage = {},
            hasReceived = { it in relaySeen },
            markReceived = { id, _ -> !relaySeen.add(id) },
            acknowledge = { 0 },
            resolveNextHops = { learned.nextHopsFor(it) },
            sendToPeer = { bytes, peer -> targeted.add(peer to bytes); true },
            onRouteObserved = { path, ingress -> learned.observe(path, ingressPeer = ingress) }
        )
        val targetSeen = mutableSetOf<String>()
        val targetFrames = mutableListOf<ByteArray>()
        val target = MeshRouter(
            nodeId = "target_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { targetFrames.add(it); true },
            onMessage = {},
            hasReceived = { it in targetSeen },
            markReceived = { id, _ -> !targetSeen.add(id) },
            acknowledge = { 0 }
        )

        assertTrue(source.sendMessageTo("target_01", "direct payload"))
        val initial = sourceFrames.map { MeshFrame.decode(it) }.single { it.destination == "target_01" }
        relay.receive(initial.encode(), "BLE/peer-source")
        assertEquals(1, relayFloods.count { MeshFrame.decode(it).destination == "target_01" })
        assertEquals(listOf("BLE/peer-source"), learned.nextHopsFor("source_01"))

        assertTrue(target.sendMessage("route advertisement"))
        val advertisement = targetFrames.map { MeshFrame.decode(it) }.first { it.kind == MeshFrame.KIND_MESSAGE }
        relay.receive(advertisement.encode(), "P2P/peer-target")
        assertEquals(listOf("P2P/peer-target"), learned.nextHopsFor("target_01"))

        targeted.clear()
        assertTrue(relay.sendMessageTo("target_01", "reply over selected route"))
        assertEquals(1, targeted.size)
        assertEquals("P2P/peer-target", targeted.single().first)
        val outgoing = MeshFrame.decode(targeted.single().second)
        assertEquals("target_01", outgoing.destination)
    }


    @Test
    fun directedDestinationConsumesMessageAndReceiptWithoutForwarding() {
        val passphrase = "a sufficiently long group passphrase"
        val sourceFrames = mutableListOf<ByteArray>()
        val destinationFrames = mutableListOf<ByteArray>()
        val sourceRelays = mutableListOf<ByteArray>()
        val destinationRelays = mutableListOf<ByteArray>()
        val sourceSeen = mutableSetOf<String>()
        val destinationSeen = mutableSetOf<String>()
        val sourceEvents = mutableListOf<String>()
        val destinationEvents = mutableListOf<String>()

        val source = MeshRouter(
            nodeId = "source_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { sourceFrames.add(it); true },
            onMessage = { sourceEvents.add(it) },
            hasReceived = { it in sourceSeen },
            markReceived = { id, _ -> !sourceSeen.add(id) },
            acknowledge = { 1 },
            validateAcknowledgement = { _, origin -> origin == "target_01" },
            sendRelay = { bytes, _ -> sourceRelays.add(bytes); true }
        )
        val destination = MeshRouter(
            nodeId = "target_01",
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { destinationFrames.add(it); true },
            onMessage = { destinationEvents.add(it) },
            hasReceived = { it in destinationSeen },
            markReceived = { id, _ -> !destinationSeen.add(id) },
            acknowledge = { 0 },
            sendRelay = { bytes, _ -> destinationRelays.add(bytes); true }
        )

        assertTrue(source.sendMessageTo("target_01", "private directed delivery"))
        val directed = sourceFrames.map { MeshFrame.decode(it) }
            .single { it.kind == MeshFrame.KIND_MESSAGE && it.destination == "target_01" }

        destination.receive(directed.encode(), "wifi-peer-source")

        assertTrue(destinationEvents.any { it.contains("private directed delivery") })
        assertTrue("The final destination re-forwarded the directed message", destinationRelays.isEmpty())
        val receipt = destinationFrames.map { MeshFrame.decode(it) }
            .single { it.kind == MeshFrame.KIND_ACK && it.ackFor == directed.id }
        assertEquals("source_01", receipt.destination)

        source.receive(receipt.encode(), "wifi-peer-target")

        assertTrue(sourceEvents.any { it.contains("Group receipt received") })
        assertTrue("The ACK destination re-forwarded the receipt", sourceRelays.isEmpty())
    }


    @Test
    fun signedOriginSurvivesRelayAndCannotBeSpoofedByGroupMember() {
        val passphrase = "a sufficiently long group passphrase"
        val senderIdentity = MeshDeviceIdentity.generateEphemeral()
        val relayIdentity = MeshDeviceIdentity.generateEphemeral()
        val destinationIdentity = MeshDeviceIdentity.generateEphemeral()
        val senderFrames = mutableListOf<ByteArray>()
        val relayFrames = mutableListOf<ByteArray>()
        val events = mutableListOf<String>()
        val senderSeen = mutableSetOf<String>()
        val relaySeen = mutableSetOf<String>()
        val destinationSeen = mutableSetOf<String>()

        val sender = MeshRouter(
            nodeId = senderIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { senderFrames.add(it); true },
            onMessage = {},
            hasReceived = { it in senderSeen },
            markReceived = { id, _ -> !senderSeen.add(id) },
            acknowledge = { 0 },
            identity = senderIdentity
        )
        val relay = MeshRouter(
            nodeId = relayIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { relayFrames.add(it); true },
            onMessage = {},
            hasReceived = { it in relaySeen },
            markReceived = { id, _ -> !relaySeen.add(id) },
            acknowledge = { 0 },
            identity = relayIdentity
        )
        val destination = MeshRouter(
            nodeId = destinationIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { true },
            onMessage = { events.add(it) },
            hasReceived = { it in destinationSeen },
            markReceived = { id, _ -> !destinationSeen.add(id) },
            acknowledge = { 0 },
            identity = destinationIdentity
        )

        assertTrue(sender.sendMessage("signed mesh origin"))
        val original = senderFrames.map { MeshFrame.decode(it) }.single { it.kind == MeshFrame.KIND_MESSAGE }
        assertEquals(senderIdentity.nodeId, original.origin)
        assertTrue(MeshDeviceIdentity.verify(original, "signed mesh origin"))
        relay.receive(original.encode(), "radio/source")
        val relayed = relayFrames.map { MeshFrame.decode(it) }.single { it.kind == MeshFrame.KIND_MESSAGE }
        assertEquals(original.signature, relayed.signature)
        destination.receive(relayed.encode(), "radio/relay")
        assertTrue(events.any { it.contains("signed mesh origin") })

        val plaintext = MeshCrypto(passphrase.toCharArray()).decrypt(
            original.nonce, original.ciphertext, MeshFrame.aad(original)
        )
        val forgedBase = original.copy(origin = "spoofed_node", hops = listOf("spoofed_node"))
        val (nonce, ciphertext) = MeshCrypto(passphrase.toCharArray()).encrypt(
            plaintext, MeshFrame.aad(forgedBase)
        )
        val forged = forgedBase.copy(nonce = nonce, ciphertext = ciphertext)
        val deliveredBefore = events.count { it.contains("signed mesh origin") }
        destination.receive(forged.encode(), "radio/malicious-member")
        assertEquals(deliveredBefore, events.count { it.contains("signed mesh origin") })
        assertTrue(events.any { it.contains("device identity signature") })
    }


    @Test
    fun directedMessageCannotBeRetiredByAnotherGroupMembersSignedReceipt() {
        val passphrase = "a sufficiently long group passphrase"
        val senderIdentity = MeshDeviceIdentity.generateEphemeral()
        val destinationIdentity = MeshDeviceIdentity.generateEphemeral()
        val attackerIdentity = MeshDeviceIdentity.generateEphemeral()
        val outgoing = mutableListOf<ByteArray>()
        val acknowledged = mutableListOf<String>()
        val events = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val sender = MeshRouter(
            nodeId = senderIdentity.nodeId,
            crypto = MeshCrypto(passphrase.toCharArray()),
            send = { outgoing.add(it); true },
            onMessage = { events.add(it) },
            hasReceived = { it in seen },
            markReceived = { id, _ -> !seen.add(id) },
            acknowledge = { id -> acknowledged.add(id); 1 },
            validateAcknowledgement = { _, origin -> origin == destinationIdentity.nodeId },
            identity = senderIdentity
        )
        assertTrue(sender.sendMessageTo(destinationIdentity.nodeId, "directed private message"))
        val message = outgoing.map { MeshFrame.decode(it) }.single { it.kind == MeshFrame.KIND_MESSAGE }

        val ack = MeshFrame(
            origin = attackerIdentity.nodeId,
            ttl = MeshFrame.MAX_TTL,
            hops = listOf(attackerIdentity.nodeId),
            nonce = "",
            ciphertext = "",
            id = "forged_ack_001",
            kind = MeshFrame.KIND_ACK,
            ackFor = message.id,
            destination = senderIdentity.nodeId
        )
        val crypto = MeshCrypto(passphrase.toCharArray())
        val prepared = attackerIdentity.prepare(ack, "")
        val (nonce, ciphertext) = crypto.encrypt("", MeshFrame.aad(prepared))
        val forgedReceipt = attackerIdentity.sign(prepared.copy(nonce = nonce, ciphertext = ciphertext))
        sender.receive(forgedReceipt.encode())

        assertTrue(acknowledged.isEmpty())
        assertTrue(events.any { it.contains("unexpected device") })
    }


    @Test
    fun directedEphemeralFramesUseOnlyBestEffortRelayAndArriveAtTarget() {
        val passphrase = "a sufficiently long group passphrase"
        val sourceIdentity = MeshDeviceIdentity.generateEphemeral()
        val relayIdentity = MeshDeviceIdentity.generateEphemeral()
        val targetIdentity = MeshDeviceIdentity.generateEphemeral()
        val sourceFrames = mutableListOf<ByteArray>()
        val relayFrames = mutableListOf<ByteArray>()
        val delivered = mutableListOf<String>()
        val sourceSeen = mutableSetOf<String>()
        val relaySeen = mutableSetOf<String>()
        val targetSeen = mutableSetOf<String>()
        val source = MeshRouter(
            nodeId = sourceIdentity.nodeId, crypto = MeshCrypto(passphrase.toCharArray()),
            send = { true }, onMessage = {}, hasReceived = { it in sourceSeen },
            markReceived = { id, _ -> !sourceSeen.add(id) }, acknowledge = { 0 },
            sendEphemeralFrame = { bytes -> sourceFrames.add(bytes); true },
            identity = sourceIdentity
        )
        val relay = MeshRouter(
            nodeId = relayIdentity.nodeId, crypto = MeshCrypto(passphrase.toCharArray()),
            send = { error("Ephemeral traffic must not enter the durable outbox") },
            onMessage = {}, hasReceived = { it in relaySeen },
            markReceived = { id, _ -> !relaySeen.add(id) }, acknowledge = { 0 },
            sendEphemeralRelay = { bytes, _ -> relayFrames.add(bytes); true },
            identity = relayIdentity
        )
        val target = MeshRouter(
            nodeId = targetIdentity.nodeId, crypto = MeshCrypto(passphrase.toCharArray()),
            send = { true }, onMessage = {}, hasReceived = { it in targetSeen },
            markReceived = { id, _ -> !targetSeen.add(id) }, acknowledge = { 0 },
            onEphemeralPayload = { origin, payload -> delivered.add("$origin:$payload") },
            identity = targetIdentity
        )

        assertTrue(source.sendEphemeralTo(targetIdentity.nodeId, "~IP1A|100.64.1.2"))
        val firstHop = sourceFrames.map { MeshFrame.decode(it) }.single()
        assertEquals(targetIdentity.nodeId, firstHop.destination)
        relay.receive(firstHop.encode(), "radio/source")
        val secondHop = relayFrames.map { MeshFrame.decode(it) }.single()
        assertEquals(firstHop.signature, secondHop.signature)
        target.receive(secondHop.encode(), "radio/relay")
        assertEquals(listOf("${sourceIdentity.nodeId}:~IP1A|100.64.1.2"), delivered)
    }


    @Test
    fun directEphemeralPeerDeliveryDoesNotUseDurableOutboxSender() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val durableFrames = mutableListOf<ByteArray>()
        val ephemeralFrames = mutableListOf<ByteArray>()
        val router = MeshRouter(
            nodeId = identity.nodeId,
            crypto = MeshCrypto("a sufficiently long group passphrase".toCharArray()),
            send = { bytes -> durableFrames.add(bytes); true },
            onMessage = {},
            hasReceived = { false },
            markReceived = { _, _ -> false },
            acknowledge = { 0 },
            resolveNextHops = { listOf("BLE/peer") },
            sendToPeer = { bytes, _ -> durableFrames.add(bytes); true },
            sendEphemeralToPeer = { bytes, _ -> ephemeralFrames.add(bytes); true },
            identity = identity
        )
        assertTrue(router.sendEphemeralTo("target_node_12345678", "~IP1P|packet_123456|0|1|AQID"))
        assertEquals(1, ephemeralFrames.size)
        assertTrue(durableFrames.isEmpty())
    }


}
