package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.search.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual SQLite ranking, preview/page/offset parity and bounded typed coverage; isolated Room. */
class SearchRelevanceRoomConnectedTest {
    private fun row(id: Int, title: String, order: Int, category: String = "English Shows") =
        SeriesItem(title,category,id).toSafeCatalogEntity(order,1)
    private fun fixture(block: suspend (SafeCatalogDao) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AtlasDatabase::class.java).build()
        try { block(db.safeCatalogDao()) } finally { db.close() }
    }
    @Test fun exactMomOutranksProviderFirstPartialAndCategoryMatchesWithAliasesAndTypedIds() = fixture { dao ->
        val exact = row(4107,"Mom",2083).copy(normalizedTitle = "mom en mom us ")
        dao.insertAll(listOf(row(1,"Momma Named Me Sheriff",0),row(2,"Mom Knows Best?",1),
            row(3,"Teen Mom",2),row(4,"Unrelated title",3,"Mom Collection"),exact,
            MovieItem("Mom","Movies",4107,"mp4","room://fixture").toSafeCatalogEntity(0,1)))
        val preview = SafeCatalogRepository(dao).searchSeriesEntities(" MoM ",18)
        assertEquals(listOf(4107,1,2,3,4),preview.map { it.itemId })
        assertEquals(5,dao.countSearchItemsForType("series","mom"))
        assertEquals(1,dao.countSearchItemsForType("movie","mom"))
        assertEquals(exact,preview.first())
        assertEquals(preview,dao.searchPage("series","mom",0,18,"MoM"))
        preview.forEachIndexed { index, item -> assertEquals(index,dao.searchOffset("series","mom",item.itemId,"MoM")) }
        assertEquals(0,RoomSearchGridSource(dao,"series"," MoM ").load(99,4107).anchor)
    }
    @Test fun rawPunctuationIsKeptForExactRankingInBothPreviewAndSeeAll() = fixture { dao ->
        val exact = row(99,"Workin' Moms",900).copy(normalizedTitle = "workin moms en workin moms ca ")
        dao.insertAll(listOf(row(1,"Workin Moms Club",0),exact))
        assertEquals(99,SafeCatalogRepository(dao).searchSeriesEntities(" Workin' Moms ",18).first().itemId)
        val grid=RoomSearchGridSource(dao,"series"," Workin' Moms ").load(0,null)
        assertEquals(listOf(99,1),grid.items.map(::searchResultId))
        assertEquals(1,dao.searchOffset("series","workin moms",1,"Workin' Moms"))
    }
    @Test fun rankedPagingCoversAllMatchesOnceAndReanchorsAfterRelevanceChanges() = fixture { dao ->
        val exact = listOf(row(9001,"Mom",900),row(9002,"MOM",900))
        val prefix=(1..40).map { row(it,"Mom Family $it",100) }
        val contains=(41..240).map { row(it,"The Mom Episode $it",0) }
        val category=(241..600).map { row(it,"Other $it",0,"Mom Collection") }
        val expected=exact+prefix+contains+category
        dao.insertAll(expected + listOf(row(700,"Excluded",0)))
        assertEquals(600+2,dao.countSearchItemsForType("series","mom"))
        val seen=(0 until expected.size step 200).flatMap { dao.searchPage("series","mom",it,200) }
        assertEquals(expected.map { it.itemId },seen.map { it.itemId })
        assertEquals(expected.size,seen.map { it.itemId }.toSet().size)
        assertEquals(expected.take(18),dao.searchItemsForType("series","mom",18))
        for (index in listOf(0,1,17,18,199,200,expected.lastIndex)) {
            assertEquals(index,dao.searchOffset("series","mom",expected[index].itemId))
            val window=dao.searchWindow("series","mom",0,expected[index].itemId)
            assertEquals(index,window.anchor)
            assertEquals(expected[index].itemId,window.items[index-window.offset].itemId)
        }
        val selected=category.last()
        dao.insertAll(listOf(selected.copy(title="Mom",normalizedTitle="mom",providerOrder=-1)))
        val moved=RoomSearchGridSource(dao,"series","Mom").load(500,selected.itemId)
        assertEquals(0,moved.anchor); assertEquals(selected.itemId,searchResultId(moved.items.first()))
        assertNull(dao.searchOffset("movie","mom",selected.itemId))
    }
    @Test fun blankQueryKeepsProviderOrderAndCountsAndAbsentSelectionRemainHonest() = fixture { dao ->
        val rows=listOf(row(9,"Mom",3),row(7,"Momma",1),row(2,"Unrelated",1))
        dao.insertAll(rows)
        val expected=listOf(rows[2],rows[1],rows[0])
        assertEquals(expected,dao.searchPage("series","",0,20))
        assertEquals(expected,dao.searchItemsForType("series","",20))
        assertEquals(3,dao.countSearchItemsForType("series",""))
        assertEquals(2,dao.searchOffset("series","",9))
        assertNull(dao.searchOffset("series","mom",2))
        assertTrue(dao.searchWindow("series","absent",0,9).items.isEmpty())
    }
}
