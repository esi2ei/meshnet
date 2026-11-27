package com.meshnet.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded streaming file transfer with receiver-acknowledged windows and durable resume
 * checkpoints. Sender I/O is streaming; receiver file data is checkpointed in batches.
 */
class MeshFileTransfer(
    private val context: Context,
    private val sendMessage: (String) -> Boolean,
    private val onEvent: (String) -> Unit,
    private val canQueueBatch: (Int) -> Boolean = { true }
) {
    private data class Incoming(
        val name: String,
        val size: Int,
        val chunks: Int,
        val digest: String,
        val file: File,
        val meta: File,
        val received: BitSet,
        var handle: RandomAccessFile?,
        var lastAckNext: Int = 0,
        var unackedChunks: Int = 0
    )

    private class SenderAck {
        val lock = java.lang.Object()
        var hasAck = false
        var nextChunk = 0
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val senderAcks = ConcurrentHashMap<String, SenderAck>()
    private val activeSends = ConcurrentHashMap.newKeySet<String>()
    private val root = File(context.filesDir, "meshnet-received").apply {
        mkdirs()
        listFiles()?.filter {
            (it.name.endsWith(".part") || it.name.endsWith(".meta") ||
                it.name.endsWith(".meta.tmp") || it.name.endsWith(".done") ||
                it.name.endsWith(".done.tmp")) &&
                System.currentTimeMillis() - it.lastModified() > PARTIAL_TTL_MS
        }?.forEach { it.delete() }
    }
    private val outgoingRoot = File(context.filesDir, "meshnet-outgoing").apply {
        mkdirs()
        listFiles()?.filter {
            it.name.endsWith(".id") &&
                System.currentTimeMillis() - it.lastModified() > OUTGOING_ID_TTL_MS
        }?.forEach { it.delete() }
    }

    fun send(uri: Uri): Boolean {
        val name = compactName(queryName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_"))
            .let { if (it.isBlank() || it == "." || it == "..") "mesh-file.bin" else it }
        val inspected = inspect(uri)
        require(inspected.first in 1..MAX_FILE_BYTES.toLong()) {
            "File must be between 1 byte and 10 MiB"
        }
        val size = inspected.first.toInt()
        val digest = inspected.second
        val id = transferIdFor(digest, name)
        if (!activeSends.add(id)) {
            onEvent("File transfer $id is already active on this device")
            return false
        }
        val ackState = senderAcks.computeIfAbsent(id) { SenderAck() }
        try {
            val count = (size + CHUNK_BYTES - 1) / CHUNK_BYTES
            val safeName = Base64.encodeToString(
                name.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            synchronized(ackState.lock) {
                ackState.hasAck = false
                ackState.nextChunk = 0
            }
            if (!canQueueBatch(1)) {
                onEvent("File not started: the durable outbox has no room for its manifest")
                return false
            }
            if (!sendMessage("~F2M|$id|$size|$count|$digest|$safeName")) {
                onEvent("File manifest could not be queued")
                return false
            }

            var next = awaitCheckpoint(ackState, null, INITIAL_ACK_TIMEOUT_MS)
            if (next == null) {
                onEvent("File transfer $id paused: receiver checkpoint was not received; retry with the same file")
                return false
            }
            next = next.coerceIn(0, count)

            while (next < count) {
                if (Thread.currentThread().isInterrupted) {
                    onEvent("File transfer $id interrupted at chunk $next/$count")
                    return false
                }
                val end = minOf(next + WINDOW_SIZE, count)
                val windowCount = end - next
                if (!waitForOutboxCapacity(windowCount)) {
                    onEvent("File transfer $id paused at chunk $next/$count: outbox remained full")
                    return false
                }
                var ack: Int? = null
                var retry = 0
                while (ack == null && retry <= MAX_WINDOW_RETRIES) {
                    if (!sendWindow(uri, id, size, count, next, end)) {
                        onEvent("File transfer $id paused at chunk $next/$count: source changed or a chunk could not be queued")
                        return false
                    }
                    ack = awaitCheckpoint(ackState, next, ACK_TIMEOUT_MS)
                    if (ack == null) retry++
                }
                if (ack == null) {
                    onEvent("File transfer $id paused at chunk $next/$count after $MAX_WINDOW_RETRIES retries; reselect the same file to resume")
                    return false
                }
                next = ack.coerceIn(next + 1, count)
                onEvent("File transfer progress: $name · $next/$count chunks")
            }

            val afterSend = inspect(uri)
            if (afterSend.first != inspected.first || afterSend.second != inspected.second) {
                onEvent("File source changed while sending; receiver will reject the digest")
                return false
            }
            onEvent("File transfer completed: $name ($size bytes, $count acknowledged chunks)")
            return true
        } finally {
            activeSends.remove(id)
            senderAcks.remove(id, ackState)
        }
    }

    fun latestVoiceNote(): File? = root.listFiles()
        ?.filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
        ?.maxByOrNull { it.lastModified() }

    @Synchronized
    fun accept(message: String): Boolean {
        if (!message.startsWith("~F2")) return false
        val fields = message.split('|')
        return when (fields.firstOrNull()) {
            "~F2M" -> receiveManifest(fields)
            "~F2C" -> receiveChunk(fields)
            "~F2A" -> receiveAck(fields)
            "~F2X" -> cancelIncoming(fields)
            else -> true
        }
    }

    private fun receiveManifest(fields: List<String>): Boolean {
        if (fields.size != 6) return true
        val id = fields[1]
        if (!validTransferId(id)) return true
        val size = fields[2].toIntOrNull() ?: return true
        val count = fields[3].toIntOrNull() ?: return true
        val digest = fields[4]
        if (size !in 1..MAX_FILE_BYTES || count !in 1..MAX_CHUNKS ||
            count != (size + CHUNK_BYTES - 1) / CHUNK_BYTES ||
            !digest.matches(Regex("[A-Za-z0-9_-]{43}"))
        ) return true

        val decodedName = try {
            String(Base64.decode(fields[5], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Exception) { "mesh-file.bin" }
        val name = compactName(decodedName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            .let { if (it.isBlank() || it == "." || it == "..") "mesh-file.bin" else it }

        incoming[id]?.let { existing ->
            if (existing.name == name && existing.size == size &&
                existing.chunks == count && existing.digest == digest
            ) {
                sendAck(id, existing.lastAckNext)
                return true
            }
            closeHandle(existing)
            incoming.remove(id)
        }

        val done = File(root, "$id.done")
        if (done.exists()) {
            val completed = runCatching { JSONObject(done.readText()) }.getOrNull()
            if (completed?.optInt("size") == size &&
                completed.optString("digest") == digest &&
                completed.optString("name") == name
            ) {
                sendAck(id, count)
                return true
            }
            done.delete()
        }

        val partial = File(root, "$id.part")
        val meta = File(root, "$id.meta")
        if (incoming.size >= MAX_ACTIVE && !incoming.containsKey(id)) return true

        try {
            val saved = if (partial.exists() && meta.exists()) runCatching {
                JSONObject(meta.readText())
            }.getOrNull() else null
            val matches = saved != null && saved.optString("name") == name &&
                saved.optInt("size") == size && saved.optInt("chunks") == count &&
                saved.optString("digest") == digest
            val bits = if (matches) restoreReceivedBits(saved!!, count) else {
                partial.delete()
                meta.delete()
                BitSet()
            }
            val handle = RandomAccessFile(partial, "rw")
            if (!matches || handle.length() != size.toLong()) {
                handle.setLength(size.toLong())
                bits.clear()
            }
            if (bits.nextSetBit(count) >= 0) bits.clear(count, bits.length())
            val state = Incoming(name, size, count, digest, partial, meta, bits, handle)
            incoming[id] = state
            checkpoint(state)
            state.lastAckNext = nextContiguous(state)
            sendAck(id, state.lastAckNext)
            onEvent(
                if (bits.cardinality() == 0) "Incoming file: $name ($size bytes)"
                else "Resuming file: $name (${bits.cardinality()}/$count chunks already stored)"
            )
            if (state.received.cardinality() == state.chunks) finishIfComplete(id, state)
        } catch (error: Exception) {
            incoming.remove(id)?.let(::closeHandle)
            partial.delete()
            meta.delete()
            onEvent("Unable to create private transfer checkpoint: ${error.message}")
        }
        return true
    }

    private fun restoreReceivedBits(saved: JSONObject, count: Int): BitSet {
        val contiguous = saved.optInt("contiguous", -1)
        if (contiguous >= 0) {
            val bits = BitSet(count)
            bits.set(0, contiguous.coerceIn(0, count))
            val sparse = runCatching {
                BitSet.valueOf(Base64.decode(saved.optString("sparse", ""), Base64.DEFAULT))
            }.getOrDefault(BitSet())
            var bit = sparse.nextSetBit(0)
            while (bit >= 0 && contiguous + bit < count) {
                bits.set(contiguous + bit)
                bit = sparse.nextSetBit(bit + 1)
            }
            return bits
        }
        // Compatibility with checkpoints written by the earlier F2 implementation.
        return runCatching {
            BitSet.valueOf(Base64.decode(saved.optString("received", ""), Base64.DEFAULT))
        }.getOrDefault(BitSet())
    }

    private fun receiveChunk(fields: List<String>): Boolean {
        if (fields.size != 5) return true
        val id = fields[1]
        if (!validTransferId(id)) return true
        val index = fields[2].toIntOrNull() ?: return true
        val count = fields[3].toIntOrNull() ?: return true
        val data = try {
            Base64.decode(fields[4], Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: Exception) { return true }
        val state = incoming[id] ?: return true
        storeChunk(id, index, count, data, state)
        return true
    }

    private fun storeChunk(id: String, index: Int, count: Int, data: ByteArray, state: Incoming) {
        if (count != state.chunks || index !in 0 until state.chunks ||
            data.size != minOf(CHUNK_BYTES, state.size - index * CHUNK_BYTES)
        ) return
        if (state.received[index]) {
            sendAck(id, state.lastAckNext)
            return
        }
        try {
            val handle = state.handle ?: throw IllegalStateException("Transfer file handle is closed")
            handle.seek((index * CHUNK_BYTES).toLong())
            handle.write(data)
            state.received.set(index)
            state.unackedChunks++
            val next = nextContiguous(state)
            if (next - state.lastAckNext >= ACK_EVERY_CHUNKS ||
                next == state.chunks || state.unackedChunks >= ACK_EVERY_CHUNKS
            ) {
                // ACK only after both file bytes and the compact resume checkpoint are durable.
                checkpoint(state)
                state.lastAckNext = next
                state.unackedChunks = 0
                sendAck(id, next)
            }
            if (state.received.cardinality() == state.chunks) finishIfComplete(id, state)
        } catch (error: Exception) {
            onEvent("File chunk could not be checkpointed durably: ${error.message}")
        }
    }

    private fun checkpoint(state: Incoming) {
        val handle = state.handle ?: throw IllegalStateException("Transfer file handle is closed")
        handle.fd.sync()
        val contiguous = nextContiguous(state)
        val sparse = state.received.get(contiguous, state.chunks)
        val json = JSONObject()
            .put("name", state.name)
            .put("size", state.size)
            .put("chunks", state.chunks)
            .put("digest", state.digest)
            .put("contiguous", contiguous)
            .put("sparse", Base64.encodeToString(sparse.toByteArray(), Base64.NO_WRAP))
        persistMetadata(state.meta, json)
    }

    private fun persistMetadata(meta: File, json: JSONObject) {
        val temp = File(root, "${meta.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (meta.exists() && !meta.delete()) {
            temp.delete()
            throw IllegalStateException("Unable to replace transfer checkpoint")
        }
        if (!temp.renameTo(meta)) {
            temp.delete()
            throw IllegalStateException("Unable to persist transfer resume state")
        }
    }

    private fun finishIfComplete(id: String, state: Incoming) {
        if (state.received.cardinality() != state.chunks) return
        try {
            checkpoint(state)
            closeHandle(state)
            if (digestFile(state.file) != state.digest) {
                state.file.delete()
                state.meta.delete()
                incoming.remove(id)
                onEvent("File integrity check failed: ${state.name}")
                return
            }
            val destination = uniqueDestination(state.name)
            val done = File(root, "$id.done")
            val doneTemp = File(root, "$id.done.tmp")
            val record = JSONObject()
                .put("name", state.name)
                .put("size", state.size)
                .put("chunks", state.chunks)
                .put("digest", state.digest)
            // Write and fsync the completion record before moving the verified file. The
            // record is not published until after the final file rename succeeds.
            FileOutputStream(doneTemp).use { output ->
                output.write(record.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            if (!state.file.renameTo(destination)) {
                doneTemp.delete()
                state.handle = RandomAccessFile(state.file, "rw")
                onEvent("Verified file could not be moved; transfer checkpoint retained for retry")
                return
            }

            if (!doneTemp.renameTo(done)) {
                // Never ACK a completed transfer without a durable deduplication marker.
                // Roll the final file back to its checkpoint path so the sender can safely retry.
                val rolledBack = destination.renameTo(state.file)
                doneTemp.delete()
                if (rolledBack) {
                    state.handle = RandomAccessFile(state.file, "rw")
                } else {
                    onEvent("File delivered to ${destination.name}, but completion metadata could not be committed; no ACK was sent")
                }
                onEvent("Completion marker could not be committed; sender may retry this file")
                return
            }

            state.meta.delete()
            incoming.remove(id)
            sendAck(id, state.chunks)
            onEvent("File received and verified: ${destination.name} (${state.size} bytes)")
        } catch (error: Exception) {
            if (state.file.exists() && state.handle == null) {
                state.handle = runCatching { RandomAccessFile(state.file, "rw") }.getOrNull()
            }
            onEvent("File completion could not be persisted: ${error.message}")
        }
    }

    private fun receiveAck(fields: List<String>): Boolean {
        if (fields.size != 3 || !validTransferId(fields[1])) return true
        val next = fields[2].toIntOrNull() ?: return true
        if (next !in 0..MAX_CHUNKS) return true
        val state = senderAcks[fields[1]] ?: return true
        synchronized(state.lock) {
            state.nextChunk = maxOf(state.nextChunk, next)
            state.hasAck = true
            state.lock.notifyAll()
        }
        return true
    }

    private fun cancelIncoming(fields: List<String>): Boolean {
        if (fields.size != 2 || !validTransferId(fields[1])) return true
        val id = fields[1]
        incoming.remove(id)?.let(::closeHandle)
        File(root, "$id.part").delete()
        File(root, "$id.meta").delete()
        onEvent("Incoming file transfer $id cancelled")
        return true
    }

    private fun sendAck(id: String, next: Int) {
        sendMessage("~F2A|$id|$next")
    }

    private fun awaitCheckpoint(state: SenderAck, previous: Int?, timeoutMs: Long): Int? {
        synchronized(state.lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!state.hasAck || (previous != null && state.nextChunk <= previous)) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L || Thread.currentThread().isInterrupted) return null
                state.lock.wait(remaining)
            }
            return state.nextChunk
        }
    }

    private fun waitForOutboxCapacity(count: Int): Boolean {
        val deadline = System.currentTimeMillis() + OUTBOX_WAIT_MS
        while (!canQueueBatch(count)) {
            if (Thread.currentThread().isInterrupted || System.currentTimeMillis() >= deadline) return false
            Thread.sleep(OUTBOX_POLL_MS)
        }
        return true
    }

    private fun sendWindow(uri: Uri, id: String, size: Int, count: Int, start: Int, endExclusive: Int): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { stream ->
            skipFully(stream, start.toLong() * CHUNK_BYTES)
            for (index in start until endExclusive) {
                val expected = minOf(CHUNK_BYTES, size - index * CHUNK_BYTES)
                val data = readExactlyUpTo(stream, expected) ?: return false
                if (data.size != expected) return false
                val encoded = Base64.encodeToString(
                    data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                if (!sendMessage("~F2C|$id|$index|$count|$encoded")) return false
            }
        }
        return true
    }

    private fun inspect(uri: Uri): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var size = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open selected file")
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                size += read
                require(size <= MAX_FILE_BYTES) { "File exceeds the 10 MiB transfer limit" }
                digest.update(buffer, 0, read)
            }
        }
        return size to Base64.encodeToString(
            digest.digest(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun digestFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return Base64.encodeToString(
            digest.digest(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /** Flushes checkpointed receiver state and closes open file descriptors when the mesh stops. */
    @Synchronized
    fun close() {
        incoming.values.toList().forEach { state ->
            runCatching { checkpoint(state) }
                .onFailure { onEvent("Final file checkpoint failed: ${it.message}") }
            closeHandle(state)
        }
        incoming.clear()
    }

    private fun closeHandle(state: Incoming) {
        runCatching { state.handle?.close() }
        state.handle = null
    }

    @Synchronized
    private fun transferIdFor(digest: String, name: String): String {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$digest|$name".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = File(outgoingRoot, "$key.id")
        if (file.exists()) {
            val saved = file.readText().trim()
            if (validTransferId(saved)) return saved
        }
        val id = ByteArray(9).also(java.security.SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        file.writeText(id)
        return id
    }

    private fun nextContiguous(state: Incoming): Int =
        state.received.nextClearBit(0).coerceAtMost(state.chunks)

    private fun validTransferId(id: String): Boolean =
        id.matches(Regex("[A-Za-z0-9_-]{8,24}"))

    private fun compactName(value: String): String {
        if (value.length <= MAX_NAME_CHARS) return value
        val dot = value.lastIndexOf('.')
        if (dot > 0 && value.length - dot <= 6) {
            val suffix = value.substring(dot)
            return value.substring(0, MAX_NAME_CHARS - suffix.length) + suffix
        }
        return value.take(MAX_NAME_CHARS)
    }

    private fun uniqueDestination(name: String): File {
        var file = File(root, name)
        var suffix = 1
        while (file.exists()) {
            val dot = name.lastIndexOf('.')
            file = if (dot > 0) File(root, name.substring(0, dot) + "-$suffix" + name.substring(dot))
            else File(root, "$name-$suffix")
            suffix++
        }
        return file
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "mesh-file.bin"
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) ?: "mesh-file.bin" else "mesh-file.bin"
                } ?: "mesh-file.bin"
        } catch (_: Exception) { "mesh-file.bin" }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (input.read() < 0) throw IllegalArgumentException("Source file ended before resume offset")
                remaining--
            }
        }
    }

    private fun readExactlyUpTo(input: InputStream, size: Int): ByteArray? {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(data, offset, size - offset)
            if (read < 0) break
            if (read > 0) offset += read
        }
        return if (offset == 0) null else data.copyOf(offset)
    }

    companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        private const val CHUNK_BYTES = 48
        private const val MAX_CHUNKS = (MAX_FILE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
        private const val MAX_NAME_CHARS = 20
        private const val MAX_ACTIVE = 2
        private const val WINDOW_SIZE = 16
        private const val ACK_EVERY_CHUNKS = 8
        private const val MAX_WINDOW_RETRIES = 3
        private const val INITIAL_ACK_TIMEOUT_MS = 20_000L
        private const val ACK_TIMEOUT_MS = 8_000L
        private const val OUTBOX_WAIT_MS = 15_000L
        private const val OUTBOX_POLL_MS = 100L
        private const val IO_BUFFER_BYTES = 8 * 1024
        private const val PARTIAL_TTL_MS = 24L * 60L * 60L * 1000L
        private const val OUTGOING_ID_TTL_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
