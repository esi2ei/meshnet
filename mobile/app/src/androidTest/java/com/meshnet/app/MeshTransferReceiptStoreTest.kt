package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.security.KeyStore

class MeshTransferReceiptStoreTest {
    private lateinit var context: Context
    private lateinit var provider: KeyPair
    private lateinit var consumer: KeyPair
    private lateinit var contract: MeshServiceContract

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = keyPair()
        consumer = keyPair()
        val now = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        contract = MeshServiceContract.create(
            "contract_$suffix", "DATA_TRANSFER", address(provider), address(consumer),
            100L, 10L * 1024 * 1024, 1000L, now - 60_000, now + 60_000,
            "MUTUAL_ONLY", emptyList(), provider.public.encoded, consumer.public.encoded
        ).signAsProvider(provider.private).signAsConsumer(consumer.private)
        AtomicFile(File(context.filesDir, JOURNAL_FILE)).delete()
        deleteJournalKey()
    }

    @org.junit.After
    fun tearDown() {
        AtomicFile(File(context.filesDir, JOURNAL_FILE)).delete()
        deleteJournalKey()
    }

    @Test
    fun appendIsDurableAndExactRetryIsIdempotent() {
        val store = MeshTransferReceiptStore(context)
        val first = receipt(1, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH)
        assertTrue(store.append(contract, first))
        assertFalse(store.append(contract, first))

        val reopened = MeshTransferReceiptStore(context)
        assertEquals(listOf(first.encode()), reopened.verifiedChain(contract).map { it.encode() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingReceiptAtExistingSequenceIsRejected() {
        val store = MeshTransferReceiptStore(context)
        val first = receipt(1, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH)
        store.append(contract, first)
        store.append(contract, receipt(1, 2048, 2048, MeshTransferReceipt.GENESIS_RECEIPT_HASH))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sequenceGapCannotBePersisted() {
        val store = MeshTransferReceiptStore(context)
        store.append(contract, receipt(2, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH))
    }

    @Test(expected = IllegalStateException::class)
    fun corruptJournalFailsClosedInsteadOfAppearingEmpty() {
        File(context.filesDir, "meshnet-transfer-receipts.json").writeText("{")
        MeshTransferReceiptStore(context).verifiedChain(contract)
    }

    @Test
    fun malformedPerContractJournalEntryFailsClosed() {
        val root = org.json.JSONObject().put(contract.id, "not-an-array")
        File(context.filesDir, "meshnet-transfer-receipts.json").writeText(root.toString())

        assertFalse(runCatching {
            MeshTransferReceiptStore(context).verifiedChain(contract)
        }.isSuccess)
    }

    @Test
    fun secondReceiptMustExtendPersistedHashAndCumulativeTotals() {
        val store = MeshTransferReceiptStore(context)
        val first = receipt(1, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH)
        store.append(contract, first)
        val previousHash = digest(first.encode())
        val second = receipt(2, 2048, 3072, previousHash)
        assertTrue(store.append(contract, second))
        assertEquals(2, store.verifiedChain(contract).size)
    }

    private fun receipt(sequence: Long, delta: Long, total: Long, previousHash: String): MeshTransferReceipt {
        val unsigned = MeshTransferReceipt.create(
            contract, sequence, delta, total, contract.maximumChargeFor(total),
            digest("payload-$sequence"), previousHash, contract.validFrom + sequence
        )
        return unsigned.signAsProvider(contract, provider.private)
            .signAsConsumer(contract, consumer.private)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun address(pair: KeyPair): String = digestBytes(pair.public.encoded)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun digestBytes(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    
    @Test
    fun journalIsEncryptedAtRestAndSurvivesReopening() {
        val store = MeshTransferReceiptStore(context)
        val first = receipt(1, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH)
        assertTrue(store.append(contract, first))

        val file = File(context.filesDir, JOURNAL_FILE)
        val stored = file.readText()
        assertTrue(stored.startsWith("MESHNET-TRANSFER-RECEIPTS-V1\\n"))
        assertFalse(stored.contains(contract.id))
        assertFalse(stored.contains(first.encode()))

        val reopened = MeshTransferReceiptStore(context)
        assertEquals(listOf(first.encode()), reopened.verifiedChain(contract).map { it.encode() })
    }

    @Test
    fun legacyPlaintextJournalMigratesToEncryptedStorage() {
        val file = File(context.filesDir, JOURNAL_FILE)
        file.writeText("{}")

        assertTrue(MeshTransferReceiptStore(context).verifiedChain(contract).isEmpty())

        val migrated = file.readText()
        assertTrue(migrated.startsWith("MESHNET-TRANSFER-RECEIPTS-V1\\n"))
        assertFalse(migrated.trimStart().startsWith("{"))
    }

    @Test(expected = IllegalStateException::class)
    fun missingKeystoreKeyDoesNotResetEncryptedJournal() {
        val store = MeshTransferReceiptStore(context)
        val first = receipt(1, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH)
        store.append(contract, first)
        deleteJournalKey()

        MeshTransferReceiptStore(context).verifiedChain(contract)
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val store = MeshTransferReceiptStore(context)
        val first = receipt(1, 1024, 1024, MeshTransferReceipt.GENESIS_RECEIPT_HASH)
        store.append(contract, first)

        val file = File(context.filesDir, JOURNAL_FILE)
        val original = file.readText()
        val tampered = original.dropLast(1) + if (original.last() == 'A') "B" else "A"
        file.writeText(tampered)

        assertFalse(runCatching {
            MeshTransferReceiptStore(context).verifiedChain(contract)
        }.isSuccess)
    }

    private fun deleteJournalKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(JOURNAL_KEY_ALIAS)) {
            keyStore.deleteEntry(JOURNAL_KEY_ALIAS)
        }
    }

    companion object {
        private const val JOURNAL_FILE = "meshnet-transfer-receipts.json"
        private const val JOURNAL_KEY_ALIAS = "meshnet-transfer-receipts-v1"
    }

}
