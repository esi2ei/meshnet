package com.meshnet.app

import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

internal class MeshRouter(
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
    private val onFramePayload: (String, String) -> Boolean = { _, _ -> false },
    private val sendEphemeralFrame: (ByteArray) -> Boolean = send,
    private val onEphemeralPayload: (String, String) -> Unit = { _, _ -> },
    private val sendRelay: (ByteArray, String?) -> Boolean = { bytes, _ -> send(bytes) },
    private val sendEphemeralRelay: (ByteArray, String?) -> Boolean = { bytes, _ -> sendEphemeralFrame(bytes) },
    private val resolveNextHops: (String) -> List<String> = { emptyList() },
    private val sendToPeer: (ByteArray, String) -> Boolean = { _, _ -> false },
    private val sendEphemeralToPeer: (ByteArray, String) -> Boolean = { _, _ -> false },
    private val onRouteObserved: (List<String>, String?) -> Unit = { _, _ -> },
    // Missing receipt policy must fail closed; callers must explicitly validate the signer and target.
    private val validateAcknowledgement: (String, String) -> Boolean = { _, _ -> false },
    private val recordDestination: (String, String) -> Unit = { _, _ -> },
    private val forgetDestination: (String) -> Unit = {},
    private val identity: MeshDeviceIdentity? = null
) {
    private val seen = LinkedHashSet<String>()
    private val displayed = LinkedHashSet<String>()
    // A frame is relayed at most once per node. Mutable hop metadata must not influence
    // forwarding decisions; alternative ingress observations are still learned separately.
    private val relayedFrames = LinkedHashSet<String>()

    @Synchronized
    fun sendEphemeral(text: String): Boolean = sendEphemeralInternal(text, null)

    /** Best-effort unicast data-plane frame; unknown routes use ephemeral flooding, never the durable outbox. */
    @Synchronized
    fun sendEphemeralTo(destination: String, text: String): Boolean {
        require(destination.matches(Regex("[A-Za-z0-9_-]{1,32}")) && destination != nodeId) {
            "Invalid or local destination node ID"
        }
        return sendEphemeralInternal(text, destination)
    }

    private fun sendEphemeralInternal(text: String, destination: String?): Boolean {
        require(text.isNotBlank()) { "Ephemeral payload cannot be empty" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Ephemeral payload is too large for one frame"
        }
        val frame = MeshFrame(
            origin = nodeId, ttl = MeshFrame.MAX_TTL, hops = listOf(nodeId),
            nonce = "", ciphertext = "", createdAt = System.currentTimeMillis(),
            id = UUID.randomUUID().toString(), kind = MeshFrame.KIND_EPHEMERAL,
            destination = destination
        )
        val encoded = encryptOriginFrame(frame, text).encode()
        if (destination == null) return sendEphemeralFrame(encoded)
        val candidates = resolveNextHops(destination)
        if (candidates.any { sendEphemeralToPeer(encoded, it) }) return true
        return sendEphemeralFrame(encoded)
    }

    @Synchronized
    fun sendMessage(text: String): Boolean = sendMessageInternal(text, null)

    /** Sends an authenticated unicast frame; unknown routes use bounded flooding. */
    @Synchronized
    fun sendMessageTo(destination: String, text: String): Boolean {
        require(destination.matches(Regex("[A-Za-z0-9_-]{1,32}")) && destination != nodeId) {
            "Invalid or local destination node ID"
        }
        return sendMessageInternal(text, destination)
    }

    private fun sendMessageInternal(text: String, destination: String?): Boolean {
        require(text.isNotBlank()) { "Message cannot be empty" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Message is too large for a single MeshNet frame"
        }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val frame = MeshFrame(
            origin = nodeId, ttl = MeshFrame.MAX_TTL, hops = listOf(nodeId),
            nonce = "", ciphertext = "", createdAt = createdAt, id = id, destination = destination
        )
        val encryptedFrame = encryptOriginFrame(frame, text)
        if (destination != null) recordDestination(id, destination)
        remember(seen, id)
        val encoded = encryptedFrame.encode()
        val accepted = if (destination == null) send(encoded) else {
            val candidates = resolveNextHops(destination)
            if (candidates.isEmpty()) send(encoded) else {
                var routed = false
                for (peer in candidates) if (sendToPeer(encoded, peer)) { routed = true; break }
                routed || sendRelay(encoded, null)
            }
        }
        if (!accepted) {
            seen.remove(id)
            if (destination != null) forgetDestination(id)
            onMessage("Outbox rejected message; it was not queued")
            return false
        }
        markReceived(id, createdAt)
        onMessage(if (destination == null) "Message encrypted and queued; waiting for a group receipt" else "Directed message encrypted and queued for $destination")
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
        if (identity != null && !MeshDeviceIdentity.verify(frame, plaintext)) {
            onMessage("Rejected frame: device identity signature is missing or invalid")
            return
        }

        // The hop list is mutable by any holder of the shared group key; it is not a path proof.
        // Learn only the directly observed reverse neighbor for the verified origin. This
        // prevents a peer from poisoning routes to arbitrary nodes by inserting fake hop IDs.
        if (viaPeer != null && frame.origin != nodeId) {
            onRouteObserved(listOf(frame.origin, nodeId), viaPeer)
        }

        if (frame.destination != null && frame.destination != nodeId) {
            if (rememberBestRoute(frame)) {
                if (frame.kind == MeshFrame.KIND_EPHEMERAL) relayDirectedEphemeral(frame, plaintext, viaPeer)
                else relayDirected(frame, plaintext, viaPeer)
            }
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
            if (rememberBestRoute(frame)) relayIfNeeded(frame, plaintext, viaPeer)
            return
        }

        if (frame.kind == MeshFrame.KIND_ACK) {
            if (frame.origin == nodeId) {
                markReceived(frame.id, frame.createdAt)
                remember(seen, frame.id)
                return
            }
            if (frame.id in seen || hasReceived(frame.id)) {
                if (rememberBestRoute(frame)) relayIfNeeded(frame, plaintext, viaPeer)
                return
            }
            val target = frame.ackFor ?: return
            if (!validateAcknowledgement(target, frame.origin)) {
                onMessage("Rejected receipt for directed message $target from unexpected device ${frame.origin}")
                return
            }
            val removed = acknowledge(target)
            if (rememberBestRoute(frame)) relayIfNeeded(frame, plaintext, viaPeer)
            markReceived(frame.id, frame.createdAt)
            remember(seen, frame.id)
            if (removed > 0) onMessage("Group receipt received for message $target")
            return
        }

        if (frame.id in seen || hasReceived(frame.id)) {
            // Retry both paths: an earlier receipt or relay may have been queued while no peer was connected.
            if (frame.origin != nodeId) sendReceipt(frame.id, frame.origin)
            if (rememberBestRoute(frame)) relayIfNeeded(frame, plaintext, viaPeer)
            return
        }

        if (frame.origin == nodeId) {
            // Never treat our own frame returning through a relay as a remote delivery receipt.
            markReceived(frame.id, frame.createdAt)
            remember(seen, frame.id)
            return
        }

        val handled = onFramePayload(frame.origin, plaintext) || onPayload(plaintext)
        if (!handled && displayed.add(frame.id)) {
            onMessage("${frame.origin}: $plaintext (hops: ${frame.hops.size - 1})")
            trim(displayed)
        } else {
            remember(displayed, frame.id)
        }
        sendReceipt(frame.id, frame.origin)
        if (rememberBestRoute(frame)) relayIfNeeded(frame, plaintext, viaPeer)

        if (markReceived(frame.id, frame.createdAt)) return
        remember(seen, frame.id)
    }

    private fun sendReceipt(messageId: String, destination: String) {
        val ackId = UUID.nameUUIDFromBytes(("meshnet-ack|" + messageId).toByteArray(Charsets.UTF_8)).toString()
        val createdAt = System.currentTimeMillis()
        val frame = MeshFrame(
            origin = nodeId, ttl = MeshFrame.MAX_TTL, hops = listOf(nodeId),
            nonce = "", ciphertext = "", createdAt = createdAt,
            id = ackId, kind = MeshFrame.KIND_ACK, ackFor = messageId, destination = destination
        )
        val encoded = encryptOriginFrame(frame, "").encode()
        val candidates = resolveNextHops(destination)
        val routed = candidates.any { peer -> sendToPeer(encoded, peer) }
        val queued = if (routed) true else if (candidates.isEmpty()) send(encoded) else sendRelay(encoded, null)
        if (queued) {
            remember(seen, ackId)
            markReceived(ackId, createdAt)
        } else {
            onMessage("Receipt for $messageId could not be queued; it will retry when the frame is received again")
        }
    }

    private fun encryptOriginFrame(frame: MeshFrame, plaintext: String): MeshFrame {
        val prepared = identity?.prepare(frame, plaintext) ?: frame
        val (nonce, ciphertext) = crypto.encrypt(plaintext, MeshFrame.aad(prepared))
        val encrypted = prepared.copy(nonce = nonce, ciphertext = ciphertext)
        return identity?.sign(encrypted) ?: encrypted
    }

    /** Prevent duplicate forwarding; untrusted hop counts never affect route decisions. */
    private fun rememberBestRoute(frame: MeshFrame): Boolean {
        if (frame.id in relayedFrames) return false
        remember(relayedFrames, frame.id)
        return true
    }

    /**
     * Keep the v6 TTL/route-length invariant without trusting hop history for loop prevention.
     * If an untrusted sender already listed this relay, normalize that entry to the final
     * position and do not charge the same relay hop twice. Local per-frame dedup still ensures
     * this node forwards a given frame only once.
     */
    private fun nextHopMetadata(frame: MeshFrame): Pair<Int, List<String>> {
        val alreadyRecorded = nodeId in frame.hops
        val normalizedHops = frame.hops.filterNot { it == nodeId } + nodeId
        val nextTtl = if (alreadyRecorded) frame.ttl else frame.ttl - 1
        return nextTtl to normalizedHops
    }
    private fun relayDirectedEphemeral(frame: MeshFrame, plaintext: String, viaPeer: String?) {
        if (frame.ttl <= 1) return
        val (nextTtl, nextHops) = nextHopMetadata(frame)
        val nextHopFrame = frame.copy(ttl = nextTtl, hops = nextHops)
        val (nonce, ciphertext) = crypto.encrypt(plaintext, MeshFrame.aad(nextHopFrame))
        val encoded = nextHopFrame.copy(nonce = nonce, ciphertext = ciphertext).encode()
        val candidates = resolveNextHops(frame.destination.orEmpty()).filter { it != viaPeer }
        var accepted = false
        for (peer in candidates) {
            if (sendEphemeralToPeer(encoded, peer)) { accepted = true; break }
        }
        if (!accepted) accepted = sendEphemeralRelay(encoded, viaPeer)
        if (!accepted) onMessage("Ephemeral directed relay dropped: no alternate peer accepted the frame")
    }

    private fun relayDirected(frame: MeshFrame, plaintext: String, viaPeer: String?) {
        if (frame.ttl <= 1) return
        val (nextTtl, nextHops) = nextHopMetadata(frame)
        val nextHopFrame = frame.copy(ttl = nextTtl, hops = nextHops)
        val (nonce, ciphertext) = crypto.encrypt(plaintext, MeshFrame.aad(nextHopFrame))
        val encoded = nextHopFrame.copy(nonce = nonce, ciphertext = ciphertext).encode()
        val candidates = resolveNextHops(frame.destination.orEmpty()).filter { it != viaPeer }
        var accepted = false
        for (peer in candidates) {
            if (sendToPeer(encoded, peer)) { accepted = true; break }
        }
        if (!accepted) accepted = sendRelay(encoded, viaPeer)
        if (!accepted) onMessage("Directed relay deferred: no alternate route or transport accepted the frame")
    }

    private fun relayIfNeeded(frame: MeshFrame, plaintext: String, viaPeer: String?) {
        // Directed traffic is consumed at the final destination; never forward a
        // delivered message or its receipt beyond the node it targets.
        if (frame.destination == nodeId) return
        if (frame.ttl <= 1) return
        val (nextTtl, nextHops) = nextHopMetadata(frame)
        val nextHopFrame = frame.copy(ttl = nextTtl, hops = nextHops)
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
        private const val MAX_PLAINTEXT_BYTES = 120
    }
}
