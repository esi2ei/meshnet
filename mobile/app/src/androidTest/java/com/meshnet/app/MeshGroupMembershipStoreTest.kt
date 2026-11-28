package com.meshnet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshGroupMembershipStoreTest {
    private lateinit var store: MeshGroupMembershipStore
    private val group = "a".repeat(64)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("meshnet-group-membership.db")
        store = MeshGroupMembershipStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase("meshnet-group-membership.db")
    }

    @Test
    fun signedJoinAndLeaveProduceLocalActiveThenLeftState() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val join = MeshMembershipEvent.create(identity, group, 1, MeshMembershipEvent.JOIN)
        val leave = MeshMembershipEvent.create(identity, group, 2, MeshMembershipEvent.LEAVE)

        assertTrue(store.append(join))
        assertEquals("ACTIVE", store.roster(group).single().status)
        assertTrue(store.append(leave))
        assertEquals("LEFT", store.roster(group).single().status)
    }

    @Test
    fun exactEventRetryIsIdempotentAndDoesNotDuplicateLog() {
        val event = MeshMembershipEvent.create(
            MeshDeviceIdentity.generateEphemeral(), group, 1, MeshMembershipEvent.JOIN
        )
        assertTrue(store.append(event))
        assertFalse(store.append(event))
        assertEquals(1, store.events(group).size)
    }

    @Test
    fun invalidSignatureAndTamperedGroupBindingAreRejected() {
        val event = MeshMembershipEvent.create(
            MeshDeviceIdentity.generateEphemeral(), group, 1, MeshMembershipEvent.JOIN
        )
        assertFalse(event.copy(type = MeshMembershipEvent.LEAVE).verify())
        assertFalse(event.copy(groupId = "b".repeat(64)).verify())
    }

    @Test
    fun counterGapIsVisibleAndResolvesAfterOfflineMerge() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val join = MeshMembershipEvent.create(identity, group, 1, MeshMembershipEvent.JOIN)
        val leave = MeshMembershipEvent.create(identity, group, 2, MeshMembershipEvent.LEAVE)
        val rejoin = MeshMembershipEvent.create(identity, group, 3, MeshMembershipEvent.JOIN)

        assertTrue(store.append(join))
        assertTrue(store.append(rejoin))
        assertEquals("INCOMPLETE", store.roster(group).single().status)
        assertEquals(1, store.merge(listOf(leave, join)))
        assertEquals("ACTIVE", store.roster(group).single().status)
    }

    @Test
    fun conflictingSameCounterEventsRemainVisibleAsEquivocation() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val first = MeshMembershipEvent.create(identity, group, 1, MeshMembershipEvent.JOIN)
        val conflict = MeshMembershipEvent.create(
            identity, group, 1, MeshMembershipEvent.LEAVE, eventId = "conflicting_event_001"
        )
        assertTrue(store.append(first))
        assertTrue(store.append(conflict))
        assertEquals("CONFLICTED", store.roster(group).single().status)
        assertEquals(2, store.events(group).size)
    }

    @Test
    fun anotherDeviceCannotForgeMemberIdentity() {
        val member = MeshDeviceIdentity.generateEphemeral()
        val attacker = MeshDeviceIdentity.generateEphemeral()
        val valid = MeshMembershipEvent.create(member, group, 1, MeshMembershipEvent.JOIN)
        val forged = valid.copy(
            memberId = attacker.nodeId,
            publicKey = attacker.publicKey
        )
        assertTrue(valid.verify())
        assertFalse(forged.verify())
    }

    @Test
    fun offlineReplicaMergeIsIdempotentAcrossGroups() {
        val identity = MeshDeviceIdentity.generateEphemeral()
        val first = MeshMembershipEvent.create(identity, group, 1, MeshMembershipEvent.JOIN)
        val otherGroup = "c".repeat(64)
        val other = MeshMembershipEvent.create(identity, otherGroup, 1, MeshMembershipEvent.JOIN)
        assertEquals(1, store.merge(listOf(first, other)))
        assertEquals(1, store.events(group).size)
        assertEquals(1, store.events(otherGroup).size)
        assertEquals(0, store.merge(listOf(first, other)))
    }
}
