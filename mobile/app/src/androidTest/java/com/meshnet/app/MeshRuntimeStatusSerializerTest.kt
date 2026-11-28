package com.meshnet.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRuntimeStatusSerializerTest {
    @Test
    fun serializesTheCompleteDashboardRuntimeContract() {
        val events = JSONArray().put("mesh started")
        val peers = JSONArray().put(JSONObject().put("id", "BLE/peer-1"))
        val routes = JSONArray().put(JSONObject().put("origin", "node-1"))
        val transfers = JSONArray().put(JSONObject().put("id", "voucher-1"))
        val receipts = JSONArray().put(JSONObject().put("id", "receipt-1"))
        val library = JSONArray().put(JSONObject().put("id", "sha256:item"))

        val status = JSONObject(
            MeshRuntimeStatusSerializer.serialize(
                running = true,
                onlineIntegrationsEnabled = false,
                walletBusy = true,
                walletAddress = "devnet-wallet",
                offlineWalletAddress = "local-wallet",
                offlineWalletTransfers = transfers,
                offlineWalletReceipts = receipts,
                offlineLibrary = library,
                offlineLibraryHealthy = true,
                offlineLibraryCorrupt = false,
                offlineLibraryOverQuota = false,
                offlineLibraryCorruptBlobCount = 0,
                offlineNetworkRunning = true,
                offlineNodeIp = "100.64.0.10",
                offlinePortalUrl = "http://100.64.0.10/",
                recentEvents = events,
                connectedPeers = peers,
                observedRoutes = routes,
                localNodeId = "node-local"
            )
        )

        assertTrue(status.getBoolean("native"))
        assertEquals("android", status.getString("platform"))
        assertTrue(status.getBoolean("running"))
        assertFalse(status.getBoolean("onlineIntegrationsEnabled"))
        assertTrue(status.getBoolean("walletBusy"))
        assertEquals("devnet-wallet", status.getString("walletAddress"))
        assertEquals("local-wallet", status.getString("offlineWalletAddress"))
        assertEquals("voucher-1", status.getJSONArray("offlineWalletTransfers").getJSONObject(0).getString("id"))
        assertEquals("receipt-1", status.getJSONArray("offlineWalletReceipts").getJSONObject(0).getString("id"))
        assertEquals("sha256:item", status.getJSONArray("offlineLibrary").getJSONObject(0).getString("id"))
        assertTrue(status.getBoolean("offlineLibraryHealthy"))
        assertFalse(status.getBoolean("offlineLibraryCorrupt"))
        assertFalse(status.getBoolean("offlineLibraryOverQuota"))
        assertEquals(0, status.getInt("offlineLibraryCorruptBlobCount"))
        assertTrue(status.getBoolean("offlineNetworkRunning"))
        assertEquals("100.64.0.10", status.getString("offlineNodeIp"))
        assertEquals("http://100.64.0.10/", status.getString("offlinePortalUrl"))
        assertEquals("mesh started", status.getJSONArray("recentEvents").getString(0))
        assertEquals("BLE/peer-1", status.getJSONArray("connectedPeers").getJSONObject(0).getString("id"))
        assertEquals("node-1", status.getJSONArray("observedRoutes").getJSONObject(0).getString("origin"))
        assertEquals("node-local", status.getString("localNodeId"))
    }

    @Test
    fun emitsExplicitNullsAndEmptyCollectionsWhenOptionalCapabilitiesAreUnavailable() {
        val status = JSONObject(
            MeshRuntimeStatusSerializer.serialize(
                running = false,
                onlineIntegrationsEnabled = false,
                walletBusy = false,
                walletAddress = null,
                offlineWalletAddress = null,
                offlineWalletTransfers = JSONArray(),
                offlineWalletReceipts = JSONArray(),
                offlineLibrary = JSONArray(),
                offlineLibraryHealthy = null,
                offlineLibraryCorrupt = null,
                offlineLibraryOverQuota = null,
                offlineLibraryCorruptBlobCount = null,
                offlineNetworkRunning = false,
                offlineNodeIp = null,
                offlinePortalUrl = null,
                recentEvents = JSONArray(),
                connectedPeers = JSONArray(),
                observedRoutes = JSONArray(),
                localNodeId = null
            )
        )

        listOf(
            "walletAddress",
            "offlineWalletAddress",
            "offlineLibraryHealthy",
            "offlineLibraryCorrupt",
            "offlineLibraryOverQuota",
            "offlineLibraryCorruptBlobCount",
            "offlineNodeIp",
            "offlinePortalUrl",
            "localNodeId"
        ).forEach { field -> assertEquals(JSONObject.NULL, status.get(field)) }
        assertEquals(0, status.getJSONArray("offlineWalletTransfers").length())
        assertEquals(0, status.getJSONArray("offlineWalletReceipts").length())
        assertEquals(0, status.getJSONArray("offlineLibrary").length())
        assertEquals(0, status.getJSONArray("recentEvents").length())
        assertEquals(0, status.getJSONArray("connectedPeers").length())
        assertEquals(0, status.getJSONArray("observedRoutes").length())
        assertFalse(status.getBoolean("offlineNetworkRunning"))
    }
}
