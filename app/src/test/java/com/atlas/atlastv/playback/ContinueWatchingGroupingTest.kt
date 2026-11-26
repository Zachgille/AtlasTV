package com.atlas.atlastv.playback

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingGroupingTest {
    private fun episode(id: Int, parent: Int?, updated: Long, completed: Boolean = false, position: Long = 90_000) =
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, id, seriesId = parent,
            title = "Shared title", seriesTitle = "Shared title", durationMs = 600_000,
            positionMs = position, updatedAtMs = updated, completed = completed)
    private fun movie(id: Int, updated: Long) = episode(id, null, updated).copy(contentType = WatchProgressContentType.Movie)

    @Test fun newestUnfinishedEpisodeRepresentsEachShowWithoutChangingAnyCheckpoint() {
        val records = listOf(episode(11, 1, 10), episode(22, 2, 20), episode(12, 1, 30),
            episode(13, 1, 50, completed = true), episode(23, 2, 40, position = 29_999))
        val snapshot = WatchProgressSnapshot(records)
        assertEquals(listOf(records[2], records[1]), snapshot.continueWatching())
        assertEquals(records, snapshot.records)
        assertEquals(5, snapshot.records.size)
    }
    @Test fun episodesAreGroupedBeforeApplyingTheShelfLimit() {
        val duplicates = (1..20).map { episode(it, 1, 200L-it) }
        val remaining = (2..13).map { episode(100+it, it, 100L-it) }
        val snapshot = WatchProgressSnapshot(duplicates + remaining)
        assertEquals((1..12).toList(), snapshot.continueWatching().map { it.seriesId })
        assertEquals(13, snapshot.continueWatching(30).size)
        assertTrue(snapshot.continueWatching(0).isEmpty())
        assertEquals(32, snapshot.records.size)
    }
    @Test fun typedMoviesShowsAndSameNamedShowsRemainDistinct() {
        val records = listOf(movie(1, 40), episode(1, 1, 30), episode(2, 2, 20), episode(3, 1, 10))
        assertEquals(records.take(3), WatchProgressSnapshot(records).continueWatching())
    }
    @Test fun missingAndInvalidParentsAreNotGuessedOrGroupedTogether() {
        val records = listOf(episode(1, null, 50), episode(2, null, 40), episode(3, 0, 30),
            episode(4, -1, 20), episode(5, 1, 10))
        assertEquals(records, WatchProgressSnapshot(records).continueWatching())
    }
    @Test fun laterCompletionOrPeekDoesNotHideAnEarlierUnfinishedEpisode() {
        val unfinished = episode(1, 1, 10)
        val records = listOf(episode(2, 1, 30, completed = true), episode(3, 1, 20, position = 0), unfinished)
        assertEquals(listOf(unfinished), WatchProgressSnapshot(records).continueWatching())
        assertTrue(WatchProgressSnapshot(records.take(2)).continueWatching().isEmpty())
    }
    @Test fun equalTimestampsKeepTheExistingStableInputOrder() {
        val records = listOf(episode(9, 1, 10), movie(1, 10), episode(2, 1, 10), episode(3, 2, 10))
        assertEquals(listOf(records[0], records[1], records[3]), WatchProgressSnapshot(records).continueWatching())
    }
}
