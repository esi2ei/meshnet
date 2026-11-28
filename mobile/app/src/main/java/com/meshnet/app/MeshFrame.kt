package com.meshnet.app

import org.json.JSONObject
import java.util.UUID

data class MeshFrame(
    val origin: String,
    val ttl: Int,
    val hops: List<String>,
    val nonce: String,
    val ciphertext: String,
    val createdAt: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
    val kind: String = KIND_MESSAGE,
    val ackFor: String? = null,
    val destination: String? = null,
    val deviceKey: String? = null,
    val payloadHash: String? = null,
    val signature: String? = null
) {
    fun encode(): ByteArray = JSONObject()
        .put("v", PROTOCOL_VERSION)
        .put("id", id)
        .put("origin", origin)
        .put("ttl", ttl)
        .put("hops", hops.joinToString(","))
        .put("nonce", nonce)
        .put("ciphertext", ciphertext)
        .put("createdAt", createdAt)
        .put("kind", kind)
        .put("ackFor", ackFor ?: JSONObject.NULL)
        .put("destination", destination ?: JSONObject.NULL)
        .put("deviceKey", deviceKey ?: JSONObject.NULL)
        .put("payloadHash", payloadHash ?: JSONObject.NULL)
        .put("signature", signature ?: JSONObject.NULL)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val PROTOCOL_VERSION = 6
        const val KIND_MESSAGE = "MESSAGE"
        const val KIND_ACK = "ACK"
        const val KIND_EPHEMERAL = "EPHEMERAL"

        fun decode(bytes: ByteArray): MeshFrame {
            require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid BLE frame size" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.optInt("v") == PROTOCOL_VERSION) { "Unsupported MeshNet frame version; update all mesh devices together" }
            val hops = json.optString("hops").split(",").filter { it.isNotBlank() }
            val ackValue = json.opt("ackFor")
            val frame = MeshFrame(
                id = json.getString("id"),
                origin = json.getString("origin"),
                ttl = json.getInt("ttl"),
                hops = hops,
                nonce = json.getString("nonce"),
                ciphertext = json.getString("ciphertext"),
                createdAt = json.getLong("createdAt"),
                kind = json.optString("kind", KIND_MESSAGE),
                ackFor = if (ackValue == null || ackValue == JSONObject.NULL) null else ackValue.toString(),
                destination = json.opt("destination")?.let { if (it == JSONObject.NULL) null else it.toString() },
                deviceKey = json.opt("deviceKey")?.let { if (it == JSONObject.NULL) null else it.toString() },
                payloadHash = json.opt("payloadHash")?.let { if (it == JSONObject.NULL) null else it.toString() },
                signature = json.opt("signature")?.let { if (it == JSONObject.NULL) null else it.toString() }
            )
            val now = System.currentTimeMillis()
            require(frame.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid frame ID" }
            require(frame.origin.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "Invalid origin ID" }
            require(frame.destination == null || frame.destination.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "Invalid destination ID" }
            require(frame.ttl in 1..MAX_TTL) { "Invalid TTL" }
            require(frame.hops.size in 1..MAX_TTL) { "Invalid route history" }
            require(frame.hops.first() == frame.origin) { "Route does not begin at origin" }
            require(frame.hops.all { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }) { "Invalid hop ID" }
            require(frame.hops.distinct().size == frame.hops.size) { "Route contains a loop" }
            require(frame.ttl + frame.hops.size == MAX_TTL + 1) { "Inconsistent TTL and route history" }
            require(frame.createdAt in (now - MAX_AGE_MS)..(now + MAX_CLOCK_SKEW_MS)) {
                "Frame is expired or has an invalid timestamp"
            }
            require(frame.nonce.isNotBlank() && frame.ciphertext.isNotBlank()) { "Missing encrypted payload" }
            val identityFields = listOf(frame.deviceKey, frame.payloadHash, frame.signature)
            require(identityFields.all { it == null } || identityFields.all { it != null }) {
                "Incomplete device identity signature"
            }
            if (frame.deviceKey != null) {
                require(frame.deviceKey.matches(Regex("[A-Za-z0-9_-]{80,160}"))) { "Invalid device public key encoding" }
                require(frame.payloadHash?.matches(Regex("[a-f0-9]{64}")) == true) { "Invalid signed payload digest" }
                require(frame.signature?.matches(Regex("[A-Za-z0-9_-]{80,120}")) == true) { "Invalid device signature encoding" }
            }
            require(frame.kind == KIND_MESSAGE || frame.kind == KIND_ACK || frame.kind == KIND_EPHEMERAL) { "Unknown frame kind" }
            if (frame.kind == KIND_ACK) {
                require(frame.ackFor?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true) {
                    "ACK is missing a valid message ID"
                }
            } else {
                require(frame.ackFor == null) { "Non-ACK frame cannot contain an ACK target" }
            }
            return frame
        }

        fun aad(frame: MeshFrame): ByteArray =
            "meshnet-v6|${frame.kind}|${frame.id}|${frame.origin}|${frame.destination.orEmpty()}|${frame.createdAt}|${frame.ackFor.orEmpty()}|${frame.deviceKey.orEmpty()}|${frame.payloadHash.orEmpty()}|${frame.ttl}|${frame.hops.joinToString(",")}"
                .toByteArray(Charsets.UTF_8)

        const val MAX_TTL = 8
        const val MAX_FRAME_BYTES = 960
        private const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        private const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L
    }
}
