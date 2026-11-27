package com.meshnet.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MeshFileTransferTest {
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
        source.delete()
        receivedDir.deleteRecursively()
    }

    @Test fun reconstructsOutOfOrderChunksAndVerifiesDigest() {
        val frames = mutableListOf<String>()
        val sender = MeshFileTransfer(context, { frames.add(it); true }, {})
        val events = mutableListOf<String>()
        val receiver = MeshFileTransfer(context, { true }, { events.add(it) })

        assertTrue(sender.send(Uri.fromFile(source)))
        assertTrue(frames.all { it.toByteArray(Charsets.UTF_8).size <= 120 })
        val manifest = frames.first { it.startsWith("~F1M|") }
        val chunks = frames.filter { it.startsWith("~F1C|") }.reversed()
        chunks.forEach { assertTrue(receiver.accept(it)) }
        assertTrue(receiver.accept(manifest))

        val saved = receivedDir.listFiles()?.firstOrNull { it.extension != "part" }
        assertTrue(saved != null)
        assertArrayEquals(source.readBytes(), saved!!.readBytes())
        assertTrue(events.any { it.contains("received and verified") })
    }

    @Test fun preflightsOutboxAndDoesNotQueuePartialFile() {
        val frames = mutableListOf<String>()
        val sender = MeshFileTransfer(context, { frames.add(it); true }, {}, canQueueBatch = { false })
        assertFalse(sender.send(Uri.fromFile(source)))
        assertTrue(frames.isEmpty())
    }

    @Test fun resumesPersistedPartialTransferAfterReceiverAndSenderRecreation() {
        val firstFrames = mutableListOf<String>()
        val firstSender = MeshFileTransfer(context, { firstFrames.add(it); true }, {})
        val firstReceiver = MeshFileTransfer(context, { true }, {})
        assertTrue(firstSender.send(Uri.fromFile(source)))
        firstReceiver.accept(firstFrames.first { it.startsWith("~F1M|") })
        val firstChunks = firstFrames.filter { it.startsWith("~F1C|") }
        firstChunks.take(firstChunks.size / 2).forEach { firstReceiver.accept(it) }

        val retryFrames = mutableListOf<String>()
        val restartedSender = MeshFileTransfer(context, { retryFrames.add(it); true }, {})
        val restartedReceiver = MeshFileTransfer(context, { true }, {})
        assertTrue(restartedSender.send(Uri.fromFile(source)))
        assertEquals(
            firstFrames.first { it.startsWith("~F1M|") }.split('|')[1],
            retryFrames.first { it.startsWith("~F1M|") }.split('|')[1]
        )
        retryFrames.forEach { restartedReceiver.accept(it) }

        val saved = receivedDir.listFiles()?.firstOrNull { it.extension != "part" && it.extension != "meta" }
        assertTrue(saved != null)
        assertArrayEquals(source.readBytes(), saved!!.readBytes())
    }

    @Test fun rejectsFilesLargerThanBoundedTransferLimit() {
        source.writeBytes(ByteArray(MeshFileTransfer.MAX_FILE_BYTES + 1))
        val sender = MeshFileTransfer(context, { true }, {})
        var rejected = false
        try { sender.send(Uri.fromFile(source)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun ignoresMalformedAndUnannouncedChunks() {
        val receiver = MeshFileTransfer(context, { true }, {})
        assertFalse(receiver.accept("ordinary chat message"))
        assertTrue(receiver.accept("~F1C|not-a-valid-transfer|0|1|AQ"))
        assertTrue(receiver.accept("~F1M|bad|0|0|not-a-hash|"))
    }
}
