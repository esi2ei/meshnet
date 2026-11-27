package com.meshnet.app

import java.util.LinkedHashMap
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
    private val acknowledge: (String) -> Int,
    /** Returns true when an authenticated plaintext payload is consumed by an application protocol. */
    private val onPayload: (String) -> Boolean = { false },
    private val sendEphemeralFrame: (ByteArray) -> Boolean = send,
    private val onEphemeralPayload: (String, String) -> Unit = { _, _ -> },
    private val sendRelay: (ByteArray, String?) -> Boolean = { bytes, _ -> send(bytes) },
    private val sendEphemeralRelay: (ByteArray, String?) -> Boolean = { bytes, _ -> sendEphemeralFrame(bytes) }
) {
    private val seen = LinkedHashSet<String>()
    private val displayed = LinkedHashSet<String>()
    private val bestRouteScores = LinkedHashMap<String, Long>()
    private val peerArrivalAgeMs = LinkedHashMap<String, Double>()

    @Synchronized
    fun sendEphemeral(text: String): Boolean {
        require(text.isNotBlank()) { "Ephemeral payload cannot be empty" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Ephemeral payload is too large for one frame"
        }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val frame = MeshFrame(
            origin = nodeId, ttl = MeshFrame.MAX_TTL, hops = listOf(nodeId),
            nonce = "", ciphertext = "", createdAt = createdAt,
            id = id, kind = MeshFrame.KIND_EPHEMERAL
        )
        val (nonce, ciphertext) = crypto.encrypt(text, MeshFrame.aad(frame))
        val accepted = sendEphemeralFrame(frame.copy(nonce = nonce, ciphertext = ciphertext).encode())
        return accepted
    }

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
        markReceived(id, createdAt)
        onMessage("Message encrypted and queued; waiting for a group receipt")
        return true
    }

    @Synchronized
    fun receive(bytes: ByteArray, viaPeer: String? = null) {
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

        if (frame.kind == MeshFrame.KIND_EPHEMERAL) {
            // Media is best-effort: no durable replay cache, chat display, or group ACK.
            if (frame.origin == nodeId) {
                remember(seen, frame.id)
                return
            }
            val duplicate = frame.id in seen
            remember(seen, frame.id)
            if (!duplicate) onEphemeralPayload(frame.origin, plaintext)
            if (rememberBestRoute(frame, viaPeer)) relayIfNeeded(frame, plaintext, viaPeer)
            return
        }

        if (frame.kind == MeshFrame.KIND_ACK) {
            if (frame.origin == nodeId) {
                markReceived(frame.id, frame.createdAt)
                remember(seen, frame.id)
                return
            }
            if (frame.id in seen || hasReceived(frame.id)) {
                if (rememberBestRoute(frame, viaPeer)) relayIfNeeded(frame, plaintext, viaPeer)
                return
            }
            val target = frame.ackFor ?: return
            val removed = acknowledge(target)
            rememberBestRoute(frame, viaPeer)
            relayIfNeeded(frame, plaintext, viaPeer)
            markReceived(frame.id, frame.createdAt)
            remember(seen, frame.id)
            if (removed > 0) onMessage("Group receipt received for message $target")
            return
        }

        if (frame.id in seen || hasReceived(frame.id)) {
            // Retry both paths: an earlier receipt or relay may have been queued while no peer was connected.
            if (frame.origin != nodeId) sendReceipt(frame.id)
            if (rememberBestRoute(frame, viaPeer)) relayIfNeeded(frame, plaintext, viaPeer)
            return
        }

        if (frame.origin == nodeId) {
            // Never treat our own frame returning through a relay as a remote delivery receipt.
            markReceived(frame.id, frame.createdAt)
            remember(seen, frame.id)
            return
        }

        val handled = onPayload(plaintext)
        if (!handled && displayed.add(frame.id)) {
            onMessage("${frame.origin}: $plaintext (hops: ${frame.hops.size - 1})")
            trim(displayed)
        } else {
            remember(displayed, frame.id)
        }
        sendReceipt(frame.id)
        if (rememberBestRoute(frame, viaPeer)) relayIfNeeded(frame, plaintext, viaPeer)

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

    /** Fewer hops always wins; equal-hop alternatives use peer historical arrival age. */
    private fun rememberBestRoute(frame: MeshFrame, viaPeer: String?): Boolean {
        val peerPenalty = viaPeer?.let { peerArrivalAgeMs[it] } ?: DEFAULT_PEER_AGE_MS
        val score = frame.hops.size * ROUTE_HOP_WEIGHT + peerPenalty.toLong().coerceIn(0L, MAX_PEER_AGE_MS)
        if (viaPeer != null) {
            val age = (System.currentTimeMillis() - frame.createdAt).coerceAtLeast(0L).toDouble()
            val previousAge = peerArrivalAgeMs[viaPeer]
            peerArrivalAgeMs[viaPeer] = if (previousAge == null) age else previousAge * 0.8 + age * 0.2
            while (peerArrivalAgeMs.size > MAX_TRACKED_PEERS) {
                val iterator = peerArrivalAgeMs.entries.iterator()
                if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
            }
        }
        val previous = bestRouteScores[frame.id]
        if (previous != null && score >= previous) return false
        bestRouteScores[frame.id] = score
        while (bestRouteScores.size > MAX_SEEN_IDS) {
            val iterator = bestRouteScores.entries.iterator()
            if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
        }
        return true
    }

    private fun relayIfNeeded(frame: MeshFrame, plaintext: String, viaPeer: String?) {
        if (frame.ttl <= 1 || frame.hops.contains(nodeId)) return
        val nextHopFrame = frame.copy(ttl = frame.ttl - 1, hops = frame.hops + nodeId)
        // TTL and route are authenticated; relays re-encrypt after updating route metadata.
        val (nonce, ciphertext) = crypto.encrypt(plaintext, MeshFrame.aad(nextHopFrame))
        val relayed = nextHopFrame.copy(nonce = nonce, ciphertext = ciphertext).encode()
        val accepted = if (frame.kind == MeshFrame.KIND_EPHEMERAL) {
            sendEphemeralRelay(relayed, viaPeer)
        } else {
            sendRelay(relayed, viaPeer)
        }
        if (!accepted) onMessage("Relay deferred: no eligible transport accepted the frame")
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
        private const val MAX_TRACKED_PEERS = 128
        private const val ROUTE_HOP_WEIGHT = 100_000L
        private const val MAX_PEER_AGE_MS = 99_999L
        private const val DEFAULT_PEER_AGE_MS = 50_000.0
        private const val MAX_PLAINTEXT_BYTES = 120
    }
}
