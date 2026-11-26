package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EpisodeMemoryTest {
    private fun episode(id: Int, season: Int, number: Int) = SeriesEpisodeItem("Episode $number", season, number, id, "mp4", "room://episode/$id")
    private val episodes = listOf(episode(11, 1, 1), episode(12, 1, 2), episode(21, 2, 1), episode(22, 2, 2))
    private fun ready() = SeriesCatalogState.Ready("fixture", null, listOf(SeriesItem("Show", "English", 5)), emptyList(),
        selectedSeriesId = 5, episodeState = SeriesEpisodeState.Ready(episodes))
    private fun progress(id: Int, time: Long, completed: Boolean = false, show: Int = 5) = WatchProgressRecord(
        WatchProgressContentType.SeriesEpisode, id, show, title = "Show", durationMs = 600_000,
        positionMs = if (completed) 600_000 else 80_000, updatedAtMs = time, completed = completed)

    @Test fun reopeningUnselectedShowFindsTheLatestPlayedEpisodeAcrossSeasons() {
        val value = ready().copy(watchProgress = listOf(progress(12, 1), progress(21, 20), progress(22, 100, show = 99)).associateBy { it.streamId })
            .withValidEpisodeSelection()
        assertEquals(21, value.selectedEpisodeId); assertEquals(2, value.selectedSeasonNumber)
    }

    @Test fun completedSeasonContinuesToNextUnwatchedAndStaleIdsCannotSelectAnotherShow() {
        val value = ready().copy(watchProgress = listOf(progress(12, 20, true), progress(21, 1, true), progress(999, 100)).associateBy { it.streamId })
            .withValidEpisodeSelection()
        assertEquals(22, value.selectedEpisodeId)
    }

    @Test fun manualSelectionWinsAndPendingSelectionSurvivesEpisodeLoading() {
        val pending = ready().copy(episodeState = SeriesEpisodeState.Loading, selectedSeasonNumber = 2, selectedEpisodeId = 22,
            watchProgress = mapOf(11 to progress(11, 999))).withValidEpisodeSelection()
        assertEquals(22, pending.selectedEpisodeId)
        assertEquals(22, pending.copy(episodeState = SeriesEpisodeState.Ready(episodes)).withValidEpisodeSelection().selectedEpisodeId)
        assertEquals(11, ready().copy(selectedSeasonNumber = 9, selectedEpisodeId = 999).withValidEpisodeSelection().selectedEpisodeId)
    }

    @Test fun reopeningUsesWatchedEpisodeInsteadOfAStoredBrowsingSelection() {
        val browsing = ready().copy(selectedSeasonNumber = 1, selectedEpisodeId = 11,
            watchProgress = mapOf(21 to progress(21, 20)))
        assertEquals(11, browsing.withValidEpisodeSelection().selectedEpisodeId)
        val reopened = browsing.withValidEpisodeSelection(preferWatchProgress = true)
        assertEquals(21, reopened.selectedEpisodeId); assertEquals(2, reopened.selectedSeasonNumber)
        assertEquals(browsing.watchProgress, reopened.watchProgress)
    }

    @Test fun reopeningAfterCompletionAdvancesAcrossSeasonWithoutRewritingCheckpoints() {
        val browsing = ready().copy(selectedSeasonNumber = 1, selectedEpisodeId = 12,
            watchProgress = mapOf(12 to progress(12, 30, completed = true)))
        val reopened = browsing.withValidEpisodeSelection(preferWatchProgress = true)
        assertEquals(21, reopened.selectedEpisodeId); assertEquals(browsing.watchProgress, reopened.watchProgress)
    }

    @Test fun invalidOrOtherShowHistoryDoesNotDisplaceStoredSelection() {
        val browsing = ready().copy(selectedSeasonNumber = 2, selectedEpisodeId = 22,
            watchProgress = listOf(progress(999, 100), progress(11, 200, show = 99)).associateBy { it.streamId })
        assertEquals(22, browsing.withValidEpisodeSelection(preferWatchProgress = true).selectedEpisodeId)
    }

    @Test fun freshHolderRestoresWatchedEpisodeAndReopeningWarmDetailsDiscardsOnlyBrowsingChoice() = runTest {
        val source = object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = ready().copy(
                episodeState = SeriesEpisodeState.Idle, selectedSeasonNumber = 1, selectedEpisodeId = 11,
                watchProgress = mapOf(21 to progress(21, 20)))
        }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler),
            loadEpisodes = { SeriesEpisodeLoadResult.Success(episodes) })
        launch { holder.loadCachedCatalog(); holder.ensureEpisodesForSelectedSeries() }; runCurrent()
        assertEquals(21, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
        holder.rememberEpisode(episodes[3]); runCurrent()
        assertEquals(22, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
        holder.restoreWatchedEpisodeForDetails()
        assertEquals(21, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }

    @Test fun savedEpisodeListRestoresViewingHistoryAndSlowRefreshCannotReplaceANewManualChoice() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = ready().copy(
                episodeState = SeriesEpisodeState.Idle, selectedSeasonNumber = 1, selectedEpisodeId = 11,
                watchProgress = mapOf(21 to progress(21, 20)))
        }
        val holder = SeriesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler),
            loadSavedEpisodes = { SavedShowDetails("fixture", 5, 1, 1, null, SeriesEpisodeLoadResult.Success(episodes)) },
            savedNeedsRefresh = { true }, episodeContext = { "fixture" },
            loadEpisodes = { gate.await(); SeriesEpisodeLoadResult.Success(episodes) })
        launch { holder.loadCachedCatalog(); holder.ensureEpisodesForSelectedSeries() }; runCurrent()
        assertEquals(21, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
        holder.rememberEpisode(episodes[3]); gate.complete(Unit); runCurrent()
        assertEquals(22, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }

    @Test fun seasonReentryDoesNotResetAndEpisodeChoicePersistsAcrossNewHolderAndStore() = runTest {
        val file = File.createTempFile("episode-memory", ".json")
        try {
            fun source() = object : SeriesCatalogSource {
                override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
                    val prefs = SeriesPreferencesStore(file).load()
                    return ready().copy(episodeState = SeriesEpisodeState.Idle,
                        selectedSeasonNumber = prefs.selectedSeasonNumber, selectedEpisodeId = prefs.selectedEpisodeId)
                }
                override suspend fun savePreferences(preferences: SeriesPreferences) { SeriesPreferencesStore(file).save(preferences) }
            }
            fun holder() = SeriesCatalogStateHolder(source(), backgroundScope, StandardTestDispatcher(testScheduler),
                loadEpisodes = { SeriesEpisodeLoadResult.Success(episodes) }, episodeContext = { "fixture-provider" })
            val first = holder()
            launch { first.loadCachedCatalog(); first.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
            first.rememberEpisode(episodes[3]); first.selectSeason(2); runCurrent(); advanceUntilIdle()
            assertEquals(22, (first.state as SeriesCatalogState.Ready).selectedEpisodeId)
            val second = holder()
            launch { second.loadCachedCatalog(); second.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
            val restored = second.state as SeriesCatalogState.Ready
            assertEquals(22, restored.selectedEpisodeId); assertEquals(2, restored.selectedSeasonNumber)
        } finally { file.delete(); File(file.parentFile, "${file.name}.pending").delete() }
    }
}
