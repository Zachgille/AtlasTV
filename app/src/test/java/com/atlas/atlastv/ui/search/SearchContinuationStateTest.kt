package com.atlas.atlastv.ui.search

import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchContinuationStateTest {
    private val movie = UnifiedSearchItem.Movie(MovieItem("Love movie", "Movies", 1, "mp4", "room://safe"))
    private val show = UnifiedSearchItem.Show(SeriesItem("Love show", "Shows", 1))
    private fun ready(query: String) = UnifiedSearchState.Ready(query, 600, 160, listOf(
        UnifiedSearchRow("unified_search_movies", "Movies", listOf(movie), 600),
        UnifiedSearchRow("unified_search_shows", "Shows", listOf(show), 160)))
    private fun grid(query: String, row: String) = SearchResultsGrid(query, row, "Results",
        CatalogGridStateHolder(CatalogGridSource { _, _ -> CatalogGridWindow(1, 0, 0, listOf(if (row.endsWith("movies")) movie else show)) },
            ::searchResultId, "safe error"))

    @Test fun leavingAndReturningRetainsQueryAndResultContextWithoutAnotherCatalogRead() = runTest {
        var calls = 0
        val holder = UnifiedSearchStateHolder(this, { calls++; ready(it) }, StandardTestDispatcher(testScheduler))
        holder.search("love"); advanceUntilIdle(); holder.selectResult(show)
        holder.listIndex = 1; holder.listOffset = 23; holder.rowScroll["unified_search_shows"] = 8 to 17
        holder.cancel(); holder.activate(); advanceUntilIdle()
        assertEquals(1, calls); assertEquals("love", holder.query)
        assertTrue(holder.restoreResult); assertEquals("show-1", holder.selectedResultId)
        assertEquals(1, holder.listIndex); assertEquals(8 to 17, holder.rowScroll["unified_search_shows"])
    }
    @Test fun continuationUsesCurrentQueryAndTypedRowAndReturnsToItsEntry() = runTest {
        val calls = mutableListOf<Pair<String,String>>()
        val holder = UnifiedSearchStateHolder(this, ::ready, StandardTestDispatcher(testScheduler), gridFactory = { q, row -> calls += q to row; grid(q,row) })
        holder.search("love"); advanceUntilIdle(); holder.openAll("unified_search_movies")
        val movies = holder.activeGrid!!; movies.holder.select(0)
        holder.selectResult(movie); assertFalse(holder.restoreResult)
        holder.cancel(); holder.activate(); assertSame(movies, holder.activeGrid)
        holder.closeAll(); assertEquals("unified_search_movies", holder.restoreEntryRowId)
        holder.openAll("unified_search_shows")
        assertEquals(listOf("love" to "unified_search_movies", "love" to "unified_search_shows"), calls)
        assertNotSame(movies.holder, holder.activeGrid!!.holder)
        holder.selectResult(show); assertEquals("show-1", holder.selectedResultId)
    }
    @Test fun newQueryClearsEveryOldGridAndScrollReferenceAndKeepsOnlyBoundedText() = runTest {
        val holder = UnifiedSearchStateHolder(this, ::ready, StandardTestDispatcher(testScheduler), gridFactory = ::grid)
        holder.search("old"); advanceUntilIdle(); holder.openAll("unified_search_movies"); holder.selectResult(movie)
        holder.listIndex = 1; holder.listOffset = 23; holder.rowScroll["unified_search_movies"] = 10 to 40
        holder.search("x".repeat(100)); advanceUntilIdle()
        assertNull(holder.activeGrid); assertNull(holder.selectedResultId); assertFalse(holder.restoreResult)
        assertNull(holder.restoreEntryRowId); assertEquals(0, holder.listIndex); assertEquals(0, holder.listOffset)
        assertTrue(holder.rowScroll.isEmpty()); assertEquals(80, holder.query.length)
        holder.openAll("unified_search_movies"); assertEquals(80, holder.activeGrid!!.query.length)
    }
    @Test fun emptyOrUnknownRowsCannotOpenContinuationAndClearCancelsPendingSearch() = runTest {
        var factories = 0
        val holder = UnifiedSearchStateHolder(this, ::ready, StandardTestDispatcher(testScheduler), gridFactory = { q,row -> factories++; grid(q,row) })
        holder.openAll("unified_search_movies"); holder.search("old"); holder.search(""); advanceUntilIdle()
        holder.openAll("unified_search_movies"); holder.search("love"); advanceUntilIdle(); holder.openAll("missing")
        assertEquals(0,factories); assertNull(holder.activeGrid)
    }
    @Test fun returningAfterCancellationRetriesPendingWorkAndCatalogChangeReloadsSavedQuery() = runTest {
        val calls = mutableListOf<String>()
        val holder = UnifiedSearchStateHolder(this, { calls += it; ready(it) }, StandardTestDispatcher(testScheduler))
        holder.search("love"); holder.cancel(); holder.activate(); advanceUntilIdle()
        assertEquals(listOf("love"),calls)
        holder.invalidateCatalog(); holder.activate(); advanceUntilIdle()
        assertEquals(listOf("love","love"),calls); assertEquals("love",holder.query)
    }
    @Test fun closingAfterCatalogShrinkFallsBackToAvailableResultOrInputInsteadOfDetachedEntry() = runTest {
        var preview = ready("love")
        val holder = UnifiedSearchStateHolder(this, { preview }, StandardTestDispatcher(testScheduler), gridFactory = ::grid)
        holder.search("love"); advanceUntilIdle(); holder.openAll("unified_search_movies")
        preview = preview.copy(movieCount=1,rows=listOf(preview.rows.first().copy(totalCount=1)))
        holder.invalidateCatalog(); holder.activate(); advanceUntilIdle(); holder.closeAll()
        assertNull(holder.restoreEntryRowId); assertTrue(holder.restoreResult); assertEquals("movie-1",holder.selectedResultId)
        holder.openAll("unified_search_movies")
        preview = preview.copy(movieCount=0,rows=emptyList())
        holder.invalidateCatalog(); holder.activate(); advanceUntilIdle(); holder.closeAll()
        assertNull(holder.restoreEntryRowId); assertFalse(holder.restoreResult); assertNull(holder.selectedResultId)
    }
}
