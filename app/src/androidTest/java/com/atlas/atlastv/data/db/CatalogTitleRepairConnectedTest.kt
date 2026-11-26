package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogTitleRepairConnectedTest {
    @Test fun titleRepairPreservesTypedSavesAndCheckpointsAndNewerImportsWin() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java).build()
        try {
            val row = SafeCatalogItemEntity("series", 7, "(2022)", " 2022 max from 2022 us ", "one", "English", 3,
                containerExtension = null, posterState = "Missing", posterSafeUri = null, posterProviderToken = null, posterRedactedSource = null, updatedAtMs = 9)
            db.safeCatalogDao().insertAll(listOf(row, row.copy(contentType = "movie", title = "Other movie")))
            db.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(7, "(2022)", "English", 101), SeriesWatchlistEntity(8, "Keep", "English", 102)))
            db.movieWatchlistDao().insertAll(listOf(MovieWatchlistEntity(7, "Other movie", "English", "mp4", 103)))
            val progress = WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 31, 7, "Episode 1", seriesTitle = "(2022)",
                durationMs = 600_000, positionMs = 125_000, updatedAtMs = 203)
            db.watchProgressDao().upsert(progress.toWatchProgressEntity())
            assertTrue(db.catalogTitleRepairDao().repair(row, "FROM (2022)"))
            assertEquals(listOf(SeriesWatchlistEntity(8, "Keep", "English", 102), SeriesWatchlistEntity(7, "FROM (2022)", "English", 101)), db.seriesWatchlistDao().items())
            assertEquals("Other movie", db.movieWatchlistDao().items().single().title)
            assertEquals(progress.copy(seriesTitle = "FROM (2022)"), db.watchProgressDao().records().single().toWatchProgressRecordOrNull())
            assertFalse(db.catalogTitleRepairDao().repair(row, "Stale"))
            db.safeCatalogDao().insertAll(listOf(row.copy(updatedAtMs = 10)))
            assertFalse(db.catalogTitleRepairDao().repair(row, "Stale"))
            db.openHelper.writableDatabase.query("SELECT title FROM safe_catalog_items WHERE contentType='series' AND itemId=7").use {
                assertTrue(it.moveToFirst()); assertEquals("(2022)", it.getString(0))
            }
            assertEquals(2, db.safeCatalogDao().countForType("series") + db.safeCatalogDao().countForType("movie"))
        } finally { db.close() }
    }
}
