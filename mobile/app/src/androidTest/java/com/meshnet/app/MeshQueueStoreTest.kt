package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.ContentValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshQueueStoreTest {
    private lateinit var store: MeshQueueStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("meshnet-outbox.db")
        store = MeshQueueStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase("meshnet-outbox.db")
    }

    @Test
    fun authenticatedReceiptRetiresMessageButKeepsReceiptQueuedUntilPeerAcceptsIt() {
        val message = frame("message_01", MeshFrame.KIND_MESSAGE)
        val receipt = frame("receipt_01", MeshFrame.KIND_ACK, "message_01")

        assertTrue(store.enqueue(message.encode()))
        assertTrue(store.enqueue(receipt.encode()))
        assertEquals(1, store.acknowledge("message_01"))
        assertFalse(store.containsFrame(message.encode()))

        val pending = store.pending().map { MeshFrame.decode(it) }
        assertEquals(1, pending.size)
        assertEquals(MeshFrame.KIND_ACK, pending.single().kind)
    }

    @Test
    fun acceptedReceiptCanBeRetiredWithoutRemovingDurableMessages() {
        val message = frame("message_03", MeshFrame.KIND_MESSAGE)
        val receipt = frame("receipt_03", MeshFrame.KIND_ACK, "message_03")

        assertTrue(store.enqueue(message.encode()))
        assertTrue(store.enqueue(receipt.encode()))
        assertTrue(store.retireAcknowledgement(receipt.encode()))
        assertFalse(store.retireAcknowledgement(message.encode()))

        val pending = store.pending().map { MeshFrame.decode(it) }
        assertEquals(1, pending.size)
        assertEquals("message_03", pending.single().id)
    }

    @Test
    fun duplicateReceiptIdDoesNotGrowOutbox() {
        val first = frame("receipt_02", MeshFrame.KIND_ACK, "message_02")
        val retry = first.copy(nonce = "new-nonce", ciphertext = "new-ciphertext")

        assertTrue(store.enqueue(first.encode()))
        assertTrue(store.enqueue(retry.encode()))
        assertEquals(1, store.pending().size)
        assertTrue(store.retireAcknowledgement(retry.encode()))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun protocolV5UpgradeClearsIncompatibleQueuedFramesAndReplayIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store.close()
        context.deleteDatabase("meshnet-outbox.db")
        val legacy = context.openOrCreateDatabase("meshnet-outbox.db", Context.MODE_PRIVATE, null)
        legacy.execSQL(
            """CREATE TABLE outbox (
                frame_id TEXT PRIMARY KEY NOT NULL,
                frame BLOB NOT NULL,
                message_id TEXT,
                frame_kind TEXT NOT NULL DEFAULT 'MESSAGE',
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )"""
        )
        legacy.execSQL("CREATE TABLE received_ids (frame_id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL)")
        val oldFrame = frame("old_frame_01", MeshFrame.KIND_MESSAGE).encode()
            .toString(Charsets.UTF_8).replace("\\\"v\\\":5", "\\\"v\\\":4").toByteArray(Charsets.UTF_8)
        legacy.insertOrThrow("outbox", null, ContentValues().apply {
            put("frame_id", "legacy-row")
            put("frame", oldFrame)
            put("message_id", "old_frame_01")
            put("frame_kind", MeshFrame.KIND_MESSAGE)
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
        })
        legacy.insertOrThrow("received_ids", null, ContentValues().apply {
            put("frame_id", "old_frame_01")
            put("created_at", System.currentTimeMillis())
        })
        legacy.version = 6
        legacy.close()

        store = MeshQueueStore(context)
        assertTrue(store.pending().isEmpty())
        assertFalse(store.hasReceived("old_frame_01"))
    }

    private fun frame(id: String, kind: String, ackFor: String? = null) = MeshFrame(
        id = id,
        origin = "node_01",
        ttl = MeshFrame.MAX_TTL,
        hops = listOf("node_01"),
        nonce = "nonce",
        ciphertext = "ciphertext",
        kind = kind,
        ackFor = ackFor
    )
}
