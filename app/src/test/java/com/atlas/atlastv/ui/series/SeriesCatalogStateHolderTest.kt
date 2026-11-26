package com.atlas.atlastv.ui.series

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
class SeriesCatalogStateHolderTest {
    private class Source(var loadAction: suspend (SeriesCatalogState.Ready?) -> SeriesCatalogState) : SeriesCatalogSource {
        val preferences = mutableListOf<SeriesPreferences>()
        val watchlists = mutableListOf<Set<Int>>()
        var writeDelay = 0L
        var writeFailure = false
        var watchlistFailure = false
        override suspend fun load(previous: SeriesCatalogState.Ready?) = loadAction(previous)
        override suspend fun savePreferences(preferences: SeriesPreferences) {
            delay(writeDelay)
            if (writeFailure) error("http://private/password")
            this.preferences += preferences
        }
        override suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) {
            delay(writeDelay)
            if (watchlistFailure) error("private diagnostic")
            watchlists += ids
        }
    }

    @Test fun slowLoadLeavesCallerResponsive() = runTest {
        val holder = SeriesCatalogStateHolder(Source { delay(1_000); ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }
        runCurrent()
        assertEquals(SeriesCatalogState.Loading, holder.state)
        assertTrue(holder.isRefreshing)
        advanceUntilIdle()
        assertTrue(holder.state is SeriesCatalogState.Ready)
        assertFalse(holder.isRefreshing)
    }

    @Test fun failedRefreshPreservesRowsAndWatchStateWithSafeRetry() = runTest {
        val source = Source { ready().copy(watchlistSeriesIds = setOf(99)) }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
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
        assertEquals("updated", (holder.state as SeriesCatalogState.Ready).source)
    }

    @Test fun firstFailureIsSafeAndRetryWorks() = runTest {
        val source = Source { error("password diagnostic") }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertFalse((holder.state as SeriesCatalogState.Error).message.contains("password"))
        source.loadAction = { ready() }
        launch { holder.ensureCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertTrue(holder.state is SeriesCatalogState.Ready)
    }

    @Test fun cancellationDoesNotPublishLateRowsOrErrors() = runTest {
        val holder = SeriesCatalogStateHolder(Source { withContext(NonCancellable) { delay(1_000) }; ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        val job = launch { holder.loadCachedCatalog() }; runCurrent(); job.cancel(); runCurrent(); advanceUntilIdle()
        assertEquals(SeriesCatalogState.Loading, holder.state)
        assertNull(holder.refreshError)
        assertFalse(holder.isRefreshing)
    }

    @Test fun newerLoadWinsWhenOlderSourceFinishesLate() = runTest {
        var count = 0
        val holder = SeriesCatalogStateHolder(Source {
            if (++count == 1) { withContext(NonCancellable) { delay(1_000) }; ready("old") } else ready("new")
        }, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        launch { holder.loadCachedCatalog() }; runCurrent(); runCurrent(); advanceUntilIdle()
        assertEquals("new", (holder.state as SeriesCatalogState.Ready).source)
    }

    @Test fun selectionDuringRefreshSurvivesStaleSnapshot() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.loadAction = { delay(1_000); ready() }
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.rememberSeries(show(2))
        advanceUntilIdle()
        assertEquals(2, (holder.state as SeriesCatalogState.Ready).selectedSeriesId)
    }

    @Test fun rapidFocusChangesSkipSupersededPreferenceWrites() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        (2..50).forEach { holder.rememberSeries(show(it)) }
        assertEquals(50, (holder.state as SeriesCatalogState.Ready).selectedSeriesId)
        runCurrent()
        assertEquals(listOf(50), source.preferences.map { it.selectedSeriesId })
    }

    @Test fun watchlistWritesRemainOrderedAndNextLoadWaitsForThem() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.writeDelay = 100
        holder.toggleWatchlistForSelectedSeries()
        holder.toggleWatchlistForSelectedSeries()
        val job = launch { holder.loadCachedCatalog() }
        runCurrent()
        assertFalse(job.isCompleted)
        advanceTimeBy(400); runCurrent(); advanceUntilIdle()
        assertEquals(listOf(setOf(1), emptySet<Int>()), source.watchlists)
        assertTrue(job.isCompleted)
    }

    @Test fun clearingSearchRejectsAnOlderResultEvenWithoutCooperativeSource() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.onSearchQueryChanged("old")
        source.loadAction = { previous -> withContext(NonCancellable) { delay(1_000) }; previous!! }
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.clearSearch()
        advanceUntilIdle()
        assertEquals("", (holder.state as SeriesCatalogState.Ready).searchQuery)
    }

    @Test fun failedPreferenceWriteDoesNotBlockFocusAndShowsSafeMessage() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.writeFailure = true
        holder.rememberSeries(show(2)); runCurrent()
        assertEquals(2, (holder.state as SeriesCatalogState.Ready).selectedSeriesId)
        assertNotNull(holder.saveError)
        assertFalse(holder.saveError!!.contains("http"))
        source.writeFailure = false
        holder.rememberSeries(show(3)); runCurrent()
        assertNull(holder.saveError)
    }

    @Test fun successfulFocusSaveCannotHideFailedWatchlistSave() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        source.watchlistFailure = true
        holder.toggleWatchlistForSelectedSeries(); runCurrent()
        holder.rememberSeries(show(2)); runCurrent()
        assertNotNull(holder.saveError)
        source.watchlistFailure = false
        holder.toggleWatchlistForSelectedSeries(); runCurrent()
        assertNull(holder.saveError)
    }

    @Test fun selectionBeforeRefreshSurvivesDefaultSelectionInNewSnapshot() = runTest {
        val holder = SeriesCatalogStateHolder(Source { ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.rememberSeries(show(2))
        holder.invalidateCachedCatalog()
        launch { holder.ensureCachedCatalog() }; runCurrent(); advanceUntilIdle()
        assertEquals(2, (holder.state as SeriesCatalogState.Ready).selectedSeriesId)
    }

    @Test fun playbackLookupCannotAttachEpisodesToAnotherShowAndRetainsCrossSeasonReturnThroughRefresh() = runTest {
        val holder = SeriesCatalogStateHolder(Source { ready() }, backgroundScope, StandardTestDispatcher(testScheduler))
        holder.ensureCachedCatalog()
        val first = SeriesEpisodeItem("One", 1, 1, 10, "mp4", "local")
        val next = SeriesEpisodeItem("Two", 2, 1, 20, "mp4", "local")
        val episodes = SeriesEpisodeState.Ready(listOf(next, first))
        holder.rememberPlaybackEpisodes(2, episodes)
        assertEquals(SeriesEpisodeState.Idle, (holder.state as SeriesCatalogState.Ready).episodeState)
        holder.rememberPlaybackEpisodes(1, episodes)
        holder.rememberEpisode(next)
        holder.refreshWatchProgress(); holder.ensureCachedCatalog()
        val result = holder.state as SeriesCatalogState.Ready
        assertEquals(2, result.selectedSeasonNumber); assertEquals(20, result.selectedEpisodeId)
        assertEquals(episodes, result.episodeState)
    }

    @Test fun failedMembershipSaveRetainsRequestedChangeAcrossReloadAndRetryCommitsOnce() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        source.watchlistFailure = true
        holder.toggleWatchlistForSelectedSeries(); runCurrent()
        assertEquals(0L, holder.catalogVersion); assertTrue(holder.canRetryWatchlistSave)
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertEquals(setOf(1), (holder.state as SeriesCatalogState.Ready).watchlistSeriesIds)
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
        val source = object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = ready()
            override suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) {
                if (++count == 1) error("private"); writes += ids
            }
        }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.toggleWatchlistForSelectedSeries(); holder.toggleWatchlistForSelectedSeries(); runCurrent()
        assertEquals(listOf(emptySet<Int>()), writes); assertNull(holder.saveError)
        assertFalse(holder.watchlistSaving); assertFalse(holder.canRetryWatchlistSave)
    }

    @Test fun pendingMembershipLoadWaitsForDurableWriteBeforePublishingRows() = runTest {
        var storedIds = emptySet<Int>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = ready().copy(watchlistSeriesIds = storedIds)
            override suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) { gate.await(); storedIds = ids }
        }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); holder.toggleWatchlistForSelectedSeries()
        val refresh = launch { holder.loadCachedCatalog() }; runCurrent(); assertFalse(refresh.isCompleted)
        gate.complete(Unit); runCurrent()
        // Successful save invalidates this older refresh; a fresh activation reads the committed membership.
        launch { holder.ensureCachedCatalog() }; runCurrent()
        assertEquals(setOf(1), (holder.state as SeriesCatalogState.Ready).watchlistSeriesIds)
        assertFalse(holder.watchlistSaving)
    }

    @Test fun explicitDetailShowMembershipIgnoresARefreshThatMovedBrowseSelection() = runTest {
        val source = Source { ready().copy(watchlistSeriesIds = setOf(1)) }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        source.loadAction = { ready().copy(series = listOf(show(2)), selectedSeriesId = 2, watchlistSeriesIds = setOf(1)) }
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertEquals(2, (holder.state as SeriesCatalogState.Ready).selectedSeriesId)
        holder.toggleWatchlistForSeries(show(1)); runCurrent()
        assertEquals(listOf(emptySet<Int>()), source.watchlists)
        holder.toggleWatchlistForSeries(show(1)); runCurrent()
        assertEquals(listOf(emptySet<Int>(), setOf(1)), source.watchlists)
    }
    @Test fun missingCatalogAvailabilityDoesNotRevertWhenRemovedWatchlistSnapshotLeavesTheRows() = runTest {
        val source = Source { ready().copy(watchlistSeriesIds = setOf(1)) }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent(); holder.rememberSeries(show(1))
        source.loadAction = { ready().copy(series = listOf(show(1).copy(available = false), show(2)), watchlistSeriesIds = setOf(1)) }
        launch { holder.loadCachedCatalog() }; runCurrent(); assertFalse(holder.availabilityFor(show(1)))
        source.loadAction = { ready().copy(series = listOf(show(2)), selectedSeriesId = 2) }
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertFalse(holder.availabilityFor(show(1))); assertTrue(holder.availabilityFor(show(2)))
    }
    @Test fun togglingDisplayedShowRetainsSelectedSeasonAndEpisodeBeforeAndAfterSaveRefresh() = runTest {
        val source = Source { ready() }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { holder.loadCachedCatalog() }; runCurrent()
        val episodes = SeriesEpisodeState.Ready(listOf(SeriesEpisodeItem("Final", 3, 4, 134, "mp4", "local")))
        holder.rememberPlaybackEpisodes(1, episodes); holder.selectSeason(3)
        holder.toggleWatchlistForSeries(show(1)); runCurrent()
        launch { holder.ensureCachedCatalog() }; runCurrent()
        val ready = holder.state as SeriesCatalogState.Ready
        assertEquals(episodes, ready.episodeState); assertEquals(3, ready.selectedSeasonNumber); assertEquals(134, ready.selectedEpisodeId)
    }
    @Test fun unavailableShowsDoNotRequestEpisodesAndCannotBeAddedBack() = runTest {
        var requests = 0
        val source = Source { ready().copy(series = listOf(show(1).copy(available = false)), watchlistSeriesIds = setOf(1)) }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler), loadEpisodes = {
            requests++; SeriesEpisodeLoadResult.Failure("private") })
        launch { holder.loadCachedCatalog() }; runCurrent(); holder.ensureEpisodesForSelectedSeries()
        assertEquals(0, requests)
        holder.toggleWatchlistForSeries(show(1).copy(available = false)); runCurrent()
        holder.toggleWatchlistForSeries(show(1).copy(available = false)); runCurrent()
        assertEquals(listOf(emptySet<Int>()), source.watchlists)
    }
    @Test fun deepSelectedShowUsesFreshUnavailableSnapshotOutsideEveryPreviewRow() = runTest {
        val source = Source { ready().copy(browseRows = listOf(com.atlas.atlastv.ui.browse.BrowseRow("other", "Shows", listOf(show(2))))) }
        var requests = 0
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler), loadEpisodes = {
            requests++; SeriesEpisodeLoadResult.Failure("private") })
        launch { holder.loadCachedCatalog() }; runCurrent()
        holder.ensureEpisodesForSelectedSeries(513); assertEquals(0, requests)
        holder.rememberSeries(show(513))
        source.loadAction = { ready().copy(series = listOf(show(2), show(513).copy(available = false)),
            selectedSeriesId = 513, watchlistSeriesIds = setOf(513),
            browseRows = listOf(com.atlas.atlastv.ui.browse.BrowseRow("other", "Shows", listOf(show(2))))) }
        launch { holder.loadCachedCatalog() }; runCurrent()
        assertEquals(513, (holder.state as SeriesCatalogState.Ready).selectedSeriesId)
        assertFalse(holder.availabilityFor(show(513)))
        holder.ensureEpisodesForSelectedSeries(); assertEquals(0, requests)
    }
    companion object {
        fun show(id: Int = 1) = SeriesItem("Show $id", "Shows", id)
        fun ready(source: String = "saved") = SeriesCatalogState.Ready(source, 0, listOf(show(1), show(2)), listOf(SeriesCategory("all", "All", 2)))
    }
}

