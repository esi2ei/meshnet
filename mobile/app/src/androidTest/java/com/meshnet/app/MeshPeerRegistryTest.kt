package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPeerRegistryTest {
    @Test
    fun registersOnlyConnectedPeersAndKeepsTransportsDistinct() {
        val registry = MeshPeerRegistry()
        registry.setConnected("BLE/GATT", "AA:BB:CC:DD:EE:01", true, 100L)
        registry.setConnected("Wi-Fi Direct", "192.168.49.2", true, 200L)
        registry.setConnected("Wi-Fi Aware", "fe80::1", true, 300L)

        val peers = registry.snapshot()
        assertEquals(3, peers.size)
        assertEquals(setOf("BLE/GATT", "Wi-Fi Direct", "Wi-Fi Aware"), peers.map { it.transport }.toSet())
        assertEquals(100L, peers.first { it.transport == "BLE/GATT" }.connectedAt)
    }

    @Test
    fun disconnectRemovesOnlyMatchingTransportPeer() {
        val registry = MeshPeerRegistry()
        registry.setConnected("Wi-Fi Direct", "192.168.49.2", true, 100L)
        registry.setConnected("Wi-Fi Aware", "192.168.49.2", true, 110L)

        registry.setConnected("Wi-Fi Direct", "192.168.49.2", false)

        val remaining = registry.snapshot()
        assertEquals(1, remaining.size)
        assertEquals("Wi-Fi Aware", remaining.single().transport)
    }

    @Test
    fun duplicateConnectPreservesOriginalConnectionTime() {
        val registry = MeshPeerRegistry()
        registry.setConnected("BLE/GATT", "AA:BB:CC:DD:EE:01", true, 100L)
        registry.setConnected("BLE/GATT", "AA:BB:CC:DD:EE:01", true, 900L)

        assertEquals(1, registry.snapshot().size)
        assertEquals(100L, registry.snapshot().single().connectedAt)
    }

    @Test
    fun clearRemovesAllPeersAndJsonContainsOnlyLiveConnections() {
        val registry = MeshPeerRegistry()
        registry.setConnected("BLE/GATT", "AA:BB:CC:DD:EE:01", true, 100L)

        val json = registry.toJson()
        assertEquals(1, json.length())
        assertTrue(json.getJSONObject(0).getString("id").startsWith("BLE/GATT:"))
        assertTrue(json.getJSONObject(0).has("connectedAt"))

        registry.clear()
        assertTrue(registry.snapshot().isEmpty())
        assertEquals(0, registry.toJson().length())
    }

    @Test
    fun ignoresBlankPeerIdentity() {
        val registry = MeshPeerRegistry()
        registry.setConnected(" ", "192.168.49.2", true)
        registry.setConnected("BLE/GATT", " ", true)

        assertFalse(registry.snapshot().isNotEmpty())
    }
}
