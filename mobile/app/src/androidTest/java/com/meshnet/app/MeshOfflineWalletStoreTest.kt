package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MeshOfflineWalletStoreTest {
    private lateinit var context: Context
    private lateinit var sender: MeshOfflineWalletStore
    private lateinit var senderId: String
    private lateinit var recipient: MeshOfflineWalletStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        senderId = "sender-$suffix"
        sender = MeshOfflineWalletStore(context, senderId)
        recipient = MeshOfflineWalletStore(context, "recipient-$suffix")
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

}
