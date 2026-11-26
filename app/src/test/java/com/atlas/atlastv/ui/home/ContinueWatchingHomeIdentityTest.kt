package com.atlas.atlastv.ui.home

import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.series.SeriesItem
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingHomeIdentityTest {
    private fun episode(id: Int, parent: Int, updated: Long) = WatchProgressRecord(
        WatchProgressContentType.SeriesEpisode, id, seriesId = parent, title = "Show - S01E$id",
        seriesTitle = "Show", episodeTitle = "S01E$id", positionMs = 90_000,
        durationMs = 600_000, updatedAtMs = updated)
    private fun card(records: List<WatchProgressRecord>) = HomeShellMapper().map("fixture",0,
        emptyList(), listOf(SeriesItem("Show","Shows",1)), emptySet(), continueWatching = records)
        .rows.first { it.id == HomeRowIds.ContinueWatching }.cards.single()

    @Test fun newEpisodeUpdatesResumePayloadButKeepsShowFocusIdentity() {
        val earlier = episode(1, 1, 10)
        val latest = episode(2, 1, 20)
        val before = card(listOf(earlier)); val after = card(listOf(earlier, latest))
        assertEquals("continue_watching-show-1", before.id)
        assertEquals(before.id, after.id)
        assertEquals(2, after.streamId)
        assertEquals(1, after.seriesId)
        assertEquals("S1 E2", after.cardProgress!!.episodeLabel)
        // This is also MainActivity's exact Resume binding, not an episode-number guess.
        val current = WatchProgressSnapshot(listOf(earlier, latest)).continueWatching()
        assertEquals(latest, current.single { it.homeContinueCardId() == after.id })
        assertNotEquals(after.id, latest.copy(contentType = WatchProgressContentType.Movie).homeContinueCardId())
    }
    @Test fun unknownParentUsesExactEpisodeIdentityRatherThanInventingAShow() {
        assertEquals("continue_watching-series_episode-7", episode(7,1,10).copy(seriesId = null).homeContinueCardId())
        assertEquals("continue_watching-series_episode-7", episode(7,1,10).copy(seriesId = 0).homeContinueCardId())
    }
}
