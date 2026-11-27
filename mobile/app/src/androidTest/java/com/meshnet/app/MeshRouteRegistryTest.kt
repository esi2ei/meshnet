package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouteRegistryTest {
    @Test
    fun recordsOnlyUniqueObservedPathsAndCountsRepeatObservations() {
        val registry = MeshRouteRegistry()
        val path = listOf("origin_01", "relay_01", "local_01")

        assertTrue(registry.observe(path, 100L))
        assertFalse(registry.observe(path, 200L))

        val route = registry.snapshot(200L).single()
        assertEquals(path, route.nodes)
        assertEquals(200L, route.observedAt)
        assertEquals(2, route.observations)
    }

    @Test
    fun rejectsLoopsMalformedIdsAndOversizedPaths() {
        val registry = MeshRouteRegistry()

        assertFalse(registry.observe(listOf("origin_01", "relay_01", "origin_01"), 100L))
        assertFalse(registry.observe(listOf("origin_01", "bad id", "local_01"), 100L))
        assertFalse(registry.observe((1..10).map { "node_$it" }, 100L))
        assertTrue(registry.snapshot(100L).isEmpty())
    }

    @Test
    fun expiresOldRouteObservationsAndBoundsHistory() {
        val registry = MeshRouteRegistry()
        registry.observe(listOf("origin_01", "local_01"), 100L)

        assertTrue(registry.snapshot(100L + 30L * 60L * 1000L + 1L).isEmpty())
    }

    @Test
    fun jsonContainsObservedPathAndMetadata() {
        val registry = MeshRouteRegistry()
        registry.observe(listOf("origin_01", "relay_01", "local_01"), 500L)

        val item = registry.toJson(500L).getJSONObject(0)
        assertEquals("origin_01>relay_01>local_01", item.getString("id"))
        assertEquals(3, item.getJSONArray("nodes").length())
        assertEquals(500L, item.getLong("observedAt"))
        assertEquals(1, item.getInt("observations"))
    }
}
