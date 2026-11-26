package com.atlas.atlastv.ui.mylist

import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.home.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyListBrowserTest {
    private val movie = MyListItem.Movie(MovieItem("Movie", "Movies", 1, "mp4", "room://safe"))
    private val show = MyListItem.Show(SeriesItem("Show", "Shows", 1))
    @Test fun reusedNumericIdsKeepTheSelectedContentTypeThroughReorderAndReentry() = runTest {
        var items: List<MyListItem> = listOf(movie, show)
        val requested = mutableListOf<MyListKey?>()
        val browser = MyListBrowser({ MyListGridSource { index, id ->
            requested += id
            CatalogGridWindow(items.size, 0, id?.let { key -> items.indexOfFirst { it.key == key }.takeIf { it >= 0 } } ?: index.coerceAtMost(items.lastIndex), items)
        } }, StandardTestDispatcher(testScheduler))
        val first = backgroundScope.launch { browser.grid.activate(0) }; runCurrent()
        browser.grid.select(1); first.cancelAndJoin()
        items = listOf(show, movie)
        val second = backgroundScope.launch { browser.grid.activate(1) }; runCurrent()
        assertEquals(show.key, requested.last()); assertEquals(show, browser.grid.state.value.item(0))
        assertEquals(0, browser.grid.state.value.selectedIndex)
        second.cancelAndJoin()
        backgroundScope.launch { browser.grid.activate(1) }; runCurrent()
        assertEquals(2, requested.size); assertEquals(0, browser.grid.state.value.selectedIndex)
    }
    @Test fun filtersReplaceOneWindowAndSelectingTheSameFilterKeepsItsSelection() = runTest {
        val browser = MyListBrowser({ filter -> MyListGridSource { _, _ ->
            val items = when (filter) { MyListFilter.All -> listOf(movie, show); MyListFilter.Movies -> listOf(movie); MyListFilter.Shows -> listOf(show) }
            CatalogGridWindow(items.size, 0, 0, items)
        } }, StandardTestDispatcher(testScheduler))
        val all = browser.grid
        browser.selectFilter(MyListFilter.Shows); val shows = browser.grid
        assertNotSame(all, shows)
        backgroundScope.launch { shows.activate(0) }; runCurrent()
        assertEquals(listOf(show), shows.state.value.items)
        browser.selectFilter(MyListFilter.Shows); assertSame(shows, browser.grid)
        browser.selectFilter(MyListFilter.Movies); assertNotSame(shows, browser.grid)
        backgroundScope.launch { browser.grid.activate(0) }; runCurrent()
        assertEquals(listOf(movie), browser.grid.state.value.items)
    }
    @Test fun canceledFilterCannotReplaceTheNewFilterWithALateResult() = runTest {
        val late = CompletableDeferred<Unit>()
        val browser = MyListBrowser({ filter -> MyListGridSource { _, _ ->
            if (filter == MyListFilter.All) withContext(NonCancellable) { late.await() }
            CatalogGridWindow(1, 0, 0, listOf(if (filter == MyListFilter.Shows) show else movie))
        } }, StandardTestDispatcher(testScheduler))
        val old = browser.grid
        val active = backgroundScope.launch { old.activate(0) }; runCurrent(); active.cancel()
        browser.selectFilter(MyListFilter.Shows)
        backgroundScope.launch { browser.grid.activate(0) }; runCurrent()
        late.complete(Unit); runCurrent()
        assertEquals(listOf(show), browser.grid.state.value.items); assertTrue(old.state.value.items.isEmpty())
    }
    @Test fun navDepartureClearsAnOldCardReturnWithoutLosingItsSelection() {
        val holder = HomeShellStateHolder({ error("unused") })
        holder.rememberDeparture("selected", HomeReturnTarget.Primary)
        holder.rememberNavDeparture("top_nav_item_my_list")
        assertNull(holder.returnFocus); assertEquals("selected", holder.selectedCardId)
        assertEquals("top_nav_item_my_list", holder.returnNavTag)
        holder.consumeReturnNavFocus(); assertNull(holder.returnNavTag)
    }
}
