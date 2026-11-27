package com.meshnet.app

import org.json.JSONObject
import java.util.UUID

data class MeshFrame(
    val id: String = UUID.randomUUID().toString(),
    val origin: String,
    val ttl: Int,
    val hops: List<String>,
    val nonce: String,
    val ciphertext: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun encode(): ByteArray = JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("origin", origin)
        .put("ttl", ttl)
        .put("hops", hops.joinToString(","))
        .put("nonce", nonce)
        .put("ciphertext", ciphertext)
        .put("createdAt", createdAt)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): MeshFrame {
            require(bytes.size <= MAX_FRAME_BYTES) { "Frame exceeds the BLE transport limit" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.optInt("v") == 1) { "Unsupported MeshNet frame version" }
            val hops = json.optString("hops").split(",").filter { it.isNotBlank() }
            return MeshFrame(
                id = json.getString("id"),
                origin = json.getString("origin"),
                ttl = json.getInt("ttl"),
                hops = hops,
                nonce = json.getString("nonce"),
                ciphertext = json.getString("ciphertext"),
                createdAt = json.getLong("createdAt")
            ).also {
                require(it.ttl in 0..MAX_TTL) { "Invalid TTL" }
                require(it.hops.size <= MAX_TTL) { "Route history too long" }
            }
        }

        const val MAX_TTL = 8
        const val MAX_FRAME_BYTES = 480
    }
}
