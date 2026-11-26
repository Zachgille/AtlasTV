package com.atlas.atlastv.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PendingProgressJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun movie(id: Int = 1, position: Long = 80_000, time: Long = 10) = WatchProgressRecord(
        WatchProgressContentType.Movie, id, title = "Movie", durationMs = 600_000, positionMs = position, updatedAtMs = time)
    private class Journal : PendingProgressJournal {
        var values = emptyList<PendingProgressWrite>()
        var broken = false
        var onRead: suspend () -> Unit = {}
        override suspend fun read(): List<PendingProgressWrite> { onRead(); return values }
        override suspend fun write(values: List<PendingProgressWrite>) { check(!broken); this.values = values.toList() }
    }
    private class Backend : WatchProgressBackend {
        var values = emptyList<WatchProgressRecord>()
        var saveGate: suspend () -> Unit = {}
        val moves = mutableListOf<Long>()
        val batches = mutableListOf<List<WatchProgressRecord>>()
        override suspend fun load() = WatchProgressSnapshot(values)
        override suspend fun save(record: WatchProgressRecord) {
            saveGate()
            if ((values.firstOrNull { it.key == record.key }?.updatedAtMs ?: -1) <= record.updatedAtMs)
                values = values.filterNot { it.key == record.key } + record
        }
        override suspend fun saveStartedPlayback(record: WatchProgressRecord, startedAtMs: Long) { save(record); moves += startedAtMs }
        override suspend fun saveBatch(records: List<WatchProgressRecord>) { batches += records; records.forEach { save(it) } }
    }

    @Test fun stalledCommitStillJournalsNewKeysAndLatestRewindForRecoveryInANewCoordinator() = runTest {
        val journal = Journal(); val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { saveGate = { gate.await() } }
        val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val writer = WatchProgressCoordinator(backend, oldScope, journal = journal)
        val first = writer.openSession(movie().key)
        writer.submit(first, movie(position = 400_000), renderedPlayback = true); runCurrent()
        writer.submit(first, movie(position = 90_000), renderedPlayback = true)
        writer.submit(writer.openSession(movie(2).key), movie(2, 120_000)); runCurrent()
        writer.awaitJournaled()
        assertTrue(backend.values.isEmpty()); assertEquals(setOf(90_000L, 120_000L), journal.values.map { it.record.positionMs }.toSet())
        val started = journal.values.first { it.record.streamId == 1 }.startedAtMs!!
        oldScope.cancel(); runCurrent()
        val restored = WatchProgressCoordinator(Backend().also { next ->
            next.saveGate = {}; next.values = backend.values
        }, backgroundScope, journal = journal)
        val snapshot = restored.loadSnapshot(); runCurrent(); restored.awaitSaved()
        assertEquals(setOf(90_000L, 120_000L), snapshot.records.map { it.positionMs }.toSet())
        assertTrue(started > 0); assertTrue(journal.values.isEmpty())
    }

    @Test fun journalFailurePreventsAcknowledgementAndExplicitRetryCanRecover() = runTest {
        val journal = Journal().apply { broken = true }; val backend = Backend()
        val writer = WatchProgressCoordinator(backend, backgroundScope, 30_000, journal)
        writer.loadSnapshot()
        val edit = (writer.setMovieWatched(movie(), true) as EpisodeEditResult.Accepted).edit
        runCurrent(); assertTrue(writer.saveFailed.value); assertTrue(backend.values.isEmpty())
        assertEquals(EpisodeEditStatus.Failed, writer.editStatus(edit)); assertFalse(writer.canUndo(edit))
        journal.broken = false; writer.retryEdit(edit); runCurrent(); writer.awaitSaved()
        assertEquals(EpisodeEditStatus.Saved, writer.editStatus(edit)); assertTrue(writer.canUndo(edit))
        assertTrue(backend.values.single().completed); assertFalse(writer.saveFailed.value)
    }

    @Test fun recoveredBatchKeepsAtomicGroupingAndNeverReplacesANewerCommittedCheckpoint() = runTest {
        val one = movie(); val two = movie(2); val newer = movie(3, 200_000, 100)
        val journal = Journal().apply { values = listOf(PendingProgressWrite(one, 77), PendingProgressWrite(two, 77), PendingProgressWrite(movie(3))) }
        val backend = Backend().apply { values = listOf(newer) }
        val writer = WatchProgressCoordinator(backend, backgroundScope, journal = journal)
        writer.loadSnapshot(); runCurrent(); writer.awaitSaved()
        assertEquals(listOf(listOf(one, two)), backend.batches)
        assertEquals(newer, backend.values.first { it.streamId == 3 }); assertTrue(journal.values.isEmpty())
    }

    @Test fun recoveredPlaybackKeepsItsOriginalWatchlistCutoffAndManualEditsNeverMove() = runTest {
        val journal = Journal().apply { values = listOf(PendingProgressWrite(movie(), startedAtMs = 123)) }
        val backend = Backend(); val writer = WatchProgressCoordinator(backend, backgroundScope, journal = journal)
        writer.loadSnapshot(); runCurrent(); writer.awaitSaved()
        assertEquals(listOf(123L), backend.moves)
        writer.setMovieWatched(movie(), true); runCurrent(); writer.awaitSaved()
        assertEquals(listOf(123L), backend.moves)
    }

    @Test fun captureDuringJournalReadWinsWithoutDiscardingOtherRecoveredTitles() = runTest {
        val gate = CompletableDeferred<Unit>()
        val journal = Journal().apply { values = listOf(PendingProgressWrite(movie()), PendingProgressWrite(movie(2))); onRead = { gate.await() } }
        val backend = Backend(); val writer = WatchProgressCoordinator(backend, backgroundScope, journal = journal)
        runCurrent(); writer.submit(writer.openSession(movie().key), movie(position = 180_000, time = 20)); gate.complete(Unit)
        runCurrent(); writer.awaitSaved()
        assertEquals(180_000L, backend.values.first { it.streamId == 1 }.positionMs); assertEquals(2, backend.values.size)
    }

    @Test fun fileRoundTripSanitizesTypedRecordsAndPreservesBatchAndSessionMetadata() = runBlocking {
        val file = File(temporary.root, "queue.json")
        val journal = FilePendingProgressJournal(file)
        val episode = movie().copy(contentType = WatchProgressContentType.SeriesEpisode, seriesId = 7, title = "https://private/series/user/pass/1")
        journal.write(listOf(PendingProgressWrite(movie(), 42), PendingProgressWrite(episode, startedAtMs = 123)))
        val values = FilePendingProgressJournal(file).read()
        assertEquals(2, values.size); assertEquals(42L, values[0].batch); assertEquals(123L, values[1].startedAtMs)
        assertEquals("Untitled", values[1].record.title); assertFalse(file.readText().contains("private"))
        journal.write(emptyList()); assertTrue(FilePendingProgressJournal(file).read().isEmpty())
    }

    @Test fun failedRecoveryReadKeepsCommittedProgressReadableAndRetriesWithoutDiscardingNewCaptures() = runTest {
        var broken = true
        val journal = Journal().apply { onRead = { if (broken) throw java.io.IOException("storage unavailable") } }
        val backend = Backend().apply { values = listOf(movie()) }
        val writer = WatchProgressCoordinator(backend, backgroundScope, journal = journal)
        assertEquals(listOf(movie()), writer.loadSnapshot().records); assertTrue(writer.saveFailed.value)
        val failedRevision = writer.revision.value
        writer.loadSnapshot(); assertEquals(failedRevision, writer.revision.value)
        writer.submit(writer.openSession(movie().key), movie(position = 150_000, time = 20)); runCurrent()
        assertEquals(80_000L, backend.values.single().positionMs)
        broken = false; advanceTimeBy(1_001); runCurrent(); writer.awaitSaved()
        assertEquals(150_000L, backend.values.single().positionMs); assertFalse(writer.saveFailed.value)
    }

    @Test fun malformedJournalIsQuarantinedAndPartialPublishCannotOverwriteTheLastGoodQueue() = runBlocking {
        val file = File(temporary.root, "queue.json"); val journal = FilePendingProgressJournal(file)
        journal.write(listOf(PendingProgressWrite(movie())))
        val blocker = File(file.parentFile, "${file.name}.pending").apply { mkdir() }
        try { journal.write(listOf(PendingProgressWrite(movie(position = 150_000)))); fail("Expected write failure") }
        catch (_: java.io.IOException) { }
        assertEquals(80_000L, journal.read().single().record.positionMs)
        blocker.delete(); file.writeText("{broken")
        assertTrue(journal.read().isEmpty()); assertTrue(File(file.parentFile, "${file.name}.corrupt").exists())
    }
}
