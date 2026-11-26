package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MovieBrowseSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private class MemoryStore(var value: MovieBrowseSession?) : MovieBrowseSessionStore {
        override suspend fun load(owner: String) = value?.takeIf { it.owner == owner }
        override fun schedule(value: MovieBrowseSession) { this.value = value }
        override suspend fun awaitSaved() {}
    }
    private fun movie(id: Int) = MovieItem("Movie $id", "Drama", id, "mp4", "")
    private fun grid(dispatcher: CoroutineDispatcher, anchor: Int = 4) = MovieGridStateHolder(MovieGridSource { _, id ->
        MovieGridWindow(10, 0, anchor, (1..10).map { movie(if (it == anchor + 1) id ?: it else it) })
    }, dispatcher)
    private fun browser(dispatcher: CoroutineDispatcher) = MovieCategoryBrowser(MovieCategoriesStateHolder(
        MovieCategoriesSource { listOf(MovieCategory("Drama", "Drama", 10)) }, dispatcher), { grid(dispatcher) })

    @Test fun shelfContextSurvivesSavedSessionAndRemainsAccountBound() = runTest {
        val d = StandardTestDispatcher(testScheduler); val categories = browser(d)
        val store = MemoryStore(null)
        val holder = MovieBrowseSessionStateHolder(store, "owner", grid(d), categories, MovieWatchlistBrowser(grid(d)))
        holder.restore(); categories.shelfNavigation.select("rated", 8, 7); categories.shelfNavigation.scroll("rated", 6, 12)
        holder.checkpoint()
        val next = browser(d)
        MovieBrowseSessionStateHolder(store, "owner", grid(d), next, MovieWatchlistBrowser(grid(d))).restore()
        assertEquals(BrowseShelfPosition(8, 7, 6, 12), next.shelfNavigation.position("rated", (1..18).toList()))
        val other = browser(d)
        MovieBrowseSessionStateHolder(store, "different", grid(d), other, MovieWatchlistBrowser(grid(d))).restore()
        assertTrue(other.shelfNavigation.snapshot().rows.isEmpty())
        // Older files have no shelves field; preserve their existing grid navigation.
        val legacy = Gson().fromJson("{\"owner\":\"owner\",\"surface\":\"browse\"}", MovieBrowseSession::class.java)
        assertNull(legacy.shelves)
    }

    @Test fun categoryAndAllGridRestoreBeforeLoadsAndReanchorByIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val all = grid(dispatcher); val categories = browser(dispatcher); val saved = MovieBrowseSession("owner", "category",
            all = MovieGridPosition(900, 500, 496, 7), categoryName = "Drama", category = MovieGridPosition(800, 200, 196, 11),
            originRowId = "movies_category_drama", selectedCategory = "Drama", categoriesIndex = 14, categoriesOffset = 9, browseIndex = 4)
        val store = MemoryStore(saved)
        val holder = MovieBrowseSessionStateHolder(store, "owner", all, categories, MovieWatchlistBrowser(grid(dispatcher)))
        holder.restore(); assertTrue(holder.ready); assertTrue(categories.grid!!.open); assertFalse(all.open)
        assertEquals("Drama", categories.categoryName); assertEquals("movies_category_drama", categories.originRowId)
        assertEquals(14, categories.categories.scrollIndex); assertEquals(4, categories.browseScrollIndex)
        assertEquals(900, all.selectedKey); assertEquals(800, categories.grid!!.selectedKey)
        backgroundScope.launch { categories.grid!!.activate(0) }; runCurrent()
        assertEquals(4, categories.grid!!.state.value.selectedIndex)
        categories.grid!!.select(4); holder.checkpoint()
        assertEquals(800, store.value!!.category!!.selectedId); assertEquals(4, store.value!!.category!!.selectedIndex)
        assertEquals(saved.all, store.value!!.all)
    }
    @Test fun closingGridRemainsClosedAndPreviousPositionsAreRetained() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(MovieBrowseSession("owner", "all", MovieGridPosition(50, 49, 48, 4)))
        val all = grid(d); val holder = MovieBrowseSessionStateHolder(store, "owner", all, browser(d), MovieWatchlistBrowser(grid(d)))
        holder.restore(); assertTrue(all.open); all.open = false; holder.checkpoint()
        assertEquals("browse", store.value!!.surface); assertEquals(50, store.value!!.all.selectedId)
        val reopened = grid(d); MovieBrowseSessionStateHolder(store, "owner", reopened, browser(d), MovieWatchlistBrowser(grid(d))).restore()
        assertFalse(reopened.open); assertEquals(50, reopened.selectedKey)
    }
    @Test fun explicitDetailNavigationDoesNotReopenSavedGrid() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(MovieBrowseSession("owner", "category", categoryName = "Drama", category = MovieGridPosition(4, 3)))
        val categories = browser(d)
        MovieBrowseSessionStateHolder(store, "owner", grid(d), categories, MovieWatchlistBrowser(grid(d))).restore(reopen = false)
        assertFalse(categories.grid!!.open); assertEquals(4, categories.grid!!.selectedKey); assertFalse(categories.categoriesOpen)
    }
    @Test fun newAccountDoesNotInheritOldAccountSelectionAndNullAccountDoesNotSave() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(MovieBrowseSession("first", "all", MovieGridPosition(50, 49)))
        val all = grid(d); val holder = MovieBrowseSessionStateHolder(store, "second", all, browser(d), MovieWatchlistBrowser(grid(d)))
        holder.restore(); assertNull(all.selectedKey); assertFalse(all.open); holder.checkpoint(); assertEquals("second", store.value!!.owner)
        MovieBrowseSessionStateHolder(store, null, grid(d), browser(d), MovieWatchlistBrowser(grid(d))).apply { restore(); checkpoint() }
        assertEquals("second", store.value!!.owner)
    }
    @Test fun canceledSlowRestorationCannotPublishAfterLeavingMovies() = runTest {
        val d = StandardTestDispatcher(testScheduler); val gate = CompletableDeferred<Unit>()
        val store = object : MovieBrowseSessionStore {
            override suspend fun load(owner: String): MovieBrowseSession { withContext(NonCancellable) { gate.await() }; return MovieBrowseSession(owner, "all") }
            override fun schedule(value: MovieBrowseSession) { error("must not save") }
            override suspend fun awaitSaved() {}
        }
        val all = grid(d); val holder = MovieBrowseSessionStateHolder(store, "owner", all, browser(d), MovieWatchlistBrowser(grid(d)))
        val job = launch { holder.restore() }; runCurrent(); job.cancel(); gate.complete(Unit); runCurrent()
        assertFalse(holder.ready); assertFalse(all.open); holder.checkpoint()
    }
    @Test fun watchlistAndCategoryListRestoreTheirIndependentContext() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(MovieBrowseSession("owner", "watchlist", selectedCategory = "Drama",
            categoriesIndex = 7, watchlist = MovieGridPosition(40, 39, 36, 10), watchlistOriginRow = true))
        val categories = browser(d); val watchlist = MovieWatchlistBrowser(grid(d))
        val holder = MovieBrowseSessionStateHolder(store, "owner", grid(d), categories, watchlist)
        holder.restore(); assertTrue(watchlist.grid.open); assertTrue(watchlist.originRow); assertEquals(40, watchlist.grid.selectedKey)
        watchlist.grid.open = false; categories.categoriesOpen = true; holder.checkpoint()
        val next = browser(d)
        MovieBrowseSessionStateHolder(store, "owner", grid(d), next, MovieWatchlistBrowser(grid(d))).restore()
        assertTrue(next.categoriesOpen); assertEquals("Drama", next.categories.selectedName); assertEquals(7, next.categories.scrollIndex)
    }
    @Test fun invalidAndMissingCategoryStateResetsWhilePositionsAreBounded() {
        assertNull(MovieBrowseSession("owner", "category").validated())
        assertNull(MovieBrowseSession("owner", categoryName = "http://private").validated())
        assertNull(MovieBrowseSession("owner", all = MovieGridPosition(-1)).validated())
        val safe = MovieBrowseSession("owner", all = MovieGridPosition(1, -2, Int.MAX_VALUE, -4)).validated()!!
        assertEquals(0, safe.all.selectedIndex); assertEquals(10_000_000, safe.all.scrollIndex); assertEquals(0, safe.all.scrollOffset)
    }
    @Test fun fileCoalescesLatestSnapshotAndMalformedFilesAreOptional() = runBlocking {
        val file = File(temp.root, "browse.json"); val store = FileMovieBrowseSessionStore(file)
        repeat(20) { store.schedule(MovieBrowseSession("owner", "all", MovieGridPosition(it + 1, it))) }
        withTimeout(5_000) { store.awaitSaved() }
        val disk = Gson().fromJson(file.readText(), MovieBrowseSession::class.java)
        assertEquals(20, disk.all.selectedId); assertNull(store.load("other"))
        assertFalse(file.readText().contains("redactedStreamUrl")); assertFalse(file.readText().contains("password"))
        val malformed = File(temp.root, "bad.json").apply { writeText("{broken") }
        assertNull(FileMovieBrowseSessionStore(malformed).load("owner"))
    }
}
