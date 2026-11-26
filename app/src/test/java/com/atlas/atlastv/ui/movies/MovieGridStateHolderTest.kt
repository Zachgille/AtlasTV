package com.atlas.atlastv.ui.movies

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieGridStateHolderTest {
    private fun movie(index: Int) = MovieItem("Movie $index", "Movies", index + 1, "mp4", "room://movie/$index")
    private fun window(index: Int, total: Int = 28349): MovieGridWindow {
        val anchor = index.coerceIn(0, maxOf(0, total - 1))
        val offset = maxOf(0, anchor / 40 - 2) * 40
        return MovieGridWindow(total, offset, anchor, (offset until minOf(offset + 200, total)).map(::movie))
    }

    @Test fun deepWindowsAreBoundedDoNotAccumulateAndIncludeLastItem() = runTest {
        val calls = mutableListOf<Int>()
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> calls += index; window(index) }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        assertEquals(28349, holder.state.value.total); assertEquals(200, holder.state.value.items.size)
        holder.select(10000); runCurrent()
        assertEquals(10001, holder.state.value.item(10000)!!.streamId)
        assertNull(holder.state.value.item(0)); assertTrue(holder.state.value.items.size <= 200)
        holder.select(28348); runCurrent()
        assertEquals(28349, holder.state.value.item(28348)!!.streamId)
        assertTrue(holder.state.value.items.size <= 200); assertEquals(3, calls.size)
    }

    @Test fun navigationWithinPrefetchedWindowDoesNotReadPerKeypress() = runTest {
        var reads = 0
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> reads++; window(index) }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        (0..100).forEach { holder.select(it) }; runCurrent()
        assertEquals(1, reads)
    }

    @Test fun leavingCancelsSlowWindowAndNeverPublishesItsLateResult() = runTest {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); var canceled = false
        val holder = MovieGridStateHolder(MovieGridSource { _, _ ->
            started.complete(Unit)
            try { withContext(NonCancellable) { gate.await(); window(0) } } finally { canceled = !currentCoroutineContext().isActive }
        }, StandardTestDispatcher(testScheduler))
        val screen = backgroundScope.launch { holder.activate(1) }
        runCurrent(); assertTrue(started.isCompleted); assertTrue(holder.state.value.loading)
        screen.cancel(); gate.complete(Unit); runCurrent()
        assertTrue(canceled); assertFalse(holder.state.value.initialized); assertFalse(holder.state.value.loading)
    }

    @Test fun failedDeepLoadRetainsGoodWindowAndRetryLoadsRequestedRegion() = runTest {
        var fail = false
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> if (fail) error("private diagnostics"); window(index) }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        fail = true; holder.select(10000); runCurrent()
        assertNotNull(holder.state.value.item(0)); assertEquals(MovieGridStateHolder.LoadFailure, holder.state.value.error)
        fail = false; holder.retry(); runCurrent()
        assertNotNull(holder.state.value.item(10000)); assertNull(holder.state.value.error)
    }

    @Test fun initialFailureRetryEmptyLibraryAndShrinkHaveSafeStates() = runTest {
        var fail = true; var total = 0
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> if (fail) error("private"); window(index, total) }, StandardTestDispatcher(testScheduler))
        val screen = backgroundScope.launch { holder.activate(1) }; runCurrent()
        assertFalse(holder.state.value.initialized); assertEquals(MovieGridStateHolder.LoadFailure, holder.state.value.error)
        fail = false; holder.retry(); runCurrent()
        assertTrue(holder.state.value.initialized); assertEquals(0, holder.state.value.total)
        screen.cancelAndJoin()
        total = 20; val next = backgroundScope.launch { holder.activate(2) }; runCurrent()
        holder.select(19); runCurrent(); next.cancelAndJoin()
        total = 3; backgroundScope.launch { holder.activate(3) }; runCurrent()
        assertEquals(2, holder.state.value.selectedIndex); assertEquals(3, holder.state.value.total)
    }

    @Test fun refreshUsesStableMovieIdAndRestoresSelectionAfterReordering() = runTest {
        var captured: Int? = null; var revision = 1
        val holder = MovieGridStateHolder(MovieGridSource { index, id ->
            captured = id
            if (revision == 1) window(index) else MovieGridWindow(28350, 0, 11, (0..199).map { if (it == 11) movie(10) else movie(it + 1) })
        }, StandardTestDispatcher(testScheduler))
        val first = backgroundScope.launch { holder.activate(1) }; runCurrent(); holder.select(10); first.cancelAndJoin()
        revision = 2; backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals(11, captured); assertEquals(11, holder.state.value.selectedIndex)
        assertEquals(11, holder.state.value.item(11)!!.streamId)
    }

    @Test fun emptyRefreshCannotErasePreviouslyAvailableLibrary() = runTest {
        var total = 100
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> window(index, total) }, StandardTestDispatcher(testScheduler))
        val first = backgroundScope.launch { holder.activate(1) }; runCurrent(); first.cancelAndJoin()
        total = 0; backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals(100, holder.state.value.total); assertEquals(MovieGridStateHolder.LoadFailure, holder.state.value.error)
    }

    @Test fun remoteMovementPreservesColumnsAndCannotEscapePartialLastRow() {
        assertEquals(5, movieGridTarget(0, 12, 5, MovieGridDirection.Down))
        assertNull(movieGridTarget(3, 12, 5, MovieGridDirection.Up))
        assertEquals(4, movieGridTarget(4, 12, 5, MovieGridDirection.Right))
        assertEquals(5, movieGridTarget(5, 12, 5, MovieGridDirection.Left))
        assertEquals(11, movieGridTarget(9, 12, 5, MovieGridDirection.Down))
        assertEquals(11, movieGridTarget(11, 12, 5, MovieGridDirection.Down))
        assertEquals(11, movieGridTarget(0, 12, 5, MovieGridDirection.PageDown))
    }

    @Test fun deepDetailVisitsRetainOnlyCurrentExtraMovieAndOriginalBrowseSnapshot() {
        val base = (0..17).map(::movie)
        var ready = MoviesCatalogState.Ready("room", 0, base, emptyList(),
            browseRows = listOf(com.atlas.atlastv.ui.browse.BrowseRow("movies", "Movies", base)))
        (10000..11000).forEach { ready = ready.withRememberedMovie(movie(it)) }
        assertEquals(19, ready.movies.size)
        assertTrue(ready.movies.containsAll(base)); assertEquals(11001, ready.selectedMovieStreamId)
        assertFalse(ready.movies.any { it.streamId == 10001 })
    }

    @Test fun enteringBeforeLegacyBootstrapReloadsWhenMoviesBecomeReady() = runTest {
        var total = 0
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> window(index, total) }, StandardTestDispatcher(testScheduler))
        val first = backgroundScope.launch { holder.activate(1, catalogReady = false) }; runCurrent()
        assertEquals(0, holder.state.value.total); first.cancelAndJoin()
        total = 100
        backgroundScope.launch { holder.activate(1, catalogReady = true) }; runCurrent()
        assertEquals(100, holder.state.value.total); assertNotNull(holder.state.value.item(0))
    }
    @Test fun canceledOldActivationCannotCancelNewWatchlistWindowOrRetry() = runTest {
        val gate = CompletableDeferred<Unit>(); var reads = 0
        val holder = MovieGridStateHolder(MovieGridSource { _, _ ->
            if (++reads == 1) withContext(NonCancellable) { gate.await(); window(0, 1) } else window(0, 3)
        }, StandardTestDispatcher(testScheduler), allowEmptyRefresh = true)
        val old = backgroundScope.launch { holder.activate(1) }; runCurrent(); old.cancel()
        backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals(3, holder.state.value.total)
        gate.complete(Unit); runCurrent(); holder.retry(); runCurrent()
        assertEquals(3, reads); assertEquals(3, holder.state.value.total); assertFalse(holder.state.value.loading)
    }
    @Test fun emptyWatchlistDoesNotRequestAnotherWindowForEveryComposition() = runTest {
        var reads = 0; var total = 0
        val holder = MovieGridStateHolder(MovieGridSource { index, _ -> reads++; window(index, total) }, StandardTestDispatcher(testScheduler), allowEmptyRefresh = true)
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        repeat(5) { holder.ensureWindow(0); runCurrent() }
        assertEquals(1, reads)
        total = 1; holder.retry(); runCurrent()
        assertEquals(2, reads); assertEquals(1, holder.state.value.total)
    }
}
