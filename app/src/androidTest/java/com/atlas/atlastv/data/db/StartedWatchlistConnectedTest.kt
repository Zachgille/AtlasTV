package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartedWatchlistConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(type: WatchProgressContentType, parent: Int? = null, time: Long = 20) =
        WatchProgressRecord(type, 7, parent, title = "Fixture", durationMs = 600_000,
            positionMs = 30_000, updatedAtMs = time)

    @Test fun movieAndShowMovesAreTypedAndTargetedAndManualReaddSurvivesRetriesAndReload() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val folder = File(context.cacheDir, "started-watchlist-${System.nanoTime()}").apply { mkdirs() }
        try {
            val movies = db.movieWatchlistDao(); val shows = db.seriesWatchlistDao()
            movies.insertAll(listOf(MovieWatchlistEntity(7, "Fixture", null, "mp4", 1),
                MovieWatchlistEntity(8, "Keep", null, "mp4", 1)))
            shows.insertAll(listOf(SeriesWatchlistEntity(7, "Different Show", null, 1),
                SeriesWatchlistEntity(70, "Parent", null, 1), SeriesWatchlistEntity(80, "Keep", null, 1)))
            val file = File(folder, WatchProgressStore.FileName)
            val store = WatchProgressStore(file, WatchProgressRoomStore(db.watchProgressDao()))
            store.saveRecordAsync(record(WatchProgressContentType.Movie), 10)
            assertEquals(listOf(8), movies.items().map { it.streamId })
            assertEquals(3, shows.count())
            store.saveRecordAsync(record(WatchProgressContentType.SeriesEpisode, 70), 10)
            assertEquals(listOf(7, 80), shows.items().map { it.seriesId })
            assertEquals(2, db.watchProgressDao().count())
            // These manual actions only mutate their own entry despite stale screen membership.
            movies.setSaved(MovieWatchlistEntity(7, "Fixture", null, "mp4", 11), true)
            shows.setSaved(SeriesWatchlistEntity(70, "Parent", null, 11), true)
            shows.setSaved(SeriesWatchlistEntity(80, "Keep", null, 12), false)
            store.saveRecordAsync(record(WatchProgressContentType.Movie, time = 21), 10)
            store.saveRecordAsync(record(WatchProgressContentType.SeriesEpisode, 70, 21), 10)
            assertEquals(setOf(7, 8), movies.items().map { it.streamId }.toSet())
            assertEquals(setOf(7, 70), shows.items().map { it.seriesId }.toSet())
            assertEquals(2, WatchProgressStore(file, WatchProgressRoomStore(db.watchProgressDao())).loadAsync().continueWatching().size)
            // Removing the last item must not resurrect the stale migration JSON on reload.
            shows.remove(7)
            File(folder, SeriesWatchlistStore.FileName).writeText("""{"watchlist":[{"seriesId":70,"title":"stale"}]}""")
            store.saveRecordAsync(record(WatchProgressContentType.SeriesEpisode, 70, 22), 12)
            assertTrue(SeriesWatchlistStore(File(folder, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(shows)).loadWatchlistIds().isEmpty())
            assertTrue(File(folder, MoviesWatchlistStore.RoomInitializedFileName).exists())
        } finally { db.close(); folder.deleteRecursively() }
    }

    @Test fun databaseFailureRollsBackBothSidesAndExportFailureCannotUndoProgressOrDeleteReaddedTitle() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val folder = File(context.cacheDir, "started-failure-${System.nanoTime()}").apply { mkdirs() }
        try {
            val movies = db.movieWatchlistDao()
            movies.insertAll(listOf(MovieWatchlistEntity(7, "Fixture", null, "mp4", 1)))
            val file = File(folder, WatchProgressStore.FileName)
            val store = WatchProgressStore(file, WatchProgressRoomStore(db.watchProgressDao()))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_move BEFORE DELETE ON movie_watchlist_items BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            var rejected = false
            try { store.saveRecordAsync(record(WatchProgressContentType.Movie), 10) } catch (_: Exception) { rejected = true }
            assertTrue(rejected); assertEquals(1, movies.count()); assertEquals(0, db.watchProgressDao().count())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_move")
            // Room commits atomically before an optional export failure; retry keeps a newer Add.
            val blockedExport = File(folder, "${file.name}.pending").apply { mkdir() }
            File(blockedExport, "block").writeText("fixture")
            rejected = false
            try { store.saveRecordAsync(record(WatchProgressContentType.Movie), 10) } catch (_: Exception) { rejected = true }
            assertTrue(rejected); assertEquals(0, movies.count()); assertEquals(1, db.watchProgressDao().count())
            movies.setSaved(MovieWatchlistEntity(7, "Fixture", null, "mp4", 11), true)
            blockedExport.deleteRecursively()
            store.saveRecordAsync(record(WatchProgressContentType.Movie), 10)
            assertEquals(1, movies.count()); assertEquals(30_000L, store.loadAsync().records.single().positionMs)
            // Missing parents cannot remove a Show with an overlapping episode ID.
            db.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(7, "Unrelated", null, 1)))
            store.saveRecordAsync(record(WatchProgressContentType.SeriesEpisode), 10)
            assertEquals(1, db.seriesWatchlistDao().count())
        } finally { db.close(); folder.deleteRecursively() }
    }
}
