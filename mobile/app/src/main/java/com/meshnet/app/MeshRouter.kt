package com.meshnet.app

import java.util.LinkedHashSet
import java.util.UUID

class MeshRouter(
    private val nodeId: String,
    private val crypto: MeshCrypto,
    /** Returns true when the frame is durably accepted by the local outbox. */
    private val send: (ByteArray) -> Boolean,
    private val onMessage: (String) -> Unit,
    /** Atomically persists the received frame ID; true means it was already seen. */
    private val markReceived: (String, Long) -> Boolean
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun sendMessage(text: String) {
        require(text.isNotBlank()) { "Message cannot be empty" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Message is too large for a single BLE frame"
        }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val (nonce, ciphertext) = crypto.encrypt(text, aad(id, nodeId, createdAt))
        val frame = MeshFrame(
            id = id, origin = nodeId, ttl = MeshFrame.MAX_TTL,
            hops = listOf(nodeId), nonce = nonce, ciphertext = ciphertext,
            createdAt = createdAt
        )
        remember(id)
        if (!send(frame.encode())) {
            seen.remove(id)
            onMessage("Outbox rejected message; it was not queued")
            return
        }
        onMessage("Message encrypted and stored in the durable outbox; delivery is unconfirmed")
    }

    @Synchronized
    fun receive(bytes: ByteArray) {
        val frame = try { MeshFrame.decode(bytes) } catch (error: Exception) {
            onMessage("Rejected malformed frame: ${error.message}")
            return
        }
        val plaintext = try {
            crypto.decrypt(frame.nonce, frame.ciphertext, aad(frame.id, frame.origin, frame.createdAt))
        } catch (_: Exception) {
            onMessage("Rejected frame: authentication failed")
            return
        }
        if (!remember(frame.id)) return
        if (markReceived(frame.id, frame.createdAt)) return
        onMessage("${frame.origin}: $plaintext (hops: ${frame.hops.size - 1})")
        if (frame.ttl <= 1 || frame.hops.contains(nodeId)) return
        if (!send(frame.copy(ttl = frame.ttl - 1, hops = frame.hops + nodeId).encode())) {
            onMessage("Relay frame could not be stored in the local outbox")
        }
    }

    private fun remember(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > MAX_SEEN_IDS) seen.remove(seen.first())
        return true
    }

    private fun aad(id: String, origin: String, createdAt: Long) =
        "meshnet-v1|$id|$origin|$createdAt".toByteArray(Charsets.UTF_8)

    companion object {
        private const val MAX_SEEN_IDS = 2048
        private const val MAX_PLAINTEXT_BYTES = 120
    }
}
