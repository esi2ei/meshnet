package com.meshnet.app

import kotlin.math.max
import kotlin.math.min

/**
 * Per-next-hop link estimator. Samples come from actual transport write outcomes,
 * measured write completion latency, and optional radio RSSI observations.
 * Scores decay toward neutral when no recent samples arrive.
 */
internal class MeshLinkQualityRegistry(
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Snapshot(
        val peer: String,
        val score: Int,
        val deliveryRatio: Double,
        val latencyMs: Double,
        val rssiDbm: Int?,
        val samples: Int,
        val observedAt: Long
    )

    private data class State(
        var deliveryRatio: Double = 0.5,
        var latencyMs: Double = 500.0,
        var rssiDbm: Int? = null,
        var samples: Int = 0,
        var observedAt: Long = 0L
    )

    private val states = linkedMapOf<String, State>()

    @Synchronized
    fun record(peer: String, success: Boolean, latencyMs: Long, rssiDbm: Int? = null) {
        require(peer.length in 3..192)
        val now = clock()
        val state = states.getOrPut(peer) { State() }
        val delivery = if (success) 1.0 else 0.0
        state.deliveryRatio = if (state.samples == 0) delivery
            else state.deliveryRatio * DELIVERY_ALPHA + delivery * (1.0 - DELIVERY_ALPHA)
        if (latencyMs >= 0L && success) {
            val sample = latencyMs.toDouble().coerceIn(1.0, MAX_LATENCY_MS)
            state.latencyMs = if (state.samples == 0) sample
                else state.latencyMs * LATENCY_ALPHA + sample * (1.0 - LATENCY_ALPHA)
        }
        if (rssiDbm != null && rssiDbm in -127..0) state.rssiDbm = rssiDbm
        state.samples = (state.samples + 1).coerceAtMost(Int.MAX_VALUE)
        state.observedAt = now
        trim()
    }

    /** Returns a 0..100 score; unseen peers are neutral, stale samples decay. */
    @Synchronized
    fun score(peer: String, now: Long = clock()): Int {
        val state = states[peer] ?: return NEUTRAL_SCORE
        val age = max(0L, now - state.observedAt)
        val freshness = (1.0 - age.toDouble() / STALE_AFTER_MS).coerceIn(0.0, 1.0)
        val delivery = state.deliveryRatio * 65.0
        val latency = (1.0 - state.latencyMs / MAX_LATENCY_MS).coerceIn(0.0, 1.0) * 20.0
        val rssi = state.rssiDbm?.let { ((it + 100.0) / 55.0).coerceIn(0.0, 1.0) * 15.0 } ?: 7.5
        val measured = delivery + latency + rssi
        return (NEUTRAL_SCORE + (measured - NEUTRAL_SCORE) * freshness).toInt().coerceIn(0, 100)
    }

    @Synchronized
    fun snapshot(now: Long = clock()): List<Snapshot> = states.map { (peer, state) ->
        Snapshot(peer, score(peer, now), state.deliveryRatio, state.latencyMs,
            state.rssiDbm, state.samples, state.observedAt)
    }.sortedByDescending { it.score }

    @Synchronized
    fun remove(peer: String) { states.remove(peer) }

    @Synchronized
    fun clear() { states.clear() }

    private fun trim() {
        while (states.size > MAX_PEERS) {
            val oldest = states.minByOrNull { it.value.observedAt } ?: return
            states.remove(oldest.key)
        }
    }

    companion object {
        const val NEUTRAL_SCORE = 50
        private const val MAX_PEERS = 512
        private const val MAX_LATENCY_MS = 5_000.0
        private const val STALE_AFTER_MS = 5L * 60L * 1000L
        private const val DELIVERY_ALPHA = 0.75
        private const val LATENCY_ALPHA = 0.8
    }
}
