package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeWatchActionsStateHolderTest {
    private val show = SeriesItem("A Show (2020)", "Shows", 7)
    private val episode = SeriesEpisodeItem("S02E02 A Show (2020)", 2, 2, 10, "mp4", "private-redacted", 600_000)
    private class Backend : WatchProgressBackend {
        var records = emptyList<WatchProgressRecord>()
        var onSave: suspend () -> Unit = {}; var onLoad: suspend () -> Unit = {}; var count = 0
        override suspend fun load(): WatchProgressSnapshot { onLoad(); return WatchProgressSnapshot(records) }
        override suspend fun save(record: WatchProgressRecord) { onSave(); count++; records = records.filterNot { it.key == record.key } + record }
    }
    @Test fun pendingRepeatedInputIsIgnoredAndUndoRestoresUnstartedWithStableAcknowledgement() = runTest {
        val gate = CompletableDeferred<Unit>(); val backend = Backend().apply { onSave = { gate.await() } }
        val writer = WatchProgressCoordinator(backend, backgroundScope); val holder = EpisodeWatchActionsStateHolder(writer, backgroundScope)
        holder.change(show, episode, true); holder.change(show, episode, false); runCurrent()
        assertTrue(holder.saving); assertNull(holder.undoEdit); assertTrue(holder.matches(7, 10))
        assertFalse(holder.canUndo(7, 10)); gate.complete(Unit); runCurrent()
        assertEquals(1, backend.count); assertEquals("Marked watched", holder.message); assertTrue(holder.canUndo(7, 10))
        holder.undo(); runCurrent(); assertEquals("Undone", holder.message); assertNull(holder.undoEdit)
        assertFalse(backend.records.single().completed); assertEquals(0L, backend.records.single().positionMs)
    }
    @Test fun failedWriteCanRetryTheExactEpisodeAndDoesNotAcceptAnotherTargetUntilSaved() = runTest {
        var broken = true
        val backend = Backend().apply { onSave = { if (broken) error("secret") } }
        val writer = WatchProgressCoordinator(backend, backgroundScope, 30_000); val holder = EpisodeWatchActionsStateHolder(writer, backgroundScope)
        holder.change(show, episode, true); runCurrent(); assertTrue(holder.failed); assertFalse(holder.saving)
        holder.change(show, episode.copy(episodeId = 11), false); assertTrue(holder.matches(7, 10))
        assertEquals("Couldn't save your change", holder.message); broken = false; holder.retry(); runCurrent()
        assertFalse(holder.failed); assertEquals("Marked watched", holder.message); assertEquals(10, backend.records.single().streamId)
    }
    @Test fun failedLookupRetriesOriginalIntentAndNeverReportsSavedBeforeTheWrite() = runTest {
        var broken = true
        val backend = Backend().apply { onLoad = { if (broken) error("private diagnostic") } }
        val holder = EpisodeWatchActionsStateHolder(WatchProgressCoordinator(backend, backgroundScope), backgroundScope)
        holder.change(show, episode, true); runCurrent(); assertTrue(holder.failed); assertNull(holder.undoEdit)
        broken = false; holder.retry(); runCurrent()
        assertTrue(backend.records.single().completed); assertEquals("Marked watched", holder.message)
    }
    @Test fun unavailableEpisodeActionsAreIgnoredAndNewerPlaybackInvalidatesUndo() = runTest {
        val backend = Backend(); val writer = WatchProgressCoordinator(backend, backgroundScope)
        val holder = EpisodeWatchActionsStateHolder(writer, backgroundScope)
        holder.change(show.copy(available = false), episode, true); runCurrent(); assertTrue(backend.records.isEmpty())
        holder.change(show, episode, true); runCurrent()
        writer.openSession(episode.manualProgressRecord(show).key)
        assertFalse(holder.canUndo(7, 10)); holder.undo(); runCurrent()
        assertEquals("Progress changed. Choose another action.", holder.message); assertTrue(backend.records.single().completed)
    }
    @Test fun manualMetadataOmitsPlaybackAddressesAndUsesCleanEpisodeName() {
        val record = episode.manualProgressRecord(show)
        assertEquals(WatchProgressContentType.SeriesEpisode, record.contentType); assertEquals(7, record.seriesId)
        assertFalse(record.title.contains("S02E02")); assertFalse(record.toString().contains("private-redacted"))
        assertEquals(0L, record.positionMs); assertFalse(record.completed)
    }
}
