package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** In-memory database and unique temporary export. Never uses personal progress. */
class ContinueWatchingBatchConnectedTest {
    private fun record(id: Int, time: Long = 10) = WatchProgressRecord(WatchProgressContentType.SeriesEpisode,id,seriesId = 5,
        title = "Sample",positionMs = 90_000,durationMs = 600_000,updatedAtMs = time)
    private fun fixture(check: suspend (AtlasDatabase, WatchProgressStore, File) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        val file = File(context.cacheDir,"continue-batch-${System.nanoTime()}.json")
        try { check(db,WatchProgressStore(file,WatchProgressRoomStore(db.watchProgressDao())),file) }
        finally { db.close(); file.delete(); File(file.parentFile,"${file.name}.pending").delete() }
    }
    @Test fun secondEpisodeWriteFailureRollsBackWholeBatchAndLeavesExportIntact() = fixture { db,store,file ->
        val original = listOf(record(1),record(2)); store.saveRecordsAsync(original); val bytes = file.readBytes()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_reset BEFORE INSERT ON watch_progress_records WHEN NEW.streamId = 2 AND NEW.positionMs = 0 BEGIN SELECT RAISE(ABORT, 'isolated failure'); END")
        try { store.saveBatchAsync(original.map { it.copy(positionMs = 0,updatedAtMs = 20) }); fail("Expected SQL failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(original.associateBy { it.key },store.loadAsync().records.associateBy { it.key }); assertArrayEquals(bytes,file.readBytes())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reset")
        store.saveBatchAsync(original.map { it.copy(positionMs = 0,updatedAtMs = 20) })
        assertTrue(store.loadAsync().records.all { it.positionMs == 0L })
    }
    @Test fun targetedBatchPreservesNewerAndUnrelatedRowsAndFileReopensSafely() = fixture { db,store,file ->
        val original = listOf(record(1,30),record(2),record(3).copy(completed = true),record(1).copy(contentType = WatchProgressContentType.Movie,seriesId = null))
        store.saveRecordsAsync(original)
        store.saveBatchAsync(listOf(record(1,20).copy(positionMs = 0),record(2,20).copy(positionMs = 0)))
        val rows = store.loadAsync().records.associateBy { it.key }
        assertEquals(original[0],rows[original[0].key]); assertEquals(0L,rows.getValue(record(2).key).positionMs)
        original.drop(2).forEach { assertEquals(it,rows[it.key]) }
        assertEquals(rows,WatchProgressStore(file,WatchProgressRoomStore(db.watchProgressDao())).loadAsync().records.associateBy { it.key })
    }
    @Test fun exportFailureDoesNotAcknowledgeBatchAndRetryThenUndoRestoreOriginals() = fixture { db,store,file ->
        val original = listOf(record(1),record(2)); store.saveRecordsAsync(original)
        file.delete(); file.mkdir(); val sentinel = File(file,"sentinel").apply { writeText("fixture") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = WatchProgressCoordinator(object : WatchProgressBackend {
            override suspend fun load() = store.loadAsync()
            override suspend fun save(record: WatchProgressRecord) = store.saveRecordAsync(record)
            override suspend fun saveBatch(records: List<WatchProgressRecord>) = store.saveBatchAsync(records)
        },scope,retryDelayMs = 30_000)
        try {
            val edit = (writer.removeFromContinueWatching(WatchProgressContentType.SeriesEpisode,1,5) as ContinueWatchingRemovalResult.Accepted).edit
            withTimeout(5_000) { writer.revision.first { writer.removalStatus(edit) == EpisodeEditStatus.Failed } }
            assertFalse(writer.canUndoRemoval(edit))
            assertTrue(WatchProgressRoomStore(db.watchProgressDao()).load().records.all { it.positionMs == 0L })
            sentinel.delete(); file.delete(); writer.retryRemoval(edit)
            withTimeout(5_000) { writer.revision.first { writer.removalStatus(edit) == EpisodeEditStatus.Saved } }
            assertTrue(writer.canUndoRemoval(edit)); assertTrue(file.isFile)
            val undone = (writer.undoRemoval(edit) as ContinueWatchingRemovalResult.Accepted).edit
            withTimeout(5_000) { writer.revision.first { writer.removalStatus(undone) == EpisodeEditStatus.Saved } }
            val reopened = WatchProgressStore(file,WatchProgressRoomStore(db.watchProgressDao())).loadAsync().records.associateBy { it.key }
            original.forEach { assertEquals(it,reopened.getValue(it.key).copy(updatedAtMs = it.updatedAtMs)) }
        } finally { scope.cancel(); sentinel.delete(); if (file.isDirectory) file.delete() }
    }

}
