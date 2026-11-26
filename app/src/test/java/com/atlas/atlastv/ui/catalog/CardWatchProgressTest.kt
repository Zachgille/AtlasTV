package com.atlas.atlastv.ui.catalog

import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import org.junit.Assert.*
import org.junit.Test

class CardWatchProgressTest {
    private fun record(position: Long = 600_000, duration: Long? = 3_600_000) =
        WatchProgressRecord(WatchProgressContentType.Movie, 1, title = "Movie", positionMs = position, durationMs = duration, updatedAtMs = 1)
    @Test fun ratioUsesActualCheckpointAndRoundsRemainingTimeUp() {
        val progress = record(600_001, 3_600_000).cardWatchProgress()
        assertEquals(600_001f / 3_600_000f, progress.fraction!!, 0.00001f)
        assertEquals("50m left", progress.timeLabel); assertNull(progress.episodeLabel)
        assertEquals("1m left", record(1, 60_001).cardWatchProgress().timeLabel)
        assertEquals("1h left", record(0, 3_600_000).cardWatchProgress().timeLabel)
    }
    @Test fun unknownInvalidAndNegativeDurationNeverInventABarOrTimeLeft() {
        for (duration in listOf(null, 0L, -1L)) {
            val progress = record(duration = duration).cardWatchProgress()
            assertNull(progress.fraction); assertEquals("At 10:00", progress.timeLabel)
        }
    }
    @Test fun malformedAndExtremePositionsStayFiniteClampedAndOverflowSafe() {
        val negative = record(-1).cardWatchProgress(); assertEquals(0f, negative.fraction!!, 0f); assertEquals("1h left", negative.timeLabel)
        val excessive = record(Long.MAX_VALUE, 1).cardWatchProgress(); assertEquals(1f, excessive.fraction!!, 0f); assertEquals("0m left", excessive.timeLabel)
        val huge = record(1, Long.MAX_VALUE).cardWatchProgress(); assertTrue(huge.fraction!!.isFinite()); assertFalse(huge.timeLabel.contains('-'))
    }
    @Test fun completedIsWatchedEvenWithUnknownDurationWithoutAlteringResumePolicy() {
        val progress = record(duration = null).copy(completed = true).cardWatchProgress()
        assertEquals("Watched", progress.timeLabel); assertEquals(1f, progress.fraction!!, 0f)
        assertFalse(WatchProgressPolicy.isResumeEligible(record().copy(completed = true)))
    }
    @Test fun explicitEpisodeCodeIsShortDeduplicatedAndIgnoresMeaningfulTitleNumbers() {
        val episode = record().copy(contentType = WatchProgressContentType.SeriesEpisode, episodeTitle = "Show 1899 - S01E003 - S1E3", title = "Show")
        assertEquals("S1 E3", episode.cardWatchProgress().episodeLabel)
        assertEquals("S1 E3 · 50m left", episode.cardWatchProgress().summary)
        assertEquals("S0 E1", episode.copy(episodeTitle = "s 00 - e 01").cardWatchProgress().episodeLabel)
    }
    @Test fun absentAmbiguousOrMalformedEpisodeNumberingIsNeverGuessed() {
        val episode = record().copy(contentType = WatchProgressContentType.SeriesEpisode)
        for (title in listOf("1899", "Episode 4", "3x7", "S1E0", "S1E2 / S2E1", "S1000E1", "Episode"))
            assertNull(episode.copy(episodeTitle = title).cardWatchProgress().episodeLabel)
        assertNull(record().copy(title = "S1E2").cardWatchProgress().episodeLabel)
        assertEquals("S5 E4", episode.copy(title = "Show - S05E04").cardWatchProgress().episodeLabel)
        assertNull(episode.copy(episodeTitle = "S1E2", title = "S2E1").cardWatchProgress().episodeLabel)
    }
    @Test fun movieAndShowIdCollisionsCannotAttachTheWrongProgress() {
        val movie = MovieItem("Movie", "Movies", 1, "mp4", "room://safe")
        val episode = record().copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 1, episodeTitle = "S01E02")
        assertNull(progressForMovie(movie, mapOf(1 to episode)).cardProgress)
        assertNull(progressForShow(SeriesItem("Show", "Shows", 1), mapOf(1 to record())))
        assertEquals("S1 E2", progressForShow(SeriesItem("Show", "Shows", 1), mapOf(1 to episode))?.episodeLabel)
    }
    @Test fun showUsesNewestEligibleEpisodeAndStableIdTiesWithoutCompletedOrWrongParent() {
        val old = record().copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 3, episodeTitle = "S1E1", streamId = 1)
        val newest = old.copy(streamId = 2, updatedAtMs = 9, episodeTitle = "S2E4")
        val records = listOf(old, newest, newest.copy(streamId = 3, episodeTitle = "S2E5"), newest.copy(streamId = 4, completed = true, updatedAtMs = 20), newest.copy(streamId = 5, seriesId = 4, updatedAtMs = 30))
        assertEquals("S2 E5", progressForShow(SeriesItem("Show", "Shows", 3), records.associateBy { it.streamId })?.episodeLabel)
    }
    @Test fun normalHomeMapperCarriesProgressWithoutLosingTypedIdentityCheckpointOrResumeAction() {
        val episode = record(duration = null).copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 3, streamId = 31,
            seriesTitle = "A Show (2015)", episodeTitle = "A Show - S01E02")
        val state = HomeShellMapper().map("check", null, emptyList(), emptyList(), emptySet(), continueWatching = listOf(record(), episode))
        val cards = state.rows.first { it.id == HomeRowIds.ContinueWatching }.cards
        assertEquals(1, cards[0].streamId); assertEquals("50m left", cards[0].cardProgress?.timeLabel)
        assertEquals(31, cards[1].streamId); assertEquals(3, cards[1].seriesId); assertEquals("S1 E2", cards[1].cardProgress?.episodeLabel)
        assertNull(cards[1].cardProgress?.fraction); assertEquals("Resume", cards[1].heroActionLabel())
        assertEquals("Resume at 10:00", cards[1].progressLabel); assertEquals(600_000L, episode.positionMs)
    }
}
