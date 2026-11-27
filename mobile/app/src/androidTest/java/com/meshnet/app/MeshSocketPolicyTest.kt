package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshSocketPolicyTest {
    @Test
    fun rejectsConnectionsWhenTransportIsStopped() {
        assertFalse(MeshSocketPolicy.canAdmit(running = false, activeConnections = 0))
    }

    @Test
    fun admitsOnlyWithinThePeerConnectionLimit() {
        assertTrue(MeshSocketPolicy.canAdmit(running = true, activeConnections = 0))
        assertTrue(MeshSocketPolicy.canAdmit(
            running = true,
            activeConnections = MeshSocketPolicy.MAX_ACTIVE_CONNECTIONS - 1
        ))
        assertFalse(MeshSocketPolicy.canAdmit(
            running = true,
            activeConnections = MeshSocketPolicy.MAX_ACTIVE_CONNECTIONS
        ))
        assertFalse(MeshSocketPolicy.canAdmit(running = true, activeConnections = -1))
    }

    @Test
    fun configuresAReadDeadlineForEachPeerSocket() {
        val socket = java.net.Socket()
        try {
            MeshSocketPolicy.configure(socket)
            assertEquals(MeshSocketPolicy.READ_TIMEOUT_MS, socket.soTimeout)
        } finally {
            socket.close()
        }
    }
}
