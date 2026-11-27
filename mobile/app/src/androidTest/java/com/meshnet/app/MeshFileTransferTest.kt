package com.meshnet.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MeshFileTransferTest {
    private val transfers = mutableListOf<MeshFileTransfer>()
    private lateinit var context: Context
    private lateinit var source: File
    private lateinit var receivedDir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = File(context.cacheDir, "mesh-transfer-test.bin")
        receivedDir = File(context.filesDir, "meshnet-received")
        receivedDir.deleteRecursively()
        source.writeBytes(ByteArray(4097) { (it % 251).toByte() })
    }

    @After fun tearDown() {
        transfers.forEach { runCatching { it.close() } }
        transfers.clear()
        source.delete()
        receivedDir.deleteRecursively()
    }

    private fun track(transfer: MeshFileTransfer): MeshFileTransfer =
        transfer.also { transfers.add(it) }

    @Test fun reconstructsOutOfOrderChunksAndVerifiesDigest() {
        val events = mutableListOf<String>()
        val receiver = track(MeshFileTransfer(context, { true }, { events.add(it) }))
        val (manifest, chunks) = protocolMessages(source, "transfer_0001")
        assertTrue(receiver.accept(manifest))
        chunks.reversed().forEach { receiver.accept(it) }

        val saved = receivedDir.listFiles()
            ?.firstOrNull { it.extension !in setOf("part", "meta", "done", "tmp") }
        assertTrue(saved != null)
        assertArrayEquals(source.readBytes(), saved!!.readBytes())
        assertTrue(events.any { it.contains("received and verified") })
        val marker = File(receivedDir, "transfer_0001.done")
        assertTrue("A completed transfer must have a durable deduplication marker", marker.isFile)
        val before = receivedDir.listFiles()?.count {
            it.isFile && it.extension !in setOf("part", "meta", "done", "tmp")
        } ?: 0
        assertTrue(receiver.accept(manifest))
        val after = receivedDir.listFiles()?.count {
            it.isFile && it.extension !in setOf("part", "meta", "done", "tmp")
        } ?: 0
        assertEquals("A repeated manifest must not create a second copy", before, after)
    }

    @Test fun preservesM4aExtensionForReceivedVoiceNotePlayback() {
        val voice = File(context.cacheDir, "a-very-long-mesh-voice-recording-name.m4a")
        voice.writeBytes(ByteArray(2048) { (it % 127).toByte() })
        val (manifest, chunks) = protocolMessages(voice, "voicefile_001")
        val receiver = track(MeshFileTransfer(context, { true }, {}))
        receiver.accept(manifest)
        chunks.forEach { receiver.accept(it) }
        assertTrue(receiver.latestVoiceNote()?.exists() == true)
        voice.delete()
    }

    @Test fun preflightsOutboxAndDoesNotQueuePartialFile() {
        val frames = mutableListOf<String>()
        val sender = track(
            MeshFileTransfer(context, { frames.add(it); true }, {}, canQueueBatch = { false })
        )
        assertFalse(sender.send(Uri.fromFile(source)))
        assertTrue(frames.isEmpty())
    }

    @Test fun resumesFromDurableReceiverCheckpointAfterRecreation() {
        val (manifest, chunks) = protocolMessages(source, "resume_00001")
        val firstReceiver = track(MeshFileTransfer(context, { true }, {}))
        firstReceiver.accept(manifest)
        val half = chunks.size / 2
        chunks.take(half).forEach { firstReceiver.accept(it) }
        firstReceiver.close()

        val acks = mutableListOf<String>()
        val restartedReceiver = track(
            MeshFileTransfer(context, { message -> acks.add(message); true }, {})
        )
        restartedReceiver.accept(manifest)
        val savedNext = acks.first { it.startsWith("~F2A|") }.substringAfterLast('|').toInt()
        assertEquals(half, savedNext)
        chunks.drop(half).forEach { restartedReceiver.accept(it) }

        val saved = receivedDir.listFiles()
            ?.firstOrNull { it.extension !in setOf("part", "meta", "done", "tmp") }
        assertTrue(saved != null)
        assertArrayEquals(source.readBytes(), saved!!.readBytes())
    }

    @Test fun sendsThroughBoundedWindowsAndCompletesAfterReceiverAcknowledges() {
        val sent = mutableListOf<String>()
        lateinit var sender: MeshFileTransfer
        val receiver = track(
            MeshFileTransfer(context, { message -> sender.accept(message); true }, {})
        )
        sender = track(
            MeshFileTransfer(context, { message ->
                sent.add(message)
                receiver.accept(message)
                true
            }, {})
        )
        assertTrue(sender.send(Uri.fromFile(source)))
        assertTrue(sent.first().startsWith("~F2M|"))
        assertTrue(sent.any { it.startsWith("~F2C|") })
    }

    @Test fun acceptsFileAt10MiBBoundaryUsingStreamingSource() {
        source.writeBytes(ByteArray(MeshFileTransfer.MAX_FILE_BYTES))
        val events = mutableListOf<String>()
        val sender = track(
            MeshFileTransfer(context, { true }, { events.add(it) }, canQueueBatch = { false })
        )
        assertFalse(sender.send(Uri.fromFile(source)))
        assertTrue(events.any { it.contains("outbox has no room") })
        assertEquals(10 * 1024 * 1024, MeshFileTransfer.MAX_FILE_BYTES)
    }

    @Test fun rejectsFilesLargerThan10MiBTransferLimit() {
        source.writeBytes(ByteArray(MeshFileTransfer.MAX_FILE_BYTES + 1))
        val sender = track(MeshFileTransfer(context, { true }, {}))
        var rejected = false
        try {
            sender.send(Uri.fromFile(source))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun protocolMessages(file: File, id: String): Pair<String, List<String>> {
        val bytes = file.readBytes()
        val digest = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(bytes),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val count = (bytes.size + 47) / 48
        val safeName = Base64.encodeToString(
            file.name.take(20).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val manifest = "~F2M|$id|${bytes.size}|$count|$digest|$safeName"
        val chunks = (0 until count).map { index ->
            val start = index * 48
            val data = bytes.copyOfRange(start, minOf(start + 48, bytes.size))
            val encoded = Base64.encodeToString(
                data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            "~F2C|$id|$index|$count|$encoded"
        }
        return manifest to chunks
    }
}
