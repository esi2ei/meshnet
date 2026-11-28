package com.meshnet.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the existing dashboard runtime snapshot without owning Android service state.
 *
 * Keep field names and null/empty semantics stable: PhoneWorkspace consumes this payload as
 * its native runtime contract. New fields must be additive and covered by contract tests.
 */
internal object MeshRuntimeStatusSerializer {
    fun serialize(
        running: Boolean,
        onlineIntegrationsEnabled: Boolean,
        walletBusy: Boolean,
        walletAddress: String?,
        offlineWalletAddress: String?,
        offlineWalletTransfers: JSONArray,
        offlineWalletReceipts: JSONArray,
        offlineLibrary: JSONArray,
        offlineLibraryHealthy: Boolean?,
        offlineLibraryCorrupt: Boolean?,
        offlineLibraryOverQuota: Boolean?,
        offlineLibraryCorruptBlobCount: Int?,
        offlineNetworkRunning: Boolean,
        offlineNodeIp: String?,
        offlinePortalUrl: String?,
        recentEvents: JSONArray,
        connectedPeers: JSONArray,
        observedRoutes: JSONArray,
        localNodeId: String?
    ): String = JSONObject()
        .put("native", true)
        .put("platform", "android")
        .put("running", running)
        .put("onlineIntegrationsEnabled", onlineIntegrationsEnabled)
        .put("walletBusy", walletBusy)
        .put("walletAddress", walletAddress ?: JSONObject.NULL)
        .put("offlineWalletAddress", offlineWalletAddress ?: JSONObject.NULL)
        .put("offlineWalletTransfers", offlineWalletTransfers)
        .put("offlineWalletReceipts", offlineWalletReceipts)
        .put("offlineLibrary", offlineLibrary)
        .put("offlineLibraryHealthy", offlineLibraryHealthy ?: JSONObject.NULL)
        .put("offlineLibraryCorrupt", offlineLibraryCorrupt ?: JSONObject.NULL)
        .put("offlineLibraryOverQuota", offlineLibraryOverQuota ?: JSONObject.NULL)
        .put("offlineLibraryCorruptBlobCount", offlineLibraryCorruptBlobCount ?: JSONObject.NULL)
        .put("offlineNetworkRunning", offlineNetworkRunning)
        .put("offlineNodeIp", offlineNodeIp ?: JSONObject.NULL)
        .put("offlinePortalUrl", offlinePortalUrl ?: JSONObject.NULL)
        .put("recentEvents", recentEvents)
        .put("connectedPeers", connectedPeers)
        .put("observedRoutes", observedRoutes)
        .put("localNodeId", localNodeId ?: JSONObject.NULL)
        .toString()
}
