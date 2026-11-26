package com.atlas.atlastv.ui.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real cold HTTP/decode and subscription checks, using isolated files and generated PNGs. */
class ArtworkPriorityConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stalledSpeculationLeavesVisibleColdPosterCapacity() = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "artwork-priority-${UUID.randomUUID()}").apply { mkdirs() }
        Server().use { server ->
            val nearby = (1..6).map { id -> async {
                AtlasProviderImageLoader.load(server.token("stalled-$id"), directory, priority = ArtworkPriority.Prefetch)
            } }
            try {
                assertTrue(server.accepted.await(3, TimeUnit.SECONDS))
                val started = System.nanoTime()
                val image = withTimeout(2_000) { AtlasProviderImageLoader.load(server.token("visible"), directory,
                    target = ArtworkSize.forPixels(256, 384)) }
                assertNotNull(image)
                assertEquals(256, image!!.width)
                assertEquals(384, image.height)
                assertEquals(1, server.stalledRequests.get())
                assertTrue(nearby.none { it.isCompleted })
                println("ARTWORK PRIORITY cold visible PNG decoded in ${(System.nanoTime() - started) / 1_000_000} ms while six speculative subscribers remained pending/stalled")
            } finally {
                nearby.forEach { it.cancel() }; nearby.forEach { it.join() }
                server.release.countDown(); directory.deleteRecursively()
            }
        }
    }

    @Test fun smallerVisibleCardSharesLargerColdPrefetchAndSurvivesItsDeparture() = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "artwork-sharing-${UUID.randomUUID()}").apply { mkdirs() }
        Server().use { server ->
            val token = server.token("stalled-shared")
            val large = async { AtlasProviderImageLoader.load(token, directory,
                target = ArtworkSize.forPixels(512, 768), priority = ArtworkPriority.Prefetch) }
            var visible: Deferred<androidx.compose.ui.graphics.ImageBitmap?>? = null
            try {
                assertTrue(server.accepted.await(3, TimeUnit.SECONDS))
                visible = async { AtlasProviderImageLoader.load(token, directory, target = ArtworkSize.forPixels(256, 384)) }
                delay(200)
                large.cancelAndJoin()
                assertFalse(visible.isCompleted)
                server.release.countDown()
                val image = withTimeout(2_000) { visible.await() }
                assertNotNull(image); assertEquals(512, image!!.width); assertEquals(768, image.height)
                assertEquals("Two sizes must share one HTTP request", 1, server.stalledRequests.get())
                val again = AtlasProviderImageLoader.load(token, directory, target = ArtworkSize.forPixels(256, 384))
                assertNotNull(again); assertEquals(1, server.stalledRequests.get())
                println("ARTWORK PRIORITY 512x768 prefetch / 256x384 visible card shared one request and survived prefetch cancellation")
            } finally {
                large.cancelAndJoin(); visible?.cancelAndJoin()
                server.release.countDown(); directory.deleteRecursively()
            }
        }
    }

    private class Server : Closeable {
        private val socket = ServerSocket(0)
        private val connections = Collections.synchronizedList(mutableListOf<Socket>())
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stalledRequests = AtomicInteger()
        private val png = Bitmap.createBitmap(768, 1152, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.GREEN)
            try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
            finally { bitmap.recycle() }
        }
        private val thread = Thread {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connections += connection
                Thread {
                    runCatching { connection.use { client ->
                        client.soTimeout = 3_000
                        val input = client.getInputStream().bufferedReader()
                        val request = input.readLine().orEmpty()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val stalled = request.contains("/stalled-")
                        if (stalled) stalledRequests.incrementAndGet()
                        val output = client.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        if (stalled) { accepted.countDown(); release.await(8, TimeUnit.SECONDS) }
                        output.write(png); output.flush()
                    } }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        fun token(path: String) = atlasImageRefFromRaw("http://127.0.0.1:${socket.localPort}/$path.png").providerImageToken
        override fun close() {
            release.countDown(); socket.close()
            synchronized(connections) { connections.forEach { runCatching { it.close() } } }
            thread.join(1_000)
        }
    }
}
