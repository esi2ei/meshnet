package com.meshnet.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshBleTransportTest {
    @Test
    fun repeatedWriteFailuresStillAllowOneMaximumSizeDefaultMtuFrame() {
        // At the default ATT MTU: 23 - 3 ATT bytes - 10 MeshNet header bytes = 10.
        // A 900-byte signed frame therefore requires 90 chunks.
        val defaultMtuPayloadBytes = 23 - 3 - 10
        val maximumFrameChunks =
            (MeshFrame.MAX_FRAME_BYTES + defaultMtuPayloadBytes - 1) / defaultMtuPayloadBytes

        assertEquals(90, maximumFrameChunks)
        assertEquals(90, MeshBleTransport.adaptiveQueueLimit(failures = 3, frameChunkCount = maximumFrameChunks))
    }

    @Test
    fun adaptiveBackoffStillBoundsSmallFrameQueueAdmission() {
        assertEquals(32, MeshBleTransport.adaptiveQueueLimit(failures = 3, frameChunkCount = 1))
        assertEquals(32, MeshBleTransport.adaptiveQueueLimit(failures = 3, frameChunkCount = 32))
        assertEquals(90, MeshBleTransport.adaptiveQueueLimit(failures = 3, frameChunkCount = 90))
        assertEquals(256, MeshBleTransport.adaptiveQueueLimit(failures = 0, frameChunkCount = 90))
    }
}
