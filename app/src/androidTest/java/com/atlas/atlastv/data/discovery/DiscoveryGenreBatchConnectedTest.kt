package com.atlas.atlastv.data.discovery

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated Room fixtures, including duplicates whose canonical title is outside the genre. */
class DiscoveryGenreBatchConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(type: String, id: Int, title: String = "Title $id") = SafeCatalogItemEntity(
        type, id, title, categoryName = "English", providerOrder = id, containerExtension = "mp4",
        posterState = "Missing", posterSafeUri = null, posterProviderToken = null,
        posterRedactedSource = null, updatedAtMs = 10,
    )

    @Test fun batchingKeepsExactRankingVariantsTypesBoundsAndRefreshResults() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            val rows = listOf("movie", "series").flatMap { type -> (1..80).map { row(type, it) } }
            dao.insertAll(rows)
            dao.insertDiscovery(rows.map { item ->
                CatalogDiscoveryEntity(item.contentType, item.itemId, 10, "variant-${item.itemId}",
                    releaseSort = 20260101 + item.itemId % 3,
                    ratingMilli = 7000 + item.itemId % 4 * 500, ratingSource = "imdb", ratingVotes = 1000,
                    genres = "|Drama|Comedy|${if (item.itemId % 2 == 0) "Action|" else "Crime|"}")
            })
            // A lower canonical ID suppresses its duplicate even if only the duplicate is Drama.
            dao.insertDiscovery(listOf(
                CatalogDiscoveryEntity("movie", 1, 10, "shared", genres = "|Family|"),
                CatalogDiscoveryEntity("movie", 2, 10, "shared", genres = "|Drama|"),
                CatalogDiscoveryEntity("movie", 3, 9, "stale", genres = "|Drama|"),
                CatalogDiscoveryEntity("movie", 4, 10, "stale", genres = "|Drama|"),
                CatalogDiscoveryEntity("movie", 5, 10, "provider", ratingMilli = 10000,
                    ratingSource = "provider", ratingVotes = 1_000_000, genres = "|Drama|"),
                CatalogDiscoveryEntity("movie", 6, 10, "too-few-votes", ratingMilli = 10000,
                    ratingSource = "imdb", ratingVotes = 999, genres = "|Drama|"),
                CatalogDiscoveryEntity("movie", 7, 10, "not-a-genre", genres = "|Dramatic|"),
            ))
            suspend fun compare() {
                for (type in listOf("movie", "series")) for (limit in listOf(1, 12, 500)) {
                    val actual = dao.genreCollections(discoveryGenreQuery(type, limit)).groupBy { it.shelfGenre }
                    for (genre in DiscoveryCollectionSpec.Genres) assertEquals("$type/$genre/$limit",
                        dao.genreDiscovery(type, genre, limit), actual[genre].orEmpty().map { it.item })
                    assertTrue(actual.values.all { it.size <= limit })
                }
            }
            compare()
            val drama = dao.genreCollections(discoveryGenreQuery("movie", 500)).filter { it.shelfGenre == "Drama" }.map { it.item.itemId }
            assertFalse(2 in drama); assertFalse(3 in drama); assertTrue(4 in drama); assertFalse(7 in drama)
            assertTrue(drama.indexOf(5) > drama.indexOf(8)); assertTrue(drama.indexOf(6) > drama.indexOf(8))
            // No persisted/runtime result cache: changed scores, genres and removals apply immediately.
            dao.insertDiscovery(listOf(CatalogDiscoveryEntity("movie", 8, 10, "refreshed",
                ratingMilli = 10000, ratingSource = "imdb", ratingVotes = 5000, genres = "|Animation|")))
            dao.removeCatalogIds("movie", listOf(1, 10))
            compare()
            assertEquals(8, dao.genreCollections(discoveryGenreQuery("movie", 12)).single { it.shelfGenre == "Animation" }.item.itemId)
            assertTrue(dao.genreCollections(discoveryGenreQuery("movie", 500)).any { it.shelfGenre == "Drama" && it.item.itemId == 2 })
        } finally { db.close() }
    }

    @Test fun emptySparseAndUnrecognizedTypesStayBounded() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            assertTrue(dao.genreCollections(discoveryGenreQuery("movie", 12)).isEmpty())
            dao.insertAll(listOf(row("movie", 1), row("movie", 2), row("series", 1)))
            dao.insertDiscovery(listOf(CatalogDiscoveryEntity("movie", 1, 10, "one", genres = "|Family|")))
            assertEquals(listOf(1), dao.genreCollections(discoveryGenreQuery("movie", 0)).map { it.item.itemId })
            assertTrue(dao.genreCollections(discoveryGenreQuery("series", 12)).isEmpty())
            assertTrue(dao.genreCollections(discoveryGenreQuery("movie' OR 1=1 --", 12)).isEmpty())
        } finally { db.close() }
    }
}
