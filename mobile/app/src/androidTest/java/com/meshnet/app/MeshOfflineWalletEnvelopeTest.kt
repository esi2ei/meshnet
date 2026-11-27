package com.meshnet.app

import org.junit.Test

class MeshOfflineWalletEnvelopeTest {
    @Test
    fun acceptsPaymentWhenEnvelopeIdMatchesVoucherId() {
        validateOfflineWalletEnvelopeId(
            "voucher-123",
            """{"id":"voucher-123","sender":"sender","recipient":"recipient","amountMinor":1}""",
            false
        )
    }

    @Test
    fun acceptsAcknowledgementWhenEnvelopeIdMatchesVoucherId() {
        validateOfflineWalletEnvelopeId(
            "voucher-456",
            """{"voucherId":"voucher-456","sender":"sender","recipient":"recipient","amountMinor":1}""",
            true
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPaymentWithMismatchedEnvelopeId() {
        validateOfflineWalletEnvelopeId("transport-id", """{"id":"signed-voucher-id"}""", false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAcknowledgementWithMismatchedEnvelopeId() {
        validateOfflineWalletEnvelopeId("transport-id", """{"voucherId":"signed-voucher-id"}""", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyEnvelopeId() {
        validateOfflineWalletEnvelopeId("", """{"id":""}""", false)
    }
}