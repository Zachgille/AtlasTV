package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieWatchActionsStateHolderTest {
    private val movie = MovieItem("A Movie", "Movies", 7, "mp4", "private-address")
    private class Backend : WatchProgressBackend {
        var records = emptyList<WatchProgressRecord>()
        var broken = false
        override suspend fun load() = WatchProgressSnapshot(records)
        override suspend fun save(record: WatchProgressRecord) {
            check(!broken)
            records = records.filterNot { it.key == record.key } + record
        }
    }

    @Test fun watchedAndUndoRestoreCheckpointWithoutChangingAnEpisodeWithTheSameId() = runTest {
        val original = movie.manualProgressRecord().copy(positionMs = 120_000, durationMs = 600_000, updatedAtMs = 1)
        val episode = original.copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 42)
        val backend = Backend().apply { records = listOf(original, episode) }
        val holder = MovieWatchActionsStateHolder(WatchProgressCoordinator(backend, backgroundScope), backgroundScope)
        holder.change(movie, true); runCurrent()
        assertTrue(holder.canUndo(7)); assertTrue(backend.records.first { it.key == original.key }.completed)
        holder.undo(); runCurrent()
        val restored = backend.records.first { it.key == original.key }
        assertEquals(original.positionMs, restored.positionMs); assertEquals(original.durationMs, restored.durationMs)
        assertFalse(restored.completed); assertEquals(episode, backend.records.first { it.key == episode.key })
    }

    @Test fun failedSaveRetriesTheOriginalMovieAndNewPlaybackInvalidatesUndo() = runTest {
        val backend = Backend().apply { broken = true }
        val writer = WatchProgressCoordinator(backend, backgroundScope, 30_000)
        val holder = MovieWatchActionsStateHolder(writer, backgroundScope)
        holder.change(movie, true); runCurrent(); assertTrue(holder.failed); assertFalse(holder.canUndo(7))
        holder.change(movie.copy(streamId = 8), false); assertTrue(holder.matches(7))
        backend.broken = false; holder.retry(); runCurrent()
        assertTrue(holder.canUndo(7)); assertEquals(7, backend.records.single().streamId)
        writer.openSession(movie.manualProgressRecord().key); assertFalse(holder.canUndo(7))
        assertFalse(movie.manualProgressRecord().toString().contains("private-address"))
    }

    @Test fun unwatchedAndUndoRetainCompletionAndUnknownDurationIsNotInvented() = runTest {
        val original = movie.manualProgressRecord().copy(completed = true, updatedAtMs = 1)
        val backend = Backend().apply { records = listOf(original) }
        val holder = MovieWatchActionsStateHolder(WatchProgressCoordinator(backend, backgroundScope), backgroundScope)
        holder.change(movie.copy(available = false), false); runCurrent(); assertEquals(original, backend.records.single())
        holder.change(movie, false); runCurrent(); assertFalse(backend.records.single().completed)
        holder.undo(); runCurrent(); assertTrue(backend.records.single().completed)
        assertNull(backend.records.single().durationMs); assertEquals(0L, backend.records.single().positionMs)
    }
}
