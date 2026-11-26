package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Separate in-memory database and temporary file, never the personal catalog/watchlist. */
class MoviesStorageConnectedTest {
    @Test fun legacyMovieSeedIsIdempotentPreservesShowsAndRollsBackOnCancellation() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val repo = SafeCatalogRepository(db.safeCatalogDao())
            repo.replaceSeries(listOf(SeriesItem("Saved show", "Shows", 9)))
            try {
                db.withTransaction { repo.seedLegacyMoviesIfEmpty(listOf(movie(1))); throw CancellationException("cancel") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(0, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie))
            repo.seedLegacyMoviesIfEmpty(listOf(movie(2)))
            repo.seedLegacyMoviesIfEmpty(listOf(movie(3)))
            assertEquals(listOf(2), repo.movieEntities().map { it.itemId })
            assertEquals(listOf(9), repo.seriesEntities().map { it.itemId })
        } finally { db.close() }
    }

    @Test fun boundedWatchlistUpdatePreservesAllUnseenRoomAndJsonSnapshots() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val file = File.createTempFile("movies-watchlist-check", ".json", context.cacheDir)
        try {
            val room = MoviesWatchlistRoomStore(db.movieWatchlistDao())
            val store = MoviesWatchlistStore(file, room)
            store.saveWatchlist((1..30).map(::movie), (1..30).toSet())
            store.saveWatchlist(listOf(movie(31)), (1..31).toSet())
            assertEquals((1..31).toSet(), store.loadWatchlistIds())
            assertEquals("Movie 1", room.loadItems().first { it.streamId == 1 }.title)
            store.saveWatchlist(listOf(movie(31)), (2..31).toSet())
            assertEquals((2..31).toSet(), store.loadWatchlistIds())
            room.clear()
            assertEquals((2..31).toSet(), store.loadWatchlistIds())
            assertEquals(30, room.count())
        } finally { db.close(); file.delete() }
    }
    private fun movie(id: Int) = MovieItem("Movie $id", "Movies", id, "mp4", "room://movie/$id")
}
