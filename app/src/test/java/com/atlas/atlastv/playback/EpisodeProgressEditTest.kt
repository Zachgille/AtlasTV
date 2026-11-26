package com.atlas.atlastv.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EpisodeProgressEditTest {
    private class Backend : WatchProgressBackend {
        var records = emptyList<WatchProgressRecord>()
        var onSave: suspend (WatchProgressRecord) -> Unit = {}
        var onLoad: suspend () -> Unit = {}
        override suspend fun load(): WatchProgressSnapshot { onLoad(); return WatchProgressSnapshot(records) }
        override suspend fun save(record: WatchProgressRecord) { onSave(record); records = records.filterNot { it.key == record.key } + record }
    }
    private fun episode(id: Int = 10, parent: Int = 7, position: Long = 45_000, duration: Long? = 600_000, watched: Boolean = false) =
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, id, parent, "Pilot", "A Show", "Pilot", "Shows", "mp4", duration, position, 1, watched)
    private fun accepted(result: EpisodeEditResult) = (result as EpisodeEditResult.Accepted).edit

    @Test fun markAndUndoRestoreExactPartialProgressWithoutTouchingTypedIdsOtherEpisodesOrParents() = runTest {
        val initial = listOf(episode(), episode(11), episode(12, 8), episode().copy(contentType = WatchProgressContentType.Movie, seriesId = null))
        val backend = Backend().apply { records = initial }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val edit = accepted(writer.setEpisodeWatched(episode(), true)); runCurrent()
        assertEquals(EpisodeEditStatus.Saved, writer.editStatus(edit)); assertTrue(writer.canUndo(edit))
        val marked = backend.records.first { it.key == episode().key }
        assertTrue(marked.completed); assertEquals(600_000L, marked.positionMs)
        assertFalse(writer.loadSnapshot().continueWatching().any { it.key == marked.key })
        accepted(writer.undoEpisodeEdit(edit)); runCurrent(); writer.awaitSaved()
        val restored = backend.records.first { it.key == episode().key }
        assertEquals(episode(), restored.copy(updatedAtMs = 1)); assertTrue(restored.updatedAtMs > marked.updatedAtMs)
        assertEquals(initial.filter { it.key != episode().key }, backend.records.filter { it.key != episode().key })
    }
    @Test fun unwatchedStartsAtZeroAndUndoRestoresCompletedCheckpointIncludingUnknownRuntime() = runTest {
        val old = episode(position = 192_000, duration = null, watched = true)
        val backend = Backend().apply { records = listOf(old) }; val writer = WatchProgressCoordinator(backend, backgroundScope)
        val edit = accepted(writer.setEpisodeWatched(old, false)); runCurrent()
        assertFalse(backend.records.single().completed); assertEquals(0L, backend.records.single().positionMs)
        assertNull(WatchProgressPolicy.resumePositionMs(backend.records.single()))
        accepted(writer.undoEpisodeEdit(edit)); runCurrent()
        assertEquals(old, backend.records.single().copy(updatedAtMs = old.updatedAtMs))
    }
    @Test fun unknownUnstartedEpisodeCanBeMarkedAndUndoReturnsToUnstartedStateWithoutFakeDuration() = runTest {
        val backend = Backend(); val writer = WatchProgressCoordinator(backend, backgroundScope)
        val base = episode(position = 0, duration = null)
        val edit = accepted(writer.setEpisodeWatched(base, true)); runCurrent()
        assertTrue(backend.records.single().completed); assertNull(backend.records.single().durationMs)
        accepted(writer.undoEpisodeEdit(edit)); runCurrent()
        assertFalse(backend.records.single().completed); assertEquals(0L, backend.records.single().positionMs)
        assertTrue(writer.loadSnapshot().continueWatching().isEmpty())
    }
    @Test fun editWinsOverExitedPlayerCaptureAndCannotBeUndoneAcrossNewPlaybackEvenBeforeItsFirstCapture() = runTest {
        val gate = CompletableDeferred<Unit>(); val backend = Backend().apply { onSave = { if (!it.completed) gate.await() } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val old = writer.openSession(episode().key); writer.submit(old, episode()); runCurrent()
        val edit = accepted(writer.setEpisodeWatched(episode(), true))
        writer.submit(old, episode(position = 90_000)); gate.complete(Unit); runCurrent(); writer.awaitSaved()
        assertTrue(backend.records.single().completed)
        val new = writer.openSession(episode().key)
        assertFalse(writer.canUndo(edit)); assertEquals(EpisodeEditResult.Conflict, writer.undoEpisodeEdit(edit))
        writer.submit(new, episode(position = 31_000)); runCurrent()
        assertFalse(backend.records.single().completed); assertEquals(31_000L, backend.records.single().positionMs)
    }
    @Test fun newerExplicitEditInvalidatesOldUndoAndUndoItselfRemainsOrdered() = runTest {
        val backend = Backend().apply { records = listOf(episode()) }; val writer = WatchProgressCoordinator(backend, backgroundScope)
        val first = accepted(writer.setEpisodeWatched(episode(), true)); runCurrent()
        val second = accepted(writer.setEpisodeWatched(episode(), false)); runCurrent()
        assertEquals(EpisodeEditResult.Conflict, writer.undoEpisodeEdit(first))
        val undone = accepted(writer.undoEpisodeEdit(second)); runCurrent()
        assertEquals(EpisodeEditStatus.Saved, writer.editStatus(undone)); assertTrue(backend.records.single().completed)
        assertEquals(EpisodeEditResult.Conflict, writer.undoEpisodeEdit(second))
    }
    @Test fun failedSaveRetainsIntentHasNoPrematureUndoAndExplicitRetryWakesBackoff() = runTest {
        var broken = true
        val backend = Backend().apply { records = listOf(episode()); onSave = { if (broken) error("private") } }
        val writer = WatchProgressCoordinator(backend, backgroundScope, retryDelayMs = 30_000)
        val edit = accepted(writer.setEpisodeWatched(episode(), true)); runCurrent()
        assertEquals(EpisodeEditStatus.Failed, writer.editStatus(edit)); assertFalse(writer.canUndo(edit))
        assertFalse(backend.records.single().completed); assertTrue(writer.loadSnapshot().records.single().completed)
        broken = false; writer.retryEdit(edit); runCurrent()
        assertEquals(EpisodeEditStatus.Saved, writer.editStatus(edit)); assertTrue(writer.canUndo(edit))
        assertTrue(backend.records.single().completed)
    }
    @Test fun delayedLookupCannotOverwriteNewerPlaybackOrClearItsCompletion() = runTest {
        val gate = CompletableDeferred<Unit>(); val backend = Backend().apply { records = listOf(episode()); onLoad = { gate.await() } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        var result: EpisodeEditResult? = null
        backgroundScope.launch { result = writer.setEpisodeWatched(episode(), true) }; runCurrent()
        val session = writer.openSession(episode().key); writer.submit(session, episode(position = 90_000))
        gate.complete(Unit); runCurrent()
        assertEquals(EpisodeEditResult.Conflict, result); assertEquals(90_000L, backend.records.single().positionMs)
    }
    @Test fun rejectsWrongContentTypeAndEpisodeParentAndAdoptsLegacyParentlessRecordSafely() = runTest {
        val backend = Backend().apply { records = listOf(episode(parent = 8)) }; val writer = WatchProgressCoordinator(backend, backgroundScope)
        assertEquals(EpisodeEditResult.Conflict, writer.setEpisodeWatched(episode(), true))
        assertEquals(EpisodeEditResult.Conflict, writer.setEpisodeWatched(episode().copy(contentType = WatchProgressContentType.Movie), true))
        backend.records = listOf(episode().copy(seriesId = null, durationMs = null))
        val edit = accepted(writer.setEpisodeWatched(episode(), true)); runCurrent()
        assertEquals(7, backend.records.single().seriesId); assertEquals(600_000L, backend.records.single().durationMs)
        accepted(writer.undoEpisodeEdit(edit)); runCurrent(); assertNull(backend.records.single().seriesId)
    }
}
