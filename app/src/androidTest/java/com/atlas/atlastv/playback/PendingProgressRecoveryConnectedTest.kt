package com.atlas.atlastv.playback

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.series.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Two explicitly selected phases, with an external force-stop between them; cache-only data. */
class PendingProgressRecoveryConnectedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root(): File {
        val id = InstrumentationRegistry.getArguments().getString("check_id") ?: error("Explicit private fixture ID required")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return File(context.cacheDir, "pending-progress-$id").apply { mkdirs() }
    }
    private fun baseline() = listOf(
        WatchProgressRecord(WatchProgressContentType.Movie, 1, title = "Movie", durationMs = 600_000, positionMs = 50_000, updatedAtMs = 1_000),
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 22, 7, "Episode 2", seriesTitle = "Show", durationMs = 600_000,
            positionMs = 45_000, updatedAtMs = 1_000),
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 1, 99, "Other Show", durationMs = 600_000, positionMs = 60_000, updatedAtMs = 1_000),
        WatchProgressRecord(WatchProgressContentType.Movie, 99, title = "Unrelated Movie", durationMs = 600_000, positionMs = 100_000, updatedAtMs = 1_000))

    @Test fun stageUncommittedMovieAndEpisodeWhileTheDatabaseWriterIsBlocked() = runBlocking {
        val directory = root(); val database = Room.databaseBuilder(context, AtlasDatabase::class.java, File(directory, "progress.db").absolutePath).build()
        val store = WatchProgressStore(File(directory, "progress.json"), WatchProgressRoomStore(database.watchProgressDao()))
        val journal = FilePendingProgressJournal(File("${store.identity}.queue"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(0, database.watchProgressDao().count())
            store.saveRecordsAsync(baseline())
            database.movieWatchlistDao().insertAll(listOf(MovieWatchlistEntity(1, "Movie", "Movies", "mp4", 1)))
            database.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(7, "Show", "Shows", 1)))
            val gate = CompletableDeferred<Unit>()
            val writer = WatchProgressCoordinator(object : WatchProgressBackend {
                override suspend fun load() = store.loadAsync()
                override suspend fun save(record: WatchProgressRecord) { gate.await(); store.saveRecordAsync(record) }
            }, scope, journal = journal)
            writer.loadSnapshot()
            val movie = baseline()[0]; val episode = baseline()[1]
            writer.submit(writer.openSession(movie.key), movie.copy(positionMs = 130_000, updatedAtMs = 2_100), renderedPlayback = true)
            val session = writer.openSession(episode.key)
            writer.submit(session, episode.copy(positionMs = 400_000, updatedAtMs = 2_150), renderedPlayback = true)
            writer.submit(session, episode.copy(positionMs = 90_000, updatedAtMs = 2_200), renderedPlayback = true)
            withTimeout(15_000) { writer.awaitJournaled() }
            assertEquals(baseline(), store.loadAsync().records.sortedBy { baseline().indexOfFirst { old -> old.key == it.key } })
            assertEquals(setOf(130_000L, 90_000L), journal.read().map { it.record.positionMs }.toSet())
            // A deliberate re-add after starting playback must survive journal replay.
            database.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(7, "Show", "Shows", session.startedAtMs + 1)))
            File(directory, "staging-pid.txt").writeText(Process.myPid().toString())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin(); database.close()
        }
    }

    @Test fun recoverAfterForceStopRestoresBothCheckpointsAndTheIntendedEpisode() = runBlocking {
        val directory = root()
        assertNotEquals("Recovery must run in a new app process", File(directory, "staging-pid.txt").readText().toInt(), Process.myPid())
        val database = Room.databaseBuilder(context, AtlasDatabase::class.java, File(directory, "progress.db").absolutePath).build()
        try {
            val store = WatchProgressStore(File(directory, "progress.json"), WatchProgressRoomStore(database.watchProgressDao()))
            val writer = WatchProgressCoordinator.forStore(store)
            val snapshot = withTimeout(15_000) { writer.loadSnapshot().also { writer.awaitSaved() } }
            val disk = store.loadAsync()
            assertEquals(130_000L, disk.recordFor(WatchProgressContentType.Movie, 1)!!.positionMs)
            assertEquals(90_000L, disk.recordFor(WatchProgressContentType.SeriesEpisode, 22)!!.positionMs)
            for (old in baseline().drop(2)) assertEquals(old, disk.records.first { it.key == old.key })
            assertTrue(database.movieWatchlistDao().items().isEmpty())
            assertEquals(listOf(7), database.seriesWatchlistDao().items().map { it.seriesId })
            val ready = SeriesCatalogState.Ready("check", null, listOf(SeriesItem("Show", "Shows", 7)), emptyList(),
                selectedSeriesId = 7, episodeState = SeriesEpisodeState.Ready(listOf(
                    SeriesEpisodeItem("First", 1, 1, 11, "mp4", ""), SeriesEpisodeItem("Second", 2, 2, 22, "mp4", ""))),
                watchProgress = snapshot.records.filter { it.contentType == WatchProgressContentType.SeriesEpisode }.associateBy { it.streamId })
                .withValidEpisodeSelection()
            assertEquals(22, ready.selectedEpisodeId); assertEquals(2, ready.selectedSeasonNumber)
            withTimeout(5_000) { while (FilePendingProgressJournal(File("${store.identity}.queue")).read().isNotEmpty()) delay(10) }
        } finally { database.close() }
    }
}
