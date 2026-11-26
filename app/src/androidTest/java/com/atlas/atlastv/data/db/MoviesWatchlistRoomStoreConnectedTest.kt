package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MoviesWatchlistRoomStoreConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AtlasDatabase
    private lateinit var roomStore: MoviesWatchlistRoomStore
    private lateinit var watchlistFile: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(targetContext, AtlasDatabase::class.java).build()
        roomStore = MoviesWatchlistRoomStore(database.movieWatchlistDao()) { 500L }
        watchlistFile = File(targetContext.cacheDir, "movies-watchlist-${System.nanoTime()}.json")
    }

    @After
    fun tearDown() {
        database.close()
        watchlistFile.delete()
    }

    @Test
    fun saveWatchlistWritesRoomAndJsonSafely() = runBlocking {
        val store = MoviesWatchlistStore(watchlistFile, roomStore)

        store.saveWatchlist(
            listOf(
                MovieItem(
                    title = "Fixture Movie",
                    categoryName = "Fixture Action",
                    streamId = 2001,
                    containerExtension = "mkv",
                    redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mkv",
                ),
            ),
            setOf(2001),
        )

        val roomJson = Gson().toJson(roomStore.loadItems())
        assertEquals(setOf(2001), store.loadWatchlistIds())
        assertEquals(1, roomStore.count())
        assertTrue(watchlistFile.exists())
        assertSafe(roomJson)
        assertSafe(watchlistFile.readText(Charsets.UTF_8))
    }

    @Test
    fun legacyJsonFallbackSeedsRoomWhenRoomIsEmpty() = runBlocking {
        watchlistFile.writeText(
            """
            {
              "watchlist": [
                {
                  "streamId": 2001,
                  "title": "http://provider.example/movie/fake_user/fake_pass/2001.mkv",
                  "categoryName": "Fixture Action",
                  "containerExtension": "mkv"
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val store = MoviesWatchlistStore(watchlistFile, roomStore)

        assertEquals(setOf(2001), store.loadWatchlistIds())

        val roomItems = roomStore.loadItems()
        assertEquals(1, roomItems.size)
        assertEquals("", roomItems.first().title)
        assertEquals("Fixture Action", roomItems.first().categoryName)
        assertSafe(Gson().toJson(roomItems))
    }

    @Test
    fun clearRemovesRoomAndJsonWatchlist() = runBlocking {
        val store = MoviesWatchlistStore(watchlistFile, roomStore)
        store.saveWatchlist(
            listOf(MovieItem("Fixture Movie", "Fixture Action", 2001, "mp4", "fixture://debug_catalog/movie/2001.mp4")),
            setOf(2001),
        )

        store.clear()

        assertFalse(watchlistFile.exists())
        assertEquals(0, roomStore.count())
        assertEquals(emptySet<Int>(), store.loadWatchlistIds())
    }

    private fun assertSafe(text: String) {
        assertFalse(text.contains("provider.example"))
        assertFalse(text.contains("fake_user"))
        assertFalse(text.contains("fake_pass"))
        assertFalse(text.contains("/movie/fake_user"))
        assertFalse(text.contains("/movie/REDACTED"))
    }
}
