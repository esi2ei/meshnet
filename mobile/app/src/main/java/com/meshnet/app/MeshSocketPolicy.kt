package com.meshnet.app

import java.net.Socket

/**
 * Resource limits for peer data sockets. Radio membership is user-controlled, but a
 * connected peer must not be able to create unbounded reader threads or block forever.
 */
internal object MeshSocketPolicy {
    const val MAX_ACTIVE_CONNECTIONS = 8
    const val READ_TIMEOUT_MS = 45_000

    fun canAdmit(running: Boolean, activeConnections: Int): Boolean =
        running && activeConnections in 0 until MAX_ACTIVE_CONNECTIONS

    fun configure(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = READ_TIMEOUT_MS
    }
}
