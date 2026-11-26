package com.atlas.atlastv.ui.series

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SeriesEpisodeLoadingStateHolderTest {
    private class Source : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogStateHolderTest.ready()
    }
    private fun episode(id: Int = 10, season: Int = 1) = SeriesEpisodeItem("Episode $id", season, id, id, "mp4", "room://episode/$id")

    @Test fun browsingChangingFocusAndSearchingNeverFetchEpisodes() = runTest {
        var calls = 0
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), { calls++; SeriesEpisodeLoadResult.Success(listOf(episode())) })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.rememberSeries(SeriesCatalogStateHolderTest.show(2))
        holder.onSearchQueryChanged("show")
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.clearSearch()
        assertEquals(0, calls)
        assertEquals(SeriesEpisodeState.Idle, (holder.state as SeriesCatalogState.Ready).episodeState)
    }

    @Test fun openingDetailsLoadsOnlyTheSelectedShowAndKeepsItOnRepeatFocus() = runTest {
        val ids = mutableListOf<Int>()
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), { id -> ids += id; delay(1_000); SeriesEpisodeLoadResult.Success(listOf(episode())) })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        holder.rememberSeries(SeriesCatalogStateHolderTest.show(2))
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent()
        assertEquals(SeriesEpisodeState.Loading, (holder.state as SeriesCatalogState.Ready).episodeState)
        advanceUntilIdle()
        assertEquals(listOf(2), ids)
        assertEquals(10, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
        holder.rememberSeries(SeriesCatalogStateHolderTest.show(2))
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        assertEquals(listOf(2), ids)
    }

    @Test fun cancelledDetailsLoadResetsToIdleWithoutPublishingLateData() = runTest {
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), { withContext(NonCancellable) { delay(1_000) }; SeriesEpisodeLoadResult.Success(listOf(episode())) })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        val job = launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); job.cancel(); advanceUntilIdle()
        assertEquals(SeriesEpisodeState.Idle, (holder.state as SeriesCatalogState.Ready).episodeState)
    }

    @Test fun switchingShowsRejectsAnOldEpisodeResponse() = runTest {
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), { id ->
            if (id == 1) withContext(NonCancellable) { delay(1_000) }
            SeriesEpisodeLoadResult.Success(listOf(episode(id * 10)))
        })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent()
        holder.rememberSeries(SeriesCatalogStateHolderTest.show(2))
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        assertEquals(20, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }

    @Test fun returningToSameShowStillRejectsAStaleRequest() = runTest {
        var calls = 0
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), {
            val count = ++calls
            if (count == 1) withContext(NonCancellable) { delay(1_000) }
            SeriesEpisodeLoadResult.Success(listOf(episode(count * 10)))
        })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent()
        holder.rememberSeries(SeriesCatalogStateHolderTest.show(2)); holder.rememberSeries(SeriesCatalogStateHolderTest.show(1))
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        assertEquals(20, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }

    @Test fun catalogRefreshRetainsLoadedEpisodesAndSeasonSelection() = runTest {
        var calls = 0
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), {
            calls++; SeriesEpisodeLoadResult.Success(listOf(episode(10, 1), episode(20, 2)))
        })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        holder.selectSeason(2)
        holder.invalidateCachedCatalog()
        launch { holder.ensureCachedCatalog() }; runCurrent(); advanceUntilIdle()
        val ready = holder.state as SeriesCatalogState.Ready
        assertEquals(2, ready.selectedSeasonNumber)
        assertEquals(20, ready.selectedEpisodeId)
        assertTrue(ready.episodeState is SeriesEpisodeState.Ready)
        assertEquals(1, calls)
    }

    @Test fun failureCopyIsSafeAndRetrySucceeds() = runTest {
        var fail = true
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), {
            if (fail) error("http://private/password")
            SeriesEpisodeLoadResult.Success(listOf(episode()))
        })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        val message = ((holder.state as SeriesCatalogState.Ready).episodeState as SeriesEpisodeState.Error).message
        assertFalse(message.contains("http") || message.contains("password"))
        fail = false; holder.retryEpisodes()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        assertEquals(10, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }

    @Test fun emptyEpisodeInfoOffersRecoveryInsteadOfAnUnusablePlayAction() = runTest {
        val holder = SeriesCatalogStateHolder(Source(), backgroundScope, StandardTestDispatcher(testScheduler), { SeriesEpisodeLoadResult.Success(emptyList()) })
        launch { holder.loadCachedCatalog() }; runCurrent(); advanceUntilIdle()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        assertTrue((holder.state as SeriesCatalogState.Ready).episodeState is SeriesEpisodeState.Error)
        assertNull((holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }
}
