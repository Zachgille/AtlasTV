package com.atlas.atlastv.playback

import com.atlas.atlastv.ui.series.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextEpisodeStateHolderTest {
    private fun episode(id: Int, season: Int? = 1, number: Int? = id, title: String = "Episode $id") =
        SeriesEpisodeItem(title, season, number, id, "mp4", "redacted")
    private fun available(state: NextEpisodeState) = (state as NextEpisodeState.Available).episode.episodeId

    @Test fun scrambledProviderOrderAdvancesWithinAndAcrossSeasonsWithoutAssumingContiguousNumbers() {
        val episodes = listOf(episode(30, 3, 1), episode(20, 1, 8), episode(11, 1, 2), episode(10, 1, 1))
        assertEquals(11, available(resolveNextEpisode(episodes, 10)))
        assertEquals(20, available(resolveNextEpisode(episodes, 11)))
        assertEquals(30, available(resolveNextEpisode(episodes, 20)))
        assertEquals(NextEpisodeState.End, resolveNextEpisode(episodes, 30))
    }

    @Test fun missingNumberingAndSpecialsFollowTheExactDetailsBrowserFallback() {
        val episodes = listOf(episode(60, null, null), episode(3, 1, null, "Z"), episode(2, 1, null, "A"), episode(1, 0, 1))
        assertEquals(2, available(resolveNextEpisode(episodes, 1)))
        assertEquals(3, available(resolveNextEpisode(episodes, 2)))
        assertEquals(60, available(resolveNextEpisode(episodes, 3)))
        assertEquals(NextEpisodeState.End, resolveNextEpisode(episodes, 60))
    }

    @Test fun invalidDuplicateIdsAndAbsentCurrentEpisodeNeverJumpToAnUnrelatedFirstEpisode() {
        val episodes = listOf(episode(0), episode(-1), episode(2), episode(2), episode(3))
        assertEquals(3, available(resolveNextEpisode(episodes, 2)))
        assertEquals(NextEpisodeState.Unavailable, resolveNextEpisode(episodes, 50))
        assertEquals(NextEpisodeState.Unavailable, resolveNextEpisode(emptyList(), 1))
    }

    @Test fun returningToAnExplicitUnknownSeasonKeepsTheEpisodeWhileInitialDetailsStartsWithNumberedSeason() {
        val known = episode(1, 1, 1)
        val unknown = episode(2, null, 1)
        val ready = SeriesCatalogState.Ready("Local", null, emptyList(), emptyList(),
            episodeState = SeriesEpisodeState.Ready(listOf(known, unknown)))
        assertEquals(1, ready.withValidEpisodeSelection().selectedEpisodeId)
        assertEquals(2, ready.copy(selectedSeasonNumber = null, selectedEpisodeId = 2).withValidEpisodeSelection().selectedEpisodeId)
        assertNull(ready.copy(selectedSeasonNumber = null, selectedEpisodeId = 2).withValidEpisodeSelection().selectedSeasonNumber)
    }

    @Test fun loadedDetailsSeedAndSubsequentEpisodesReuseOneShowWithoutNetworkTraffic() = runTest {
        var calls = 0
        val holder = NextEpisodeStateHolder({ calls++; error("unexpected") }, StandardTestDispatcher(testScheduler))
        holder.ensureNext(1, 1, SeriesEpisodeState.Ready(listOf(episode(2), episode(1), episode(3))))
        assertEquals(2, available(holder.state))
        holder.deactivate()
        holder.ensureNext(1, 2)
        assertEquals(3, available(holder.state))
        holder.ensureNext(1, 3)
        assertEquals(NextEpisodeState.End, holder.state)
        assertEquals(0, calls)
    }

    @Test fun slowOldShowCannotReplaceCurrentEpisodeOrTheSingleShowCache() = runTest {
        val holder = NextEpisodeStateHolder({ id ->
            withContext(NonCancellable) { delay(if (id == 1) 1_000 else 50) }
            SeriesEpisodeLoadResult.Success(listOf(episode(id * 10), episode(id * 10 + 1)))
        }, StandardTestDispatcher(testScheduler))
        val old = backgroundScope.launch { holder.ensureNext(1, 10) }; runCurrent()
        old.cancel(); holder.deactivate()
        backgroundScope.launch { holder.ensureNext(2, 20) }; runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(21, available(holder.state))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(21, available(holder.state))
        assertNull(holder.loadedFor(1)); assertNotNull(holder.loadedFor(2))
    }

    @Test fun changingCurrentEpisodeRejectsLateSameShowLookup() = runTest {
        var calls = 0
        val holder = NextEpisodeStateHolder({
            val call = ++calls
            withContext(NonCancellable) { delay(if (call == 1) 1_000 else 10) }
            SeriesEpisodeLoadResult.Success(listOf(episode(1), episode(2), episode(3)))
        }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { holder.ensureNext(1, 1) }; runCurrent()
        backgroundScope.launch { holder.ensureNext(1, 2) }; runCurrent()
        advanceTimeBy(10); runCurrent(); assertEquals(3, available(holder.state))
        advanceTimeBy(1_000); runCurrent(); assertEquals(3, available(holder.state))
    }

    @Test fun optionalFailureIsSafeAndExplicitRetryRefetchesEvenWhenDetailsSeedIsStale() = runTest {
        var fail = true
        var calls = 0
        val holder = NextEpisodeStateHolder({
            calls++
            if (fail) error("private credentials") else SeriesEpisodeLoadResult.Success(listOf(episode(1), episode(2)))
        }, StandardTestDispatcher(testScheduler))
        holder.ensureNext(1, 1)
        assertEquals(NextEpisodeState.Unavailable, holder.state)
        holder.retry(); fail = false
        holder.ensureNext(1, 1, SeriesEpisodeState.Ready(listOf(episode(99))))
        assertEquals(2, available(holder.state)); assertEquals(2, calls)
    }

    @Test fun cancellationAndCatalogInvalidationRejectLateDataAndPermitFreshReentry() = runTest {
        var calls = 0
        val holder = NextEpisodeStateHolder({ calls++; delay(100); SeriesEpisodeLoadResult.Success(listOf(episode(1), episode(2))) }, StandardTestDispatcher(testScheduler))
        val job = backgroundScope.launch { holder.ensureNext(1, 1) }; runCurrent(); job.cancel(); runCurrent()
        assertEquals(NextEpisodeState.Idle, holder.state)
        holder.ensureNext(1, 1)
        assertEquals(2, available(holder.state))
        holder.deactivate(clearCache = true); assertNull(holder.loadedFor(1))
        holder.ensureNext(1, 1)
        assertEquals(3, calls)
    }
    @Test fun catalogInvalidationWakesAnOpenPlayerAndDoesNotReuseItsStaleDetailsSeed() = runTest {
        var calls = 0
        val holder = NextEpisodeStateHolder({ calls++; SeriesEpisodeLoadResult.Success(listOf(episode(1), episode(3))) }, StandardTestDispatcher(testScheduler))
        val stale = SeriesEpisodeState.Ready(listOf(episode(1), episode(2)))
        holder.ensureNext(1, 1, stale); assertEquals(2, available(holder.state))
        val previousRevision = holder.retryVersion
        holder.deactivate(clearCache = true)
        assertTrue(holder.retryVersion > previousRevision)
        holder.ensureNext(1, 1, stale)
        assertEquals(3, available(holder.state)); assertEquals(1, calls)
    }
}
