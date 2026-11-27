package com.meshnet.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded history of packet paths actually observed by this node.
 *
 * These are route observations, not a claim that every edge is currently online.
 */
internal class MeshRouteRegistry {
    data class Route(
        val id: String,
        val nodes: List<String>,
        val observedAt: Long,
        val observations: Int
    )

    private val routes = linkedMapOf<String, Route>()

    @Synchronized
    fun observe(nodes: List<String>, now: Long = System.currentTimeMillis()): Boolean {
        val path = nodes.map(String::trim)
        if (path.size !in 2..MAX_ROUTE_NODES) return false
        if (path.any { it.isEmpty() || !it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }) return false
        if (path.distinct().size != path.size) return false

        prune(now)
        val id = path.joinToString(">")
        val previous = routes[id]
        if (previous == null) {
            routes[id] = Route(id, path, now, 1)
        } else {
            routes[id] = previous.copy(observedAt = now, observations = (previous.observations + 1).coerceAtMost(Int.MAX_VALUE))
        }
        trim()
        return previous == null
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): List<Route> {
        prune(now)
        return routes.values.sortedByDescending { it.observedAt }
    }

    @Synchronized
    fun toJson(now: Long = System.currentTimeMillis()): JSONArray = JSONArray().apply {
        snapshot(now).forEach { route ->
            put(JSONObject()
                .put("id", route.id)
                .put("nodes", JSONArray(route.nodes))
                .put("observedAt", route.observedAt)
                .put("observations", route.observations))
        }
    }

    @Synchronized
    fun clear() {
        routes.clear()
    }

    private fun prune(now: Long) {
        routes.entries.removeAll { now - it.value.observedAt > ROUTE_RETENTION_MS }
    }

    private fun trim() {
        while (routes.size > MAX_ROUTES) {
            val oldest = routes.values.minByOrNull { it.observedAt } ?: return
            routes.remove(oldest.id)
        }
    }

    companion object {
        const val MAX_ROUTE_NODES = 9
        private const val MAX_ROUTES = 64
        private const val ROUTE_RETENTION_MS = 30L * 60L * 1000L
    }
}
