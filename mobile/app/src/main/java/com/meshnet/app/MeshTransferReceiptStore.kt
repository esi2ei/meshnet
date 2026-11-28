package com.meshnet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable, bounded and idempotent journal for mutually signed transfer receipts.
 *
 * The caller supplies the signed contract on every operation. The journal verifies the
 * complete receipt chain from genesis before returning or extending it; it never treats
 * a single valid signature as proof of a valid chain or as settled money.
 *
 * The journal stores signed accounting metadata only, not payload bytes or private keys.
 * Its contents are AES-GCM encrypted with a non-exportable Android Keystore key. A missing
 * key or failed authentication is fatal: the journal is never reset to an empty ledger.
 * Android backup is disabled for this app, so the journal is device-install bound and must
 * be exported by an explicit future recovery flow before uninstall or device migration.
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
        val bytes = try {
            journal.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return JSONObject()
        }
        require(bytes.size <= MAX_ENCRYPTED_JOURNAL_BYTES) {
            "Receipt journal exceeds its storage limit; refusing to discard accounting history"
        }

        val raw = bytes.toString(Charsets.UTF_8)
        if (raw.startsWith("$FILE_HEADER\n")) {
            val plaintext = decryptJournal(raw)
            require(plaintext.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES) {
                "Decrypted receipt journal exceeds its storage limit"
            }
            return parseRoot(plaintext)
        }

        // Migrate the previous plaintext JSON format only after it has parsed successfully.
        // If migration cannot be completed, the original AtomicFile is left intact.
        require(bytes.size <= MAX_JOURNAL_BYTES && raw.trimStart().startsWith("{")) {
            "Receipt journal format is unknown; refusing to discard accounting history"
        }
        val legacyRoot = parseRoot(raw)
        writeAtomically(legacyRoot.toString())
        return legacyRoot
    }

    private fun parseRoot(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (error: Exception) {
        throw IllegalStateException(
            "Receipt journal is corrupt; refusing to discard accounting history",
            error
        )
    }

    private fun writeAtomically(encoded: String) {
        val plaintext = encoded.toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_JOURNAL_BYTES) { "Receipt journal storage limit reached" }
        val encrypted = encryptJournal(plaintext)
        require(encrypted.size <= MAX_ENCRYPTED_JOURNAL_BYTES) {
            "Encrypted receipt journal exceeds its storage limit"
        }

        val output = journal.startWrite()
        try {
            output.write(encrypted)
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            throw IllegalStateException("Could not durably persist transfer receipt", error)
        }
    }

    private fun encryptJournal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(FILE_HEADER.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        val encoded = buildString {
            append(FILE_HEADER).append('\n')
            append(encodeBase64(cipher.iv)).append('\n')
            append(encodeBase64(ciphertext))
        }
        return encoded.toByteArray(Charsets.UTF_8)
    }

    private fun decryptJournal(encoded: String): String = try {
        val parts = encoded.split('\n')
        require(parts.size == 3 && parts[0] == FILE_HEADER) {
            "Receipt journal encryption envelope is malformed"
        }
        val iv = decodeBase64(parts[1])
        require(iv.size == GCM_IV_BYTES) { "Receipt journal IV is invalid" }
        val ciphertext = decodeBase64(parts[2])
        require(ciphertext.size >= GCM_TAG_BYTES) { "Receipt journal ciphertext is truncated" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(FILE_HEADER.toByteArray(Charsets.UTF_8))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    } catch (error: Exception) {
        throw IllegalStateException(
            "Receipt journal authentication failed; refusing to discard accounting history",
            error
        )
    }

    private fun getOrCreateKey(): SecretKey {
        loadExistingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadExistingKey(): SecretKey =
        loadExistingKeyOrNull()
            ?: throw IllegalStateException(
                "Receipt journal encryption key is missing; refusing to discard accounting history"
            )

    private fun loadExistingKeyOrNull(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun encodeBase64(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeBase64(value: String): ByteArray {
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(encodeBase64(decoded) == value) { "Receipt journal contains non-canonical base64" }
        return decoded
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
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "meshnet-transfer-receipts-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_HEADER = "MESHNET-TRANSFER-RECEIPTS-V1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val MAX_RECEIPTS = 2048
        private const val MAX_JOURNAL_BYTES = 2 * 1024 * 1024
        private const val MAX_ENCRYPTED_JOURNAL_BYTES = (MAX_JOURNAL_BYTES + 2) / 3 * 4 + 128
        private val PROCESS_LOCK = Any()
    }
}
