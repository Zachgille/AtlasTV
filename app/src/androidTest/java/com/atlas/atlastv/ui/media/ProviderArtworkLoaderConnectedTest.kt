package com.atlas.atlastv.ui.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.AtlasDatabase
import com.atlas.atlastv.data.db.ProviderArtworkRoomStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated Room/cache and a loopback PNG; no provider/network/account dependency. */
class ProviderArtworkLoaderConnectedTest {
    @Test fun diskPosterLoadsWhileAllFourHttpSlotsAreStalled() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artwork-disk-lane-${UUID.randomUUID()}").apply { mkdirs() }
        val socket = ServerSocket(0)
        val accepted = CountDownLatch(4); val release = CountDownLatch(1)
        val connections = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val requests = AtomicInteger()
        val server = Thread {
            try {
                repeat(4) {
                    val connection = socket.accept(); connections.add(connection)
                    connection.soTimeout = 2_000
                    val input = connection.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                    }
                    requests.incrementAndGet(); accepted.countDown()
                }
                release.await(6, TimeUnit.SECONDS)
            } catch (_: Exception) { }
            finally { connections.forEach { runCatching { it.close() } } }
        }.apply { isDaemon = true; start() }
        val saved = atlasImageRefFromRaw("http://127.0.0.1:${socket.localPort}/saved.png").providerImageToken
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        assertTrue(AtlasProviderImageCache(File(directory, AtlasProviderImageCache.DirectoryName)).write(saved, bytes))
        AtlasProviderImageLoader.clearMemoryForTests()
        val jobs = (0..3).map { key -> async {
            val token = atlasImageRefFromRaw("http://127.0.0.1:${socket.localPort}/stalled-$key.png").providerImageToken
            AtlasProviderImageLoader.load(token, directory)
        } }
        try {
            assertTrue("Expected four active HTTP requests", accepted.await(4, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val cached = withTimeout(1_000) { AtlasProviderImageLoader.load(saved, directory) }
            assertNotNull(cached); assertEquals(64, cached!!.width); assertEquals(96, cached.height)
            assertEquals(4, requests.get()); assertTrue(jobs.none { it.isCompleted })
            println("Cached poster with four stalled downloads: ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            release.countDown(); socket.close(); server.join(1_000)
            directory.deleteRecursively(); AtlasProviderImageLoader.clearMemoryForTests()
        }
    }

    @Test fun coldRegistryLookupDownloadsAndDecodesThenLoadsCachedPosterWithoutNetwork() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val directory = File(context.cacheDir, "artwork-loader-${UUID.randomUUID()}").apply { mkdirs() }
        val socket = ServerSocket(0)
        val requests = AtomicInteger()
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val server = Thread {
            runCatching {
                socket.accept().use { connection ->
                    val input = connection.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    connection.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val address = "http://127.0.0.1:${socket.localPort}/poster.png"
            val token = atlasImageRefFromRaw(address).providerImageToken
            val index = AtlasProviderImageIndex(File(directory, AtlasProviderImageIndex.FileName), ProviderArtworkRoomStore(db.providerArtworkDao()))
            index.registerAllAsync(listOf(token to address))
            AtlasProviderImageRegistry.clearForTests()
            // A damaged compressed file must be replaced by one valid download, never poison the cache.
            AtlasProviderImageCache(File(directory, AtlasProviderImageCache.DirectoryName)).write(token, byteArrayOf(1, 2, 3))
            val first = AtlasProviderImageLoader.load(token, directory, index)
            assertNotNull(first)
            assertEquals(64, first!!.width); assertEquals(96, first.height)
            assertEquals(1, requests.get())
            socket.close(); server.join(2_000)
            AtlasProviderImageLoader.clearMemoryForTests()
            val cached = AtlasProviderImageLoader.load(token, directory, AtlasProviderImageIndex(File(directory, AtlasProviderImageIndex.FileName), ProviderArtworkRoomStore(db.providerArtworkDao())))
            assertNotNull(cached)
            assertEquals(1, requests.get())
        } finally { socket.close(); db.close(); directory.deleteRecursively() }
    }

    @Test fun largeSourceIsDecodedForTheCardAndDiskReuseSupportsDifferentSizes() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artwork-sizing-${UUID.randomUUID()}").apply { mkdirs() }
        val socket = ServerSocket(0)
        val source = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> source.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        source.recycle()
        val server = Thread {
            runCatching { socket.accept().use { connection ->
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            val token = atlasImageRefFromRaw("http://127.0.0.1:${socket.localPort}/large.png").providerImageToken
            val first = AtlasProviderImageLoader.load(token, directory, target = ArtworkSize.forPixels(320, 480))!!
            assertEquals(384, first.width); assertEquals(576, first.height)
            socket.close(); server.join(2_000)
            AtlasProviderImageLoader.clearMemoryForTests()
            val smaller = AtlasProviderImageLoader.load(token, directory, target = ArtworkSize.forPixels(128, 128))!!
            assertEquals(128, smaller.width); assertEquals(192, smaller.height)
            assertTrue(AtlasProviderImageLoader.cachedMemoryBytes() <= AtlasProviderImageLoader.MaxMemoryBytes)
            println("Artwork decode: 1600x2400 source -> ${first.width}x${first.height} card; cold disk -> ${smaller.width}x${smaller.height}")
        } finally { socket.close(); directory.deleteRecursively(); AtlasProviderImageLoader.clearMemoryForTests() }
    }

    @Test fun sharedStalledDownloadSurvivesOneSubscriberAndClosesWhenLastSubscriberLeaves() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artwork-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        val socket = ServerSocket(0)
        val accepted = CountDownLatch(1); val closed = CountDownLatch(1); val requests = AtomicInteger()
        val server = Thread {
            runCatching { socket.accept().use { connection ->
                connection.soTimeout = 3_000
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                requests.incrementAndGet()
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                }
                accepted.countDown()
                if (input.read() == -1) closed.countDown()
            } }
        }.apply { isDaemon = true; start() }
        val token = atlasImageRefFromRaw("http://127.0.0.1:${socket.localPort}/stalled.png").providerImageToken
        val first = async { AtlasProviderImageLoader.load(token, directory) }
        val second = async { AtlasProviderImageLoader.load(token, directory) }
        try {
            assertTrue(accepted.await(3, TimeUnit.SECONDS)); delay(150)
            first.cancelAndJoin()
            assertFalse(closed.await(200, TimeUnit.MILLISECONDS)); assertFalse(second.isCompleted)
            val started = System.nanoTime()
            second.cancelAndJoin()
            assertTrue("Last subscriber must close stalled HTTP promptly", closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, requests.get())
            println("Artwork cancellation: shared request closed after last subscriber in ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally { first.cancel(); second.cancel(); socket.close(); server.join(3_500); directory.deleteRecursively() }
    }

    @Test fun unknownLengthOversizedDownloadReturnsFallbackAndIsNeverCached() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artwork-stream-limit-${UUID.randomUUID()}").apply { mkdirs() }
        val socket = ServerSocket(0)
        val server = Thread {
            runCatching { socket.accept().use { connection ->
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                connection.getOutputStream().apply {
                    write("HTTP/1.0 200 OK\r\nContent-Type: image/png\r\nConnection: close\r\n\r\n".toByteArray())
                    val chunk = ByteArray(64 * 1024)
                    repeat(129) { write(chunk) }; flush()
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            val token = atlasImageRefFromRaw("http://127.0.0.1:${socket.localPort}/oversize.png").providerImageToken
            assertNull(AtlasProviderImageLoader.load(token, directory))
            val cache = AtlasProviderImageCache(File(directory, AtlasProviderImageCache.DirectoryName))
            assertNull(cache.read(token))
            assertTrue(AtlasProviderImageLoader.cachedMemoryBytes() <= AtlasProviderImageLoader.MaxMemoryBytes)
        } finally { socket.close(); server.join(2_000); directory.deleteRecursively() }
    }
}
