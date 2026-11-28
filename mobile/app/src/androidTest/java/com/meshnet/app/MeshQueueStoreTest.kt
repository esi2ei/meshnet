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
    fun fullOutboxRejectsNewFramesWithoutEvictingOldestUnacknowledgedMessage() {
        val db = store.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            repeat(6000) { index ->
                val id = "queued_$index"
                db.insertOrThrow("outbox", null, ContentValues().apply {
                    put("frame_id", "seed_$index")
                    put("frame", frame(id, MeshFrame.KIND_MESSAGE).encode())
                    put("message_id", id)
                    put("frame_kind", MeshFrame.KIND_MESSAGE)
                    put("created_at", now + index)
                    put("attempts", 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        assertFalse(store.canAcceptBatch(1))
        assertFalse(store.enqueue(frame("new_message_01", MeshFrame.KIND_MESSAGE).encode()))
        val oldest = MeshFrame.decode(store.pending(1).single())
        assertEquals("queued_0", oldest.id)
        assertEquals(6000, store.pending().size)
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
    fun everyPreV6DatabaseVersionQuarantinesIncompatibleQueuedFramesAndReplayIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (1..7).forEach { legacyVersion ->
            store.close()
            context.deleteDatabase("meshnet-outbox.db")

            val legacy = context.openOrCreateDatabase("meshnet-outbox.db", Context.MODE_PRIVATE, null)
            if (legacyVersion < 3) {
                legacy.execSQL(
                    """CREATE TABLE outbox (
                        frame_id TEXT PRIMARY KEY NOT NULL,
                        frame BLOB NOT NULL,
                        created_at INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            } else {
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
            }
            if (legacyVersion >= 2) {
                legacy.execSQL(
                    "CREATE TABLE received_ids (frame_id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL)"
                )
            }

            val messageId = "old_frame_$legacyVersion"
            val frameId = "legacy-row-$legacyVersion"
            val oldFrame = """{"v":5,"id":"$messageId"}""".toByteArray(Charsets.UTF_8)
            val createdAt = 1_700_000_000_000L + legacyVersion
            legacy.insertOrThrow("outbox", null, ContentValues().apply {
                put("frame_id", frameId)
                put("frame", oldFrame)
                if (legacyVersion >= 3) {
                    put("message_id", messageId)
                    put("frame_kind", MeshFrame.KIND_MESSAGE)
                }
                put("created_at", createdAt)
                put("attempts", 3)
            })
            if (legacyVersion >= 2) {
                legacy.insertOrThrow("received_ids", null, ContentValues().apply {
                    put("frame_id", messageId)
                    put("created_at", createdAt)
                })
            }
            legacy.version = legacyVersion
            legacy.close()

            store = MeshQueueStore(context)
            assertTrue("v$legacyVersion: legacy frames must not remain dispatchable", store.pending().isEmpty())
            assertFalse("v$legacyVersion: legacy replay markers must not remain active", store.hasReceived(messageId))

            val quarantined = store.quarantinedFrames().single()
            assertEquals(frameId, quarantined.frameId)
            assertTrue(oldFrame.contentEquals(quarantined.frame))
            assertEquals(if (legacyVersion >= 3) messageId else null, quarantined.messageId)
            assertEquals(MeshFrame.KIND_MESSAGE, quarantined.frameKind)
            assertEquals(createdAt, quarantined.createdAt)
            assertEquals(3, quarantined.attempts)
            assertEquals(legacyVersion, quarantined.sourceVersion)
            assertTrue(quarantined.reason.isNotBlank())

            val replayMarkers = store.quarantinedReplayIds()
            if (legacyVersion == 1) {
                assertTrue(replayMarkers.isEmpty())
            } else {
                val replayMarker = replayMarkers.single()
                assertEquals(messageId, replayMarker.frameId)
                assertEquals(createdAt, replayMarker.createdAt)
                assertEquals(legacyVersion, replayMarker.sourceVersion)
                assertTrue(replayMarker.reason.isNotBlank())
            }
        }
    }

    @Test
    fun nonProtocolUpgradePreservesCompatibleProtocolV6OutboxFrames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val currentFrame = frame("current_frame_01", MeshFrame.KIND_MESSAGE).encode()
        assertTrue(store.enqueue(currentFrame))
        store.close()

        // Simulate a database created by schema v9: the protocol-v6 outbox is compatible
        // with v10 and must not be quarantined or removed by a storage-only migration.
        val database = context.openOrCreateDatabase("meshnet-outbox.db", Context.MODE_PRIVATE, null)
        database.version = 9
        database.close()

        store = MeshQueueStore(context)
        val pending = store.pending()
        assertEquals(1, pending.size)
        assertTrue(currentFrame.contentEquals(pending.single()))
        assertTrue(store.quarantinedFrames().isEmpty())
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

    @Test
    fun directedReceiptMustMatchPersistedDestinationEvenWithoutOutboxFrame() {
        store.recordDestination("directed_message_01", "destination_01")
        assertFalse(store.receiptMatches("directed_message_01", "attacker_01"))
        assertTrue(store.receiptMatches("directed_message_01", "destination_01"))
        assertEquals(1, store.acknowledge("directed_message_01"))
        // Once the directed target record is retired, no later receipt may be trusted.
        assertFalse(store.receiptMatches("directed_message_01", "attacker_01"))
    }

    @Test
    fun groupBroadcastReceiptCannotRetireMessageWithoutMemberRoster() {
        val broadcast = frame("group_message_01", MeshFrame.KIND_MESSAGE)
        assertTrue(store.enqueue(broadcast.encode()))

        // A shared group key and a valid device signature do not establish the
        // intended recipient set or prove delivery to every group member.
        assertFalse(store.receiptMatches("group_message_01", "any_group_member"))
        assertEquals(1, store.pending().size)
    }

    @Test
    fun directedDestinationIsAlsoReadFromDurableFrameWhenPresent() {
        val directed = frame("directed_message_02", MeshFrame.KIND_MESSAGE).copy(destination = "destination_02")
        assertTrue(store.enqueue(directed.encode()))
        assertFalse(store.receiptMatches("directed_message_02", "attacker_02"))
        assertTrue(store.receiptMatches("directed_message_02", "destination_02"))
    }

}
