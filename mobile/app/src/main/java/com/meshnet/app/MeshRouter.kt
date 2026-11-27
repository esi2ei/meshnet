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
    private val markReceived: (String, Long) -> Boolean,
    /** Removes the original group message after an authenticated group receipt. */
    private val acknowledge: (String) -> Int
) {
    private val seen = LinkedHashSet<String>()
    private val displayed = LinkedHashSet<String>()

    @Synchronized
    fun sendMessage(text: String): Boolean {
        require(text.isNotBlank()) { "Message cannot be empty" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Message is too large for a single BLE frame"
        }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val frame = MeshFrame(
            origin = nodeId, ttl = MeshFrame.MAX_TTL, hops = listOf(nodeId),
            nonce = "", ciphertext = "", createdAt = createdAt, id = id
        )
        val (nonce, ciphertext) = crypto.encrypt(text, MeshFrame.aad(frame))
        val encryptedFrame = frame.copy(nonce = nonce, ciphertext = ciphertext)
        remember(seen, id)
        if (!send(encryptedFrame.encode())) {
            seen.remove(id)
            onMessage("Outbox rejected message; it was not queued")
            return false
        }
        onMessage("Message encrypted and queued; waiting for a group receipt")
        return true
    }

    @Synchronized
    fun receive(bytes: ByteArray) {
        val frame = try { MeshFrame.decode(bytes) } catch (error: Exception) {
            onMessage("Rejected malformed frame: ${error.message}")
            return
        }
        val plaintext = try {
            crypto.decrypt(frame.nonce, frame.ciphertext, MeshFrame.aad(frame))
        } catch (_: Exception) {
            onMessage("Rejected frame: authentication failed")
            return
        }

        if (frame.kind == MeshFrame.KIND_ACK) {
            if (frame.id in seen || hasReceived(frame.id)) {
                relayIfNeeded(frame)
                return
            }
            val target = frame.ackFor ?: return
            val removed = acknowledge(target)
            relayIfNeeded(frame)
            markReceived(frame.id, frame.createdAt)
            remember(seen, frame.id)
            if (removed > 0) onMessage("Group receipt received for message $target")
            return
        }

        if (frame.id in seen || hasReceived(frame.id)) {
            // Retry both paths: an earlier receipt or relay may have been queued while no peer was connected.
            sendReceipt(frame.id)
            relayIfNeeded(frame)
            return
        }

        if (displayed.add(frame.id)) {
            onMessage("${frame.origin}: $plaintext (hops: ${frame.hops.size - 1})")
            trim(displayed)
        }
        sendReceipt(frame.id)
        relayIfNeeded(frame)

        if (markReceived(frame.id, frame.createdAt)) return
        remember(seen, frame.id)
    }

    private fun sendReceipt(messageId: String) {
        val ackId = UUID.nameUUIDFromBytes(("meshnet-ack|" + messageId).toByteArray(Charsets.UTF_8)).toString()
        val createdAt = System.currentTimeMillis()
        val frame = MeshFrame(
            origin = nodeId, ttl = MeshFrame.MAX_TTL, hops = listOf(nodeId),
            nonce = "", ciphertext = "", createdAt = createdAt,
            id = ackId, kind = MeshFrame.KIND_ACK, ackFor = messageId
        )
        val (nonce, ciphertext) = crypto.encrypt("", MeshFrame.aad(frame))
        if (send(frame.copy(nonce = nonce, ciphertext = ciphertext).encode())) {
            remember(seen, ackId)
            markReceived(ackId, createdAt)
        } else {
            onMessage("Receipt for $messageId could not be queued; it will retry when the frame is received again")
        }
    }

    private fun relayIfNeeded(frame: MeshFrame) {
        if (frame.ttl <= 1 || frame.hops.contains(nodeId)) return
        val relayed = frame.copy(ttl = frame.ttl - 1, hops = frame.hops + nodeId).encode()
        if (!send(relayed)) onMessage("Relay deferred: local outbox is unavailable or full")
    }

    private fun remember(target: LinkedHashSet<String>, id: String) {
        target.add(id)
        trim(target)
    }

    private fun trim(target: LinkedHashSet<String>) {
        while (target.size > MAX_SEEN_IDS) target.remove(target.first())
    }

    companion object {
        private const val MAX_SEEN_IDS = 2048
        private const val MAX_PLAINTEXT_BYTES = 120
    }
}
