package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterReceiptTest {
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
}
