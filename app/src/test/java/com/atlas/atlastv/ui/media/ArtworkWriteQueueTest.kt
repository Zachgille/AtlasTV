package com.atlas.atlastv.ui.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtworkWriteQueueTest {
    @Test fun largerPrefetchedImageIsReusableAndMemoryEvictionStillApplies() = runTest {
        val images = ArtworkLoadCoordinator<Int, ByteArray>(backgroundScope, 8, 4) { it.size.toLong() }
        images.load(384) { byteArrayOf(1, 2, 3, 4) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), images.cachedMatching { it >= 256 })
        assertNull(images.cachedMatching { it >= 512 })
        images.load(640) { ByteArray(8) }
        assertEquals(8L, images.cachedBytes())
        assertNull(images.cachedMatching { it == 384 })
    }
    @Test fun slowDiskCannotBlockDeliveryAndQueuedBytesStayBounded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = ArtworkWriteQueue<Int>(backgroundScope) { gate.await(); written += it }
        assertTrue(queue.offer(1)); runCurrent()
        assertTrue(queue.offer(2)); assertFalse(queue.offer(3))
        assertTrue(written.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(1, 2), written)
    }

    @Test fun optionalWriteFailureDoesNotStopLaterImages() = runTest {
        val written = mutableListOf<Int>()
        val queue = ArtworkWriteQueue<Int>(backgroundScope) { if (it == 1) error("disk full") else written += it }
        queue.offer(1); runCurrent(); queue.offer(2); runCurrent()
        assertEquals(listOf(2), written)
    }
}
