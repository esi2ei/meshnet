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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("meshnet-group-membership.db")
        senderStore = MeshGroupMembershipStore(context)
        receiverStore = MeshGroupMembershipStore(context)
        sender = MeshDeviceIdentity.generateEphemeral()
        receiver = MeshDeviceIdentity.generateEphemeral()
    }

    @After
    fun tearDown() {
        senderStore.close()
        receiverStore.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase("meshnet-group-membership.db")
    }

    @Test
    fun chunkedSignedEventSynchronizesOutOfOrderAndDeduplicates() {
        val event = MeshMembershipEvent.create(sender, group, 1, MeshMembershipEvent.JOIN)
        assertTrue(senderStore.append(event))
        val packets = mutableListOf<String>()
        val senderProtocol = MeshMembershipSyncProtocol(group, sender.nodeId, senderStore, send = { _, payload ->
            packets.add(payload)
            true
        })
        val receiverProtocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, send = { _, _ -> true })

        assertTrue(senderProtocol.accept(receiver.nodeId, "~GM1|S|$group"))
        assertTrue(packets.isNotEmpty())
        assertTrue(packets.all { it.toByteArray(Charsets.UTF_8).size <= 120 })
        packets.reversed().forEach { assertTrue(receiverProtocol.accept(sender.nodeId, it)) }
        assertEquals(1, receiverStore.events(group).size)
        assertEquals("ACTIVE", receiverStore.roster(group).single().status)

        packets.forEach { receiverProtocol.accept(sender.nodeId, it) }
        assertEquals(1, receiverStore.events(group).size)
    }

    @Test
    fun wrongGroupAndMalformedChunksAreIgnored() {
        val receiverProtocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, send = { _, _ -> true })
        assertTrue(receiverProtocol.accept(sender.nodeId, "~GM1|S|${"c".repeat(64)}"))
        assertTrue(receiverProtocol.accept(sender.nodeId, "~GM1|C|${group.take(16)}|bad|0|1|not_base64!"))
        assertEquals(0, receiverStore.events(group).size)
    }

    @Test
    fun partialHistoryIsExplicitlyReported() {
        val events = (1L..70L).map { counter ->
            MeshMembershipEvent.create(sender, group, counter, if (counter == 1L) MeshMembershipEvent.JOIN else MeshMembershipEvent.LEAVE)
        }
        senderStore.merge(events)
        val packets = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val senderProtocol = MeshMembershipSyncProtocol(group, sender.nodeId, senderStore, send = { _, payload ->
            packets.add(payload)
            true
        })
        val receiverProtocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, { _, _ -> true }) {
            notices.add(it)
        }
        assertTrue(senderProtocol.accept(receiver.nodeId, "~GM1|S|$group"))
        assertTrue(packets.any { it.startsWith("~GM1|P|") })
        assertTrue(notices.any { it.contains("partial") })
    }

    @Test
    fun syncRequestIsRateLimitedUnlessForced() {
        val protocol = MeshMembershipSyncProtocol(group, receiver.nodeId, receiverStore, send = { _, _ -> true })
        assertTrue(protocol.requestSync(force = true))
        assertFalse(protocol.requestSync())
    }
}
