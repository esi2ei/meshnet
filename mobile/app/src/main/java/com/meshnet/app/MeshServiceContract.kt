package com.meshnet.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.security.MessageDigest
import java.math.BigInteger

/**
 * Canonical, mutually signed terms for a peer-provided service.
 *
 * This is a contract envelope, not a payment processor or proof that bytes were delivered.
 * Billable bytes are application payload bytes accepted by the receiving application;
 * transport framing, routing headers and retransmissions are excluded by definition.
 * Implementations must create receipts separately and reconcile them idempotently.
 */
internal data class MeshServiceContract(
    val id: String,
    val service: String,
    val provider: String,
    val consumer: String,
    val unitPriceMinorPerMiB: Long,
    val maxBillableBytes: Long,
    val maxCostMinor: Long,
    val validFrom: Long,
    val validUntil: Long,
    val cancellation: String,
    val relayShares: List<RelayShare>,
    val providerPublicKey: String,
    val consumerPublicKey: String,
    val providerSignature: String? = null,
    val consumerSignature: String? = null
) {
    data class RelayShare(val nodeId: String, val basisPoints: Int)

    init { validateTerms() }

    fun signAsProvider(privateKey: PrivateKey): MeshServiceContract {
        val publicBytes = decodeKey(providerPublicKey)
        require(addressFor(publicBytes) == provider) { "Provider key does not match provider address" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        val bytes = canonicalBytes()
        val signature = sign(privateKey, bytes)
        require(verifySignature(publicKey, signature, bytes)) { "Provider private key does not match the contract identity" }
        return copy(providerSignature = signature)
    }

    fun signAsConsumer(privateKey: PrivateKey): MeshServiceContract {
        val publicBytes = decodeKey(consumerPublicKey)
        require(addressFor(publicBytes) == consumer) { "Consumer key does not match consumer address" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        val bytes = canonicalBytes()
        val signature = sign(privateKey, bytes)
        require(verifySignature(publicKey, signature, bytes)) { "Consumer private key does not match the contract identity" }
        return copy(consumerSignature = signature)
    }

    fun verify(): Boolean = runCatching {
        validateTerms()
        val providerBytes = decodeKey(providerPublicKey)
        val consumerBytes = decodeKey(consumerPublicKey)
        require(addressFor(providerBytes) == provider)
        require(addressFor(consumerBytes) == consumer)
        val providerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(providerBytes))
        val consumerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(consumerBytes))
        require(providerKey is ECPublicKey && isP256(providerKey))
        require(consumerKey is ECPublicKey && isP256(consumerKey))
        val providerSig = providerSignature ?: throw IllegalArgumentException("Provider signature is missing")
        val consumerSig = consumerSignature ?: throw IllegalArgumentException("Consumer signature is missing")
        val signedBytes = canonicalBytes()
        verifySignature(providerKey, providerSig, signedBytes) &&
            verifySignature(consumerKey, consumerSig, signedBytes)
    }.getOrDefault(false)

    fun encode(): String = JSONObject()
        .put("version", VERSION).put("id", id).put("service", service)
        .put("provider", provider).put("consumer", consumer)
        .put("unitPriceMinorPerMiB", unitPriceMinorPerMiB)
        .put("maxBillableBytes", maxBillableBytes).put("maxCostMinor", maxCostMinor)
        .put("validFrom", validFrom).put("validUntil", validUntil)
        .put("cancellation", cancellation)
        .put("relayShares", JSONArray().apply {
            relayShares.sortedBy { it.nodeId }.forEach { put(JSONObject().put("nodeId", it.nodeId).put("basisPoints", it.basisPoints)) }
        })
        .put("providerPublicKey", providerPublicKey).put("consumerPublicKey", consumerPublicKey)
        .put("providerSignature", providerSignature ?: JSONObject.NULL)
        .put("consumerSignature", consumerSignature ?: JSONObject.NULL)
        .toString()

    /** Contract validity is evaluated against an explicit clock supplied by the caller. */
    fun isActiveAt(now: Long): Boolean = now in validFrom until validUntil

    /**
     * Price is prorated by payload volume, rounding any partial MiB up to the next unit.
     * The agreed maximum cost is a hard cap; this method does not debit or settle funds.
     */
    fun maximumChargeFor(billableBytes: Long): Long {
        require(billableBytes in 0..maxBillableBytes) { "Billable bytes exceed the signed contract limit" }
        if (billableBytes == 0L || unitPriceMinorPerMiB == 0L) return 0L
        val numerator = BigInteger.valueOf(unitPriceMinorPerMiB).multiply(BigInteger.valueOf(billableBytes))
        val divisor = BigInteger.valueOf(BYTES_PER_MIB)
        val rounded = numerator.add(divisor.subtract(BigInteger.ONE)).divide(divisor)
        return rounded.min(BigInteger.valueOf(maxCostMinor)).longValueExact()
    }

    /** Exact canonical UTF-8 bytes signed by both parties; JSON key order is irrelevant. */
    fun canonicalBytes(): ByteArray {
        validateTerms()
        val relayText = relayShares.sortedBy { it.nodeId }
            .joinToString(";") { "${it.nodeId}:${it.basisPoints}" }
        val fields = listOf(
            "meshnet-service-contract-v1", id, service, provider, consumer,
            unitPriceMinorPerMiB.toString(), maxBillableBytes.toString(), maxCostMinor.toString(),
            validFrom.toString(), validUntil.toString(), cancellation, relayText,
            providerPublicKey, consumerPublicKey
        )
        return fields.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun validateTerms() {
        require(id.matches(Regex("[A-Za-z0-9_-]{16,64}"))) { "Invalid contract ID" }
        require(service.matches(Regex("[A-Z][A-Z0-9_-]{1,31}"))) { "Invalid service code" }
        require(provider.matches(Regex("[0-9a-f]{64}")) && consumer.matches(Regex("[0-9a-f]{64}")) && provider != consumer) {
            "Invalid contract parties"
        }
        require(unitPriceMinorPerMiB in 0..MAX_PRICE_MINOR && maxBillableBytes in 1..MAX_BILLABLE_BYTES)
        require(maxCostMinor in 0..MAX_COST_MINOR && validFrom > 0 && validUntil > validFrom)
        require(cancellation in ALLOWED_CANCELLATION_POLICIES)
        require(relayShares.size <= MAX_RELAYS)
        require(relayShares.map { it.nodeId }.distinct().size == relayShares.size)
        require(relayShares.all { it.nodeId.matches(Regex("[A-Za-z0-9_-]{1,32}")) && it.basisPoints in 0..10_000 })
        require(relayShares.sumOf { it.basisPoints } <= 10_000) { "Relay shares exceed 100%" }
        require(providerPublicKey.matches(BASE64_KEY) && consumerPublicKey.matches(BASE64_KEY))
        require(providerSignature == null || providerSignature.matches(BASE64_SIGNATURE))
        require(consumerSignature == null || consumerSignature.matches(BASE64_SIGNATURE))
    }

    private fun decodeKey(value: String): ByteArray {
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(encodeBase64(decoded) == value) { "Non-canonical public key encoding" }
        return decoded
    }

    private fun sign(key: PrivateKey, bytes: ByteArray): String =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(bytes); encodeBase64(sign())
        }

    private fun verifySignature(key: PublicKey, encoded: String, bytes: ByteArray): Boolean {
        val signatureBytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        if (encodeBase64(signatureBytes) != encoded) return false
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(bytes); verify(signatureBytes)
        }
    }

    companion object {
        const val BILLING_UNIT = "APPLICATION_PAYLOAD_BYTES"
        const val VERSION = 1
        private const val MAX_PRICE_MINOR = 9_000_000_000_000L
        private const val MAX_BILLABLE_BYTES = 1L shl 40
        private const val MAX_COST_MINOR = 9_000_000_000_000L
        private const val MAX_RELAYS = 8
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val MAX_ENCODED_BYTES = 16 * 1024
        private val ALLOWED_CANCELLATION_POLICIES = setOf("MUTUAL_ONLY", "EITHER_BEFORE_START", "EITHER_WITH_NOTICE")
        private val BASE64_KEY = Regex("[A-Za-z0-9_-]{80,160}")
        private val BASE64_SIGNATURE = Regex("[A-Za-z0-9_-]{80,120}")

        fun decode(payload: String): MeshServiceContract {
            require(payload.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) { "Contract envelope is too large" }
            val json = JSONObject(payload)
            require(json.optInt("version", -1) == VERSION) { "Unsupported service contract version" }
            val sharesJson = json.getJSONArray("relayShares")
            require(sharesJson.length() <= MAX_RELAYS) { "Too many relay shares" }
            val shares = (0 until sharesJson.length()).map { index ->
                val item = sharesJson.getJSONObject(index)
                RelayShare(item.getString("nodeId"), item.getInt("basisPoints"))
            }
            return MeshServiceContract(
                id = json.getString("id"), service = json.getString("service"),
                provider = json.getString("provider"), consumer = json.getString("consumer"),
                unitPriceMinorPerMiB = json.getLong("unitPriceMinorPerMiB"),
                maxBillableBytes = json.getLong("maxBillableBytes"), maxCostMinor = json.getLong("maxCostMinor"),
                validFrom = json.getLong("validFrom"), validUntil = json.getLong("validUntil"),
                cancellation = json.getString("cancellation"), relayShares = shares,
                providerPublicKey = json.getString("providerPublicKey"),
                consumerPublicKey = json.getString("consumerPublicKey"),
                providerSignature = json.optString("providerSignature").takeIf { it.isNotBlank() && it != "null" },
                consumerSignature = json.optString("consumerSignature").takeIf { it.isNotBlank() && it != "null" }
            )
        }

        fun create(
            id: String, service: String, provider: String, consumer: String,
            unitPriceMinorPerMiB: Long, maxBillableBytes: Long, maxCostMinor: Long,
            validFrom: Long, validUntil: Long, cancellation: String,
            relayShares: List<RelayShare>, providerPublicKey: ByteArray, consumerPublicKey: ByteArray
        ) = MeshServiceContract(
            id, service, provider, consumer, unitPriceMinorPerMiB, maxBillableBytes, maxCostMinor,
            validFrom, validUntil, cancellation, relayShares,
            encodeBase64(providerPublicKey), encodeBase64(consumerPublicKey)
        )

        private fun isP256(key: ECPublicKey): Boolean = runCatching {
            val expected = java.security.AlgorithmParameters.getInstance("EC").apply {
                init(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            val actual = key.params
            actual.curve.field.fieldSize == 256 && actual.curve == expected.curve &&
                actual.generator == expected.generator && actual.order == expected.order &&
                actual.cofactor == expected.cofactor
        }.getOrDefault(false)

        private fun addressFor(publicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKey)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun encodeBase64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
