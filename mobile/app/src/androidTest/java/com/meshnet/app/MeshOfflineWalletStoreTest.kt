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
        val suffix = UUID.randomUUID().toString().replace("-", "")
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

}
