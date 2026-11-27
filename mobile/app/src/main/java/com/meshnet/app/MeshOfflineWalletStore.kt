package com.meshnet.app

import android.content.Context
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
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Payment payload is too large" }
        val json = JSONObject(payload)
        require(json.optInt("version", -1) == VERSION) { "Unsupported offline payment version" }
        require(json.optString("asset") == ASSET_CODE) { "Unsupported offline asset" }
        val id = json.getString("id")
        require(id.length in 1..64) { "Invalid payment identifier" }
        val amount = json.getLong("amountMinor")
        require(amount in 1..MAX_AMOUNT_MINOR) { "Invalid payment amount" }
        val sender = json.getString("sender")
        val recipient = json.getString("recipient")
        require(recipient == address()) { "This payment is addressed to a different wallet" }
        val senderPublicKey = Base64.decode(json.getString("senderPublicKey"), Base64.URL_SAFE or Base64.NO_WRAP)
        require(addressFor(senderPublicKey) == sender) { "Sender wallet address does not match its public key" }
        val publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(senderPublicKey))
        val signatureBytes = Base64.decode(json.getString("signature"), Base64.URL_SAFE or Base64.NO_WRAP)
        val unsigned = JSONObject(json.toString()).apply { remove("signature") }
        val valid = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(canonical(unsigned))
            verify(signatureBytes)
        }
        require(valid) { "Offline payment signature is invalid" }
        val createdAt = json.getLong("createdAt")
        require(createdAt > 0L) { "Invalid payment timestamp" }

        val received = readIds()
        require(id !in received) { "This offline payment was already imported on this device" }
        received.add(id)
        val receipt = Receipt(id, sender, recipient, amount, ASSET_CODE, createdAt)
        val history = readHistory()
        history.put(
            JSONObject()
                .put("id", receipt.id)
                .put("sender", receipt.sender)
                .put("recipient", receipt.recipient)
                .put("amountMinor", receipt.amountMinor)
                .put("asset", receipt.asset)
                .put("createdAt", receipt.createdAt)
        )
        check(preferences.edit()
            .putStringSet(KEY_RECEIVED_IDS, received)
            .putString(KEY_HISTORY, history.toString())
            .commit()) { "Could not persist offline payment receipt and replay protection" }
        return receipt
    }

    data class OutgoingTransfer(
        val id: String,
        val recipient: String,
        val amountMinor: Long,
        val createdAt: Long,
        val status: String
    )

    /** Persist the signed voucher before any radio send is attempted. */
    @Synchronized
    fun recordOutgoing(payload: String) {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Payment payload is too large" }
        val json = JSONObject(payload)
        require(json.optInt("version", -1) == VERSION) { "Unsupported offline payment version" }
        require(json.optString("sender") == address()) { "Outgoing voucher belongs to another wallet" }
        val id = json.getString("id")
        val recipient = json.getString("recipient")
        val amount = json.getLong("amountMinor")
        val createdAt = json.getLong("createdAt")
        require(id.isNotBlank() && recipient.matches(Regex("[0-9a-f]{64}")) && amount in 1..MAX_AMOUNT_MINOR)
        val entries = readOutgoing()
        require((0 until entries.length()).none { entries.optJSONObject(it)?.optString("id") == id }) {
            "Outgoing voucher is already recorded"
        }
        entries.put(JSONObject()
            .put("id", id)
            .put("recipient", recipient)
            .put("amountMinor", amount)
            .put("createdAt", createdAt)
            .put("status", "QUEUED"))
        check(preferences.edit().putString(KEY_OUTGOING, entries.toString()).commit()) {
            "Could not persist outgoing offline transfer"
        }
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
        check(preferences.edit().putString(KEY_OUTGOING, entries.toString()).commit()) {
            "Could not persist recipient acknowledgement"
        }
        return OutgoingTransfer(id, recipient, amount, item.getLong("createdAt"), "RECEIVED")
    }

    @Synchronized
    fun outgoingHistory(): List<OutgoingTransfer> {
        val array = readOutgoing()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                OutgoingTransfer(
                    item.getString("id"), item.getString("recipient"), item.getLong("amountMinor"),
                    item.getLong("createdAt"), item.getString("status")
                )
            }.getOrNull()
        }
    }

    private fun readOutgoing(): JSONArray =
        runCatching { JSONArray(preferences.getString(KEY_OUTGOING, "[]")) }.getOrDefault(JSONArray())

    private fun canonicalAcknowledgement(json: JSONObject): ByteArray {
        val fields = listOf("version", "voucherId", "sender", "recipient", "amountMinor", "recipientPublicKey", "acknowledgedAt")
        return fields.joinToString("\n") { key -> "$key=${json.get(key)}" }.toByteArray(Charsets.UTF_8)
    }

    @Synchronized
    fun receiptById(id: String): Receipt? = history().firstOrNull { it.id == id }

    @Synchronized
    fun history(): List<Receipt> {
        val array = readHistory()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                Receipt(
                    item.getString("id"), item.getString("sender"), item.getString("recipient"),
                    item.getLong("amountMinor"), item.getString("asset"), item.getLong("createdAt")
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear offline wallet data" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
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

    private fun readIds(): MutableSet<String> =
        preferences.getStringSet(KEY_RECEIVED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun readHistory(): JSONArray =
        runCatching { JSONArray(preferences.getString(KEY_HISTORY, "[]")) }.getOrDefault(JSONArray())

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
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val VERSION = 1
        private const val MAX_AMOUNT_MINOR = 9_000_000_000_000L
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
    }
}
