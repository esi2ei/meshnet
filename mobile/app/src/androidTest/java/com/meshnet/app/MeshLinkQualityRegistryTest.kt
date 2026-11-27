package com.meshnet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshLinkQualityRegistryTest {
    @Test
    fun successfulLowLatencyLinkOutranksFailingHighLatencyLink() {
        var now = 1_000L
        val registry = MeshLinkQualityRegistry { now }
        repeat(12) { registry.record("BLE/good", true, 18L, -55) }
        repeat(12) { registry.record("P2P/poor", false, 900L, -105) }

        assertTrue(registry.score("BLE/good") > registry.score("P2P/poor"))
    }

    @Test
    fun staleMeasurementsDecayTowardNeutral() {
        var now = 1_000L
        val registry = MeshLinkQualityRegistry { now }
        repeat(10) { registry.record("BLE/peer", true, 12L, -48) }
        val fresh = registry.score("BLE/peer")
        now += 10L * 60L * 1000L

        assertTrue(fresh > registry.score("BLE/peer"))
        assertTrue(registry.score("BLE/peer") in 45..55)
    }

    @Test
    fun disconnectedPeerCanBeRemoved() {
        val registry = MeshLinkQualityRegistry { 1_000L }
        registry.record("AWARE/peer", true, 30L)
        registry.remove("AWARE/peer")
        assertTrue(registry.score("AWARE/peer") == MeshLinkQualityRegistry.NEUTRAL_SCORE)
    }
}
