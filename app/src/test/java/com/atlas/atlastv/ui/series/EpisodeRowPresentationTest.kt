package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.*
import org.junit.Assert.*
import org.junit.Test

class EpisodeRowPresentationTest {
    private val episode = SeriesEpisodeItem("Show - S01E01 - Pilot", 1, 1, 11, "mp4", "", 2_520_000)
    private val saved = WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 11, 7, "Show", positionMs = 630_000, durationMs = 2_520_000, updatedAtMs = 1)
    @Test fun runtimeAndActualCheckpointUseCompactLabelsWithoutRedundantCode() {
        val row = episode.rowPresentation(7, saved)
        assertEquals("42m", row.durationLabel); assertEquals("32m left", row.progress?.timeLabel)
        assertEquals(0.25f, row.progress!!.fraction!!, 0f); assertNull(row.progress.episodeLabel)
        assertEquals("Pilot", episode.displayEpisodeName("Show"))
        assertEquals(630_000L, saved.positionMs)
    }
    @Test fun providerRuntimeCanFillUnknownSavedDurationWithoutChangingTheCheckpoint() {
        val unknown = saved.copy(durationMs = null)
        assertEquals(0.25f, episode.rowPresentation(7, unknown).progress!!.fraction!!, 0f)
        assertNull(unknown.durationMs)
        val absent = episode.copy(durationMs = null).rowPresentation(7, unknown)
        assertNull(absent.durationLabel); assertNull(absent.progress!!.fraction)
        assertEquals("At 10:30", absent.progress.timeLabel)
    }
    @Test fun checkpointDurationKeepsRuntimeAndProgressConsistentAndInvalidMetadataIsOmitted() {
        val row = episode.rowPresentation(7, saved.copy(durationMs = 1_260_000))
        assertEquals("21m", row.durationLabel); assertEquals(0.5f, row.progress!!.fraction!!, 0f)
        assertEquals("11m left", row.progress.timeLabel)
        assertNull(episode.copy(durationMs = -1).rowPresentation(7, null).durationLabel)
        assertEquals("1h 1m", episode.copy(durationMs = 3_600_001).rowPresentation(7, null).durationLabel)
    }
    @Test fun absurdProviderAndCheckpointRuntimesNeverReachTheLabelOrProgressFraction() {
        val row = episode.copy(durationMs = Long.MAX_VALUE).rowPresentation(7, saved.copy(durationMs = Long.MAX_VALUE))
        assertNull(row.durationLabel); assertNull(row.progress!!.fraction); assertEquals("At 10:30", row.progress.timeLabel)
        val fallback = episode.rowPresentation(7, saved.copy(durationMs = Long.MAX_VALUE))
        assertEquals("42m", fallback.durationLabel); assertEquals(0.25f, fallback.progress!!.fraction!!, 0f)
        assertEquals(2_520_000L, saved.durationMs)
    }
    @Test fun unstartedCompletedAndSmallPeekHaveHonestDistinctStates() {
        assertNull(episode.rowPresentation(7, null).progress)
        assertNull(episode.rowPresentation(7, saved.copy(positionMs = 0)).progress)
        assertEquals("Watched", episode.rowPresentation(7, saved.copy(completed = true)).progress?.timeLabel)
        assertEquals(1f, episode.rowPresentation(7, saved.copy(completed = true)).progress!!.fraction!!, 0f)
        assertNotNull(episode.rowPresentation(7, saved.copy(positionMs = 1_000)).progress)
    }
    @Test fun reusedMovieWrongShowAndWrongEpisodeCannotMarkTheEpisodeWatched() {
        for (wrong in listOf(saved.copy(contentType = WatchProgressContentType.Movie), saved.copy(seriesId = 8), saved.copy(streamId = 12))) {
            assertNull(episode.rowPresentation(7, wrong.copy(completed = true)).progress)
            val state = SeriesCatalogState.Ready("test", null, emptyList(), emptyList(), selectedSeriesId = 7,
                watchProgress = mapOf(11 to wrong.copy(completed = true)))
            assertFalse(state.episodeDetails(episode).completed)
            assertNull(state.episodeDetails(episode).progressPercent)
        }
        assertNotNull(episode.rowPresentation(7, saved.copy(seriesId = null)).progress)
    }
    @Test fun unknownNumberingUsesHumanFallbackRatherThanProviderIds() {
        assertEquals("Episode", episode.copy(title = "", episodeNumber = null).displayEpisodeName(null))
        assertEquals("Episode 1", episode.copy(title = "Show - S01E01").displayEpisodeName("Show"))
    }
}
