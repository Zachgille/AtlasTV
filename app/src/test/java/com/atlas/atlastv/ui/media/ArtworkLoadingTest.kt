package com.atlas.atlastv.ui.media

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkLoadingTest {
    @Test fun diskHitsBypassSaturatedNetworkSlotsAndMissesStillRespectTheNetworkLimit() = runTest {
        val gate = CompletableDeferred<Unit>(); var networkStarts = 0
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 4) { 1 }
        val network = (0..3).map { key -> async {
            loader.load("network-$key", { null }) { networkStarts++; gate.await(); "download" }
        } }
        runCurrent(); assertEquals(4, networkStarts)
        assertEquals("disk", loader.load("saved", { "disk" }) { error("A disk hit must not fetch") })
        val miss = async { loader.load("miss", { null }) { networkStarts++; "later" } }
        runCurrent(); assertFalse(miss.isCompleted); assertEquals(4, networkStarts)
        // The saved hit is also available in the same decoded memory cache.
        assertEquals("disk", loader.load("saved") { error("Memory hit must not fetch") })
        gate.complete(Unit)
        network.forEach { assertEquals("download", it.await()) }
        assertEquals("later", miss.await()); assertEquals(5, networkStarts)
    }

    @Test fun diskReadsAreBoundedAndAbandonedQueuedReadsDoNotStart() = runTest {
        val gate = CompletableDeferred<Unit>(); var starts = 0; var active = 0; var peak = 0
        val loader = ArtworkLoadCoordinator<Int, String>(backgroundScope, 32, 4) { 1 }
        val jobs = (0..5).map { key -> async {
            loader.load(key, {
                starts++; active++; peak = maxOf(peak, active)
                try { gate.await(); "disk" } finally { active-- }
            }) { error("Saved posters must not download") }
        } }
        runCurrent(); assertEquals(2, starts)
        jobs.last().cancelAndJoin(); gate.complete(Unit)
        jobs.dropLast(1).forEach { assertEquals("disk", it.await()) }
        assertEquals(5, starts); assertEquals(2, peak)
    }

    @Test fun sharedDiskReadKeepsRemainingSubscriberAndHonorsMemoryPressure() = runTest {
        val gate = CompletableDeferred<Unit>(); var reads = 0; var cancelled = false
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 4) { 1 }
        val read: suspend () -> String? = {
            reads++
            try { gate.await(); "disk" } finally { cancelled = !currentCoroutineContext().isActive }
        }
        val first = async { loader.load("saved", read) { error("Unexpected download") } }
        val second = async { loader.load("saved", read) { error("Unexpected download") } }
        runCurrent(); first.cancelAndJoin(); assertEquals(1, reads); assertFalse(cancelled)
        loader.clearMemory(); gate.complete(Unit)
        assertEquals("disk", second.await()); assertEquals(0L, loader.cachedBytes())
    }

    @Test fun duplicateSubscribersShareWorkAndOneLeavingDoesNotCancelTheOther() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0; var cancelled = false
        val loader = ArtworkLoadCoordinator<String, ByteArray>(backgroundScope, 16, 2) { it.size.toLong() }
        val fetch: suspend () -> ByteArray? = { calls++; try { gate.await(); byteArrayOf(1) } finally { cancelled = !currentCoroutineContext().isActive } }
        val first = async { loader.load("same", fetch) }; val second = async { loader.load("same", fetch) }
        runCurrent(); assertEquals(1, calls)
        first.cancelAndJoin(); runCurrent(); assertFalse(cancelled)
        gate.complete(Unit); assertEquals(listOf<Byte>(1), second.await()!!.toList())
        assertEquals(1, calls)
    }

    @Test fun lastSubscriberLeavingCancelsRunningWorkAndAllowsFreshRetry() = runTest {
        var cancelled = false
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 16, 1) { 1 }
        val first = async { loader.load("poster") { try { awaitCancellation() } finally { cancelled = true } } }
        runCurrent(); first.cancelAndJoin(); runCurrent(); assertTrue(cancelled)
        assertEquals("fresh", loader.load("poster") { "fresh" })
    }

    @Test fun concurrencyIsBoundedAndAbandonedQueuedLoadNeverStarts() = runTest {
        val gate = CompletableDeferred<Unit>(); var active = 0; var peak = 0; var starts = 0
        val loader = ArtworkLoadCoordinator<Int, String>(backgroundScope, 32, 4) { 1 }
        val jobs = (0..9).map { key -> async { loader.load(key) { starts++; active++; peak = maxOf(peak, active); gate.await(); active--; "ok" } } }
        runCurrent(); assertEquals(4, starts)
        jobs.last().cancelAndJoin(); gate.complete(Unit)
        jobs.dropLast(1).forEach { assertEquals("ok", it.await()) }
        assertEquals(9, starts); assertEquals(4, peak)
    }

    @Test fun memoryEvictsLeastRecentlyUsedAndOversizedResultsAreNotRetained() = runTest {
        val calls = mutableListOf<String>()
        val loader = ArtworkLoadCoordinator<String, ByteArray>(backgroundScope, 6, 2) { it.size.toLong() }
        suspend fun load(key: String, size: Int = 3) = loader.load(key) { calls += key; ByteArray(size) }
        load("a"); load("b"); load("a"); load("c"); load("b")
        assertEquals(listOf("a", "b", "c", "b"), calls)
        assertEquals(6, loader.cachedBytes())
        load("large", 7); load("large", 7)
        assertEquals(2, calls.count { it == "large" }); assertEquals(6, loader.cachedBytes())
    }

    @Test fun memoryPressureClearDoesNotRepopulateFromAlreadyRunningLoads() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 16, 1) { 1 }
        val first = async { loader.load("a") { gate.await(); "ok" } }
        runCurrent(); loader.clearMemory(); gate.complete(Unit)
        assertEquals("ok", first.await()); assertEquals(0, loader.cachedBytes())
        assertEquals("new", loader.load("a") { "new" }); assertEquals(1, loader.cachedBytes())
        loader.clearMemory(); assertEquals(0, loader.cachedBytes())
    }

    @Test fun missingAndFailedLoadsDoNotPoisonFutureLoads() = runTest {
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 16, 1) { 1 }
        assertNull(loader.load("a") { null })
        assertEquals("recovered", loader.load("a") { "recovered" })
    }

    @Test fun streamedBodiesStopAtLimitPlusOneAndRejectEmptyOrOversize() {
        assertNull(readArtworkBytes(ByteArrayInputStream(byteArrayOf()), 4))
        assertEquals(4, readArtworkBytes(ByteArrayInputStream(ByteArray(4)), 4)!!.size)
        val input = ByteArrayInputStream(ByteArray(100))
        assertNull(readArtworkBytes(input, 4)); assertEquals(95, input.available())
    }

    @Test fun decodingPreservesSmallPostersAndBoundsLargeOrExtremeAspectImages() {
        val target = ArtworkSize.forPixels(320, 480)
        val small = ArtworkDecodePlan.forSource(64, 96, target)!!
        assertEquals(1, small.sampleSize); assertEquals(64, small.width); assertEquals(96, small.height)
        val large = ArtworkDecodePlan.forSource(4000, 6000, target)!!
        assertTrue(large.sampleSize >= 8); assertTrue(large.width <= 384 && large.height <= 576)
        val extreme = ArtworkDecodePlan.forSource(16, 32768, target)!!
        assertTrue(extreme.width.toLong() * extreme.height <= ArtworkDecodePlan.MaxDecodedPixels)
        assertTrue(extreme.height <= ArtworkDecodePlan.MaxDecodedSide)
        assertNull(ArtworkDecodePlan.forSource(100_000, 100_000, target))
        assertNull(ArtworkDecodePlan.forSource(0, 100, target))
        assertEquals(ArtworkSize.forPixels(330, 450), ArtworkSize.forPixels(340, 460))
        assertEquals(1024, ArtworkSize.forPixels(Int.MAX_VALUE, Int.MAX_VALUE).width)
    }

    @Test fun diskEvictionUsesRecentReadsAndSurvivesNewCacheInstance() = withDirectory { dir ->
        val cache = AtlasProviderImageCache(dir, maxBytes = 4, maxTotalBytes = 8)
        val a = token('a'); val b = token('b'); val c = token('c')
        assertTrue(cache.write(a, ByteArray(4))); assertTrue(cache.write(b, ByteArray(4)))
        File(dir, cache.fileNameForToken(a)!!).setLastModified(1)
        File(dir, cache.fileNameForToken(b)!!).setLastModified(2)
        assertNotNull(cache.read(a)); assertTrue(cache.write(c, ByteArray(4)))
        val cold = AtlasProviderImageCache(dir, maxBytes = 4, maxTotalBytes = 8)
        assertNotNull(cold.read(a)); assertNull(cold.read(b)); assertNotNull(cold.read(c))
        assertEquals(8, dir.listFiles()!!.sumOf { it.length() })
    }

    @Test fun oversizedLegacyCacheAndPendingFilesAreTrimmedWithoutTouchingUnrelatedFiles() = withDirectory { dir ->
        val cache = AtlasProviderImageCache(dir, maxBytes = 4, maxTotalBytes = 4)
        File(dir, cache.fileNameForToken(token('a'))!!).writeBytes(ByteArray(8))
        File(dir, "${cache.fileNameForToken(token('b'))}.pending").writeBytes(ByteArray(3))
        val unrelated = File(dir, "keep.txt").apply { writeText("keep") }
        assertNull(cache.read(token('a')))
        assertEquals(listOf(unrelated.name), dir.listFiles()!!.map { it.name })
        assertFalse(cache.write("a".repeat(64), ByteArray(1)))
        assertFalse(cache.write(token('a'), ByteArray(5)))
    }

    @Test fun concurrentCacheInstancesKeepCompleteEntriesWithinTotalBudget() = withDirectory { dir ->
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = (0..31).map { index -> executor.submit<Boolean> {
                AtlasProviderImageCache(dir, maxBytes = 1024, maxTotalBytes = 4096)
                    .write(PROVIDER_IMAGE_TOKEN_PREFIX + index.toString(16).padStart(64, '0'), ByteArray(1024) { index.toByte() })
            } }
            assertTrue(work.all { it.get() })
            val files = dir.listFiles()!!
            assertEquals(4096, files.sumOf { it.length() })
            assertTrue(files.all { file -> val bytes = file.readBytes(); bytes.size == 1024 && bytes.all { it == bytes[0] } })
            assertFalse(files.any { it.name.endsWith(".pending") })
        } finally { executor.shutdownNow() }
    }

    @Test fun repeatedHitsAndMissesShareMaintenanceAcrossFreshCacheInstances() = withDirectory { dir ->
        var scans=0
        val counted=object : File(dir.path) { override fun listFiles(): Array<File>? {scans++;return super.listFiles()} }
        File(dir,"${'a'.toString().repeat(64)}.img").writeBytes(byteArrayOf(1))
        repeat(40) {
            val cache=AtlasProviderImageCache(counted,clockNanos={0L})
            assertEquals(listOf<Byte>(1),cache.read(token('a'))!!.toList())
            assertNull(cache.read(token('b')))
        }
        assertEquals("A full directory scan must not precede every hit or miss",1,scans)
    }

    @Test fun periodicMaintenanceAndPolicyChangeStillCleanLegacyFilesAndEnforceBudgets() = withDirectory { dir ->
        var now=0L
        val cache=AtlasProviderImageCache(dir,maxBytes=4,maxTotalBytes=8,clockNanos={now})
        assertTrue(cache.write(token('a'),ByteArray(4)))
        File(dir,"${cache.fileNameForToken(token('b'))}.pending").writeBytes(ByteArray(3))
        assertNotNull(cache.read(token('a')))
        now=30_000_000_000L
        assertNotNull(cache.read(token('a')))
        assertFalse(File(dir,"${cache.fileNameForToken(token('b'))}.pending").exists())
        // A smaller budget must not reuse another instance's maintenance grace period.
        val smaller=AtlasProviderImageCache(dir,maxBytes=2,maxTotalBytes=2,clockNanos={now})
        assertNull(smaller.read(token('a')))
        assertFalse(File(dir,cache.fileNameForToken(token('a'))!!).exists())
    }

    @Test fun failedReplacementPreservesPreviouslyCommittedBytes() = withDirectory { dir ->
        val cache = AtlasProviderImageCache(dir, maxBytes = 8, maxTotalBytes = 16)
        val key = token('a'); val original = byteArrayOf(1, 2, 3)
        assertTrue(cache.write(key, original))
        File(dir, "${cache.fileNameForToken(key)}.pending").mkdir()
        assertFalse(cache.write(key, byteArrayOf(4, 5, 6)))
        assertEquals(original.toList(), cache.read(key)!!.toList())
    }

    @Test fun failedWriteUnderSmallerPolicyDoesNotProtectAnEntryAboveTheEntireBudget() = withDirectory { dir ->
        val original=AtlasProviderImageCache(dir,maxBytes=8,maxTotalBytes=16)
        assertTrue(original.write(token('a'),ByteArray(8)))
        File(dir,"${original.fileNameForToken(token('a'))}.pending").mkdir()
        val smaller=AtlasProviderImageCache(dir,maxBytes=8,maxTotalBytes=4)
        assertFalse(smaller.write(token('a'),ByteArray(3)))
        assertTrue(dir.listFiles()!!.filter {it.isFile}.sumOf {it.length()}<=4)
    }

    private fun token(char: Char) = PROVIDER_IMAGE_TOKEN_PREFIX + char.toString().repeat(64)
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("atlas-artwork-bounds-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
