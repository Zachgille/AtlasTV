package com.atlas.atlastv.playback

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartOverTest {
    private fun movie() = WatchProgressRecord(WatchProgressContentType.Movie, 1, title = "Movie", positionMs = 255_000, durationMs = 600_000, updatedAtMs = 17)
    @Test fun startOverReturnsZeroWithoutChangingTheStoredMovieOrEpisode() = runTest {
        for (record in listOf(movie(), movie().copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 7))) {
            val snapshot = WatchProgressSnapshot(listOf(record))
            val holder = PlaybackStartStateHolder(backgroundScope) { snapshot }
            holder.startOver(record.copy(positionMs = 0)) { "local video" }; runCurrent()
            val ready = holder.state.value as PlaybackStartState.Ready
            assertEquals(0L, ready.resumeMs); assertTrue(ready.startedFromBeginning)
            assertEquals(record, ready.record); assertEquals(record, snapshot.records.single())
            holder.start(record); runCurrent()
            assertEquals(255_000L, (holder.state.value as PlaybackStartState.Ready).resumeMs)
            assertFalse((holder.state.value as PlaybackStartState.Ready).startedFromBeginning)
        }
    }
    @Test fun preparationAndReadRetryKeepTheExplicitStartOverChoice() = runTest {
        var readFailure = true; var prepareFailure = true
        val record = movie()
        val holder = PlaybackStartStateHolder(backgroundScope) {
            if (readFailure) error("private diagnostic") else WatchProgressSnapshot(listOf(record))
        }
        holder.startOver(record) { if (prepareFailure) error("private diagnostic") else "local video" }; runCurrent()
        assertEquals(PlaybackStartState.Failed, holder.state.value)
        readFailure = false; holder.retry(); runCurrent(); assertEquals(PlaybackStartState.Failed, holder.state.value)
        prepareFailure = false; holder.retry(); runCurrent()
        val ready = holder.state.value as PlaybackStartState.Ready
        assertEquals(0L, ready.resumeMs); assertTrue(ready.startedFromBeginning); assertEquals(record, ready.record)
    }
    @Test fun cancelAndNewResumeRejectAnUncooperativeOldStartOver() = runTest {
        var calls = 0
        val holder = PlaybackStartStateHolder(backgroundScope) {
            if (++calls == 1) withContext(NonCancellable) { delay(1_000) }
            WatchProgressSnapshot(listOf(movie()))
        }
        holder.startOver(movie()); runCurrent(); holder.cancel()
        holder.start(movie()); runCurrent()
        val current = holder.state.value as PlaybackStartState.Ready
        assertEquals(255_000L, current.resumeMs); assertFalse(current.startedFromBeginning)
        advanceTimeBy(1_000); runCurrent(); assertEquals(current, holder.state.value)
    }
    @Test fun reusedEpisodeIdFromAnotherShowCannotSupplyTheRestartRecord() = runTest {
        val other = movie().copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 99)
        val base = other.copy(seriesId = 7, title = "Our episode", positionMs = 0, updatedAtMs = 0)
        val holder = PlaybackStartStateHolder(backgroundScope) { WatchProgressSnapshot(listOf(other, movie())) }
        holder.startOver(base); runCurrent()
        assertEquals(base, (holder.state.value as PlaybackStartState.Ready).record)
        holder.start(base); runCurrent(); assertNull((holder.state.value as PlaybackStartState.Ready).resumeMs)
    }
    @Test fun onlyTheCorrectStartedUnfinishedTitleOffersStartOverIncludingSmallPeeks() {
        val peek = movie().copy(positionMs = 1)
        assertTrue(canStartOver(peek, WatchProgressContentType.Movie, 1))
        assertFalse(canStartOver(null, WatchProgressContentType.Movie, 1))
        assertFalse(canStartOver(peek.copy(positionMs = 0), WatchProgressContentType.Movie, 1))
        assertFalse(canStartOver(peek.copy(completed = true), WatchProgressContentType.Movie, 1))
        assertFalse(canStartOver(peek, WatchProgressContentType.Movie, 2))
        assertFalse(canStartOver(peek, WatchProgressContentType.SeriesEpisode, 1, 7))
        val episode = peek.copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 7)
        assertTrue(canStartOver(episode, WatchProgressContentType.SeriesEpisode, 1, 7))
        assertFalse(canStartOver(episode, WatchProgressContentType.SeriesEpisode, 1, 8))
        assertTrue(canStartOver(episode.copy(seriesId = null), WatchProgressContentType.SeriesEpisode, 1, 7))
    }
    @Test fun restartCannotSaveUntilARealFrameAndReadinessButRetryKeepsItsNewPosition() {
        val recovery = PlaybackRecovery(0, true, requireFirstFrame = true)
        assertEquals(0L, recovery.beginAttempt()); assertFalse(recovery.canSaveProgress)
        recovery.markReady(); assertFalse(recovery.canSaveProgress)
        recovery.markFirstFrame(); assertTrue(recovery.canSaveProgress)
        recovery.checkpoint(18_000); assertEquals(18_000L, recovery.beginAttempt()); assertFalse(recovery.canSaveProgress)
        recovery.markReady(); assertFalse(recovery.canSaveProgress)
        assertEquals(18_000L, recovery.checkpoint(0))
        recovery.markFirstFrame(); assertTrue(recovery.canSaveProgress)
        val frameFirst = PlaybackRecovery(0, true, requireFirstFrame = true)
        frameFirst.markFirstFrame(); assertFalse(frameFirst.canSaveProgress)
        frameFirst.markReady(); assertTrue(frameFirst.canSaveProgress)
    }
    @Test fun ordinaryResumeStillUsesItsSavedPointAndExistingReadinessPolicy() {
        val recovery = PlaybackRecovery(255_000, true)
        assertEquals(255_000L, recovery.beginAttempt()); assertFalse(recovery.canSaveProgress)
        recovery.markReady(); assertTrue(recovery.canSaveProgress)
        assertEquals(256_000L, recovery.checkpoint(256_000))
    }
}
