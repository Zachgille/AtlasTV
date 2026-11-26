package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.movies.MovieItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Separate in-memory Room only; exact category/type filtering, full coverage and refresh identities. */
class SeriesCategoryRoomConnectedTest {
    @Test fun allCategoriesIncludingSingletonsAndCollidingDisplayNamesHaveCompleteBoundedWindows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            val rows = (0..1000).map { i -> SeriesItem("Family Show $i", "Kids & Family", 1001 - i).toSafeCatalogEntity(i / 2, 1) }
            dao.insertAll(rows + (0..30).map { i -> SeriesItem("Other Show $i", "Category $i", 2000 + i).toSafeCatalogEntity(i, 1) } +
                listOf(SeriesItem("Different", "Kids Family", 5000).toSafeCatalogEntity(0, 1)))
            // Same label/type-independent ID must not enter the Movie category.
            dao.insertAll(listOf(MovieItem("A Movie", "Kids & Family", 1, "mp4", "room://safe").toSafeCatalogEntity(0, 1)))
            val categories = RoomSeriesCategoriesSource(dao).load()
            assertEquals(33, categories.size); assertEquals(31, categories.count { it.id.startsWith("Category ") && it.itemCount == 1 })
            assertEquals(1001, categories.single { it.id == "Kids & Family" }.itemCount)
            assertEquals(1, categories.single { it.id == "Kids Family" }.itemCount)
            val seen = mutableSetOf<Int>()
            for (offset in 0..1000 step 200) dao.seriesCategoryPage("Kids & Family", offset, 200).forEach {
                assertEquals("series", it.contentType); assertEquals("Kids & Family", it.categoryName); assertTrue(seen.add(it.itemId))
            }
            assertEquals(1001, seen.size)
            val first = RoomSeriesCategoryGridSource(dao, "Kids & Family").load(0, null)
            assertEquals(listOf(1000, 1001), first.items.take(2).map { it.seriesId })
            val last = dao.seriesCategoryWindow("Kids & Family", Int.MAX_VALUE)
            assertEquals(1000, last.anchor); assertEquals(1, last.items.last().itemId); assertTrue(last.items.size <= 200)
            val chosen = last.items.last()
            dao.insertAll(listOf(chosen.copy(providerOrder = -1)))
            val reordered = dao.seriesCategoryWindow("Kids & Family", last.anchor, chosen.itemId)
            assertEquals(0, reordered.anchor); assertEquals(chosen.itemId, reordered.items.first().itemId)
            assertNull(dao.categoryOffset("series", "Kids & Family", 5000))
            assertEquals(0, dao.seriesCategoryWindow("Missing", 1000).total)
            dao.insertAll(listOf(chosen.copy(categoryName = "New Category")))
            assertNull(dao.categoryOffset("series", "Kids & Family", chosen.itemId))
            assertEquals(999, dao.seriesCategoryWindow("Kids & Family", last.anchor, chosen.itemId).anchor)
        } finally { db.close() }
    }
}
