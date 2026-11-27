package com.meshnet.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded history of authenticated packet paths and reverse next-hop candidates.
 * Group AEAD authenticates possession of the shared key, not an individual device identity.
 */
internal class MeshRouteRegistry {
    data class Route(val id: String, val nodes: List<String>, val observedAt: Long, val observations: Int)
    data class NextHop(val peer: String, val distance: Int, val observedAt: Long)

    private val routes = linkedMapOf<String, Route>()
    private val nextHops = linkedMapOf<String, LinkedHashMap<String, NextHop>>()

    @Synchronized
    fun observe(nodes: List<String>, now: Long = System.currentTimeMillis(), ingressPeer: String? = null): Boolean {
        val path = nodes.map { it.trim() }
        if (path.size !in 2..MAX_ROUTE_NODES) return false
        if (path.any { it.isEmpty() || !it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }) return false
        if (path.distinct().size != path.size) return false
        prune(now)
        val id = path.joinToString(">")
        val previous = routes[id]
        routes[id] = if (previous == null) Route(id, path, now, 1) else previous.copy(
            observedAt = now,
            observations = if (previous.observations == Int.MAX_VALUE) Int.MAX_VALUE else previous.observations + 1
        )
        if (!ingressPeer.isNullOrBlank() && ingressPeer.length <= 160) {
            val localIndex = path.lastIndex
            for (index in 0 until localIndex) {
                val destination = path[index]
                val distance = localIndex - index
                val candidates = nextHops.getOrPut(destination) { linkedMapOf() }
                val previousHop = candidates[ingressPeer]
                if (previousHop == null || distance < previousHop.distance || distance == previousHop.distance) {
                    candidates[ingressPeer] = NextHop(ingressPeer, distance, now)
                }
                while (candidates.size > MAX_ALTERNATIVES) {
                    val worst = candidates.values.maxWithOrNull(compareBy<NextHop> { it.distance }.thenBy { it.observedAt }) ?: break
                    candidates.remove(worst.peer)
                }
            }
        }
        trim()
        return previous == null
    }

    @Synchronized
    fun nextHopsFor(destination: String, now: Long = System.currentTimeMillis()): List<String> {
        prune(now)
        return nextHops[destination]?.values
            ?.sortedWith(compareBy<NextHop> { it.distance }.thenByDescending { it.observedAt })
            ?.map { it.peer } ?: emptyList()
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): List<Route> {
        prune(now)
        return routes.values.sortedByDescending { it.observedAt }
    }

    @Synchronized
    fun toJson(now: Long = System.currentTimeMillis()): JSONArray = JSONArray().apply {
        snapshot(now).forEach { route ->
            put(JSONObject().put("id", route.id).put("nodes", JSONArray(route.nodes))
                .put("observedAt", route.observedAt).put("observations", route.observations))
        }
    }

    @Synchronized
    fun removePeer(peer: String) {
        nextHops.entries.removeAll { (_, candidates) ->
            candidates.remove(peer)
            candidates.isEmpty()
        }
    }

    @Synchronized
    fun clear() {
        routes.clear()
        nextHops.clear()
    }

    private fun prune(now: Long) {
        routes.entries.removeAll { now - it.value.observedAt > ROUTE_RETENTION_MS }
        nextHops.entries.removeAll { (_, candidates) ->
            candidates.entries.removeAll { now - it.value.observedAt > ROUTE_RETENTION_MS }
            candidates.isEmpty()
        }
    }

    private fun trim() {
        while (routes.size > MAX_ROUTES) {
            val oldest = routes.values.minByOrNull { it.observedAt } ?: return
            routes.remove(oldest.id)
        }
        while (nextHops.size > MAX_DESTINATIONS) {
            val oldest = nextHops.entries.minByOrNull { entry -> entry.value.values.maxOfOrNull { it.observedAt } ?: 0L } ?: return
            nextHops.remove(oldest.key)
        }
    }

    companion object {
        const val MAX_ROUTE_NODES = 9
        private const val MAX_ROUTES = 64
        private const val MAX_DESTINATIONS = 256
        private const val MAX_ALTERNATIVES = 3
        private const val ROUTE_RETENTION_MS = 30L * 60L * 1000L
    }
}
