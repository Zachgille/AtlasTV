package com.atlas.atlastv.playback

import com.atlas.atlastv.ui.home.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingRemovalTest {
    private fun movie(id: Int = 1) = WatchProgressRecord(WatchProgressContentType.Movie, id,
        title = "Sample Movie", positionMs = 90_000, durationMs = 600_000, updatedAtMs = 100)
    private fun episode(id: Int, show: Int = 10) = movie(id).copy(contentType = WatchProgressContentType.SeriesEpisode,
        seriesId = show, seriesTitle = "Sample Show", episodeTitle = "Episode $id", categoryName = "Shows", extension = "mkv")
    private class Backend(initial: List<WatchProgressRecord>) : WatchProgressBackend {
        var rows = initial.associateBy { it.key }
        var calls = 0
        var batch: List<WatchProgressRecord> = emptyList()
        var write: suspend () -> Unit = {}
        var read: suspend () -> Unit = {}
        override suspend fun load(): WatchProgressSnapshot { read(); return WatchProgressSnapshot(rows.values.toList()) }
        override suspend fun save(record: WatchProgressRecord) { write(); rows = rows + (record.key to record) }
        override suspend fun saveBatch(records: List<WatchProgressRecord>) { calls++; batch = records; write(); rows = rows + records.associateBy { it.key } }
    }
    private suspend fun remove(writer: WatchProgressCoordinator) =
        (writer.removeFromContinueWatching(WatchProgressContentType.SeriesEpisode, 1, 10) as ContinueWatchingRemovalResult.Accepted).edit

    @Test fun batchRemovalWaitsForAllAcknowledgementsAndPreservesUnrelatedCompletedAndPeekRows() = runTest {
        val original = listOf(episode(1), episode(2), episode(3).copy(completed = true), episode(4).copy(positionMs = 5_000), episode(5,20), movie())
        val gate = CompletableDeferred<Unit>(); val backend = Backend(original).apply { write = { gate.await() } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val edit = remove(writer); runCurrent()
        assertEquals(EpisodeEditStatus.Saving, writer.removalStatus(edit)); assertFalse(writer.canUndoRemoval(edit))
        assertEquals(setOf("series_episode:1","series_episode:2"), backend.batch.map { it.key }.toSet())
        assertEquals(original.associateBy { it.key }, backend.rows)
        gate.complete(Unit); runCurrent()
        assertEquals(EpisodeEditStatus.Saved, writer.removalStatus(edit)); assertTrue(writer.canUndoRemoval(edit))
        original.drop(2).forEach { assertEquals(it, backend.rows[it.key]) }
        assertTrue(backend.rows.getValue(episode(1).key).positionMs == 0L)
    }

    @Test fun undoRestoresAllOriginalFieldsAndIsNotUndoableAgain() = runTest {
        val original = listOf(episode(1), episode(2).copy(positionMs = 200_000)); val backend = Backend(original)
        val writer = WatchProgressCoordinator(backend, backgroundScope); val edit = remove(writer); runCurrent()
        val undo = (writer.undoRemoval(edit) as ContinueWatchingRemovalResult.Accepted).edit; runCurrent()
        assertEquals(EpisodeEditStatus.Saved, writer.removalStatus(undo))
        original.forEach { assertEquals(it, backend.rows.getValue(it.key).copy(updatedAtMs = it.updatedAtMs)) }
        assertFalse(writer.canUndoRemoval(edit)); assertEquals(2, backend.calls)
    }

    @Test fun newSessionForAnyRemovedEpisodeInvalidatesTheWholeUndoAndRejectsOldCaptures() = runTest {
        val backend = Backend(listOf(episode(1),episode(2))); val writer = WatchProgressCoordinator(backend, backgroundScope)
        val old = writer.openSession(episode(1).key)
        val edit = remove(writer); runCurrent()
        writer.submit(old, episode(1).copy(positionMs = 300_000)); runCurrent()
        assertEquals(0L, backend.rows.getValue(episode(1).key).positionMs)
        writer.openSession(episode(2).key)
        assertFalse(writer.canUndoRemoval(edit)); assertEquals(ContinueWatchingRemovalResult.Conflict, writer.undoRemoval(edit))
        assertEquals(1, backend.calls)
    }

    @Test fun playbackDuringLoadWinsWithoutAnyReset() = runTest {
        val backend = Backend(listOf(episode(1))); val gate = CompletableDeferred<Unit>()
        backend.read = { gate.await() }; val writer = WatchProgressCoordinator(backend, backgroundScope)
        val result = async { writer.removeFromContinueWatching(WatchProgressContentType.SeriesEpisode,1,10) }; runCurrent()
        writer.openSession(episode(1).key); gate.complete(Unit); runCurrent()
        assertEquals(ContinueWatchingRemovalResult.Conflict, result.await()); assertEquals(0, backend.calls)
        assertEquals(90_000L, backend.rows.getValue(episode(1).key).positionMs)
    }

    @Test fun failedBatchKeepsOriginalDiskRowsAndRetryAcknowledgesTheOriginalGroup() = runTest {
        val backend = Backend(listOf(episode(1),episode(2))).apply { write = { error("private failure") } }
        val writer = WatchProgressCoordinator(backend, backgroundScope, retryDelayMs = 30_000)
        val edit = remove(writer); runCurrent()
        assertEquals(EpisodeEditStatus.Failed, writer.removalStatus(edit)); assertFalse(writer.canUndoRemoval(edit))
        assertTrue(backend.rows.values.all { it.positionMs == 90_000L })
        backend.write = {}; writer.retryRemoval(edit); runCurrent()
        assertEquals(EpisodeEditStatus.Saved, writer.removalStatus(edit)); assertTrue(writer.canUndoRemoval(edit))
        assertTrue(backend.rows.values.all { it.positionMs == 0L })
    }

    @Test fun inFlightRemovalCannotDiscardNewerReplayCapture() = runTest {
        val backend = Backend(listOf(movie())); val gate = CompletableDeferred<Unit>(); backend.write = { gate.await() }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val edit = (writer.removeFromContinueWatching(WatchProgressContentType.Movie,1) as ContinueWatchingRemovalResult.Accepted).edit; runCurrent()
        val session = writer.openSession(movie().key); writer.submit(session,movie().copy(positionMs = 150_000))
        gate.complete(Unit); runCurrent()
        assertEquals(150_000L, backend.rows.getValue(movie().key).positionMs)
        assertEquals(EpisodeEditStatus.Superseded, writer.removalStatus(edit))
    }

    @Test fun stateHolderRejectsDuplicatesAndOffersUndoOnlyAfterSavedThenRetryForFailedUndo() = runTest {
        val backend = Backend(listOf(movie())); val gate = CompletableDeferred<Unit>(); backend.write = { gate.await() }
        val writer = WatchProgressCoordinator(backend, backgroundScope, retryDelayMs = 30_000)
        val actions = ContinueWatchingActionsStateHolder(writer, backgroundScope)
        val card = HomeCard("movie", "Sample Movie (2020)", HomeContentType.Movie, "Movies", 1, statusText = "")
        actions.remove(card); actions.remove(card); runCurrent()
        assertTrue(actions.saving); assertFalse(actions.canUndo); assertEquals(1, backend.calls)
        gate.complete(Unit); runCurrent()
        assertEquals("Removed", actions.message); assertTrue(actions.canUndo); assertEquals("Sample Movie", actions.title)
        backend.write = { error("private failure") }; actions.undo(); runCurrent()
        assertTrue(actions.failed); assertFalse(actions.canUndo); assertEquals("Retry", actions.actionLabel)
        backend.write = {}; actions.retry(); runCurrent()
        assertEquals("Restored", actions.message); assertFalse(actions.failed); assertFalse(actions.canUndo)
        assertEquals(90_000L,backend.rows.getValue(movie().key).positionMs)
        actions.act(); assertNull(actions.message)
    }

    @Test fun loadFailureRetryRetainsOriginalTargetAndNeverClaimsRemovalEarly() = runTest {
        val backend = Backend(listOf(movie(),movie(2))).apply { read = { error("http://private/secret") } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val actions = ContinueWatchingActionsStateHolder(writer, backgroundScope)
        actions.remove(HomeCard("movie", "Sample", HomeContentType.Movie,"Movies",1,statusText = "")); runCurrent()
        assertTrue(actions.failed); assertFalse(actions.canUndo); assertFalse(actions.message!!.contains("secret"))
        backend.read = {}; actions.retry(); runCurrent()
        assertEquals("Removed",actions.message); assertEquals(0L,backend.rows.getValue(movie().key).positionMs)
        assertEquals(90_000L,backend.rows.getValue(movie(2).key).positionMs)
        writer.openSession(movie().key); runCurrent(); assertFalse(actions.canUndo); assertEquals("Done",actions.actionLabel)
    }
}
