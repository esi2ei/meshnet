package com.meshnet.app

import android.util.Base64

/**
 * Best-effort control-plane synchronization for the signed membership journal.
 * Payloads use authenticated v6 ephemeral frames and detached event signatures; no
 * membership claim is accepted merely because a peer sent the transport envelope.
 */
internal class MeshMembershipSyncProtocol(
    private val groupId: String,
    private val localNodeId: String,
    private val store: MeshGroupMembershipStore,
    private val send: (destination: String?, payload: String) -> Boolean,
    private val onEvent: (String) -> Unit = {}
) {
    private data class Assembly(
        val count: Int,
        val createdAt: Long,
        val chunks: MutableMap<Int, String> = mutableMapOf()
    )

    private val assemblies = linkedMapOf<String, Assembly>()
    private val lastResponseByPeer = linkedMapOf<String, Long>()
    private var lastSyncRequestAt = 0L

    init {
        require(groupId.matches(Regex("[a-f0-9]{64}")))
        require(localNodeId.matches(Regex("[A-Za-z0-9_-]{20,32}")))
    }

    @Synchronized
    fun publish(event: MeshMembershipEvent): Int {
        require(event.groupId == groupId && event.verify())
        return sendEvent(event, null)
    }

    /** Broadcast a bounded sync request; peers reply with their most recent event page. */
    @Synchronized
    fun requestSync(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncRequestAt < SYNC_REQUEST_INTERVAL_MS) return false
        lastSyncRequestAt = now
        return send(null, "$PREFIX|S|$groupId")
    }

    /** Returns true when the payload belongs to this control protocol (including rejected input). */
    @Synchronized
    fun accept(origin: String, payload: String): Boolean {
        if (!payload.startsWith("$PREFIX|")) return false
        pruneAssemblies()
        val fields = payload.split('|')
        if (fields.size < 3 || fields[0] != PREFIX) return true
        val expectedGroupTag = if (fields[1] == "C") groupId.take(GROUP_TAG_CHARS) else groupId
        if (fields[2] != expectedGroupTag) return true
        when (fields[1]) {
            "S" -> {
                if (fields.size != 3 || origin == localNodeId) return true
                val now = System.currentTimeMillis()
                if (now - (lastResponseByPeer[origin] ?: 0L) < SYNC_RESPONSE_INTERVAL_MS) return true
                if (lastResponseByPeer.size >= MAX_SYNC_PEERS && origin !in lastResponseByPeer) {
                    lastResponseByPeer.remove(lastResponseByPeer.keys.first())
                }
                lastResponseByPeer[origin] = now
                val total = store.eventCount(groupId)
                val page = store.eventsForSync(groupId, MAX_SYNC_EVENTS)
                if (total > page.size) {
                    send(origin, "$PREFIX|P|$groupId|$total")
                    onEvent("Membership sync is partial: peer has $total events; this response contains the latest ${page.size}")
                }
                page.forEach { sendEvent(it, origin) }
                return true
            }
            "P" -> {
                if (fields.size != 4) return true
                val total = fields[3].toIntOrNull() ?: return true
                if (total > MAX_SYNC_EVENTS) {
                    onEvent("Peer membership history exceeds the current sync page limit; roster may remain incomplete")
                }
                return true
            }
            "C" -> {
                if (fields.size != 7 || origin == localNodeId) return true
                acceptChunk(origin, fields)
                return true
            }
            else -> return true
        }
    }

    private fun sendEvent(event: MeshMembershipEvent, destination: String?): Int {
        val encoded = Base64.encodeToString(
            event.encode().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val chunks = encoded.chunked(CHUNK_CHARS)
        require(chunks.size in 1..MAX_CHUNKS) { "Membership event exceeds sync frame budget" }
        var accepted = 0
        chunks.forEachIndexed { index, chunk ->
            val payload = "$PREFIX|C|$groupId|${event.eventId}|$index|${chunks.size}|$chunk"
            if (payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES &&
                send(destination, payload)
            ) accepted++
        }
        if (accepted != chunks.size) {
            onEvent("Membership event ${event.eventId.take(8)} was only partially queued; it will be retried on reconnect")
        }
        return accepted
    }

    private fun acceptChunk(origin: String, fields: List<String>) {
        val eventId = fields[3]
        if (!eventId.matches(Regex("[A-Za-z0-9_-]{16,64}"))) return
        val index = fields[4].toIntOrNull() ?: return
        val count = fields[5].toIntOrNull() ?: return
        val chunk = fields[6]
        if (count !in 1..MAX_CHUNKS || index !in 0 until count ||
            chunk.isEmpty() || chunk.length > CHUNK_CHARS ||
            !chunk.matches(Regex("[A-Za-z0-9_-]+"))
        ) return

        val key = "$origin|$eventId"
        var assembly = assemblies[key]
        if (assembly == null) {
            if (assemblies.size >= MAX_ASSEMBLIES) return
            assembly = Assembly(count, System.currentTimeMillis())
            assemblies[key] = assembly
        } else if (assembly.count != count) {
            assemblies.remove(key)
            return
        }
        val previous = assembly.chunks[index]
        if (previous != null && previous != chunk) {
            assemblies.remove(key)
            onEvent("Rejected conflicting membership chunk for event ${eventId.take(8)}")
            return
        }
        assembly.chunks[index] = chunk
        if (assembly.chunks.size != count) return

        assemblies.remove(key)
        val pieces = (0 until count).map { assembly.chunks[it] ?: return }
        val encoded = pieces.joinToString("")
        val event = runCatching {
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(bytes.size in 1..MAX_EVENT_BYTES)
            MeshMembershipEvent.decode(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return
        if (event.eventId != eventId || event.groupId != groupId || !event.verify()) {
            onEvent("Rejected invalid signed membership event ${eventId.take(8)}")
            return
        }
        runCatching { store.append(event) }
            .onSuccess { inserted ->
                if (inserted) onEvent("Verified membership event stored: ${event.type} · ${event.memberId.take(8)}")
            }
            .onFailure { onEvent("Membership event rejected: ${it.message}") }
    }

    private fun pruneAssemblies() {
        val cutoff = System.currentTimeMillis() - ASSEMBLY_TTL_MS
        assemblies.entries.removeAll { it.value.createdAt < cutoff }
    }

    companion object {
        private const val PREFIX = "~GM1"
        private const val GROUP_TAG_CHARS = 16
        private const val CHUNK_CHARS = 48
        private const val MAX_CHUNKS = 24
        private const val MAX_PAYLOAD_BYTES = 120
        private const val MAX_EVENT_BYTES = 864
        private const val MAX_ASSEMBLIES = 64
        private const val MAX_SYNC_EVENTS = 64
        private const val SYNC_REQUEST_INTERVAL_MS = 15_000L
        private const val SYNC_RESPONSE_INTERVAL_MS = 10_000L
        private const val MAX_SYNC_PEERS = 64
        private const val ASSEMBLY_TTL_MS = 10L * 60L * 1000L
    }
}
