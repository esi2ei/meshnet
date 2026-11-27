package com.meshnet.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Thread-safe registry of currently connected transport peers.
 *
 * Discovery results are deliberately excluded: a peer enters this registry only after
 * a transport has established its data channel, and leaves when that channel closes.
 */
internal class MeshPeerRegistry {
    data class Peer(
        val id: String,
        val address: String,
        val transport: String,
        val connectedAt: Long
    )

    private val peers = linkedMapOf<String, Peer>()

    @Synchronized
    fun setConnected(transport: String, address: String, connected: Boolean, now: Long = System.currentTimeMillis()) {
        val normalizedTransport = transport.trim()
        val normalizedAddress = address.trim()
        if (normalizedTransport.isEmpty() || normalizedAddress.isEmpty()) return
        val id = "$normalizedTransport:$normalizedAddress"
        if (connected) {
            peers.putIfAbsent(id, Peer(id, normalizedAddress, normalizedTransport, now))
        } else {
            peers.remove(id)
        }
    }

    @Synchronized
    fun clear() {
        peers.clear()
    }

    @Synchronized
    fun snapshot(): List<Peer> = peers.values.toList()

    @Synchronized
    fun toJson(): JSONArray = JSONArray().apply {
        peers.values.forEach { peer ->
            put(JSONObject()
                .put("id", peer.id)
                .put("address", peer.address)
                .put("transport", peer.transport)
                .put("connectedAt", peer.connectedAt))
        }
    }
}
