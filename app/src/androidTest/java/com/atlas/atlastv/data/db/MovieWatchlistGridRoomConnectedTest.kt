package com.atlas.atlastv.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.movies.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated Room/files only. Never touches the installed personal database/watchlist. */
class MovieWatchlistGridRoomConnectedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun dir() = File(context.cacheDir, "watchlist-room-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    private fun movie(id: Int) = MovieItem("Current Movie $id", "Movies", id, "mp4", "room://safe")
    @Test fun completeBoundedWatchlistKeepsOrphansAndMovieTypeIsolationWithDeterministicIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.movieWatchlistDao()
            val snapshots = (1..1001).map { MovieWatchlistEntity(it, "Saved Movie $it", "Movies", "mp4", (it / 2).toLong()) }
            dao.insertAll(snapshots)
            db.safeCatalogDao().insertAll((1..1000).map { movie(it).toSafeCatalogEntity(it, 1) } +
                listOf(com.atlas.atlastv.ui.series.SeriesItem("Wrong Show", "Movies", 1001).toSafeCatalogEntity(0, 1)))
            assertEquals(1001, dao.count())
            val seen = mutableSetOf<Int>()
            for (start in 0..1000 step 200) for (entry in dao.page(start, 200)) assertTrue(seen.add(entry.saved.streamId))
            assertEquals(1001, seen.size)
            val first = RoomMovieWatchlistGridSource(dao).load(0, null)
            assertEquals(listOf(1000, 1001), first.items.take(2).map { it.streamId })
            assertEquals("Current Movie 1000", first.items[0].title); assertTrue(first.items[0].available)
            assertEquals("Saved Movie 1001", first.items[1].title); assertFalse(first.items[1].available)
            assertTrue(first.items.size <= 200)
            val last = dao.window(Int.MAX_VALUE); assertEquals(1000, last.anchor); assertTrue(last.items.size <= 200)
            assertEquals(1, last.items.last().saved.streamId)
            dao.insertAll(listOf(snapshots.first().copy(addedAtMs = 9000)))
            assertEquals(0, dao.window(1000, 1).anchor)
            dao.replaceAll(listOf(snapshots[1]))
            assertEquals(1, dao.window(1000, 1).total); assertEquals(0, dao.window(1000, 1).anchor)
            dao.clear(); assertEquals(0, dao.window(1000, 1).total)
        } finally { db.close() }
    }
    @Test fun boundedSavePreservesUnseenSnapshotsAndTimestampsAndNewMovieSortsFirst() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build(); val folder = dir()
        try {
            val dao = db.movieWatchlistDao()
            val rows = (1..513).map { movie(it).toMovieWatchlistEntity(it.toLong()) }
            dao.insertAll(rows)
            val store = MoviesWatchlistStore(File(folder, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(dao))
            val ids = store.loadWatchlistIds(); assertEquals(513, ids.size)
            store.saveWatchlist(listOf(movie(9000)), ids - 513 + 9000)
            val saved = dao.items().associateBy { it.streamId }
            for (old in rows.dropLast(1)) assertEquals(old, saved[old.streamId])
            assertFalse(513 in saved); assertEquals(513, saved.size)
            assertEquals(9000, dao.page(0, 1).single().saved.streamId)
            assertEquals(ids - 513 + 9000, MoviesWatchlistStore(File(folder, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(dao)).loadWatchlistIds())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun migrationRunsOnceAndAnEmptyRoomListNeverResurrectsStaleLegacyJson() = runBlocking {
        val folder = dir(); val file = File(folder, MoviesWatchlistStore.FileName)
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            MoviesWatchlistStore(file).saveWatchlist(listOf(movie(1)), setOf(1))
            val legacy = file.readText()
            val store = MoviesWatchlistStore(file, MoviesWatchlistRoomStore(db.movieWatchlistDao()))
            assertEquals(setOf(1), store.loadWatchlistIds())
            assertTrue(File(folder, MoviesWatchlistStore.RoomInitializedFileName).isFile)
            store.saveWatchlist(emptyList(), emptySet())
            file.writeText(legacy)
            assertEquals(emptySet<Int>(), MoviesWatchlistStore(file, MoviesWatchlistRoomStore(db.movieWatchlistDao())).loadWatchlistIds())
            assertEquals(0, db.movieWatchlistDao().count())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun failedOptionalJsonMirrorDoesNotUndoCommittedRemovalAndDatabaseReopenStaysEmpty() = runBlocking {
        val folder = dir(); val file = File(folder, MoviesWatchlistStore.FileName); check(file.mkdir())
        val databasePath = File(folder, "fixture.db").absolutePath
        var db = Room.databaseBuilder(context, AtlasDatabase::class.java, databasePath).build()
        try {
            db.movieWatchlistDao().insertAll(listOf(movie(1).toMovieWatchlistEntity(5)))
            val store = MoviesWatchlistStore(file, MoviesWatchlistRoomStore(db.movieWatchlistDao()))
            assertEquals(setOf(1), store.loadWatchlistIds())
            store.saveWatchlist(emptyList(), emptySet()) // JSON path is a directory; Room commit still succeeds.
            db.close(); db = Room.databaseBuilder(context, AtlasDatabase::class.java, databasePath).build()
            assertEquals(emptySet<Int>(), MoviesWatchlistStore(file, MoviesWatchlistRoomStore(db.movieWatchlistDao())).loadWatchlistIds())
            assertEquals(0, db.movieWatchlistDao().count())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun noRemainingMovieCatalogStillLoadsARemovableSavedSnapshot() = runBlocking {
        val folder = dir(); val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.movieWatchlistDao().insertAll(listOf(movie(7).toMovieWatchlistEntity(5)))
            val source = RoomMoviesCatalogSource(context,
                cacheStore = com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore(folder),
                preferencesStore = MoviesPreferencesStore(File(folder, "preferences.json")),
                watchlistStore = MoviesWatchlistStore(File(folder, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(db.movieWatchlistDao())),
                progressStore = com.atlas.atlastv.playback.WatchProgressStore(File(folder, "progress.json")),
                catalogRepository = SafeCatalogRepository(db.safeCatalogDao()), watchlistDao = db.movieWatchlistDao())
            val loaded = source.load(null) as MoviesCatalogState.Ready
            assertEquals(setOf(7), loaded.watchlistStreamIds)
            assertEquals("movies_watchlist", loaded.browseRows.single().id)
            assertFalse(loaded.movies.single().available); assertEquals(7, loaded.selectedMovieStreamId)
            source.saveWatchlist(loaded.movies, emptySet()); assertEquals(0, db.movieWatchlistDao().count())
            val empty = source.load(loaded) as MoviesCatalogState.Ready
            assertTrue(empty.movies.isEmpty()); assertTrue(empty.browseRows.isEmpty()); assertTrue(empty.watchlistStreamIds.isEmpty())
            assertNull(empty.selectedMovieStreamId)
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun savedPlaceholdersDoNotSuppressSafeLegacyCatalogBootstrap() = runBlocking {
        val folder = dir(); val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.movieWatchlistDao().insertAll(listOf(movie(7).toMovieWatchlistEntity(5)))
            val cache = com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore(folder)
            cache.write("""{"playableVodItems":[{"streamId":7,"title":"Legacy English Movie","categoryName":"Movies","containerExtension":"mp4"},{"streamId":8,"title":"Blocked","categoryName":"FRENCH MOVIES","containerExtension":"mp4"}]}""")
            val source = RoomMoviesCatalogSource(context, cacheStore = cache,
                preferencesStore = MoviesPreferencesStore(File(folder, "preferences.json")),
                watchlistStore = MoviesWatchlistStore(File(folder, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(db.movieWatchlistDao())),
                progressStore = com.atlas.atlastv.playback.WatchProgressStore(File(folder, "progress.json")),
                catalogRepository = SafeCatalogRepository(db.safeCatalogDao()), watchlistDao = db.movieWatchlistDao())
            val loaded = source.load(null) as MoviesCatalogState.Ready
            assertEquals(setOf(7), loaded.watchlistStreamIds)
            assertEquals(1, db.safeCatalogDao().countForType("movie"))
            assertTrue(loaded.movies.single().available); assertEquals("Legacy English Movie", loaded.movies.single().title)
        } finally { db.close(); folder.deleteRecursively() }
    }
}
