package com.meshnet.app

import java.util.LinkedHashSet
import java.util.UUID

class MeshRouter(
    private val nodeId: String,
    private val crypto: MeshCrypto,
    private val send: (ByteArray) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun sendMessage(text: String) {
        require(text.isNotBlank()) { "Message cannot be empty" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Message is too large for a single BLE frame"
        }
        val id = UUID.randomUUID().toString()
        val (nonce, ciphertext) = crypto.encrypt(text, aad(id, nodeId))
        val frame = MeshFrame(id, nodeId, MeshFrame.MAX_TTL, listOf(nodeId), nonce, ciphertext)
        remember(id)
        send(frame.encode())
        onMessage("Me: $text")
    }

    @Synchronized
    fun receive(bytes: ByteArray) {
        val frame = try { MeshFrame.decode(bytes) } catch (error: Exception) {
            onMessage("Rejected malformed frame: ${error.message}")
            return
        }
        if (!remember(frame.id)) return
        try {
            val plaintext = crypto.decrypt(frame.nonce, frame.ciphertext, aad(frame.id, frame.origin))
            onMessage("${frame.origin}: $plaintext (hops: ${frame.hops.size - 1})")
        } catch (_: Exception) {
            onMessage("Rejected frame: authentication failed")
            return
        }
        if (frame.ttl <= 1 || frame.hops.contains(nodeId)) return
        send(frame.copy(ttl = frame.ttl - 1, hops = frame.hops + nodeId).encode())
    }

    private fun remember(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > MAX_SEEN_IDS) seen.remove(seen.first())
        return true
    }

    private fun aad(id: String, origin: String) =
        "meshnet-v1|$id|$origin".toByteArray(Charsets.UTF_8)

    companion object {
        private const val MAX_SEEN_IDS = 2048
        private const val MAX_PLAINTEXT_BYTES = 120
    }
}
