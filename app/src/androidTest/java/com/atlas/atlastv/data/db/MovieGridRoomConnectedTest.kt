package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.movies.MovieItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Separate in-memory Room database; never touches the personal catalog. */
class MovieGridRoomConnectedTest {
    @Test fun fullPagingHasNoDuplicatesStableTiesAndIdBasedRefreshRestoration() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            val rows = (0..20000).map { index -> MovieItem("Movie $index", "Movies", 20001 - index, "mp4", "room://$index").toSafeCatalogEntity(index / 2, 1) }
            dao.insertAll(rows)
            val seen = mutableSetOf<Int>()
            for (offset in 0..20000 step 200) {
                val page = dao.pageForType("movie", offset, 200)
                assertTrue(page.size <= 200)
                page.forEach { assertTrue("Duplicate catalog ID", seen.add(it.itemId)) }
            }
            assertEquals(20001, seen.size)
            val first = dao.movieWindow(0)
            assertEquals(listOf(20000, 20001), first.items.take(2).map { it.itemId })
            val deep = dao.movieWindow(5001)
            assertEquals(15001, deep.items[deep.anchor - deep.offset].itemId)
            assertEquals(5001, dao.offsetForItem("movie", 15001))
            val last = dao.movieWindow(Int.MAX_VALUE)
            assertEquals(20000, last.anchor); assertEquals(1, last.items.last().itemId)
            assertTrue(last.items.size <= 200)
            // Move the selected title ahead of all other rows; restoration follows identity, not old offset.
            dao.insertAll(listOf(rows.first { it.itemId == 15001 }.copy(providerOrder = -1)))
            val restored = dao.movieWindow(5001, 15001)
            assertEquals(0, restored.anchor); assertEquals(15001, restored.items.first().itemId)
            assertNull(dao.offsetForItem("movie", -1))
        } finally { db.close() }
    }
}
