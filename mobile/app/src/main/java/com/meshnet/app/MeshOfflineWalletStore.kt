package com.meshnet.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Offline signed payment-request/receipt envelope.
 *
 * This is deliberately NOT a money ledger: an offline device cannot establish global
 * balance or prevent the same value being signed for multiple recipients. Amounts are
 * local demonstration units and receipts remain provisional until an authoritative
 * settlement system exists.
 */
class MeshOfflineWalletStore(context: Context, walletId: String = "primary") {
    private val walletId = walletId.also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) { "Invalid local wallet ID" } }
    private val keyAlias = "meshnet-offline-wallet-signing-$walletId"
    private val storageKeyAlias = "meshnet-offline-wallet-storage-$walletId"
    data class Receipt(
        val id: String,
        val sender: String,
        val recipient: String,
        val amountMinor: Long,
        val asset: String,
        val createdAt: Long
    )

    private val preferences = context.applicationContext
        .getSharedPreferences("$PREFERENCES-$walletId", Context.MODE_PRIVATE)

    @Synchronized
    fun address(): String = addressFor(identity().public.encoded)

    @Synchronized
    fun createPayment(recipientAddress: String, amountMinor: Long): String {
        require(recipientAddress.matches(Regex("[0-9a-f]{64}"))) { "Recipient wallet address is invalid" }
        require(amountMinor in 1..MAX_AMOUNT_MINOR) { "Amount must be a positive supported integer" }
        val pair = identity()
        val senderAddress = addressFor(pair.public.encoded)
        require(recipientAddress != senderAddress) { "Cannot transfer to the same wallet" }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val unsigned = JSONObject()
            .put("version", VERSION)
            .put("id", id)
            .put("asset", ASSET_CODE)
            .put("amountMinor", amountMinor)
            .put("sender", senderAddress)
            .put("senderPublicKey", encode(pair.public.encoded))
            .put("recipient", recipientAddress)
            .put("createdAt", createdAt)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(pair.private)
            update(canonical(unsigned))
            sign()
        }
        return JSONObject(unsigned.toString())
            .put("signature", encode(signature))
            .toString()
    }

    @Synchronized
    fun acceptPayment(payload: String): Receipt {
        val json = verifyPaymentPayload(payload)
        val id = json.getString("id")
        val amount = json.getLong("amountMinor")
        val sender = json.getString("sender")
        val recipient = json.getString("recipient")
        require(recipient == address()) { "This payment is addressed to a different wallet" }
        val createdAt = json.getLong("createdAt")

        val received = readIds()
        require(id !in received) { "This offline payment was already imported on this device" }
        // Fail closed rather than evict replay IDs: forgetting an old ID could make a
        // previously accepted offline voucher importable again after storage pressure.
        require(received.size < MAX_RECEIVED_PAYMENTS) {
            "Offline receipt protection is full; reconcile/export wallet history before accepting more vouchers"
        }
        val history = readHistory()
        require(history.length() < MAX_HISTORY_ENTRIES) {
            "Offline wallet receipt history is full; export or reconcile it before accepting more vouchers"
        }
        received.add(id)
        val receipt = Receipt(id, sender, recipient, amount, ASSET_CODE, createdAt)
        history.put(
            JSONObject()
                .put("id", receipt.id)
                .put("sender", receipt.sender)
                .put("recipient", receipt.recipient)
                .put("amountMinor", receipt.amountMinor)
                .put("asset", receipt.asset)
                .put("createdAt", receipt.createdAt)
        )
        val encodedIds = JSONArray().apply { received.sorted().forEach { put(it) } }.toString()
        check(preferences.edit()
            .putString(KEY_RECEIVED_IDS, encryptPreference(KEY_RECEIVED_IDS, encodedIds))
            .putString(KEY_HISTORY, encryptPreference(KEY_HISTORY, history.toString()))
            .commit()) { "Could not persist encrypted offline payment receipt and replay protection" }
        return receipt
    }

    data class OutgoingTransfer(
        val id: String,
        val recipient: String,
        val amountMinor: Long,
        val createdAt: Long,
        val status: String
    )

    data class PendingVoucher(val id: String, val payload: String)

    /** Persist the signed voucher before any radio send is attempted. */
    @Synchronized
    fun recordOutgoing(payload: String) {
        val json = verifyPaymentPayload(payload)
        require(json.getString("sender") == address()) { "Outgoing voucher belongs to another wallet" }
        val id = json.getString("id")
        val recipient = json.getString("recipient")
        val amount = json.getLong("amountMinor")
        val createdAt = json.getLong("createdAt")
        val entries = readOutgoing()
        require((0 until entries.length()).none { entries.optJSONObject(it)?.optString("id") == id }) {
            "Outgoing voucher is already recorded"
        }
        require(entries.length() < MAX_OUTGOING_ENTRIES) {
            "Offline outgoing history is full; export or reconcile it before creating more vouchers"
        }
        entries.put(JSONObject()
            .put("id", id)
            .put("recipient", recipient)
            .put("amountMinor", amount)
            .put("createdAt", createdAt)
            .put("payload", payload)
            .put("status", "QUEUED"))
        check(preferences.edit()
            .putString(KEY_OUTGOING, encryptPreference(KEY_OUTGOING, entries.toString()))
            .commit()) { "Could not persist encrypted outgoing offline transfer" }
    }

    /** The recipient signs an application-level acknowledgement after its receipt is durable. */
    @Synchronized
    fun createAcknowledgement(receipt: Receipt): String {
        require(receipt.recipient == address()) { "Receipt belongs to another recipient wallet" }
        val pair = identity()
        val unsigned = JSONObject()
            .put("version", VERSION)
            .put("voucherId", receipt.id)
            .put("sender", receipt.sender)
            .put("recipient", receipt.recipient)
            .put("amountMinor", receipt.amountMinor)
            .put("recipientPublicKey", encode(pair.public.encoded))
            .put("acknowledgedAt", System.currentTimeMillis())
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(pair.private)
            update(canonicalAcknowledgement(unsigned))
            sign()
        }
        return JSONObject(unsigned.toString()).put("signature", encode(signature)).toString()
    }

    /** Verify that the actual recipient wallet acknowledged the exact queued voucher. */
    @Synchronized
    fun acceptAcknowledgement(payload: String): OutgoingTransfer {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Acknowledgement is too large" }
        val json = JSONObject(payload)
        require(json.optInt("version", -1) == VERSION) { "Unsupported acknowledgement version" }
        val id = json.getString("voucherId")
        val sender = json.getString("sender")
        val recipient = json.getString("recipient")
        val amount = json.getLong("amountMinor")
        val acknowledgedAt = json.getLong("acknowledgedAt")
        require(sender == address()) { "Acknowledgement is addressed to another sender wallet" }
        require(amount in 1..MAX_AMOUNT_MINOR && acknowledgedAt > 0L)
        val recipientKey = Base64.decode(json.getString("recipientPublicKey"), Base64.URL_SAFE or Base64.NO_WRAP)
        require(addressFor(recipientKey) == recipient) { "Recipient wallet address does not match its public key" }
        val publicKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(recipientKey))
        val signatureBytes = Base64.decode(json.getString("signature"), Base64.URL_SAFE or Base64.NO_WRAP)
        val unsigned = JSONObject(json.toString()).apply { remove("signature") }
        val valid = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(canonicalAcknowledgement(unsigned))
            verify(signatureBytes)
        }
        require(valid) { "Recipient acknowledgement signature is invalid" }

        val entries = readOutgoing()
        val index = (0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("id") == id }
            ?: throw IllegalArgumentException("Acknowledgement does not match a queued outgoing voucher")
        val item = entries.getJSONObject(index)
        require(item.getString("recipient") == recipient && item.getLong("amountMinor") == amount) {
            "Acknowledgement does not match the queued recipient and amount"
        }
        item.put("status", "RECEIVED").put("acknowledgedAt", acknowledgedAt)
        check(preferences.edit()
            .putString(KEY_OUTGOING, encryptPreference(KEY_OUTGOING, entries.toString()))
            .commit()) { "Could not persist encrypted recipient acknowledgement" }
        return OutgoingTransfer(id, recipient, amount, item.getLong("createdAt"), "RECEIVED")
    }

    /** Return exact signed vouchers that still need a recipient acknowledgement. */
    @Synchronized
    fun pendingOutgoingVouchers(): List<PendingVoucher> {
        val array = readOutgoing()
        require(array.length() <= MAX_OUTGOING_ENTRIES) { "Offline wallet outgoing history exceeds its limit" }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val status = item.getString("status")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && status in OUTGOING_STATUSES) {
                    "Offline wallet outgoing entry $index is malformed"
                }
                val recipient = item.getString("recipient")
                val amount = item.getLong("amountMinor")
                val createdAt = item.getLong("createdAt")
                require(recipient.matches(Regex("[0-9a-f]{64}")) &&
                    amount in 1..MAX_AMOUNT_MINOR && createdAt > 0L) {
                    "Offline wallet outgoing entry $index has invalid terms"
                }
                val payload = item.getString("payload")
                val voucher = verifyPaymentPayload(payload)
                require(voucher.getString("id") == id &&
                    voucher.getString("sender") == address() &&
                    voucher.getString("recipient") == recipient &&
                    voucher.getLong("amountMinor") == amount &&
                    voucher.getLong("createdAt") == createdAt) {
                    "Offline wallet outgoing entry $index does not match its signed voucher"
                }
                if (status != "RECEIVED") add(PendingVoucher(id, payload))
            }
        }
    }

    @Synchronized
    fun outgoingHistory(): List<OutgoingTransfer> {
        val array = readOutgoing()
        require(array.length() <= MAX_OUTGOING_ENTRIES) { "Offline wallet outgoing history exceeds its limit" }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val status = item.getString("status")
                val recipient = item.getString("recipient")
                val amount = item.getLong("amountMinor")
                val createdAt = item.getLong("createdAt")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                    recipient.matches(Regex("[0-9a-f]{64}")) &&
                    amount in 1..MAX_AMOUNT_MINOR && createdAt > 0L &&
                    status in OUTGOING_STATUSES) {
                    "Offline wallet outgoing entry $index is malformed"
                }
                add(OutgoingTransfer(id, recipient, amount, createdAt, status))
            }
        }
    }

    private fun readOutgoing(): JSONArray =
        runCatching { JSONArray(readEncryptedPreference(KEY_OUTGOING, "[]")) }
            .getOrElse { throw IllegalStateException("Offline wallet outgoing history is corrupt", it) }

    private fun canonicalAcknowledgement(json: JSONObject): ByteArray {
        val fields = listOf("version", "voucherId", "sender", "recipient", "amountMinor", "recipientPublicKey", "acknowledgedAt")
        return fields.joinToString("\n") { key -> "$key=${json.get(key)}" }.toByteArray(Charsets.UTF_8)
    }

    @Synchronized
    fun receiptById(id: String): Receipt? = history().firstOrNull { it.id == id }

    @Synchronized
    fun history(): List<Receipt> {
        val array = readHistory()
        require(array.length() <= MAX_HISTORY_ENTRIES) { "Offline wallet receipt history exceeds its limit" }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val receipt = Receipt(
                    item.getString("id"), item.getString("sender"), item.getString("recipient"),
                    item.getLong("amountMinor"), item.getString("asset"), item.getLong("createdAt")
                )
                require(receipt.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                    receipt.sender.matches(Regex("[0-9a-f]{64}")) &&
                    receipt.recipient.matches(Regex("[0-9a-f]{64}")) &&
                    receipt.amountMinor in 1..MAX_AMOUNT_MINOR &&
                    receipt.asset == ASSET_CODE && receipt.createdAt > 0L) {
                    "Offline wallet receipt history entry $index is malformed"
                }
                add(receipt)
            }
        }
    }

    /** Load persisted, incomplete voucher/ack chunks from the wallet-encrypted store. */
    @Synchronized
    fun loadPendingEnvelopeState(): String {
        val encoded = readEncryptedPreference(KEY_PENDING_ENVELOPES, "[]")
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PENDING_ENVELOPE_BYTES) {
            "Pending wallet envelope state exceeds its storage limit"
        }
        val entries = JSONArray(encoded)
        require(entries.length() <= MAX_PENDING_ENVELOPES) {
            "Too many pending wallet envelopes"
        }
        return encoded
    }

    /** Persist partial payment/ack payload chunks using the wallet's Keystore AES-GCM key. */
    @Synchronized
    fun storePendingEnvelopeState(encoded: String) {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PENDING_ENVELOPE_BYTES) {
            "Pending wallet envelope state exceeds its storage limit"
        }
        require(JSONArray(encoded).length() <= MAX_PENDING_ENVELOPES) {
            "Too many pending wallet envelopes"
        }
        check(preferences.edit()
            .putString(KEY_PENDING_ENVELOPES, encryptPreference(KEY_PENDING_ENVELOPES, encoded))
            .commit()) { "Could not persist encrypted pending wallet envelopes" }
    }

    @Synchronized
    fun hasPendingEnvelopeState(): Boolean = preferences.contains(KEY_PENDING_ENVELOPES)

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear offline wallet data" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        if (keyStore.containsAlias(storageKeyAlias)) keyStore.deleteEntry(storageKeyAlias)
    }

    private fun identity(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(keyAlias, null) as? java.security.PrivateKey
        val certificate = keyStore.getCertificate(keyAlias)
        if (privateKey != null && certificate != null) return KeyPair(certificate.publicKey, privateKey)
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        generator.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or
                    android.security.keystore.KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    /**
     * Verify the exact signed voucher before either accepting it or placing it in the
     * outgoing retry journal. A caller cannot persist an unsigned or forged "pending" payment.
     */
    private fun verifyPaymentPayload(payload: String): JSONObject {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Payment payload is too large" }
        val json = JSONObject(payload)
        require(json.optInt("version", -1) == VERSION) { "Unsupported offline payment version" }
        require(json.optString("asset") == ASSET_CODE) { "Unsupported offline asset" }
        require(json.getString("id").matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid payment identifier" }
        require(json.getLong("amountMinor") in 1..MAX_AMOUNT_MINOR) { "Invalid payment amount" }
        require(json.getString("sender").matches(Regex("[0-9a-f]{64}"))) { "Invalid sender wallet address" }
        require(json.getString("recipient").matches(Regex("[0-9a-f]{64}"))) { "Invalid recipient wallet address" }
        require(json.getLong("createdAt") > 0L) { "Invalid payment timestamp" }
        val senderPublicKey = decodeCanonicalBase64(json.getString("senderPublicKey"))
        require(addressFor(senderPublicKey) == json.getString("sender")) {
            "Sender wallet address does not match its public key"
        }
        val publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(senderPublicKey))
        val signatureBytes = decodeCanonicalBase64(json.getString("signature"))
        require(signatureBytes.size in 64..80) { "Invalid offline payment signature length" }
        val unsigned = JSONObject(json.toString()).apply { remove("signature") }
        val valid = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(canonical(unsigned))
            verify(signatureBytes)
        }
        require(valid) { "Offline payment signature is invalid" }
        return json
    }

    private fun decodeCanonicalBase64(value: String): ByteArray {
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val canonical = Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(canonical == value) { "Non-canonical offline wallet encoding" }
        return decoded
    }

    /**
     * Wallet records are encrypted separately per preference key so values cannot be swapped
     * between replay protection, history and outgoing vouchers without GCM authentication failing.
     * Legacy plaintext values are migrated on first read; malformed encrypted data and a missing
     * Keystore key fail closed rather than appearing as an empty wallet.
     */
    private fun readEncryptedPreference(key: String, defaultValue: String): String {
        val stored = preferences.all[key] ?: return defaultValue
        val value = stored as? String
            ?: throw IllegalStateException("Wallet preference $key has an unsupported legacy type")
        if (value.startsWith("$STORAGE_PREFIX|")) return decryptPreference(key, value)
        if (value.startsWith("MNW")) {
            throw IllegalStateException("Wallet preference $key has an unknown encryption envelope")
        }
        check(preferences.edit().putString(key, encryptPreference(key, value)).commit()) {
            "Could not migrate wallet preference $key to encrypted storage"
        }
        return value
    }

    private fun readIds(): MutableSet<String> {
        val stored = preferences.all[KEY_RECEIVED_IDS] ?: return mutableSetOf()
        if (stored is Set<*>) {
            require(stored.all { it is String }) {
                "Legacy wallet replay protection contains an unsupported value"
            }
            val migrated = stored.map { it as String }.toSet()
            require(migrated.size == stored.size) { "Legacy wallet replay protection contains duplicate IDs" }
            require(migrated.size <= MAX_RECEIVED_PAYMENTS) { "Legacy wallet replay set exceeds its limit" }
            require(migrated.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) {
                "Legacy wallet replay protection contains malformed IDs"
            }
            val encoded = JSONArray().apply { migrated.sorted().forEach { put(it) } }.toString()
            check(preferences.edit()
                .putString(KEY_RECEIVED_IDS, encryptPreference(KEY_RECEIVED_IDS, encoded))
                .commit()) { "Could not migrate encrypted wallet replay protection" }
            return migrated.toMutableSet()
        }
        val array = runCatching {
            JSONArray(readEncryptedPreference(KEY_RECEIVED_IDS, "[]"))
        }.getOrElse { throw IllegalStateException("Offline wallet replay protection is corrupt", it) }
        require(array.length() <= MAX_RECEIVED_PAYMENTS) { "Offline wallet replay protection exceeds its limit" }
        val ids = (0 until array.length()).map { array.getString(it) }
        require(ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } &&
            ids.distinct().size == ids.size) {
            "Offline wallet replay protection contains malformed or duplicate IDs"
        }
        return ids.toMutableSet()
    }

    private fun readHistory(): JSONArray =
        runCatching { JSONArray(readEncryptedPreference(KEY_HISTORY, "[]")) }
            .getOrElse { throw IllegalStateException("Offline wallet receipt history is corrupt", it) }

    private fun encryptPreference(key: String, plaintext: String): String {
        val cipher = Cipher.getInstance(STORAGE_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateStorageKey())
        cipher.updateAAD(storageAdditionalData(key))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$STORAGE_PREFIX|${encode(cipher.iv)}|${encode(encrypted)}"
    }

    private fun decryptPreference(key: String, encoded: String): String = try {
        val parts = encoded.split('|')
        require(parts.size == 3 && parts[0] == STORAGE_PREFIX) {
            "Wallet preference $key has a malformed encryption envelope"
        }
        val iv = decodeCanonicalBase64(parts[1])
        require(iv.size == STORAGE_IV_BYTES) { "Wallet preference $key has an invalid IV" }
        val ciphertext = decodeCanonicalBase64(parts[2])
        require(ciphertext.size >= STORAGE_TAG_BYTES) { "Wallet preference $key is truncated" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(storageKeyAlias, null) as? SecretKey
            ?: throw IllegalStateException("Wallet encryption key is missing for $walletId")
        val cipher = Cipher.getInstance(STORAGE_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(STORAGE_TAG_BITS, iv))
        cipher.updateAAD(storageAdditionalData(key))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    } catch (error: Exception) {
        throw IllegalStateException(
            "Wallet preference $key authentication failed; refusing to discard wallet history",
            error
        )
    }

    private fun getOrCreateStorageKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(storageKeyAlias, null) as? SecretKey)?.let { return it }

        // Never rotate a missing key while any encrypted record still depends on it.
        // That would make the remaining wallet history permanently unreadable.
        val existingEncryptedRecords = preferences.all.any { (key, value) ->
            key != KEY_RECEIVED_IDS && value is String && value.startsWith("$STORAGE_PREFIX|")
        } || runCatching {
            val legacyOrEncryptedIds = preferences.all[KEY_RECEIVED_IDS]
            legacyOrEncryptedIds is String && legacyOrEncryptedIds.startsWith("$STORAGE_PREFIX|")
        }.getOrDefault(false)
        check(!existingEncryptedRecords) {
            "Wallet storage key is missing while encrypted records exist; refusing to create a replacement key"
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                storageKeyAlias,
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

    private fun storageAdditionalData(key: String): ByteArray =
        "meshnet-offline-wallet-storage-v1|$walletId|$key".toByteArray(Charsets.UTF_8)

    private fun addressFor(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun canonical(json: JSONObject): ByteArray {
        val fields = listOf("version", "id", "asset", "amountMinor", "sender", "senderPublicKey", "recipient", "createdAt")
        return fields.joinToString("\n") { key -> "$key=${json.get(key)}" }.toByteArray(Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val ASSET_CODE = "MESH-LOCAL-UNIT"
        private const val PREFERENCES = "meshnet-offline-wallet-v1"
        private const val KEY_RECEIVED_IDS = "received_payment_ids"
        private const val KEY_HISTORY = "received_payment_history"
        private const val KEY_OUTGOING = "outgoing_payment_history"
        private const val KEY_PENDING_ENVELOPES = "pending_offline_payment_envelopes"
        private const val MAX_PENDING_ENVELOPES = 16
        private const val MAX_PENDING_ENVELOPE_BYTES = 512 * 1024
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val STORAGE_PREFIX = "MNW1"
        private const val STORAGE_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val STORAGE_IV_BYTES = 12
        private const val STORAGE_TAG_BITS = 128
        private const val STORAGE_TAG_BYTES = STORAGE_TAG_BITS / 8
        private val OUTGOING_STATUSES = setOf("QUEUED", "RECEIVED")
        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val VERSION = 1
        private const val MAX_AMOUNT_MINOR = 9_000_000_000_000L
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
        private const val MAX_RECEIVED_PAYMENTS = 4096
        private const val MAX_HISTORY_ENTRIES = 1000
        private const val MAX_OUTGOING_ENTRIES = 4096
    }
}
