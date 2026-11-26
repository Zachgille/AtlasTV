package com.atlas.atlastv.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.mylist.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.series.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** In-memory SQL and disposable cache files, never the personal database. */
class MyListRoomConnectedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun movie(id: Int) = MovieItem("Current Movie $id", "Movies", id, "mp4", "room://safe")
    private fun show(id: Int) = SeriesItem("Current Show $id", "Shows", id)
    @Test fun simultaneousFirstFilterLoadsShareOneInitialization() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        try {
            val source = RoomMyListSource(db.myListDao()) { calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val first = async(Dispatchers.IO) { source.load(MyListFilter.All, 0, null) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val second = async(Dispatchers.IO) { source.load(MyListFilter.Shows, 0, null) }
            delay(100); release.countDown()
            assertEquals(0, first.await().total); assertEquals(0, second.await().total); assertEquals(1, calls.get())
        } finally { release.countDown(); db.close() }
    }
    @Test fun otherContentTypeCannotSupplyArtworkOrAvailabilityToAnOrphan() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.movieWatchlistDao().insertAll(listOf(MovieWatchlistEntity(1, "Unknown title", "FRENCH MOVIES", "mp4", 1)))
            db.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(2, "Unknown title", "SWEDISH SERIES", 1)))
            db.safeCatalogDao().insertAll(listOf(show(1).toSafeCatalogEntity(0, 1), movie(2).toSafeCatalogEntity(0, 1)))
            val items = db.myListDao().page("", 0, 20).map { it.toItem() }
            assertFalse((items[0] as MyListItem.Movie).item.available); assertFalse((items[1] as MyListItem.Show).item.available)
            assertFalse(items.any { it.title == "Unknown title" || it.title.startsWith("Current") })
        } finally { db.close() }
    }
    @Test fun combinedPagingIsCompleteTypedStableBoundedAndKeepsOrphans() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.movieWatchlistDao().insertAll((1..1001).map { MovieWatchlistEntity(it, "Saved Movie $it", "Movies", "mp4", it.toLong()) })
            db.seriesWatchlistDao().insertAll((1..1001).map { SeriesWatchlistEntity(it, "Saved Show $it", "Shows", it.toLong()) })
            db.safeCatalogDao().insertAll((1..1000).flatMap { listOf(movie(it).toSafeCatalogEntity(it, 1), show(it).toSafeCatalogEntity(it, 1)) })
            val dao = db.myListDao(); assertEquals(2002, dao.count()); assertEquals(1001, dao.count("movie")); assertEquals(1001, dao.count("series"))
            val keys = mutableSetOf<MyListKey>()
            for (start in 0 until 2002 step 200) for (row in dao.page("", start, 200)) assertTrue(keys.add(row.toItem().key))
            assertEquals(2002, keys.size)
            val first = dao.window("", 0, null).items.map { it.toItem() }
            assertEquals(listOf(MyListKey(MyListType.Movie, 1001), MyListKey(MyListType.Show, 1001)), first.take(2).map { it.key })
            assertFalse((first[0] as MyListItem.Movie).item.available); assertFalse((first[1] as MyListItem.Show).item.available)
            assertEquals("Current Movie 1000", first[2].title); assertEquals("Current Show 1000", first[3].title)
            for (filter in MyListFilter.entries) {
                val last = dao.window(filter.storageKey, Int.MAX_VALUE, null)
                assertEquals(last.total - 1, last.anchor); assertTrue(last.items.size <= 200)
                if (filter != MyListFilter.All) assertTrue(last.items.all { it.saved.type == filter.storageKey })
            }
            assertEquals(0, dao.offset("", "movie", 1001)); assertEquals(1, dao.offset("", "series", 1001))
            assertNull(dao.offset("movie", "series", 1001))
            db.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(1, "Moved Show", "Shows", 9000)))
            assertEquals(0, dao.window("", 1999, MyListKey(MyListType.Show, 1)).anchor)
            assertEquals(1, dao.window("", 1999, MyListKey(MyListType.Movie, 1001)).anchor)
            db.movieWatchlistDao().clear(); assertEquals(1001, dao.count()); assertEquals(0, dao.count("movie"))
            db.seriesWatchlistDao().clear(); val empty = dao.window("", 1000, MyListKey(MyListType.Show, 1))
            assertEquals(0, empty.total); assertTrue(empty.items.isEmpty())
        } finally { db.close() }
    }
    @Test fun removingOneContentTypePreservesSameIdInTheOtherTypeAndItsSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val folder = File(context.cacheDir, "my-list-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            val snapshot = SeriesWatchlistEntity(7, "Saved Show 7", "Shows", 123)
            db.seriesWatchlistDao().insertAll(listOf(snapshot)); db.movieWatchlistDao().insertAll(listOf(movie(7).toMovieWatchlistEntity(456)))
            val store = MoviesWatchlistStore(File(folder, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(db.movieWatchlistDao()))
            assertEquals(setOf(7), store.loadWatchlistIds()); store.saveWatchlist(emptyList(), emptySet())
            assertEquals(listOf(snapshot), db.seriesWatchlistDao().items())
            assertEquals(MyListKey(MyListType.Show, 7), db.myListDao().page("", 0, 1).single().toItem().key)
            assertEquals(1, db.myListDao().count())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun firstCombinedLoadMigratesBothLegacyListsOnlyOnceAndCannotResurrectEmptyRoom() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val folder = File(context.cacheDir, "my-list-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            val mf = File(folder, MoviesWatchlistStore.FileName); val sf = File(folder, SeriesWatchlistStore.FileName)
            MoviesWatchlistStore(mf).saveWatchlist(listOf(movie(7)), setOf(7)); SeriesWatchlistStore(sf).saveWatchlist(listOf(show(7)), setOf(7))
            val oldMovie = mf.readText(); val oldShow = sf.readText(); var attempts = 0
            val source = RoomMyListSource(db.myListDao()) {
                attempts++
                if (attempts == 1) error("fixture init failure")
                MoviesWatchlistStore(mf, MoviesWatchlistRoomStore(db.movieWatchlistDao())).loadWatchlistIds()
                SeriesWatchlistStore(sf, SeriesWatchlistRoomStore(db.seriesWatchlistDao())).loadWatchlistIds()
            }
            assertTrue(runCatching { source.load(MyListFilter.All, 0, null) }.isFailure)
            assertEquals(2, source.load(MyListFilter.All, 0, null).total)
            source.load(MyListFilter.Shows, 0, null); assertEquals(2, attempts)
            MoviesWatchlistStore(mf, MoviesWatchlistRoomStore(db.movieWatchlistDao())).saveWatchlist(emptyList(), emptySet())
            SeriesWatchlistStore(sf, SeriesWatchlistRoomStore(db.seriesWatchlistDao())).saveWatchlist(emptyList(), emptySet())
            mf.writeText(oldMovie); sf.writeText(oldShow)
            val reopened = RoomMyListSource(db.myListDao()) {
                MoviesWatchlistStore(mf, MoviesWatchlistRoomStore(db.movieWatchlistDao())).loadWatchlistIds()
                SeriesWatchlistStore(sf, SeriesWatchlistRoomStore(db.seriesWatchlistDao())).loadWatchlistIds()
            }
            assertEquals(0, reopened.load(MyListFilter.All, 0, null).total)
        } finally { db.close(); folder.deleteRecursively() }
    }
}
