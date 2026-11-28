package com.meshnet.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Durable, bounded and idempotent journal for mutually signed transfer receipts.
 *
 * The caller supplies the signed contract on every operation. The journal verifies the
 * complete receipt chain from genesis before returning or extending it; it never treats
 * a single valid signature as proof of a valid chain or as settled money.
 *
 * Receipts contain public accounting data only. No private signing keys are persisted.
 */
internal class MeshTransferReceiptStore(context: Context) {
    private val journal = AtomicFile(
        File(context.applicationContext.filesDir, JOURNAL_FILE)
    )

    /** Returns true when appended, false when the exact same receipt was already stored. */
    fun append(contract: MeshServiceContract, receipt: MeshTransferReceipt): Boolean =
        synchronized(PROCESS_LOCK) {
            require(contract.verify()) { "Cannot journal receipts for an invalid contract" }
            val root = readRoot()
            val key = contract.id
            val chain = decodeAndVerifyChain(readContractChain(root, key), contract)

            val existing = chain.firstOrNull { it.sequence == receipt.sequence }
            if (existing != null) {
                require(existing.encode() == receipt.encode()) {
                    "Conflicting receipt already exists at this sequence"
                }
                return@synchronized false
            }

            val previous = chain.lastOrNull()
            require(receipt.verifyNext(contract, previous)) {
                "Receipt does not extend the verified contract chain"
            }
            val storedContractIds = root.keys().asSequence().toList()
            require(storedContractIds.all { it.matches(Regex("[A-Za-z0-9_-]{16,64}")) }) {
                "Receipt journal contains an invalid contract key"
            }
            val totalCount = storedContractIds.sumOf { root.getJSONArray(it).length() }
            require(totalCount < MAX_RECEIPTS) {
                "Receipt journal is full; export and reconcile before accepting more receipts"
            }

            val updated = JSONArray().apply {
                chain.forEach { put(it.encode()) }
                put(receipt.encode())
            }
            root.put(key, updated)
            val encoded = root.toString()
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES) {
                "Receipt journal storage limit reached"
            }
            writeAtomically(encoded)
            true
        }

    /** Returns a fully verified chain, or fails closed if stored data is corrupt. */
    fun verifiedChain(contract: MeshServiceContract): List<MeshTransferReceipt> =
        synchronized(PROCESS_LOCK) {
            require(contract.verify()) { "Cannot read receipts for an invalid contract" }
            decodeAndVerifyChain(readContractChain(readRoot(), contract.id), contract)
        }

    private fun readContractChain(root: JSONObject, contractId: String): JSONArray {
        if (!root.has(contractId)) return JSONArray()
        // A present-but-malformed entry is corruption, not an empty chain.
        return root.getJSONArray(contractId)
    }

    private fun readRoot(): JSONObject {
        val raw = try {
            journal.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            "{}"
        }
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES) {
            "Receipt journal exceeds its storage limit; refusing to discard accounting history"
        }
        return try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Receipt journal is corrupt; refusing to discard accounting history", error)
        }
    }

    private fun writeAtomically(encoded: String) {
        val output = journal.startWrite()
        try {
            output.write(encoded.toByteArray(Charsets.UTF_8))
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            throw IllegalStateException("Could not durably persist transfer receipt", error)
        }
    }

    private fun decodeAndVerifyChain(
        encodedReceipts: JSONArray,
        contract: MeshServiceContract
    ): List<MeshTransferReceipt> {
        require(encodedReceipts.length() <= MAX_RECEIPTS) { "Receipt chain exceeds journal limit" }
        val verified = ArrayList<MeshTransferReceipt>(encodedReceipts.length())
        var previous: MeshTransferReceipt? = null
        for (index in 0 until encodedReceipts.length()) {
            val encoded = encodedReceipts.getString(index)
            val receipt = MeshTransferReceipt.decode(encoded)
            require(receipt.verifyNext(contract, previous)) {
                "Stored receipt chain failed verification at index $index"
            }
            require(verified.none { it.sequence == receipt.sequence }) {
                "Stored receipt chain contains a duplicate sequence"
            }
            verified.add(receipt)
            previous = receipt
        }
        return verified
    }

    companion object {
        private const val JOURNAL_FILE = "meshnet-transfer-receipts.json"
        private const val MAX_RECEIPTS = 2048
        private const val MAX_JOURNAL_BYTES = 2 * 1024 * 1024
        private val PROCESS_LOCK = Any()
    }
}
