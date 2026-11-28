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
    private data class SyncProgress(val snapshotRowId: Long, val cursorRowId: Long)

    private val assemblies = linkedMapOf<String, Assembly>()
    private val syncProgressByPeer = linkedMapOf<String, SyncProgress>()
    private val lastResponseByPeer = linkedMapOf<String, Long>()
    private val lastPublishedByEvent = linkedMapOf<String, Long>()
    private var lastSyncRequestAt = 0L

    init {
        require(groupId.matches(Regex("[a-f0-9]{64}")))
        require(localNodeId.matches(Regex("[A-Za-z0-9_-]{20,32}")))
    }

    @Synchronized
    fun publish(event: MeshMembershipEvent): Int {
        require(event.groupId == groupId && event.verify())
        val now = System.currentTimeMillis()
        if (now - (lastPublishedByEvent[event.eventId] ?: 0L) < PUBLISH_INTERVAL_MS) return 0
        val sent = sendEvent(event, null)
        if (sent > 0) {
            if (lastPublishedByEvent.size >= MAX_SYNC_PEERS && event.eventId !in lastPublishedByEvent) {
                lastPublishedByEvent.remove(lastPublishedByEvent.keys.first())
            }
            lastPublishedByEvent[event.eventId] = now
        }
        return sent
    }

    /** Broadcast a bounded sync request; peers reply with their most recent event page. */
    @Synchronized
    fun requestSync(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val minimumInterval = if (force) MIN_FORCED_SYNC_INTERVAL_MS else SYNC_REQUEST_INTERVAL_MS
        if (now - lastSyncRequestAt < minimumInterval) return false
        lastSyncRequestAt = now
        if (force) syncProgressByPeer.clear()
        return send(null, "$PREFIX|S|$groupId|0|0")
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
                if (fields.size !in setOf(3, 5) || origin == localNodeId) return true
                val afterRowId = if (fields.size == 5) fields[3].toLongOrNull() ?: return true else 0L
                val requestedSnapshot = if (fields.size == 5) fields[4].toLongOrNull() ?: return true else 0L
                if (afterRowId < 0L || requestedSnapshot < 0L || afterRowId > requestedSnapshot) return true

                val now = System.currentTimeMillis()
                if (now - (lastResponseByPeer[origin] ?: 0L) < PAGE_RESPONSE_INTERVAL_MS) return true
                if (lastResponseByPeer.size >= MAX_SYNC_PEERS && origin !in lastResponseByPeer) {
                    lastResponseByPeer.remove(lastResponseByPeer.keys.first())
                }
                lastResponseByPeer[origin] = now

                val highWatermark = store.syncHighWatermark(groupId)
                val snapshot = if (requestedSnapshot == 0L) highWatermark else requestedSnapshot
                if (snapshot > highWatermark || afterRowId > snapshot) {
                    onEvent("Rejected membership sync cursor outside the local snapshot")
                    return true
                }
                val page = store.syncEventsAfterRowId(groupId, afterRowId, snapshot, MAX_SYNC_EVENTS)
                val completePageQueued = page.all { record ->
                    sendEvent(record.event, origin) == expectedChunkCount(record.event)
                }
                if (!completePageQueued) {
                    // Do not advance the remote cursor when any event chunk was not queued.
                    // A fresh sync starts again from row zero and exact event IDs are idempotent.
                    onEvent("Membership sync page was only partially queued; retry from the beginning on reconnect")
                    return true
                }
                val nextCursor = page.lastOrNull()?.rowId ?: afterRowId
                val done = page.size < MAX_SYNC_EVENTS || nextCursor >= snapshot
                send(origin, "$PREFIX|P|$groupId|$snapshot|$nextCursor|${if (done) 1 else 0}")
                return true
            }
            "P" -> {
                // Older peers can report only a total count; keep the limitation explicit.
                if (fields.size == 4) {
                    val total = fields[3].toIntOrNull() ?: return true
                    if (total > MAX_SYNC_EVENTS) {
                        onEvent("Legacy peer reports $total membership events but does not support cursor paging")
                    }
                    return true
                }
                if (fields.size != 6) return true
                val snapshot = fields[3].toLongOrNull() ?: return true
                val cursor = fields[4].toLongOrNull() ?: return true
                val done = when (fields[5]) { "1" -> true; "0" -> false; else -> return true }
                if (snapshot < 0L || cursor < 0L || cursor > snapshot || (!done && cursor == 0L)) return true

                val previous = syncProgressByPeer[origin]
                if (previous != null &&
                    (previous.snapshotRowId != snapshot || cursor <= previous.cursorRowId)
                ) return true
                if (!done && previous == null && syncProgressByPeer.size >= MAX_SYNC_PEERS) {
                    syncProgressByPeer.remove(syncProgressByPeer.keys.first())
                }
                if (done) {
                    syncProgressByPeer.remove(origin)
                    onEvent("Membership sync page sequence completed with snapshot row $snapshot")
                    return true
                }
                syncProgressByPeer[origin] = SyncProgress(snapshot, cursor)
                if (!send(origin, "$PREFIX|S|$groupId|$cursor|$snapshot")) {
                    onEvent("Membership sync continuation could not be queued; it will restart on reconnect")
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

    private fun expectedChunkCount(event: MeshMembershipEvent): Int {
        val encoded = Base64.encodeToString(
            event.encode().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return (encoded.length + CHUNK_CHARS - 1) / CHUNK_CHARS
    }

    private fun sendEvent(event: MeshMembershipEvent, destination: String?): Int {
        val eventBytes = event.encode().toByteArray(Charsets.UTF_8)
        if (eventBytes.size !in 1..MAX_EVENT_BYTES) {
            onEvent("Membership event ${event.eventId.take(8)} exceeds the bounded sync record size")
            return 0
        }
        val encoded = Base64.encodeToString(
            eventBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val chunks = encoded.chunked(CHUNK_CHARS)
        if (chunks.size !in 1..MAX_CHUNKS) {
            onEvent("Membership event ${event.eventId.take(8)} exceeds the bounded sync frame count")
            return 0
        }
        var accepted = 0
        chunks.forEachIndexed { index, chunk ->
            val payload = "$PREFIX|C|${groupId.take(GROUP_TAG_CHARS)}|${event.eventId}|$index|${chunks.size}|$chunk"
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
        private const val GROUP_TAG_CHARS = 24
        private const val CHUNK_CHARS = 40
        private const val MAX_CHUNKS = 24
        private const val MAX_PAYLOAD_BYTES = 120
        private const val MAX_EVENT_BYTES = 720
        private const val MAX_ASSEMBLIES = 64
        private const val MAX_SYNC_EVENTS = 16
        private const val SYNC_REQUEST_INTERVAL_MS = 15_000L
        private const val MIN_FORCED_SYNC_INTERVAL_MS = 2_000L
        private const val PUBLISH_INTERVAL_MS = 10_000L
        private const val PAGE_RESPONSE_INTERVAL_MS = 150L
        private const val MAX_SYNC_PEERS = 64
        private const val ASSEMBLY_TTL_MS = 10L * 60L * 1000L
    }
}
