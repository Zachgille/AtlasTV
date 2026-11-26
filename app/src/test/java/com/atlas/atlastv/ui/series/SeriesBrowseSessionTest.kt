package com.atlas.atlastv.ui.series

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
class SeriesBrowseSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private class MemoryStore(var value: SeriesBrowseSession?) : SeriesBrowseSessionStore {
        override suspend fun load(owner: String) = value?.takeIf { it.owner == owner }
        override fun schedule(value: SeriesBrowseSession) { this.value = value }
        override suspend fun awaitSaved() {}
    }
    private fun show(id: Int) = SeriesItem("Show $id", "Drama", id)
    private fun grid(dispatcher: CoroutineDispatcher, anchor: Int = 4) = SeriesGridStateHolder(SeriesGridSource { _, id ->
        SeriesGridWindow(10, 0, anchor, (1..10).map { show(if (it == anchor + 1) id ?: it else it) })
    }, dispatcher)
    private fun browser(dispatcher: CoroutineDispatcher) = SeriesCategoryBrowser(SeriesCategoriesStateHolder(
        SeriesCategoriesSource { listOf(SeriesCategory("Drama", "Drama", 10)) }, dispatcher), { grid(dispatcher) })

    @Test fun shelfContextSurvivesSavedSessionAndRemainsAccountBound() = runTest {
        val d = StandardTestDispatcher(testScheduler); val categories = browser(d)
        val store = MemoryStore(null)
        val holder = SeriesBrowseSessionStateHolder(store, "owner", grid(d), categories, SeriesWatchlistBrowser(grid(d)))
        holder.restore(); categories.shelfNavigation.select("rated", 8, 7); categories.shelfNavigation.scroll("rated", 6, 12)
        holder.checkpoint()
        val next = browser(d)
        SeriesBrowseSessionStateHolder(store, "owner", grid(d), next, SeriesWatchlistBrowser(grid(d))).restore()
        assertEquals(BrowseShelfPosition(8, 7, 6, 12), next.shelfNavigation.position("rated", (1..18).toList()))
        val other = browser(d)
        SeriesBrowseSessionStateHolder(store, "different", grid(d), other, SeriesWatchlistBrowser(grid(d))).restore()
        assertTrue(other.shelfNavigation.snapshot().rows.isEmpty())
        // Older files have no shelves field; preserve their existing grid navigation.
        val legacy = Gson().fromJson("{\"owner\":\"owner\",\"surface\":\"browse\"}", SeriesBrowseSession::class.java)
        assertNull(legacy.shelves)
    }

    @Test fun categoryAndAllGridRestoreBeforeLoadsAndReanchorByIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val all = grid(dispatcher); val categories = browser(dispatcher); val saved = SeriesBrowseSession("owner", "category",
            all = SeriesGridPosition(900, 500, 496, 7), categoryName = "Drama", category = SeriesGridPosition(800, 200, 196, 11),
            originRowId = "shows_category_drama", selectedCategory = "Drama", categoriesIndex = 14, categoriesOffset = 9, browseIndex = 4)
        val store = MemoryStore(saved)
        val holder = SeriesBrowseSessionStateHolder(store, "owner", all, categories, SeriesWatchlistBrowser(grid(dispatcher)))
        holder.restore(); assertTrue(holder.ready); assertTrue(categories.grid!!.open); assertFalse(all.open)
        assertEquals("Drama", categories.categoryName); assertEquals("shows_category_drama", categories.originRowId)
        assertEquals(14, categories.categories.scrollIndex); assertEquals(4, categories.browseScrollIndex)
        assertEquals(900, all.selectedKey); assertEquals(800, categories.grid!!.selectedKey)
        backgroundScope.launch { categories.grid!!.activate(0) }; runCurrent()
        assertEquals(4, categories.grid!!.state.value.selectedIndex)
        categories.grid!!.select(4); holder.checkpoint()
        assertEquals(800, store.value!!.category!!.selectedId); assertEquals(4, store.value!!.category!!.selectedIndex)
        assertEquals(saved.all, store.value!!.all)
    }
    @Test fun closingGridRemainsClosedAndPreviousPositionsAreRetained() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(SeriesBrowseSession("owner", "all", SeriesGridPosition(50, 49, 48, 4)))
        val all = grid(d); val holder = SeriesBrowseSessionStateHolder(store, "owner", all, browser(d), SeriesWatchlistBrowser(grid(d)))
        holder.restore(); assertTrue(all.open); all.open = false; holder.checkpoint()
        assertEquals("browse", store.value!!.surface); assertEquals(50, store.value!!.all.selectedId)
        val reopened = grid(d); SeriesBrowseSessionStateHolder(store, "owner", reopened, browser(d), SeriesWatchlistBrowser(grid(d))).restore()
        assertFalse(reopened.open); assertEquals(50, reopened.selectedKey)
    }
    @Test fun explicitDetailNavigationDoesNotReopenSavedGrid() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(SeriesBrowseSession("owner", "category", categoryName = "Drama", category = SeriesGridPosition(4, 3)))
        val categories = browser(d)
        SeriesBrowseSessionStateHolder(store, "owner", grid(d), categories, SeriesWatchlistBrowser(grid(d))).restore(reopen = false)
        assertFalse(categories.grid!!.open); assertEquals(4, categories.grid!!.selectedKey); assertFalse(categories.categoriesOpen)
    }
    @Test fun newAccountDoesNotInheritOldAccountSelectionAndNullAccountDoesNotSave() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(SeriesBrowseSession("first", "all", SeriesGridPosition(50, 49)))
        val all = grid(d); val holder = SeriesBrowseSessionStateHolder(store, "second", all, browser(d), SeriesWatchlistBrowser(grid(d)))
        holder.restore(); assertNull(all.selectedKey); assertFalse(all.open); holder.checkpoint(); assertEquals("second", store.value!!.owner)
        SeriesBrowseSessionStateHolder(store, null, grid(d), browser(d), SeriesWatchlistBrowser(grid(d))).apply { restore(); checkpoint() }
        assertEquals("second", store.value!!.owner)
    }
    @Test fun canceledSlowRestorationCannotPublishAfterLeavingShows() = runTest {
        val d = StandardTestDispatcher(testScheduler); val gate = CompletableDeferred<Unit>()
        val store = object : SeriesBrowseSessionStore {
            override suspend fun load(owner: String): SeriesBrowseSession { withContext(NonCancellable) { gate.await() }; return SeriesBrowseSession(owner, "all") }
            override fun schedule(value: SeriesBrowseSession) { error("must not save") }
            override suspend fun awaitSaved() {}
        }
        val all = grid(d); val holder = SeriesBrowseSessionStateHolder(store, "owner", all, browser(d), SeriesWatchlistBrowser(grid(d)))
        val job = launch { holder.restore() }; runCurrent(); job.cancel(); gate.complete(Unit); runCurrent()
        assertFalse(holder.ready); assertFalse(all.open); holder.checkpoint()
    }
    @Test fun watchlistAndCategoryListRestoreTheirIndependentContext() = runTest {
        val d = StandardTestDispatcher(testScheduler); val store = MemoryStore(SeriesBrowseSession("owner", "watchlist", selectedCategory = "Drama",
            categoriesIndex = 7, watchlist = SeriesGridPosition(40, 39, 36, 10), watchlistOriginRow = true))
        val categories = browser(d); val watchlist = SeriesWatchlistBrowser(grid(d))
        val holder = SeriesBrowseSessionStateHolder(store, "owner", grid(d), categories, watchlist)
        holder.restore(); assertTrue(watchlist.grid.open); assertTrue(watchlist.originRow); assertEquals(40, watchlist.grid.selectedKey)
        watchlist.grid.open = false; categories.categoriesOpen = true; holder.checkpoint()
        val next = browser(d)
        SeriesBrowseSessionStateHolder(store, "owner", grid(d), next, SeriesWatchlistBrowser(grid(d))).restore()
        assertTrue(next.categoriesOpen); assertEquals("Drama", next.categories.selectedName); assertEquals(7, next.categories.scrollIndex)
    }
    @Test fun invalidAndMissingCategoryStateResetsWhilePositionsAreBounded() {
        assertNull(SeriesBrowseSession("owner", "category").validated())
        assertNull(SeriesBrowseSession("owner", categoryName = "http://private").validated())
        assertNull(SeriesBrowseSession("owner", all = SeriesGridPosition(-1)).validated())
        val safe = SeriesBrowseSession("owner", all = SeriesGridPosition(1, -2, Int.MAX_VALUE, -4)).validated()!!
        assertEquals(0, safe.all.selectedIndex); assertEquals(10_000_000, safe.all.scrollIndex); assertEquals(0, safe.all.scrollOffset)
    }
    @Test fun fileCoalescesLatestSnapshotAndMalformedFilesAreOptional() = runBlocking {
        val file = File(temp.root, "browse.json"); val store = FileSeriesBrowseSessionStore(file)
        repeat(20) { store.schedule(SeriesBrowseSession("owner", "all", SeriesGridPosition(it + 1, it))) }
        withTimeout(5_000) { store.awaitSaved() }
        val disk = Gson().fromJson(file.readText(), SeriesBrowseSession::class.java)
        assertEquals(20, disk.all.selectedId); assertNull(store.load("other"))
        assertFalse(file.readText().contains("redactedStreamUrl")); assertFalse(file.readText().contains("password"))
        val malformed = File(temp.root, "bad.json").apply { writeText("{broken") }
        assertNull(FileSeriesBrowseSessionStore(malformed).load("owner"))
    }
}
