package com.meshnet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshGroupDeliveryLedgerTest {
    private lateinit var ledger: MeshGroupDeliveryLedger
    private val group = "d".repeat(64)
    private lateinit var sender: MeshDeviceIdentity
    private lateinit var memberA: MeshDeviceIdentity
    private lateinit var memberB: MeshDeviceIdentity
    private lateinit var snapshot: MeshGroupDeliverySnapshot

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("meshnet-group-delivery.db")
        ledger = MeshGroupDeliveryLedger(context)
        sender = MeshDeviceIdentity.generateEphemeral()
        memberA = MeshDeviceIdentity.generateEphemeral()
        memberB = MeshDeviceIdentity.generateEphemeral()
        snapshot = MeshGroupDeliverySnapshot.create(
            sender,
            "message_snapshot_001",
            group,
            listOf(
                MeshMembershipEvent.create(memberA, group, 1, MeshMembershipEvent.JOIN),
                MeshMembershipEvent.create(memberB, group, 1, MeshMembershipEvent.JOIN),
                MeshMembershipEvent.create(sender, group, 1, MeshMembershipEvent.JOIN)
            ),
            createdAt = System.currentTimeMillis()
        )
        assertTrue(ledger.createSnapshot(snapshot))
    }

    @After
    fun tearDown() {
        ledger.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase("meshnet-group-delivery.db")
    }

    @Test
    fun snapshotIsSignedImmutableAndIdempotent() {
        assertTrue(snapshot.verify())
        assertFalse(ledger.createSnapshot(snapshot))
        val changed = snapshot.copy(recipients = snapshot.recipients.drop(1))
        assertFalse(changed.verify())
        assertNotNull(ledger.snapshot(snapshot.messageId))
    }

    @Test
    fun oneMemberReceiptDoesNotCompleteTwoMemberDelivery() {
        val receipt = MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED)
        assertTrue(ledger.recordReceipt(receipt))
        assertEquals(1 to 2, ledger.progress(snapshot.messageId))
        assertFalse(ledger.allDelivered(snapshot.messageId))
    }

    @Test
    fun allSnapshotMembersMustProvideDeliveredOrReadReceipt() {
        assertTrue(ledger.recordReceipt(
            MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED)
        ))
        assertTrue(ledger.recordReceipt(
            MeshMemberDeliveryReceipt.create(memberB, snapshot, MeshMemberDeliveryReceipt.READ)
        ))
        assertTrue(ledger.allDelivered(snapshot.messageId))
    }

    @Test
    fun acceptedReceiptDoesNotCountAsDeliveredAndReadIsMonotonic() {
        val accepted = MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.ACCEPTED)
        val delivered = MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED)
        assertTrue(ledger.recordReceipt(accepted))
        assertFalse(ledger.allDelivered(snapshot.messageId))
        assertTrue(ledger.recordReceipt(delivered))
        assertEquals(1 to 2, ledger.progress(snapshot.messageId))
        assertEquals(2, ledger.receiptCount(snapshot.messageId))
    }

    @Test
    fun duplicateReceiptIsIdempotentAndReceiptIdEquivocationRejected() {
        val receipt = MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED)
        assertTrue(ledger.recordReceipt(receipt))
        assertFalse(ledger.recordReceipt(receipt))
        val unsignedEquivocation = receipt.copy(type = MeshMemberDeliveryReceipt.READ, signature = "")
        val equivocation = unsignedEquivocation.copy(
            signature = memberA.signDetached("meshnet.group.receipt.v1", unsignedEquivocation.canonicalPayload())
        )
        assertTrue(equivocation.verify())
        assertTrue(runCatching { ledger.recordReceipt(equivocation) }.isFailure)
        assertEquals(1, ledger.receiptCount(snapshot.messageId))
    }

    @Test
    fun nonRecipientAndWrongSnapshotReceiptsAreRejected() {
        val outsider = MeshDeviceIdentity.generateEphemeral()
        val unsignedOutsider = MeshMemberDeliveryReceipt(
            snapshot.messageId, group, outsider.nodeId, outsider.publicKey,
            snapshot.digest, "outsider_receipt_01", MeshMemberDeliveryReceipt.DELIVERED,
            System.currentTimeMillis(), ""
        )
        val notInSnapshot = unsignedOutsider.copy(
            signature = outsider.signDetached("meshnet.group.receipt.v1", unsignedOutsider.canonicalPayload())
        )
        assertTrue(notInSnapshot.verify())
        assertFalse(ledger.recordReceipt(notInSnapshot))
        val unsignedWrongSnapshot = MeshMemberDeliveryReceipt.create(
            memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED
        ).copy(snapshotDigest = "e".repeat(64), signature = "")
        val wrongSnapshot = unsignedWrongSnapshot.copy(
            signature = memberA.signDetached("meshnet.group.receipt.v1", unsignedWrongSnapshot.canonicalPayload())
        )
        assertTrue(wrongSnapshot.verify())
        assertFalse(ledger.recordReceipt(wrongSnapshot))
    }

    @Test
    fun tamperedReceiptFieldsFailSignatureVerification() {
        val receipt = MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED)
        assertFalse(receipt.copy(messageId = "another_message").verify())
        assertFalse(receipt.copy(memberId = memberB.nodeId).verify())
        assertFalse(receipt.copy(groupId = "f".repeat(64)).verify())
    }

    @Test
    fun snapshotAndReceiptLedgerSurviveDatabaseRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receipt = MeshMemberDeliveryReceipt.create(memberA, snapshot, MeshMemberDeliveryReceipt.DELIVERED)
        assertTrue(ledger.recordReceipt(receipt))
        ledger.close()
        ledger = MeshGroupDeliveryLedger(context)
        assertNotNull(ledger.snapshot(snapshot.messageId))
        assertEquals(1 to 2, ledger.progress(snapshot.messageId))
        assertEquals(1, ledger.receiptCount(snapshot.messageId))
    }

    @Test
    fun membersJoiningAfterSnapshotAreNotAddedRetroactively() {
        val lateMember = MeshDeviceIdentity.generateEphemeral()
        val lateJoin = MeshMembershipEvent.create(lateMember, group, 1, MeshMembershipEvent.JOIN)
        assertTrue(lateJoin.verify())
        val persisted = ledger.snapshot(snapshot.messageId) ?: error("Missing snapshot")
        assertEquals(2, persisted.recipients.size)
        assertFalse(persisted.recipients.any { it.memberId == lateMember.nodeId })
    }

    @Test
    fun snapshotRejectsForgedMembershipEvent() {
        val valid = MeshMembershipEvent.create(memberA, group, 1, MeshMembershipEvent.JOIN)
        val forged = valid.copy(memberId = memberB.nodeId)
        val result = runCatching {
            MeshGroupDeliverySnapshot.create(
                sender, "message_snapshot_003", group,
                listOf(forged, MeshMembershipEvent.create(memberB, group, 1, MeshMembershipEvent.JOIN))
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun incompleteOrConflictedRosterCannotBeSnapshotted() {
        val invalid = listOf(
            MeshMembershipEvent.create(memberA, group, 1, MeshMembershipEvent.JOIN),
            MeshMembershipEvent.create(memberB, group, 1, MeshMembershipEvent.JOIN),
            MeshMembershipEvent.create(memberB, group, 3, MeshMembershipEvent.LEAVE)
        )
        val result = runCatching {
            MeshGroupDeliverySnapshot.create(sender, "message_snapshot_002", group, invalid)
        }
        assertTrue(result.isFailure)
    }
}
