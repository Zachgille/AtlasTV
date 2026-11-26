package com.atlas.atlastv.ui.search

import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val movies = "unified_search_movies"
    private val shows = "unified_search_shows"
    private fun item(row: String, id: Int): UnifiedSearchItem = if (row == movies)
        UnifiedSearchItem.Movie(MovieItem("Movie $id", "Movies", id, "mp4", ""))
        else UnifiedSearchItem.Show(SeriesItem("Show $id", "Shows", id))
    private fun result(query: String) = UnifiedSearchState.Ready(query, 500, 500, listOf(
        UnifiedSearchRow(movies, "Movies", (1..18).map { item(movies, it) }, 500),
        UnifiedSearchRow(shows, "Shows", (1..18).map { item(shows, it) }, 500)))
    private class MemoryStore(var value: SearchSession?) : SearchSessionStore {
        var load: suspend (String) -> SearchSession? = { owner -> value?.takeIf { it.owner == owner } }
        override suspend fun load(owner: String) = load.invoke(owner)
        override fun schedule(session: SearchSession) { value = session }
        override suspend fun awaitSaved() {}
    }

    @Test fun restartRestoresQueryTypedGridIdentityAndReanchorsAnItemWhosePositionChanged() = runTest {
        val store = MemoryStore(SearchSession("owner", "term", "show-300", position = SearchScrollPosition(1, 8),
            rows = mapOf(shows to SearchScrollPosition(3, 12)), grid = SearchGridSession(shows, 300, 299, SearchScrollPosition(290, 4))))
        var requested: Int? = null
        val holder = UnifiedSearchStateHolder(backgroundScope, ::result, StandardTestDispatcher(testScheduler), 0,
            gridFactory = { query, row -> SearchResultsGrid(query, row, "Results", CatalogGridStateHolder(CatalogGridSource { _, id ->
                requested = id
                CatalogGridWindow(500, 0, 9, (0..199).map { item(row, if (it == 9) 300 else it + 1000) })
            }, ::searchResultId, "Error", StandardTestDispatcher(testScheduler))) }, sessionStore = store, sessionOwner = { "owner" })
        holder.activate(); runCurrent()
        assertEquals("term", holder.query); assertEquals(shows, holder.activeGrid!!.rowId)
        val grid = holder.activeGrid!!.holder
        assertEquals(300, grid.selectedKey); assertEquals(290, grid.scrollIndex)
        backgroundScope.launch { grid.activate(0) }; runCurrent()
        assertEquals(300, requested); assertEquals(9, grid.state.value.selectedIndex)
        grid.select(9); holder.checkpoint()
        assertEquals(300, store.value!!.grid!!.selectedId); assertEquals(9, store.value!!.grid!!.selectedIndex)
        assertEquals(1, holder.listIndex); assertEquals(3 to 12, holder.rowScroll[shows])
    }

    @Test fun clearingSearchPersistsAnEmptyContextInsteadOfResurrectingAnOldGrid() = runTest {
        val store = MemoryStore(SearchSession("owner", "term", "movie-4"))
        fun holder() = UnifiedSearchStateHolder(backgroundScope, ::result, StandardTestDispatcher(testScheduler), 0,
            sessionStore = store, sessionOwner = { "owner" })
        val first = holder(); first.activate(); runCurrent(); first.search(""); runCurrent()
        assertEquals("", store.value!!.query); assertNull(store.value!!.grid); assertNull(store.value!!.selectedResultId)
        val second = holder(); second.activate(); runCurrent()
        assertEquals("", second.query); assertNull(second.activeGrid); assertFalse(second.restoreResult)
    }

    @Test fun lateSavedContextCannotReplaceTextEnteredWhileItWasLoading() = runTest {
        val old = SearchSession("owner", "old", "movie-4")
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore(old).apply { load = { withContext(NonCancellable) { release.await(); old } } }
        val holder = UnifiedSearchStateHolder(backgroundScope, ::result, StandardTestDispatcher(testScheduler), 0,
            sessionStore = store, sessionOwner = { "owner" })
        holder.activate(); runCurrent(); holder.search("new"); release.complete(Unit); runCurrent()
        assertEquals("new", holder.query); assertEquals("new", store.value!!.query); assertNull(holder.selectedResultId)
    }

    @Test fun providerChangesCannotRestoreOrSaveAnotherAccountsSearchContext() = runTest {
        var owner = "first"
        val store = MemoryStore(SearchSession(owner, "private search", "movie-4"))
        val holder = UnifiedSearchStateHolder(backgroundScope, ::result, StandardTestDispatcher(testScheduler), 0,
            sessionStore = store, sessionOwner = { owner })
        holder.activate(); runCurrent(); assertEquals("private search", holder.query)
        owner = "second"; holder.checkpoint(); assertEquals("first", store.value!!.owner)
        holder.activate(); runCurrent()
        assertEquals("", holder.query); assertNull(holder.selectedResultId); assertNull(holder.activeGrid)
        assertEquals("second", store.value!!.owner)
    }

    @Test fun previewFocusAndBothRowPositionsAreRetainedWithoutStoringResultModels() = runTest {
        val store = MemoryStore(SearchSession("owner", "term", "movie-4", position = SearchScrollPosition(1, 6),
            rows = mapOf(movies to SearchScrollPosition(2, 10), shows to SearchScrollPosition(3, 20))))
        val holder = UnifiedSearchStateHolder(backgroundScope, ::result, StandardTestDispatcher(testScheduler), 0,
            sessionStore = store, sessionOwner = { "owner" })
        holder.activate(); runCurrent(); assertTrue(holder.restoreResult); assertEquals("movie-4", holder.selectedResultId)
        holder.rememberEntryFocus(shows); assertEquals(shows, store.value!!.entryRowId)
        holder.rememberResultFocus(item(shows, 7)); assertNull(store.value!!.entryRowId); assertEquals("show-7", store.value!!.selectedResultId)
        assertEquals(mapOf(movies to SearchScrollPosition(2, 10), shows to SearchScrollPosition(3, 20)), store.value!!.rows)
    }

    @Test fun missingSavedIdentityFallsBackToAValidSurvivingResult() = runTest {
        val store = MemoryStore(SearchSession("owner", "term", grid = SearchGridSession(movies, 999, 999, SearchScrollPosition(995, 0))))
        val holder = UnifiedSearchStateHolder(backgroundScope, ::result, StandardTestDispatcher(testScheduler), 0,
            gridFactory = { query, row -> SearchResultsGrid(query, row, "Results", CatalogGridStateHolder(CatalogGridSource { index, _ ->
                CatalogGridWindow(5, 0, index.coerceIn(0, 4), (1..5).map { item(row, it) })
            }, ::searchResultId, "Error", StandardTestDispatcher(testScheduler))) }, sessionStore = store, sessionOwner = { "owner" })
        holder.activate(); runCurrent(); val grid = holder.activeGrid!!.holder
        backgroundScope.launch { grid.activate(0) }; runCurrent(); assertEquals(4, grid.state.value.selectedIndex)
        grid.select(4); holder.checkpoint(); assertEquals(5, store.value!!.grid!!.selectedId)
    }

    @Test fun fileWritesCoalesceToTheLatestContextAndNeverPersistModelsOrCredentials() = runBlocking {
        val file = File(temporary.root, "search.json"); val store = FileSearchSessionStore(file)
        repeat(20) { store.schedule(SearchSession("owner", "term $it", "movie-4")) }
        withTimeout(5_000) { store.awaitSaved() }
        val disk = Gson().fromJson(file.readText(), SearchSession::class.java)
        assertEquals("term 19", disk.query); assertNull(store.load("other"))
        assertFalse(file.readText().contains("redactedStreamUrl")); assertFalse(file.readText().contains("password"))
    }

    @Test fun removedPreviewRowsAndEmptyResultsCannotLeaveARestoredFocusTargetThatDoesNotExist() = runTest {
        val saved = SearchSession("owner", "term", entryRowId = shows)
        val store = MemoryStore(saved)
        val holder = UnifiedSearchStateHolder(backgroundScope, { result(it).copy(seriesCount = 0, rows = result(it).rows.take(1)) },
            StandardTestDispatcher(testScheduler), 0, sessionStore = store, sessionOwner = { "owner" })
        holder.activate(); runCurrent(); assertNull(holder.restoreEntryRowId); assertTrue(holder.restoreResult)
        val empty = UnifiedSearchStateHolder(backgroundScope, { UnifiedSearchState.Ready(it, 0, 0, emptyList()) },
            StandardTestDispatcher(testScheduler), 0, sessionStore = MemoryStore(saved), sessionOwner = { "owner" })
        empty.activate(); runCurrent(); assertNull(empty.restoreEntryRowId); assertFalse(empty.restoreResult)
    }

    @Test fun malformedOrUnsafeSessionsResetSafelyAndCoordinatesStayBounded() = runBlocking {
        val file = File(temporary.root, "bad.json").apply { writeText("{broken") }
        assertNull(FileSearchSessionStore(file).load("owner"))
        assertNull(SearchSession("owner", "http://private").validated())
        assertNull(SearchSession("owner", "term", grid = SearchGridSession("bad", 1, 0, SearchScrollPosition())).validated())
        val safe = SearchSession("owner", "term", position = SearchScrollPosition(-5, Int.MAX_VALUE)).validated()!!
        assertEquals(SearchScrollPosition(0, 100_000), safe.position)
    }
}
