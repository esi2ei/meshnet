package com.meshnet.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES, Context.MODE_PRIVATE
    )

    /** Returns true when appended, false when the exact same receipt was already stored. */
    fun append(contract: MeshServiceContract, receipt: MeshTransferReceipt): Boolean =
        synchronized(PROCESS_LOCK) {
            require(contract.verify()) { "Cannot journal receipts for an invalid contract" }
            val root = readRoot()
            val key = contract.id
            val chain = decodeAndVerifyChain(root.optJSONArray(key) ?: JSONArray(), contract)

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
            val totalCount = root.keys().asSequence().sumOf { root.optJSONArray(it)?.length() ?: 0 }
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
            check(preferences.edit().putString(KEY_JOURNAL, encoded).commit()) {
                "Could not durably persist transfer receipt"
            }
            true
        }

    /** Returns a fully verified chain, or fails closed if stored data is corrupt. */
    fun verifiedChain(contract: MeshServiceContract): List<MeshTransferReceipt> =
        synchronized(PROCESS_LOCK) {
            require(contract.verify()) { "Cannot read receipts for an invalid contract" }
            decodeAndVerifyChain(readRoot().optJSONArray(contract.id) ?: JSONArray(), contract)
        }

    private fun readRoot(): JSONObject {
        val raw = preferences.getString(KEY_JOURNAL, "{}") ?: "{}"
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES) {
            "Receipt journal exceeds its storage limit; refusing to discard accounting history"
        }
        return try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Receipt journal is corrupt; refusing to discard accounting history", error)
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
        private const val PREFERENCES = "meshnet-transfer-receipt-journal-v1"
        private const val KEY_JOURNAL = "chains"
        private const val MAX_RECEIPTS = 2048
        private const val MAX_JOURNAL_BYTES = 2 * 1024 * 1024
        private val PROCESS_LOCK = Any()
    }
}
