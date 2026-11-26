package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.browse.BrowseRow
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesGridStateHolderTest {
    private fun show(index: Int) = SeriesItem("Show ${index + 1}", "Shows", index + 100_000)
    private fun window(index: Int): SeriesGridWindow {
        val anchor = index.coerceIn(0, 10456)
        val offset = maxOf(0, anchor / 40 - 2) * 40
        return SeriesGridWindow(10457, offset, anchor, (offset until minOf(10457, offset + 200)).map(::show))
    }
    @Test fun showsUseSeriesIdentityAndKeepTheirCursorIndependentOfMovies() = runTest {
        val shows = SeriesGridStateHolder(SeriesGridSource { index, _ -> window(index) }, StandardTestDispatcher(testScheduler))
        val movies = MovieGridStateHolder(MovieGridSource { _, _ -> MovieGridWindow(1, 0, 0, listOf(MovieItem("Movie", "Movies", 100_000, "mp4", "room://movie"))) }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { shows.activate(1) }; backgroundScope.launch { movies.activate(1) }; runCurrent()
        shows.select(6001); runCurrent()
        assertEquals(106001, shows.state.value.item(6001)!!.seriesId)
        assertEquals(0, movies.state.value.selectedIndex); assertEquals("Movie", movies.state.value.item(0)!!.title)
        shows.select(10456); runCurrent()
        assertEquals(110456, shows.state.value.item(10456)!!.seriesId)
        assertTrue(shows.state.value.items.size <= SeriesGridStateHolder.MaxItems)
    }
    @Test fun newestRegionWinsAgainstLateNoncooperativeReadAndFailureRetriesSafely() = runTest {
        val gate = CompletableDeferred<Unit>(); var fail = false
        val holder = SeriesGridStateHolder(SeriesGridSource { index, _ ->
            if (index == 5000) withContext(NonCancellable) { gate.await() }
            if (fail) error("private diagnostic")
            window(index)
        }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        holder.select(5000); runCurrent(); holder.select(10000); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(10000, holder.state.value.selectedIndex); assertNotNull(holder.state.value.item(10000))
        fail = true; holder.select(100); runCurrent()
        assertEquals(SeriesGridStateHolder.LoadFailure, holder.state.value.error)
        assertNotNull(holder.state.value.item(10000))
        fail = false; holder.retry(); runCurrent(); assertNotNull(holder.state.value.item(100))
    }
    @Test fun reorderedSeriesRestoresBySeriesIdRatherThanNumericOffset() = runTest {
        var revised = false; var restoredId: Int? = null
        val holder = SeriesGridStateHolder(SeriesGridSource { index, id ->
            restoredId = id
            if (!revised) window(index) else SeriesGridWindow(10457, 0, 1, listOf(show(0), show(10)))
        }, StandardTestDispatcher(testScheduler))
        val first = backgroundScope.launch { holder.activate(1) }; runCurrent(); holder.select(10); first.cancelAndJoin()
        revised = true; backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals(100010, restoredId); assertEquals(1, holder.state.value.selectedIndex)
        assertEquals(100010, holder.state.value.item(1)!!.seriesId)
    }
    @Test fun deepDetailsKeepOnlyOneExtraShowAndDoNotLoseWatchlistIds() {
        val base = (0..17).map(::show)
        val ids = setOf(106000, 106001)
        var ready = SeriesCatalogState.Ready("room", 0, base, emptyList(), watchlistSeriesIds = ids,
            browseRows = listOf(BrowseRow("shows", "Shows", base)))
        (6000..6500).forEach { ready = ready.withRememberedSeries(show(it)) }
        assertEquals(19, ready.series.size); assertTrue(ready.series.containsAll(base))
        assertEquals(106500, ready.selectedSeriesId); assertEquals(ids, ready.watchlistSeriesIds)
    }
}
