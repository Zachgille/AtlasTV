package com.atlas.atlastv.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.series.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated Room/files only. Never touches the installed personal database/watchlist. */
class SeriesWatchlistGridRoomConnectedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun dir() = File(context.cacheDir, "watchlist-room-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    private fun movie(id: Int) = SeriesItem("Current Show $id", "Shows", id)
    @Test fun completeBoundedWatchlistKeepsOrphansAndShowTypeIsolationWithDeterministicIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.seriesWatchlistDao()
            val snapshots = (1..1001).map { SeriesWatchlistEntity(it, "Saved Show $it", "Shows", (it / 2).toLong()) }
            dao.insertAll(snapshots)
            db.safeCatalogDao().insertAll((1..1000).map { movie(it).toSafeCatalogEntity(it, 1) } +
                listOf(com.atlas.atlastv.ui.movies.MovieItem("Wrong Movie", "Shows", 1001, "mp4", "room://safe").toSafeCatalogEntity(0, 1)))
            assertEquals(1001, dao.count())
            val seen = mutableSetOf<Int>()
            for (start in 0..1000 step 200) for (entry in dao.page(start, 200)) assertTrue(seen.add(entry.saved.seriesId))
            assertEquals(1001, seen.size)
            val first = RoomSeriesWatchlistGridSource(dao).load(0, null)
            assertEquals(listOf(1000, 1001), first.items.take(2).map { it.seriesId })
            assertEquals("Current Show 1000", first.items[0].name); assertTrue(first.items[0].available)
            assertEquals("Saved Show 1001", first.items[1].name); assertFalse(first.items[1].available)
            assertTrue(first.items.size <= 200)
            val last = dao.window(Int.MAX_VALUE); assertEquals(1000, last.anchor); assertTrue(last.items.size <= 200)
            assertEquals(1, last.items.last().saved.seriesId)
            dao.insertAll(listOf(snapshots.first().copy(addedAtMs = 9000)))
            assertEquals(0, dao.window(1000, 1).anchor)
            dao.replaceAll(listOf(snapshots[1]))
            assertEquals(1, dao.window(1000, 1).total); assertEquals(0, dao.window(1000, 1).anchor)
            dao.clear(); assertEquals(0, dao.window(1000, 1).total)
        } finally { db.close() }
    }
    @Test fun boundedSavePreservesUnseenSnapshotsAndTimestampsAndNewShowSortsFirst() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build(); val folder = dir()
        try {
            val dao = db.seriesWatchlistDao()
            val rows = (1..513).map { movie(it).toSeriesWatchlistEntity(it.toLong()) }
            dao.insertAll(rows)
            val store = SeriesWatchlistStore(File(folder, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(dao))
            val ids = store.loadWatchlistIds(); assertEquals(513, ids.size)
            store.saveWatchlist(listOf(movie(9000)), ids - 513 + 9000)
            val saved = dao.items().associateBy { it.seriesId }
            for (old in rows.dropLast(1)) assertEquals(old, saved[old.seriesId])
            assertFalse(513 in saved); assertEquals(513, saved.size)
            assertEquals(9000, dao.page(0, 1).single().saved.seriesId)
            assertEquals(ids - 513 + 9000, SeriesWatchlistStore(File(folder, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(dao)).loadWatchlistIds())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun migrationRunsOnceAndAnEmptyRoomListNeverResurrectsStaleLegacyJson() = runBlocking {
        val folder = dir(); val file = File(folder, SeriesWatchlistStore.FileName)
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            SeriesWatchlistStore(file).saveWatchlist(listOf(movie(1)), setOf(1))
            val legacy = file.readText()
            val store = SeriesWatchlistStore(file, SeriesWatchlistRoomStore(db.seriesWatchlistDao()))
            assertEquals(setOf(1), store.loadWatchlistIds())
            assertTrue(File(folder, SeriesWatchlistStore.RoomInitializedFileName).isFile)
            store.saveWatchlist(emptyList(), emptySet())
            file.writeText(legacy)
            assertEquals(emptySet<Int>(), SeriesWatchlistStore(file, SeriesWatchlistRoomStore(db.seriesWatchlistDao())).loadWatchlistIds())
            assertEquals(0, db.seriesWatchlistDao().count())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun failedOptionalJsonMirrorDoesNotUndoCommittedRemovalAndDatabaseReopenStaysEmpty() = runBlocking {
        val folder = dir(); val file = File(folder, SeriesWatchlistStore.FileName); check(file.mkdir())
        val databasePath = File(folder, "fixture.db").absolutePath
        var db = Room.databaseBuilder(context, AtlasDatabase::class.java, databasePath).build()
        try {
            db.seriesWatchlistDao().insertAll(listOf(movie(1).toSeriesWatchlistEntity(5)))
            val store = SeriesWatchlistStore(file, SeriesWatchlistRoomStore(db.seriesWatchlistDao()))
            assertEquals(setOf(1), store.loadWatchlistIds())
            store.saveWatchlist(emptyList(), emptySet()) // JSON path is a directory; Room commit still succeeds.
            db.close(); db = Room.databaseBuilder(context, AtlasDatabase::class.java, databasePath).build()
            assertEquals(emptySet<Int>(), SeriesWatchlistStore(file, SeriesWatchlistRoomStore(db.seriesWatchlistDao())).loadWatchlistIds())
            assertEquals(0, db.seriesWatchlistDao().count())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun noRemainingShowCatalogStillLoadsARemovableSavedSnapshot() = runBlocking {
        val folder = dir(); val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.seriesWatchlistDao().insertAll(listOf(movie(7).toSeriesWatchlistEntity(5)))
            val source = RoomSeriesCatalogSource(context,
                cacheStore = com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore(folder),
                preferencesStore = SeriesPreferencesStore(File(folder, "preferences.json")),
                watchlistStore = SeriesWatchlistStore(File(folder, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(db.seriesWatchlistDao())),
                progressStore = com.atlas.atlastv.playback.WatchProgressStore(File(folder, "progress.json")),
                catalogRepository = SafeCatalogRepository(db.safeCatalogDao()), watchlistDao = db.seriesWatchlistDao())
            val loaded = source.load(null) as SeriesCatalogState.Ready
            assertEquals(setOf(7), loaded.watchlistSeriesIds)
            assertEquals("series_watchlist", loaded.browseRows.single().id)
            assertFalse(loaded.series.single().available); assertEquals(7, loaded.selectedSeriesId)
            source.saveWatchlist(loaded.series, emptySet()); assertEquals(0, db.seriesWatchlistDao().count())
            val empty = source.load(loaded) as SeriesCatalogState.Ready
            assertTrue(empty.series.isEmpty()); assertTrue(empty.browseRows.isEmpty()); assertTrue(empty.watchlistSeriesIds.isEmpty())
            assertNull(empty.selectedSeriesId)
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun savedPlaceholdersDoNotSuppressSafeLegacyCatalogBootstrap() = runBlocking {
        val folder = dir(); val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.seriesWatchlistDao().insertAll(listOf(movie(7).toSeriesWatchlistEntity(5)))
            val cache = com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore(folder)
            cache.write("""{"playableSeriesItems":[{"seriesId":7,"name":"Legacy English Show","categoryName":"Shows","containerExtension":"mp4"},{"seriesId":8,"name":"Blocked","categoryName":"FRENCH SERIES","containerExtension":"mp4"}]}""")
            val source = RoomSeriesCatalogSource(context, cacheStore = cache,
                preferencesStore = SeriesPreferencesStore(File(folder, "preferences.json")),
                watchlistStore = SeriesWatchlistStore(File(folder, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(db.seriesWatchlistDao())),
                progressStore = com.atlas.atlastv.playback.WatchProgressStore(File(folder, "progress.json")),
                catalogRepository = SafeCatalogRepository(db.safeCatalogDao()), watchlistDao = db.seriesWatchlistDao())
            val loaded = source.load(null) as SeriesCatalogState.Ready
            assertEquals(setOf(7), loaded.watchlistSeriesIds)
            assertEquals(1, db.safeCatalogDao().countForType("series"))
            assertTrue(loaded.series.single().available); assertEquals("Legacy English Show", loaded.series.single().name)
        } finally { db.close(); folder.deleteRecursively() }
    }
}
