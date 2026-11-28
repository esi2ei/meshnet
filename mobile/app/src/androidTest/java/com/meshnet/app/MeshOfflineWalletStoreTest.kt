package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MeshOfflineWalletStoreTest {
    private lateinit var context: Context
    private lateinit var sender: MeshOfflineWalletStore
    private lateinit var senderId: String
    private lateinit var recipientId: String
    private lateinit var recipient: MeshOfflineWalletStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        senderId = "sender-$suffix"
        recipientId = "recipient-$suffix"
        sender = MeshOfflineWalletStore(context, senderId)
        recipient = MeshOfflineWalletStore(context, recipientId)
    }

    @After
    fun tearDown() {
        sender.clear()
        recipient.clear()
    }

    @Test
    fun signedOfflineTransferIsAcceptedByIntendedWallet() {
        val voucher = sender.createPayment(recipient.address(), 1250L)
        val receipt = recipient.acceptPayment(voucher)

        assertEquals(1250L, receipt.amountMinor)
        assertEquals(sender.address(), receipt.sender)
        assertEquals(recipient.address(), receipt.recipient)
        assertEquals(MeshOfflineWalletStore.ASSET_CODE, receipt.asset)
        assertEquals(1, recipient.history().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOfflineTransferIsRejected() {
        val voucher = sender.createPayment(recipient.address(), 10L)
        recipient.acceptPayment(voucher)
        recipient.acceptPayment(voucher)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferTamperingIsRejected() {
        val voucher = sender.createPayment(recipient.address(), 10L)
        val tampered = org.json.JSONObject(voucher).put("amountMinor", 999L).toString()
        recipient.acceptPayment(tampered)
    }

    @Test
    fun walletAddressIsStableAcrossStoreInstances() {
        val address = sender.address()
        assertTrue(address.matches(Regex("[0-9a-f]{64}")))
        assertEquals(address, MeshOfflineWalletStore(context, senderId).address())
    }

    @Test
    fun recipientAcknowledgementIsSignedAndPersistedBySender() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        sender.recordOutgoing(voucher)
        val receipt = recipient.acceptPayment(voucher)
        val acknowledgement = recipient.createAcknowledgement(receipt)

        val confirmed = sender.acceptAcknowledgement(acknowledgement)
        assertEquals(875L, confirmed.amountMinor)
        assertEquals(recipient.address(), confirmed.recipient)
        assertEquals("RECEIVED", confirmed.status)
        assertEquals("RECEIVED", sender.outgoingHistory().single().status)
        assertTrue(sender.pendingOutgoingVouchers().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun acknowledgementTamperingIsRejected() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        sender.recordOutgoing(voucher)
        val receipt = recipient.acceptPayment(voucher)
        val acknowledgement = recipient.createAcknowledgement(receipt)
        val tampered = org.json.JSONObject(acknowledgement)
            .put("amountMinor", 1L)
            .toString()
        sender.acceptAcknowledgement(tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun acknowledgementWithoutMatchingOutgoingVoucherIsRejected() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        val receipt = recipient.acceptPayment(voucher)
        sender.acceptAcknowledgement(recipient.createAcknowledgement(receipt))
    }

    @Test
    fun outgoingTransferJournalSurvivesStoreRecreation() {
        val voucher = sender.createPayment(recipient.address(), 450L)
        sender.recordOutgoing(voucher)
        val reopened = MeshOfflineWalletStore(context, senderId)

        assertEquals("QUEUED", reopened.outgoingHistory().single().status)
        assertEquals(450L, reopened.outgoingHistory().single().amountMinor)
        assertEquals(voucher, reopened.pendingOutgoingVouchers().single().payload)
    }

    @Test
    fun pendingOutgoingVoucherCanBeRetriedUntilRecipientAcknowledges() {
        val voucher = sender.createPayment(recipient.address(), 120L)
        sender.recordOutgoing(voucher)
        val pending = sender.pendingOutgoingVouchers().single()
        assertEquals(org.json.JSONObject(voucher).getString("id"), pending.id)
        assertEquals(voucher, pending.payload)

        val receipt = recipient.acceptPayment(voucher)
        sender.acceptAcknowledgement(recipient.createAcknowledgement(receipt))
        assertTrue(sender.pendingOutgoingVouchers().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun outgoingJournalRejectsVoucherWhoseSignatureWasTampered() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        val tampered = org.json.JSONObject(voucher).put("amountMinor", 1L).toString()
        sender.recordOutgoing(tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outgoingJournalRejectsVoucherSignedByAnotherWallet() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        val other = MeshOfflineWalletStore(context, "other-${UUID.randomUUID().toString().take(8)}")
        try {
            other.recordOutgoing(voucher)
        } finally {
            other.clear()
        }
    }

    @Test
    fun offlineWalletReplayHistoryAndOutgoingVoucherAreEncryptedAtRest() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        sender.recordOutgoing(voucher)
        val receipt = recipient.acceptPayment(voucher)

        val senderPreferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$senderId", Context.MODE_PRIVATE
        )
        val recipientPreferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$recipientId", Context.MODE_PRIVATE
        )
        val outgoing = senderPreferences.getString("outgoing_payment_history", null)
        val replayIds = recipientPreferences.getString("received_payment_ids", null)
        val history = recipientPreferences.getString("received_payment_history", null)

        assertTrue(outgoing?.startsWith("MNW1|") == true)
        assertTrue(replayIds?.startsWith("MNW1|") == true)
        assertTrue(history?.startsWith("MNW1|") == true)
        assertFalse(outgoing!!.contains(voucher))
        assertFalse(history!!.contains(receipt.id))

        val reopenedSender = MeshOfflineWalletStore(context, senderId)
        val reopenedRecipient = MeshOfflineWalletStore(context, recipientId)
        assertEquals(voucher, reopenedSender.pendingOutgoingVouchers().single().payload)
        assertEquals(receipt.id, reopenedRecipient.history().single().id)
    }

    @Test
    fun legacyPlaintextWalletHistoryMigratesOnFirstRead() {
        val preferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$senderId", Context.MODE_PRIVATE
        )
        assertTrue(preferences.edit().putString("outgoing_payment_history", "[]").commit())

        assertTrue(MeshOfflineWalletStore(context, senderId).outgoingHistory().isEmpty())
        assertTrue(
            preferences.getString("outgoing_payment_history", null)?.startsWith("MNW1|") == true
        )
    }

    @Test
    fun missingWalletStorageKeyDoesNotResetOutgoingHistory() {
        val voucher = sender.createPayment(recipient.address(), 120L)
        sender.recordOutgoing(voucher)
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("meshnet-offline-wallet-storage-$senderId")

        assertFalse(runCatching { sender.pendingOutgoingVouchers() }.isSuccess)
    }

    @Test
    fun tamperedWalletCiphertextFailsClosed() {
        val voucher = sender.createPayment(recipient.address(), 120L)
        sender.recordOutgoing(voucher)
        val preferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$senderId", Context.MODE_PRIVATE
        )
        val original = preferences.getString("outgoing_payment_history", null)!!
        val tampered = original.dropLast(1) + if (original.last() == 'A') "B" else "A"
        assertTrue(preferences.edit().putString("outgoing_payment_history", tampered).commit())

        assertFalse(runCatching { sender.outgoingHistory() }.isSuccess)
    }

    @Test
    fun malformedOutgoingRecordFailsClosedInsteadOfBeingOmitted() {
        val preferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$senderId", Context.MODE_PRIVATE
        )
        assertTrue(preferences.edit().putString("outgoing_payment_history", "[{}]").commit())

        val reopened = MeshOfflineWalletStore(context, senderId)
        assertFalse(runCatching { reopened.outgoingHistory() }.isSuccess)
        assertFalse(runCatching { reopened.pendingOutgoingVouchers() }.isSuccess)
    }

    @Test
    fun malformedReceiptHistoryFailsClosedInsteadOfBeingOmitted() {
        val preferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$recipientId", Context.MODE_PRIVATE
        )
        assertTrue(preferences.edit().putString("received_payment_history", "[{}]").commit())

        val reopened = MeshOfflineWalletStore(context, recipientId)
        assertFalse(runCatching { reopened.history() }.isSuccess)
    }

    @Test
    fun partialVoucherAndAcknowledgementChunksAreEncryptedAtRest() {
        val pending = """[{"id":"voucher_123","count":2,"prefix":"MESHOP1","createdAt":1700000000000,"chunks":{"0":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}]"""
        sender.storePendingEnvelopeState(pending)

        val preferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$senderId", Context.MODE_PRIVATE
        )
        val stored = preferences.getString("pending_offline_payment_envelopes", null)
        assertTrue(stored?.startsWith("MNW1|") == true)
        assertFalse(stored!!.contains("voucher_123"))
        assertFalse(stored.contains("MESHOP1"))
        assertEquals(pending, MeshOfflineWalletStore(context, senderId).loadPendingEnvelopeState())
    }

    @Test
    fun pendingEnvelopeStateFailsClosedWhenStorageKeyIsMissing() {
        sender.storePendingEnvelopeState("""[{"id":"voucher_123"}]""")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("meshnet-offline-wallet-storage-$senderId")

        assertFalse(runCatching { MeshOfflineWalletStore(context, senderId).loadPendingEnvelopeState() }.isSuccess)
    }

    @Test
    fun pendingEnvelopeStateRejectsOversizedOrMalformedJson() {
        assertFalse(runCatching {
            sender.storePendingEnvelopeState("{")
        }.isSuccess)
        assertFalse(runCatching {
            sender.storePendingEnvelopeState("[" + " ".repeat(600 * 1024) + "]")
        }.isSuccess)
    }

    @Test
    fun writingNewWalletStateDoesNotGenerateReplacementKeyWhenEncryptedRecordsRemain() {
        sender.storePendingEnvelopeState("""[{"id":"voucher_123","count":1,"createdAt":1700000000000,"prefix":"MESHOP1","chunks":{"0":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}]""")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("meshnet-offline-wallet-storage-$senderId")

        assertFalse(runCatching {
            sender.storePendingEnvelopeState("[]")
        }.isSuccess)
        assertFalse(runCatching {
            MeshOfflineWalletStore(context, senderId).loadPendingEnvelopeState()
        }.isSuccess)

        val preferences = context.getSharedPreferences(
            "meshnet-offline-wallet-v1-$senderId", Context.MODE_PRIVATE
        )
        assertTrue(preferences.getString("pending_offline_payment_envelopes", null)?.startsWith("MNW1|") == true)
    }

    @Test
    fun missingSigningKeyDoesNotGenerateReplacementWalletIdentity() {
        val voucher = sender.createPayment(recipient.address(), 875L)
        sender.recordOutgoing(voucher)
        val alias = "meshnet-offline-wallet-signing-$senderId"
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("The sender signing key must exist before simulating key loss", keyStore.containsAlias(alias))
        keyStore.deleteEntry(alias)

        val reopened = MeshOfflineWalletStore(context, senderId)
        val failure = runCatching { reopened.address() }.exceptionOrNull()
        assertTrue("A missing signing key with retained wallet records must fail closed", failure is IllegalStateException)
        assertTrue(
            "The error must explain why a new wallet identity was not created",
            failure?.message?.contains("signing key is missing") == true
        )
        val refreshedKeyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("The missing wallet identity must not be silently regenerated", refreshedKeyStore.containsAlias(alias))
        assertFalse(
            "Pending vouchers must not be exposed under a replacement wallet identity",
            runCatching { reopened.pendingOutgoingVouchers() }.isSuccess
        )
    }

}
