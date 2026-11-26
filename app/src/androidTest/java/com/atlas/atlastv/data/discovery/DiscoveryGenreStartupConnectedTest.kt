package com.atlas.atlastv.data.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only profiling of the retained library; no provider or personal-state writes. */
class DiscoveryGenreStartupConnectedTest {
    @Test fun sharedShelvesMatchEveryRetainedTitleAndReportCosts() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val db = AtlasDatabaseProvider.database(InstrumentationRegistry.getInstrumentation().targetContext)
        val dao = db.safeCatalogDao()
        for (round in 1..3) for (type in listOf("movie", "series")) {
            val expected = mutableMapOf<String, List<com.atlas.atlastv.data.db.SafeCatalogItemEntity>>()
            var shared = emptyMap<String, List<DiscoveryGenreItem>>()
            suspend fun individual(): Long {
                val start = System.nanoTime()
                for (genre in DiscoveryCollectionSpec.Genres) expected[genre] = dao.genreDiscovery(type, genre, 12)
                return (System.nanoTime() - start) / 1_000_000
            }
            suspend fun batched(): Long {
                val start = System.nanoTime()
                shared = dao.genreCollections(discoveryGenreQuery(type, 12)).groupBy { it.shelfGenre }
                return (System.nanoTime() - start) / 1_000_000
            }
            val oldMs: Long
            val batchMs: Long
            if (round % 2 == 0) { oldMs = individual(); batchMs = batched() }
            else { batchMs = batched(); oldMs = individual() }
            for (genre in DiscoveryCollectionSpec.Genres) assertEquals("$type/$genre", expected[genre], shared[genre].orEmpty().map { it.item })
            println("GENRE BATCH $type round=$round shared-ms=$batchMs individual-ms=$oldMs")
        }
    }
}
