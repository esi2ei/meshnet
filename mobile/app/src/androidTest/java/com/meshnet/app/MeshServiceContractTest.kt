package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest

class MeshServiceContractTest {
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(256)
    }.generateKeyPair()

    private fun address(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unsignedContract(provider: KeyPair, consumer: KeyPair) =
        MeshServiceContract.create(
            id = "contract-id-000001",
            service = "DATA_RELAY",
            provider = address(provider.public.encoded),
            consumer = address(consumer.public.encoded),
            unitPriceMinorPerMiB = 250,
            maxBillableBytes = 8L * 1024 * 1024,
            maxCostMinor = 2000,
            validFrom = 1_800_000_000_000,
            validUntil = 1_800_003_600_000,
            cancellation = "MUTUAL_ONLY",
            relayShares = listOf(MeshServiceContract.RelayShare("relay_1", 2500)),
            providerPublicKey = provider.public.encoded,
            consumerPublicKey = consumer.public.encoded
        )

    @Test
    fun requiresBothPartiesToSignAndRoundTripsEnvelope() {
        val provider = keyPair()
        val consumer = keyPair()
        val providerSigned = unsignedContract(provider, consumer).signAsProvider(provider.private)
        assertFalse(providerSigned.verify())
        val fullySigned = providerSigned.signAsConsumer(consumer.private)
        assertTrue(fullySigned.verify())
        assertTrue(MeshServiceContract.decode(fullySigned.encode()).verify())
    }

    @Test
    fun rejectsTermsChangedAfterSigning() {
        val provider = keyPair()
        val consumer = keyPair()
        val signed = unsignedContract(provider, consumer)
            .signAsProvider(provider.private)
            .signAsConsumer(consumer.private)
        assertFalse(signed.copy(maxCostMinor = signed.maxCostMinor - 1).verify())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRelaySharesAboveOneHundredPercent() {
        val provider = keyPair()
        val consumer = keyPair()
        MeshServiceContract.create(
            "contract-id-000001", "DATA_RELAY",
            address(provider.public.encoded), address(consumer.public.encoded),
            1, 1024, 1, 1, 2, "MUTUAL_ONLY",
            listOf(
                MeshServiceContract.RelayShare("relay_a", 6000),
                MeshServiceContract.RelayShare("relay_b", 5000)
            ),
            provider.public.encoded, consumer.public.encoded
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateRelayEntries() {
        val provider = keyPair()
        val consumer = keyPair()
        MeshServiceContract.create(
            "contract-id-000001", "DATA_RELAY",
            address(provider.public.encoded), address(consumer.public.encoded),
            1, 1024, 1, 1, 2, "MUTUAL_ONLY",
            listOf(
                MeshServiceContract.RelayShare("relay_a", 1000),
                MeshServiceContract.RelayShare("relay_a", 1000)
            ),
            provider.public.encoded, consumer.public.encoded
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedContractEnvelope() {
        MeshServiceContract.decode(" ".repeat(16 * 1024 + 1))
    }
    @Test
    fun proratesPayloadPriceAndRoundsPartialMebibytesUpWithinCap() {
        val provider = keyPair()
        val consumer = keyPair()
        val contract = unsignedContract(provider, consumer)
        assertEquals(0L, contract.maximumChargeFor(0))
        assertEquals(1L, contract.maximumChargeFor(1))
        assertEquals(250L, contract.maximumChargeFor(1024L * 1024L))
        assertEquals(251L, contract.maximumChargeFor(1024L * 1024L + 1))
        assertEquals(2000L, contract.maximumChargeFor(contract.maxBillableBytes))
    }

    @Test
    fun evaluatesContractValidityUsingCallerSuppliedClock() {
        val provider = keyPair()
        val consumer = keyPair()
        val contract = unsignedContract(provider, consumer)
        assertFalse(contract.isActiveAt(contract.validFrom - 1))
        assertTrue(contract.isActiveAt(contract.validFrom))
        assertTrue(contract.isActiveAt(contract.validUntil - 1))
        assertFalse(contract.isActiveAt(contract.validUntil))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPayloadVolumeAboveContractLimit() {
        val provider = keyPair()
        val consumer = keyPair()
        val contract = unsignedContract(provider, consumer)
        contract.maximumChargeFor(contract.maxBillableBytes + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesSignatureFromPrivateKeyThatDoesNotMatchPartyIdentity() {
        val provider = keyPair()
        val consumer = keyPair()
        val impostor = keyPair()
        unsignedContract(provider, consumer).signAsProvider(impostor.private)
    }

}
