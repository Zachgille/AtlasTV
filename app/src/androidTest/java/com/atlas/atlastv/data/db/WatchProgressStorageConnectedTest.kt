package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** In-memory Room and uniquely named cache exports. No personal database or files. */
class WatchProgressStorageConnectedTest {
    private fun record(id: Int = 1, position: Long = 100_000, time: Long = 10) = WatchProgressRecord(
        WatchProgressContentType.Movie, id, title = "Test", durationMs = 600_000, positionMs = position, updatedAtMs = time,
    )
    @Test fun concurrentLegacySeedIsAtomicAndCannotReplaceExistingRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val store = WatchProgressRoomStore(database.watchProgressDao())
            val first = async { store.seedIfEmpty(listOf(record())) }
            val second = async { store.seedIfEmpty(listOf(record(2))) }
            first.await(); second.await()
            assertEquals(1, store.count())
            val saved = store.load()
            store.seedIfEmpty(listOf(record(3)))
            assertEquals(saved, store.load())
        } finally { database.close() }
    }
    @Test fun newerRewindSurvivesStaleSaveAndExportFailureCanBeRetried() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val directory = File(context.cacheDir, "progress-storage-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "progress.json")
        try {
            val room = WatchProgressRoomStore(database.watchProgressDao())
            val store = WatchProgressStore(file, room)
            store.saveRecordAsync(record(position = 300_000, time = 30))
            room.saveRecord(record(position = 400_000, time = 20))
            assertEquals(300_000L, room.load().records.single().positionMs)
            store.saveRecordAsync(record(position = 0, time = 40))
            assertEquals(0L, store.loadAsync().records.single().positionMs)
            // A destination directory makes replacing the export fail after the Room transaction.
            assertTrue(file.delete()); assertTrue(file.mkdir())
            File(file, "sentinel").writeText("kept")
            assertTrue(runCatching { store.saveRecordAsync(record(position = 200_000, time = 50)) }.isFailure)
            assertEquals(200_000L, store.loadAsync().records.single().positionMs)
            File(file, "sentinel").delete(); file.delete()
            store.saveRecordAsync(record(position = 200_000, time = 50))
            assertTrue(file.isFile)
            assertFalse(File(directory, "progress.json.pending").exists())
            store.clearAsync()
            assertTrue(store.loadAsync().records.isEmpty())
            assertTrue(WatchProgressStore(file).loadAsync().records.isEmpty())
        } finally { database.close(); directory.deleteRecursively() }
    }
}
