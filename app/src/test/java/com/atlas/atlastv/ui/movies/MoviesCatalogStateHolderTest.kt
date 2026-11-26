package com.atlas.atlastv.ui.movies

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MoviesCatalogStateHolderTest {
    private class Source(var loadAction: suspend (MoviesCatalogState.Ready?) -> MoviesCatalogState) : MoviesCatalogSource {
        val preferences = mutableListOf<MoviesPreferences>()
        val watchlists = mutableListOf<Set<Int>>()
        var writeDelay = 0L
        var writeFailure = false
        var watchlistFailure = false
        override suspend fun load(previous: MoviesCatalogState.Ready?) = loadAction(previous)
        override suspend fun savePreferences(preferences: MoviesPreferences) {
            delay(writeDelay)
            if (writeFailure) error("http://private/password")
            this.preferences += preferences
        }
        override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) {
            delay(writeDelay)
            if (watchlistFailure) error("private diagnostic")
            watchlists += ids
        }
    }

    @Test fun slowLoadLeavesCallerResponsive() = runTest {
        val holder = MoviesCatalogStateHolder(Source { delay(1_000); ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }
        runCurrent()
        assertEquals(MoviesCatalogState.Loading, holder.state)
        assertTrue(holder.isRefreshing)
        advanceUntilIdle()
        assertTrue(holder.state is MoviesCatalogState.Ready)
        assertFalse(holder.isRefreshing)
    }

    @Test fun failedRefreshPreservesRowsAndWatchStateWithSafeRetry() = runTest {
        val source = Source { ready().copy(watchlistStreamIds = setOf(99)) }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        val saved = holder.state
        source.loadAction = { delay(1_000); error("http://private/password") }
        holder.invalidateCachedCatalog()
        launch { holder.ensureCachedCatalog() }; runCurrent()
        assertSame(saved, holder.state)
        advanceUntilIdle()
        assertSame(saved, holder.state)
        assertNotNull(holder.refreshError)
        assertFalse(holder.refreshError!!.contains("private"))
        source.loadAction = { ready("updated") }
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertNull(holder.refreshError)
        assertEquals("updated", (holder.state as MoviesCatalogState.Ready).source)
    }

    @Test fun firstFailureIsSafeAndRetryWorks() = runTest {
        val source = Source { error("password diagnostic") }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertFalse((holder.state as MoviesCatalogState.Error).message.contains("password"))
        source.loadAction = { ready() }
        launch { holder.ensureCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertTrue(holder.state is MoviesCatalogState.Ready)
    }

    @Test fun cancellationDoesNotPublishLateRowsOrErrors() = runTest {
        val holder = MoviesCatalogStateHolder(Source { withContext(NonCancellable) { delay(1_000) }; ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        val job = launch { holder.loadCachedCatalog() }; runCurrent(); job.cancel(); runCurrent(); advanceUntilIdle()
        assertEquals(MoviesCatalogState.Loading, holder.state)
        assertNull(holder.refreshError)
        assertFalse(holder.isRefreshing)
    }

    @Test fun newerLoadWinsWhenOlderSourceFinishesLate() = runTest {
        var count = 0
        val holder = MoviesCatalogStateHolder(Source {
            if (++count == 1) { withContext(NonCancellable) { delay(1_000) }; ready("old") } else ready("new")
        }, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        launch { holder.loadCachedCatalog() }; runCurrent(); runCurrent(); advanceUntilIdle()
        assertEquals("new", (holder.state as MoviesCatalogState.Ready).source)
    }

    @Test fun selectionDuringRefreshSurvivesStaleSnapshot() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.loadAction = { delay(1_000); ready() }
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.rememberMovie(movie(2))
        advanceUntilIdle()
        assertEquals(2, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
    }

    @Test fun rapidFocusChangesSkipSupersededPreferenceWrites() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        (2..50).forEach { holder.rememberMovie(movie(it)) }
        assertEquals(50, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
        runCurrent()
        assertEquals(listOf(50), source.preferences.map { it.selectedMovieStreamId })
    }

    @Test fun watchlistWritesRemainOrderedAndNextLoadWaitsForThem() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.writeDelay = 100
        holder.toggleWatchlistForSelectedMovie()
        holder.toggleWatchlistForSelectedMovie()
        val job = launch { holder.loadCachedCatalog() }
        runCurrent()
        assertFalse(job.isCompleted)
        advanceTimeBy(400); runCurrent(); advanceUntilIdle()
        assertEquals(listOf(setOf(1), emptySet<Int>()), source.watchlists)
        assertTrue(job.isCompleted)
    }

    @Test fun clearingSearchRejectsAnOlderResultEvenWithoutCooperativeSource() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.onSearchQueryChanged("old")
        source.loadAction = { previous -> withContext(NonCancellable) { delay(1_000) }; previous!! }
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.clearSearch()
        advanceUntilIdle()
        assertEquals("", (holder.state as MoviesCatalogState.Ready).searchQuery)
    }

    @Test fun failedPreferenceWriteDoesNotBlockFocusAndShowsSafeMessage() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.writeFailure = true
        holder.rememberMovie(movie(2)); runCurrent()
        assertEquals(2, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
        assertNotNull(holder.saveError)
        assertFalse(holder.saveError!!.contains("http"))
        source.writeFailure = false
        holder.rememberMovie(movie(3)); runCurrent()
        assertNull(holder.saveError)
    }

    @Test fun successfulFocusSaveCannotHideFailedWatchlistSave() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.watchlistFailure = true
        holder.toggleWatchlistForSelectedMovie(); runCurrent()
        holder.rememberMovie(movie(2)); runCurrent()
        assertNotNull(holder.saveError)
        source.watchlistFailure = false
        holder.toggleWatchlistForSelectedMovie(); runCurrent()
        assertNull(holder.saveError)
    }

    @Test fun selectionBeforeRefreshSurvivesDefaultSelectionInNewSnapshot() = runTest {
        val holder = MoviesCatalogStateHolder(Source { ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.rememberMovie(movie(2))
        holder.invalidateCachedCatalog()
        launch { holder.ensureCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertEquals(2, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
    }

    @Test fun failedMembershipSaveRetainsRequestedChangeAcrossReloadAndRetryCommitsOnce() = runTest {
        val source = Source { ready() }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        source.watchlistFailure = true
        holder.toggleWatchlistForSelectedMovie(); runCurrent()
        assertEquals(0L, holder.catalogVersion); assertTrue(holder.canRetryWatchlistSave)
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertEquals(setOf(1), (holder.state as MoviesCatalogState.Ready).watchlistStreamIds)
        source.watchlistFailure = false; source.writeDelay = 100
        holder.retryWatchlistSave(); holder.retryWatchlistSave(); runCurrent()
        assertTrue(holder.watchlistSaving); assertEquals(0L, holder.catalogVersion)
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(setOf(1)), source.watchlists)
        assertEquals(1L, holder.catalogVersion); assertNull(holder.saveError); assertFalse(holder.canRetryWatchlistSave)
    }

    @Test fun olderFailedWriteCannotReplaceASuccessfulNewerDesiredMembership() = runTest {
        var count = 0
        val writes = mutableListOf<Set<Int>>()
        val source = object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = ready()
            override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) {
                if (++count == 1) error("private"); writes += ids
            }
        }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.toggleWatchlistForSelectedMovie(); holder.toggleWatchlistForSelectedMovie(); runCurrent()
        assertEquals(listOf(emptySet<Int>()), writes); assertNull(holder.saveError)
        assertFalse(holder.watchlistSaving); assertFalse(holder.canRetryWatchlistSave)
    }

    @Test fun pendingMembershipLoadWaitsForDurableWriteBeforePublishingRows() = runTest {
        var ids = emptySet<Int>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = ready().copy(watchlistStreamIds = ids)
            override suspend fun saveWatchlist(movies: List<MovieItem>, next: Set<Int>) { gate.await(); ids = next }
        }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); holder.toggleWatchlistForSelectedMovie()
        val refresh = launch { holder.loadCachedCatalog() }; runCurrent(); assertFalse(refresh.isCompleted)
        gate.complete(Unit); runCurrent()
        // Successful save invalidates this older refresh; a fresh activation reads the committed membership.
        launch { holder.ensureCachedCatalog() }; runCurrent()
        assertEquals(setOf(1), (holder.state as MoviesCatalogState.Ready).watchlistStreamIds)
        assertFalse(holder.watchlistSaving)
    }

    @Test fun explicitDetailMovieMembershipIgnoresARefreshThatMovedBrowseSelection() = runTest {
        val source = Source { ready().copy(watchlistStreamIds = setOf(1)) }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        source.loadAction = { ready().copy(movies = listOf(movie(2)), selectedMovieStreamId = 2, watchlistStreamIds = setOf(1)) }
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertEquals(2, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
        holder.toggleWatchlistForMovie(movie(1)); runCurrent()
        assertEquals(listOf(emptySet<Int>()), source.watchlists)
        holder.toggleWatchlistForMovie(movie(1)); runCurrent()
        assertEquals(listOf(emptySet<Int>(), setOf(1)), source.watchlists)
    }
    @Test fun missingCatalogAvailabilityDoesNotRevertWhenRemovedWatchlistSnapshotLeavesTheRows() = runTest {
        val source = Source { ready().copy(watchlistStreamIds = setOf(1)) }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); holder.rememberMovie(movie(1))
        source.loadAction = { ready().copy(movies = listOf(movie(1).copy(available = false), movie(2)), watchlistStreamIds = setOf(1)) }
        launch { holder.loadCachedCatalog() }; runCurrent(); assertFalse(holder.availabilityFor(movie(1)))
        source.loadAction = { ready().copy(movies = listOf(movie(2)), selectedMovieStreamId = 2) }
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertFalse(holder.availabilityFor(movie(1))); assertTrue(holder.availabilityFor(movie(2)))
    }
    @Test fun deepSelectedMovieUsesFreshUnavailableSnapshotOutsideEveryPreviewRow() = runTest {
        val source = Source { ready().copy(browseRows = listOf(com.atlas.atlastv.ui.browse.BrowseRow("other", "Movies", listOf(movie(2))))) }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); holder.rememberMovie(movie(513))
        source.loadAction = { ready().copy(movies = listOf(movie(2), movie(513).copy(available = false)),
            selectedMovieStreamId = 513, watchlistStreamIds = setOf(513),
            browseRows = listOf(com.atlas.atlastv.ui.browse.BrowseRow("other", "Movies", listOf(movie(2))))) }
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertEquals(513, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
        assertFalse(holder.availabilityFor(movie(513)))
    }
    companion object {
        fun movie(id: Int = 1) = MovieItem("Movie $id", "Movies", id, "mp4", "room://$id")
        fun ready(source: String = "saved") = MoviesCatalogState.Ready(source, 0, listOf(movie(1), movie(2)), listOf(MovieCategory("all", "All", 2)))
    }
}

