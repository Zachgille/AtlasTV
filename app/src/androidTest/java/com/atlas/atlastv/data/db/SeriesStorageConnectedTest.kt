package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Separate in-memory database and temporary file, never the personal catalog/watchlist. */
class SeriesStorageConnectedTest {
    @Test fun legacySeriesSeedIsIdempotentPreservesMoviesAndRollsBackOnCancellation() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val repo = SafeCatalogRepository(db.safeCatalogDao())
            repo.replaceMovies(listOf(com.atlas.atlastv.ui.movies.MovieItem("Saved movie", "Movies", 9, "mp4", "room://movie/9")))
            try {
                db.withTransaction { repo.seedLegacySeriesIfEmpty(listOf(show(1))); throw CancellationException("cancel") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(0, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series))
            repo.seedLegacySeriesIfEmpty(listOf(show(2)))
            repo.seedLegacySeriesIfEmpty(listOf(show(3)))
            assertEquals(listOf(2), repo.seriesEntities().map { it.itemId })
            assertEquals(listOf(9), repo.movieEntities().map { it.itemId })
        } finally { db.close() }
    }

    @Test fun boundedWatchlistUpdatePreservesAllUnseenRoomAndJsonSnapshots() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val file = File.createTempFile("series-watchlist-check", ".json", context.cacheDir)
        try {
            val room = SeriesWatchlistRoomStore(db.seriesWatchlistDao())
            val store = SeriesWatchlistStore(file, room)
            store.saveWatchlist((1..30).map(::show), (1..30).toSet())
            store.saveWatchlist(listOf(show(31)), (1..31).toSet())
            assertEquals((1..31).toSet(), store.loadWatchlistIds())
            assertEquals("Show 1", room.loadItems().first { it.seriesId == 1 }.title)
            store.saveWatchlist(listOf(show(31)), (2..31).toSet())
            assertEquals((2..31).toSet(), store.loadWatchlistIds())
            room.clear()
            assertEquals((2..31).toSet(), store.loadWatchlistIds())
            assertEquals(30, room.count())
        } finally { db.close(); file.delete() }
    }
    private fun show(id: Int) = SeriesItem("Show $id", "Shows", id)
}
