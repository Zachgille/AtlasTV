package com.atlas.atlastv.data.discovery

import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.BuildConfig
import com.atlas.atlastv.data.db.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Run with -e require_non_debug true against the installed preview. All DB writes are in memory. */
class BundledDiscoveryConnectedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun verifyTarget() {
        if (InstrumentationRegistry.getArguments().getString("require_non_debug") == "true")
            assertEquals("Expected the normal non-debug app", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
    }
    private fun item(type: String, id: Int, title: String) = SafeCatalogItemEntity(type, id, title,
        categoryId = "fixture", categoryName = "English", providerOrder = id, containerExtension = "mp4",
        posterState = "Missing", posterSafeUri = null, posterProviderToken = null, posterRedactedSource = null, updatedAtMs = 100)

    @Test fun freshDatabaseBuildsRankedMovieAndShowShelvesFromTheActualPackagedSnapshot() = runBlocking(Dispatchers.IO) {
        verifyTarget()
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            assertEquals(0, dao.countForType("movie")); assertEquals(0, dao.countForType("series"))
            val rows = listOf(item("movie", 1, "The Shawshank Redemption (1994)"), item("movie", 2, "Inception (2010)"),
                item("movie", 3, "Unmatched fixture title ZQX (2025)"), item("series", 1, "Breaking Bad (2008)"), item("series", 2, "Mom (2013)"))
            dao.insertAll(rows)
            assertEquals(5, DiscoveryLegacySeeder(dao).run())
            // Even a legacy 10/10 provider score must neither enter Rated nor survive an unmatched independent lookup.
            dao.insertDiscovery(listOf(DiscoveryMetadata.legacy(rows[2], System.currentTimeMillis()).copy(
                ratingMilli = 10000, ratingSource = "provider-10", ratingVotes = 1_000_000)))
            assertTrue(dao.ratedDiscovery("movie", 18).isEmpty())
            assertEquals(5, ImdbDiscoveryIndexer(dao, { ImdbDiscoveryIndex.from(context) }).update())
            assertEquals(listOf(1, 2), dao.ratedDiscovery("movie", 18).map { it.itemId })
            assertEquals(listOf(1, 2), dao.ratedDiscovery("series", 18).map { it.itemId })
            val movies = dao.discoveryMetadataForIds("movie", listOf(1, 2, 3)).associateBy { it.itemId }
            assertTrue((movies.getValue(1).ratingMilli ?: 0) >= 7000); assertEquals("tt0111161", movies.getValue(1).imdbId)
            assertTrue((movies.getValue(2).ratingMilli ?: 0) >= 7000); assertEquals("imdb", movies.getValue(2).ratingSource)
            assertNull(movies.getValue(3).ratingMilli); assertNull(movies.getValue(3).ratingSource)
            assertTrue(movies.values.all { it.ratingDataset == BuildConfig.IMDB_DISCOVERY_DATASET })
            assertEquals(0, ImdbDiscoveryIndexer(dao, { error("Verified data must not reload its index") }).update())
            val repo = SafeCatalogRepository(dao)
            assertTrue(repo.discoveryCollections("movie", 18).any { it.id == "movies_rated" })
            assertTrue(repo.discoveryCollections("series", 18).any { it.id == "series_rated" })
        } finally { db.close() }
    }
    @Test fun packagedProvenanceAndAttributionMatchThePinnedBuildMetadata() {
        verifyTarget()
        val receipt = context.assets.open("imdb-discovery-receipt.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        assertEquals(BuildConfig.IMDB_DISCOVERY_SHA256, receipt["snapshotSha256"].asString)
        assertEquals(BuildConfig.IMDB_DISCOVERY_RECORDS, receipt["records"].asInt)
        assertEquals(BuildConfig.IMDB_DISCOVERY_MINIMUM_VOTES, receipt["minimumVotes"].asInt)
        val notice = context.assets.open("IMDb-NOTICE.txt").bufferedReader().use { it.readText() }
        assertTrue(notice.contains("Information courtesy of IMDb")); assertTrue(notice.contains("Used with permission."))
    }
}
