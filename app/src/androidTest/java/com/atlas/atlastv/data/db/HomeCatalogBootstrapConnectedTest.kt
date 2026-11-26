package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** In-memory migration checks; never opens or writes the personal catalog database. */
class HomeCatalogBootstrapConnectedTest {
    @Test fun seedIsAtomicIdempotentAndCannotReplaceAnExistingCatalog() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java).build()
        try {
            val repository = SafeCatalogRepository(db.safeCatalogDao())
            repository.seedLegacyCatalogIfEmpty(listOf(movie(1)), listOf(SeriesItem("Saved show", "Shows", 2)))
            assertEquals(1, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie))
            assertEquals(1, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series))
            repository.seedLegacyCatalogIfEmpty(listOf(movie(3)), listOf(SeriesItem("Other show", "Shows", 4)))
            assertEquals(listOf(1), repository.movieEntities().map { it.itemId })
            assertEquals(listOf(2), repository.seriesEntities().map { it.itemId })
            db.safeCatalogDao().deleteType(SafeCatalogContentTypes.Series)
            repository.seedLegacyCatalogIfEmpty(listOf(movie(3)), listOf(SeriesItem("Other show", "Shows", 4)))
            assertEquals(listOf(1), repository.movieEntities().map { it.itemId })
            assertEquals(0, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series))
        } finally { db.close() }
    }

    @Test fun cancelledMigrationRollsBackBothContentTypes() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java).build()
        try {
            val repository = SafeCatalogRepository(db.safeCatalogDao())
            try {
                db.withTransaction {
                    repository.seedLegacyCatalogIfEmpty(listOf(movie(1)), listOf(SeriesItem("Saved show", "Shows", 2)))
                    throw CancellationException("cancel migration")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(0, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie))
            assertEquals(0, db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series))
        } finally { db.close() }
    }

    private fun movie(id: Int) = MovieItem("Saved movie", "Movies", id, "mp4", "room://movie/$id")
}
