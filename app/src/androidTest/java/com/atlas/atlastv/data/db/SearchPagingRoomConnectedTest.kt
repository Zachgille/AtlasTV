package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.search.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Full filtered paging uses in-memory Room only, including overlapping Movie/Show IDs. */
class SearchPagingRoomConnectedTest {
    @Test fun completeFilteredPagesAndReorderedIdentityDoNotLeakOtherTypesOrNonMatches() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            val movies = (0..12000).map { i -> MovieItem("${if (i % 2 == 0) "Match" else "Other"} $i", "Archive", 12001-i, "mp4", "room://safe").toSafeCatalogEntity(i/3,1) }
            val shows = (0..2000).map { i -> SeriesItem("${if (i % 2 == 0) "Match" else "Other"} $i", "Shows", 2001-i).toSafeCatalogEntity(i/3,1) }
            dao.insertAll(movies + shows)
            for ((type, expected) in listOf("movie" to movies, "series" to shows)) {
                val matched = expected.filter { it.title.startsWith("Match") }.sortedWith(compareBy<SafeCatalogItemEntity> { it.providerOrder }.thenBy { it.itemId })
                val count = dao.countSearchItemsForType(type,"match")
                assertEquals(matched.size,count)
                val seen = mutableListOf<Int>()
                for (offset in 0 until count step 200) dao.searchPage(type,"match",offset,200).forEach {
                    assertEquals(type,it.contentType); assertTrue(it.title.startsWith("Match")); seen += it.itemId
                }
                assertEquals(matched.map { it.itemId },seen); assertEquals(seen.size,seen.toSet().size)
                val source = RoomSearchGridSource(dao,type,"match")
                val deepIndex = if (type == "movie") 5001 else 999
                val deep = source.load(deepIndex,null)
                assertTrue(deep.items.size <= 200); assertEquals(matched[deepIndex].itemId, searchResultId(deep.items[deep.anchor-deep.offset]))
                val last = source.load(Int.MAX_VALUE,null)
                assertEquals(count-1,last.anchor); assertEquals(matched.last().itemId,searchResultId(last.items.last()))
                val selected = matched[deepIndex]
                dao.insertAll(listOf(selected.copy(providerOrder = -3)))
                val moved = source.load(deepIndex,selected.itemId)
                assertEquals(0,moved.anchor); assertEquals(selected.itemId,searchResultId(moved.items.first()))
                dao.insertAll(listOf(selected.copy(title="Removed match",normalizedTitle="unrelated",categoryName="Elsewhere")))
                val removed = source.load(Int.MAX_VALUE,selected.itemId)
                assertEquals(count-2,removed.anchor); assertTrue(removed.items.none { searchResultId(it)==selected.itemId })
            }
            assertEquals(12000,dao.countSearchItemsForType("movie","archive"))
            assertEquals(0,dao.countSearchItemsForType("series","archive"))
        } finally { db.close() }
    }
}
