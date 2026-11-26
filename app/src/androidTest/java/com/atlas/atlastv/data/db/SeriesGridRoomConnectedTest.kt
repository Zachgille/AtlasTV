package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.movies.MovieItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** In-memory Room; overlapping Movie IDs must never appear in the Shows grid. */
class SeriesGridRoomConnectedTest {
    @Test fun fullSeriesPagingHasStableTiesNoMovieLeakAndRestoresReorderedShow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            val rows = (0..12000).map { index -> SeriesItem("Show $index", "Shows", 12001 - index).toSafeCatalogEntity(index / 2, 1) }
            dao.insertAll(rows)
            dao.insertAll(listOf(MovieItem("Movie collision", "Movies", 6001, "mp4", "room://movie").toSafeCatalogEntity(-1, 1)))
            val seen = mutableSetOf<Int>()
            for (offset in 0..12000 step 200) {
                val page = dao.pageForType("series", offset, 200)
                assertTrue(page.size <= 200)
                page.forEach { assertEquals("series", it.contentType); assertTrue(seen.add(it.itemId)) }
            }
            assertEquals(12001, seen.size)
            val source = RoomSeriesGridSource(dao)
            val first = source.load(0, null)
            assertEquals(listOf(12000, 12001), first.items.take(2).map { it.seriesId })
            val deep = source.load(6001, null)
            assertEquals(6001, deep.items[deep.anchor - deep.offset].seriesId)
            val last = source.load(Int.MAX_VALUE, null)
            assertEquals(12000, last.anchor); assertEquals(1, last.items.last().seriesId)
            dao.insertAll(listOf(rows.first { it.itemId == 6001 }.copy(providerOrder = -2)))
            val restore = source.load(6001, 6001)
            assertEquals(0, restore.anchor); assertEquals(6001, restore.items.first().seriesId)
            assertTrue(restore.items.size <= 200); assertEquals(1, dao.countForType("movie"))
        } finally { db.close() }
    }
}
