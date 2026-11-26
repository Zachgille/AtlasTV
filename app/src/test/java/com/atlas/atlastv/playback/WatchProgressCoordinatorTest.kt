package com.atlas.atlastv.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchProgressCoordinatorTest {
    private class Backend : WatchProgressBackend {
        var snapshot = WatchProgressSnapshot()
        val writes = mutableListOf<WatchProgressRecord>()
        val moves = mutableListOf<WatchProgressRecord>()
        var saveAction: suspend (WatchProgressRecord) -> Unit = {}
        var loadAction: (suspend () -> WatchProgressSnapshot)? = null
        override suspend fun load() = loadAction?.invoke() ?: snapshot
        override suspend fun save(record: WatchProgressRecord) {
            saveAction(record)
            writes += record
            snapshot = WatchProgressSnapshot(snapshot.records.filterNot { it.key == record.key } + record)
        }
        override suspend fun saveStartedPlayback(record: WatchProgressRecord, startedAtMs: Long) {
            save(record)
            moves += record
        }
    }
    private fun record(position: Long = 100_000, id: Int = 1, time: Long = 10) = WatchProgressRecord(
        WatchProgressContentType.Movie, id, title = "Test", durationMs = 600_000, positionMs = position, updatedAtMs = time,
    )

    @Test fun automaticMoveRequiresRenderedResumeEligibleProgressAndHappensOncePerSession() = runTest {
        val backend = Backend()
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val session = writer.openSession(record().key)
        writer.submit(session, record(100_000)); runCurrent() // Ready/seek without a frame.
        writer.submit(session, record(29_999), renderedPlayback = true); runCurrent()
        assertTrue(backend.moves.isEmpty())
        writer.submit(session, record(30_000), renderedPlayback = true); runCurrent()
        assertEquals(listOf(30_000L), backend.moves.map { it.positionMs })
        writer.submit(session, record(60_000), renderedPlayback = true); runCurrent()
        assertEquals(1, backend.moves.size) // A deliberate re-add is not removed by periodic saves.
        writer.awaitSaved()
    }

    @Test fun failedAutomaticMoveRetriesBeforeAcknowledgementThenStopsMoving() = runTest {
        val backend = Backend().apply { saveAction = { error("disk full") } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val session = writer.openSession(record().key)
        writer.submit(session, record(), renderedPlayback = true); runCurrent()
        assertTrue(writer.saveFailed.value)
        assertTrue(backend.moves.isEmpty())
        backend.saveAction = {}
        advanceTimeBy(1_000); runCurrent()
        writer.awaitSaved()
        writer.submit(session, record(200_000), renderedPlayback = true); runCurrent()
        assertFalse(writer.saveFailed.value)
        assertEquals(1, backend.moves.size)
    }

    @Test fun episodeUsesItsParentAndMissingParentsAndCompletedEditsNeverMove() = runTest {
        val backend = Backend()
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val episode = record().copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = null)
        val session = writer.openSession(episode.key)
        writer.submit(session, episode, renderedPlayback = true); runCurrent()
        writer.submit(session, episode.copy(seriesId = -1), renderedPlayback = true); runCurrent()
        writer.submit(session, episode.copy(seriesId = 77, completed = true), renderedPlayback = true); runCurrent()
        assertTrue(backend.moves.isEmpty())
        writer.submit(session, episode.copy(seriesId = 77), renderedPlayback = true); runCurrent()
        assertEquals(77, backend.moves.single().seriesId)
        val edit = writer.setEpisodeWatched(episode.copy(seriesId = 77), true)
        assertTrue(edit is EpisodeEditResult.Accepted); runCurrent()
        assertEquals(1, backend.moves.size)
    }

    @Test fun submissionIsImmediateAndPendingProgressIsReadable() = runTest {
        val backend = Backend().apply { saveAction = { delay(5_000) } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        writer.submit(writer.openSession(record().key), record())
        runCurrent()
        assertTrue(backend.writes.isEmpty())
        assertEquals(100_000L, writer.loadSnapshot().records.single().positionMs)
        advanceTimeBy(5_000); runCurrent()
        writer.awaitSaved()
        assertEquals(100_000L, backend.snapshot.records.single().positionMs)
    }

    @Test fun coalescesQueuedSnapshotsAndAllowsRewind() = runTest {
        val backend = Backend()
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val session = writer.openSession(record().key)
        writer.submit(session, record(400_000))
        writer.submit(session, record(200_000))
        runCurrent()
        assertEquals(listOf(200_000L), backend.writes.map { it.positionMs })
        assertEquals(11L, backend.writes.single().updatedAtMs)
    }

    @Test fun inFlightSaveCannotRemoveNewerCaptureOrCompleteItsBarrierEarly() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { saveAction = { if (it.positionMs == 100_000L) gate.await() else delay(100) } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val session = writer.openSession(record().key)
        writer.submit(session, record()); runCurrent()
        writer.submit(session, record(300_000))
        var saved = false
        launch { writer.awaitSaved(); saved = true }; runCurrent()
        gate.complete(Unit); runCurrent()
        assertFalse(saved)
        assertEquals(300_000L, writer.loadSnapshot().records.single().positionMs)
        advanceTimeBy(100); runCurrent()
        assertTrue(saved)
        assertEquals(listOf(100_000L, 300_000L), backend.writes.map { it.positionMs })
    }

    @Test fun latePreviousSessionIsRejectedEvenForSameTitle() = runTest {
        val backend = Backend()
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val old = writer.openSession(record().key)
        writer.submit(old, record(400_000))
        val new = writer.openSession(record().key)
        writer.submit(new, record(80_000))
        writer.submit(old, record(500_000))
        runCurrent()
        assertEquals(80_000L, backend.writes.single().positionMs)
    }

    @Test fun failedSaveRetainsLatestProgressAndRetriesWithSafeState() = runTest {
        val backend = Backend().apply { saveAction = { error("http://private/password") } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val session = writer.openSession(record().key)
        writer.submit(session, record()); runCurrent()
        assertTrue(writer.saveFailed.value)
        writer.submit(session, record(200_000))
        assertEquals(200_000L, writer.loadSnapshot().records.single().positionMs)
        backend.saveAction = {}
        advanceTimeBy(1_000); runCurrent()
        assertFalse(writer.saveFailed.value)
        assertEquals(200_000L, backend.writes.single().positionMs)
    }

    @Test fun readOverlappingAcknowledgementCannotReturnStaleDiskSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { loadAction = { gate.await(); WatchProgressSnapshot(listOf(record(40_000))) } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        var result: WatchProgressSnapshot? = null
        launch { result = writer.loadSnapshot() }; runCurrent()
        writer.submit(writer.openSession(record().key), record(300_000)); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(300_000L, result!!.records.single().positionMs)
    }

    @Test fun newUnwatchedTitleIsIgnoredButRewindingExistingTitleToStartPersists() = runTest {
        val backend = Backend().apply { snapshot = WatchProgressSnapshot(listOf(record())) }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        writer.loadSnapshot()
        writer.submit(writer.openSession(record(id = 2).key), record(0, 2))
        writer.submit(writer.openSession(record().key), record(0))
        runCurrent()
        assertEquals(listOf(0L), backend.writes.map { it.positionMs })
        assertTrue(writer.loadSnapshot().continueWatching().isEmpty())
    }

    @Test fun leavingCallerScopeDoesNotCancelProcessWriter() = runTest {
        val backend = Backend().apply { saveAction = { delay(100) } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val screen = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        screen.launch { writer.submit(writer.openSession(record().key), record()) }; runCurrent()
        screen.cancel()
        advanceTimeBy(100); runCurrent()
        assertEquals(1, backend.writes.size)
    }

    @Test fun keysAreIndependentAndCompletedStateSurvivesOrderedDrain() = runTest {
        val backend = Backend()
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        writer.submit(writer.openSession(record().key), record(600_000).copy(completed = true))
        writer.submit(writer.openSession(record(id = 2).key), record(200_000, id = 2))
        runCurrent()
        assertTrue(backend.snapshot.recordFor(WatchProgressContentType.Movie, 1)!!.completed)
        assertEquals(listOf(2), writer.loadSnapshot().continueWatching().map { it.streamId })
    }

    @Test fun removingWhileSaveIsInFlightWinsAndRejectsLatePlayerCaptures() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { saveAction = { if (it.positionMs > 0) gate.await() } }
        val writer = WatchProgressCoordinator(backend, backgroundScope)
        val old = writer.openSession(record().key)
        writer.submit(old, record()); runCurrent()
        writer.removeFromContinueWatching(WatchProgressContentType.Movie, 1)
        writer.submit(old, record(200_000))
        assertTrue(writer.loadSnapshot().continueWatching().isEmpty())
        gate.complete(Unit); runCurrent(); writer.awaitSaved()
        assertEquals(0L,backend.snapshot.records.single().positionMs)
        val replay = writer.openSession(record().key)
        writer.submit(replay,record(60_000)); runCurrent()
        assertEquals(60_000L,writer.loadSnapshot().continueWatching().single().positionMs)
    }

    @Test fun removingShowClearsAllItsResumeEpisodesAndPreservesOtherTitlesAndCompletions() = runTest {
        val episodes = (1..3).map { record(id=it).copy(contentType=WatchProgressContentType.SeriesEpisode,seriesId=100,completed=it==3) }
        val backend=Backend().apply { snapshot=WatchProgressSnapshot(episodes+record(id=1)+record(id=4).copy(contentType=WatchProgressContentType.SeriesEpisode,seriesId=200)) }
        val writer=WatchProgressCoordinator(backend,backgroundScope)
        writer.removeFromContinueWatching(WatchProgressContentType.SeriesEpisode,1,100); runCurrent(); writer.awaitSaved()
        assertEquals(setOf("movie:1","series_episode:4"),writer.loadSnapshot().continueWatching().map { it.key }.toSet())
        assertTrue(backend.snapshot.recordFor(WatchProgressContentType.SeriesEpisode,3)!!.completed)
    }
}
