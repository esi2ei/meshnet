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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

        // Simulate process death after the final file rename but before .done was published.
        val pendingMarker = File(receivedDir, "transfer_0001.done.tmp")
        assertTrue(marker.renameTo(pendingMarker))
        val restarted = track(MeshFileTransfer(context, { true }, {}))
        assertTrue(restarted.accept(manifest))
        assertTrue("Pending completion record should be recovered", marker.isFile)
        val recoveredCount = receivedDir.listFiles()?.count {
            it.isFile && it.extension !in setOf("part", "meta", "done", "tmp")
        } ?: 0
        assertEquals("Recovery must not duplicate the final file", before, recoveredCount)
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

    @Test fun doesNotSendTerminalAckUntilDigestAndCompletionMarkerAreValid() {
        val replies = mutableListOf<String>()
        val events = mutableListOf<String>()
        val receiver = track(MeshFileTransfer(context, { message ->
            replies.add(message)
            true
        }, { events.add(it) }))
        val (manifest, chunks) = protocolMessages(source, "digest_0001")
        val count = chunks.size
        receiver.accept(manifest)
        chunks.dropLast(1).forEach { receiver.accept(it) }

        val finalFields = chunks.last().split('|').toMutableList()
        val corrupted = Base64.decode(finalFields[4], Base64.URL_SAFE or Base64.NO_WRAP)
        corrupted[0] = (corrupted[0].toInt() xor 0x5a).toByte()
        finalFields[4] = Base64.encodeToString(
            corrupted, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        receiver.accept(finalFields.joinToString("|"))

        assertFalse(replies.contains("~F2A|digest_0001|$count"))
        assertFalse(File(receivedDir, "digest_0001.done").exists())
        assertTrue(events.any { it.contains("integrity check failed") })
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

    @Test fun exposesCompletedFilesAndExportsThemThroughContentResolver() {
        val receiver = track(MeshFileTransfer(context, { true }, {}))
        val (manifest, chunks) = protocolMessages(source, "export_0001")
        receiver.accept(manifest)
        chunks.forEach { receiver.accept(it) }

        val received = receiver.receivedFiles().single()
        val exported = File(context.cacheDir, "mesh-exported-copy.bin")
        receiver.exportReceivedFile(received.name, Uri.fromFile(exported))
        assertArrayEquals(source.readBytes(), exported.readBytes())
        exported.delete()
    }

    @Test fun refusesToExportReceivedFileWhoseContentsChangedAfterVerification() {
        val receiver = track(MeshFileTransfer(context, { true }, {}))
        val (manifest, chunks) = protocolMessages(source, "tamper_0001")
        receiver.accept(manifest)
        chunks.forEach { receiver.accept(it) }
        val received = receiver.receivedFiles().single()
        received.writeBytes(ByteArray(received.length().toInt()) { 0x5a })

        val exported = File(context.cacheDir, "mesh-tampered-export.bin")
        var rejected = false
        try {
            receiver.exportReceivedFile(received.name, Uri.fromFile(exported))
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(exported.exists())
        assertFalse(File(receivedDir, "tamper_0001.done").exists())
    }

    @Test fun retainsCompletedInboxMarkersBeyondPartialCheckpointTtl() {
        val receiver = track(MeshFileTransfer(context, { true }, {}))
        val (manifest, chunks) = protocolMessages(source, "retain_0001")
        receiver.accept(manifest)
        chunks.forEach { receiver.accept(it) }
        val marker = File(receivedDir, "retain_0001.done")
        assertTrue(marker.isFile)
        marker.setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1000L)

        val restarted = track(MeshFileTransfer(context, { true }, {}))
        assertTrue(restarted.receivedFiles().any { it.exists() })
        assertTrue(marker.exists())
    }

    @Test fun boundsCompletedInboxByFileCountAndEvictsOldEntries() {
        val tiny = File(context.cacheDir, "mesh-quota.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val receiver = track(MeshFileTransfer(context, { true }, {}))
        repeat(51) { index ->
            val id = "quota_${index.toString().padStart(5, '0')}"
            val (manifest, chunks) = protocolMessages(tiny, id)
            receiver.accept(manifest)
            chunks.forEach { receiver.accept(it) }
        }
        assertEquals(50, receiver.receivedFiles().size)
        assertTrue(receiver.receivedFiles().all { it.exists() && it.length() == 3L })
        tiny.delete()
    }

    @Test fun cancelsAnActiveSenderAndSendsPeerCancellationControl() {
        val sent = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val manifestQueued = CountDownLatch(1)
        val sender = track(MeshFileTransfer(context, { message ->
            sent.add(message)
            if (message.startsWith("~F2M|")) manifestQueued.countDown()
            true
        }, { events.add(it) }))
        val result = AtomicReference<Boolean?>()
        val worker = Thread({ result.set(sender.send(Uri.fromFile(source))) }, "mesh-file-cancel-test")
        worker.start()

        assertTrue("Sender did not queue its manifest", manifestQueued.await(5, TimeUnit.SECONDS))
        assertTrue(sender.cancelOutgoing())
        worker.join(5_000)
        assertTrue("Sender worker did not stop after cancellation", !worker.isAlive)
        assertFalse(result.get() == true)
        assertTrue(sent.any { it.startsWith("~F2X|") })
        assertTrue(events.any { it.contains("cancelled on this device") })
    }

    @Test fun cancellationTombstoneBlocksDelayedManifestReplay() {
        val receiver = track(MeshFileTransfer(context, { true }, {}))
        val (manifest, _) = protocolMessages(source, "abort_00001")
        receiver.accept(manifest)
        assertTrue(File(receivedDir, "abort_00001.part").exists())

        receiver.accept("~F2X|abort_00001")
        assertTrue(File(receivedDir, "abort_00001.cancelled").isFile)
        assertFalse(File(receivedDir, "abort_00001.part").exists())

        // A stale manifest still in a durable sender outbox must not resurrect the cancelled transfer.
        receiver.accept(manifest)
        assertFalse(File(receivedDir, "abort_00001.part").exists())
        assertTrue(File(receivedDir, "abort_00001.cancelled").exists())
    }

    @Test fun rotatesTransferIdWhenRetryingAfterCancellation() {
        val sent = CopyOnWriteArrayList<String>()
        val firstManifest = CountDownLatch(1)
        val firstSender = track(MeshFileTransfer(context, { message ->
            sent.add(message)
            if (message.startsWith("~F2M|")) firstManifest.countDown()
            true
        }, {}))
        val first = Thread({ firstSender.send(Uri.fromFile(source)) }, "mesh-file-first-cancel")
        first.start()
        assertTrue(firstManifest.await(5, TimeUnit.SECONDS))
        val firstId = sent.first { it.startsWith("~F2M|") }.split('|')[1]
        assertTrue(firstSender.cancelOutgoing())
        first.join(5_000)
        assertFalse(first.isAlive)

        val secondManifest = CountDownLatch(1)
        val secondSender = track(MeshFileTransfer(context, { message ->
            sent.add(message)
            if (message.startsWith("~F2M|")) secondManifest.countDown()
            true
        }, {}))
        val second = Thread({ secondSender.send(Uri.fromFile(source)) }, "mesh-file-second-cancel")
        second.start()
        assertTrue(secondManifest.await(5, TimeUnit.SECONDS))
        val secondId = sent.last { it.startsWith("~F2M|") }.split('|')[1]
        assertTrue("A cancelled transfer must use a fresh ID on retry", firstId != secondId)
        assertTrue(secondSender.cancelOutgoing())
        second.join(5_000)
        assertFalse(second.isAlive)
    }

    @Test fun reportsAcknowledgedTransferProgressAndThroughput() {
        val senderEvents = mutableListOf<String>()
        val receiverEvents = mutableListOf<String>()
        lateinit var sender: MeshFileTransfer
        val receiver = track(
            MeshFileTransfer(context, { message -> sender.accept(message); true }, { receiverEvents.add(it) })
        )
        sender = track(
            MeshFileTransfer(context, { message ->
                receiver.accept(message)
                true
            }, { senderEvents.add(it) })
        )
        assertTrue(sender.send(Uri.fromFile(source)))
        assertTrue(senderEvents.any { it.contains("B/s") && it.contains("%") })
        assertTrue(receiverEvents.any { it.startsWith("Incoming file progress:") })
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
