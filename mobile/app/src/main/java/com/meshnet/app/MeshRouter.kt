package com.meshnet.app

import java.util.LinkedHashSet
import java.util.UUID

class MeshRouter(
    private val nodeId: String,
    private val crypto: MeshCrypto,
    /** Returns true when the frame is durably accepted by the local outbox. */
    private val send: (ByteArray) -> Boolean,
    private val onMessage: (String) -> Unit,
    private val hasReceived: (String) -> Boolean,
    /** Atomically persists the received frame ID; true means it was already seen. */
    private val markReceived: (String, Long) -> Boolean
) {
    private val seen = LinkedHashSet<String>()
    private val displayed = LinkedHashSet<String>()

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
        remember(seen, id)
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
        if (frame.id in seen || hasReceived(frame.id)) return

        if (displayed.add(frame.id)) {
            onMessage("${frame.origin}: $plaintext (hops: ${frame.hops.size - 1})")
            trim(displayed)
        }

        // Do not persist the replay marker until a required relay is durably queued.
        // This allows a later duplicate radio frame to retry a relay after temporary queue pressure.
        if (frame.ttl > 1 && !frame.hops.contains(nodeId)) {
            val relayed = frame.copy(ttl = frame.ttl - 1, hops = frame.hops + nodeId).encode()
            if (!send(relayed)) {
                onMessage("Relay deferred: local outbox is unavailable or full")
                return
            }
        }

        if (markReceived(frame.id, frame.createdAt)) return
        remember(seen, frame.id)
    }

    private fun remember(target: LinkedHashSet<String>, id: String) {
        target.add(id)
        trim(target)
    }

    private fun trim(target: LinkedHashSet<String>) {
        while (target.size > MAX_SEEN_IDS) target.remove(target.first())
    }

    private fun aad(id: String, origin: String, createdAt: Long) =
        "meshnet-v1|$id|$origin|$createdAt".toByteArray(Charsets.UTF_8)

    companion object {
        private const val MAX_SEEN_IDS = 2048
        private const val MAX_PLAINTEXT_BYTES = 120
    }
}
