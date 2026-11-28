package com.meshnet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshMembershipSyncProtocolTest {
    private lateinit var senderStore: MeshGroupMembershipStore
    private lateinit var receiverStore: MeshGroupMembershipStore
    private val group = "b".repeat(64)
    private lateinit var sender: MeshDeviceIdentity
    private lateinit var receiver: MeshDeviceIdentity

    private val senderDatabase = "meshnet-membership-sender-test.db"
    private val receiverDatabase = "meshnet-membership-receiver-test.db"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(senderDatabase)
        context.deleteDatabase(receiverDatabase)
        senderStore = MeshGroupMembershipStore(context, senderDatabase)
        receiverStore = MeshGroupMembershipStore(context, receiverDatabase)
        sender = MeshDeviceIdentity.generateEphemeral()
        receiver = MeshDeviceIdentity.generateEphemeral()
    }

    @After
    fun tearDown() {
        senderStore.close()
        receiverStore.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(senderDatabase)
        context.deleteDatabase(receiverDatabase)
    }

    @Test
    fun chunkedSignedEventSynchronizesOutOfOrderAndDeduplicates() {
        val event = MeshMembershipEvent.create(sender, group, 1, MeshMembershipEvent.JOIN)
        assertTrue(senderStore.append(event))
        assertTrue("Sender data must not appear in the receiver database before sync", receiverStore.events(group).isEmpty())
        val packets = mutableListOf<String>()
        val senderProtocol = MeshMembershipSyncProtocol(group, sender.nodeId, senderStore, send = { _, payload ->
            packets.add(payload)
            true
        })
        val receiverProtocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, send = { _, _ -> true })

        assertTrue(senderProtocol.accept(receiver.nodeId, "~GM1|S|$group|0|0"))
        assertTrue(packets.isNotEmpty())
        assertTrue(packets.all { it.toByteArray(Charsets.UTF_8).size <= 120 })
        packets.reversed().forEach { assertTrue(receiverProtocol.accept(sender.nodeId, it)) }
        assertEquals(1, receiverStore.events(group).size)
        assertEquals("ACTIVE", receiverStore.roster(group).single().status)

        packets.forEach { receiverProtocol.accept(sender.nodeId, it) }
        assertEquals(1, receiverStore.events(group).size)
        packets.clear()
        senderProtocol.accept(receiver.nodeId, "~GM1|S|$group")
        assertTrue("Repeated sync response should be rate limited", packets.isEmpty())
    }

    @Test
    fun wrongGroupAndMalformedChunksAreIgnored() {
        val receiverProtocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, send = { _, _ -> true })
        assertTrue(receiverProtocol.accept(sender.nodeId, "~GM1|S|${"c".repeat(64)}"))
        assertTrue(receiverProtocol.accept(sender.nodeId, "~GM1|C|${group.take(24)}|bad|0|1|not_base64!"))
        assertEquals(0, receiverStore.events(group).size)
    }

    @Test
    fun membershipHistoryIsDeliveredAcrossAllBoundedSnapshotPages() {
        val events = (1L..70L).map { counter ->
            MeshMembershipEvent.create(
                sender, group, counter,
                if (counter == 1L || counter % 2L == 0L) MeshMembershipEvent.JOIN else MeshMembershipEvent.LEAVE,
                createdAt = 1_700_000_000_000L + counter
            )
        }
        senderStore.merge(events)
        val packets = mutableListOf<String>()
        val continuationRequests = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val senderProtocol = MeshMembershipSyncProtocol(
            group, sender.nodeId, senderStore,
            send = { _, payload -> packets.add(payload); true }
        )
        val receiverProtocol = MeshMembershipSyncProtocol(
            group, receiver.nodeId, receiverStore,
            send = { _, payload -> continuationRequests.add(payload); true },
            onEvent = { notices.add(it) }
        )

        assertTrue(receiverProtocol.requestSync(force = true))
        var pages = 0
        while (continuationRequests.isNotEmpty()) {
            val request = continuationRequests.removeAt(0)
            assertTrue(senderProtocol.accept(receiver.nodeId, request))
            val batch = packets.toList()
            packets.clear()
            assertTrue("Every snapshot page must contain a completion marker", batch.any { it.startsWith("~GM1|P|") })
            if (pages == 0) {
                val marker = batch.single { it.startsWith("~GM1|P|") }.split('|')
                val snapshot = marker[3].toLong()
                val nextCursor = marker[4].toLong()
                // A requester cannot skip to an arbitrary row within a valid snapshot.
                assertTrue(senderProtocol.accept(receiver.nodeId, "~GM1|S|$group|${nextCursor + 1L}|$snapshot"))
                assertTrue("Unexpected cursor must not produce another page", packets.isEmpty())
            }
            batch.filterNot { it.startsWith("~GM1|P|") }.reversed()
                .forEach { assertTrue(receiverProtocol.accept(sender.nodeId, it)) }
            batch.filter { it.startsWith("~GM1|P|") }
                .forEach { assertTrue(receiverProtocol.accept(sender.nodeId, it)) }
            pages++
        }

        assertTrue("More than one page must be used for 70 events", pages > 1)
        assertEquals(70, receiverStore.events(group).size)
        assertEquals("ACTIVE", receiverStore.roster(group).single().status)
        assertTrue(notices.any { it.contains("completed") })
    }

    @Test
    fun partialHistoryIsExplicitlyReported() {
        val events = (1L..70L).map { counter ->
            MeshMembershipEvent.create(
                sender, group, counter,
                if (counter == 1L) MeshMembershipEvent.JOIN else MeshMembershipEvent.LEAVE,
                createdAt = 1_700_000_000_000L + counter
            )
        }
        senderStore.merge(events)
        val packets = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val senderProtocol = MeshMembershipSyncProtocol(
            group, sender.nodeId, senderStore,
            send = { _, payload -> packets.add(payload); true },
            onEvent = { notices.add(it) }
        )
        val receiverProtocol = MeshMembershipSyncProtocol(
            group, receiver.nodeId, receiverStore, send = { _, _ -> true }
        )
        assertTrue(senderProtocol.accept(receiver.nodeId, "~GM1|S|$group"))
        assertTrue(packets.any { it.startsWith("~GM1|P|") })
        assertTrue(notices.any { it.contains("partial") })
        packets.forEach { receiverProtocol.accept(sender.nodeId, it) }
        assertEquals("INCOMPLETE", receiverStore.roster(group).single().status)
    }

    @Test
    fun syncRequestIsRateLimitedUnlessForced() {
        val protocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, send = { _, _ -> true })
        assertTrue(protocol.requestSync(force = true))
        assertFalse(protocol.requestSync())
        assertFalse(protocol.requestSync(force = true))
    }
    @Test
    fun legacyV6SyncRequestReceivesLegacySinglePageMarkerAndBoundedEvents() {
        val events = (1L..70L).map { counter ->
            MeshMembershipEvent.create(
                sender, group, counter,
                if (counter == 1L || counter % 2L == 0L) MeshMembershipEvent.JOIN else MeshMembershipEvent.LEAVE,
                createdAt = 1_700_000_000_000L + counter
            )
        }
        senderStore.merge(events)
        val packets = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val senderProtocol = MeshMembershipSyncProtocol(
            group, sender.nodeId, senderStore,
            send = { _, payload -> packets.add(payload); true },
            onEvent = { notices.add(it) }
        )

        assertTrue(senderProtocol.accept(receiver.nodeId, "~GM1|S|$group"))

        val markers = packets.filter { it.startsWith("~GM1|P|") }
        val chunks = packets.filter { it.startsWith("~GM1|C|") }
        assertEquals(1, markers.size)
        assertEquals(4, markers.single().split('|').size)
        assertEquals("70", markers.single().split('|')[3])
        assertTrue(chunks.isNotEmpty())
        assertFalse(packets.any { it.startsWith("~GM1|P|$group|") && it.split('|').size == 6 })
        assertTrue(notices.any { it.contains("Legacy peer supports one page only") })
    }


}
