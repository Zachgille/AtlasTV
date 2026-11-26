package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
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
class SeriesWatchlistRoomStoreConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AtlasDatabase
    private lateinit var roomStore: SeriesWatchlistRoomStore
    private lateinit var watchlistFile: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(targetContext, AtlasDatabase::class.java).build()
        roomStore = SeriesWatchlistRoomStore(database.seriesWatchlistDao()) { 500L }
        watchlistFile = File(targetContext.cacheDir, "series-watchlist-${System.nanoTime()}.json")
    }

    @After
    fun tearDown() {
        database.close()
        watchlistFile.delete()
    }

    @Test
    fun saveWatchlistWritesRoomAndJsonSafely() = runBlocking {
        val store = SeriesWatchlistStore(watchlistFile, roomStore)

        store.saveWatchlist(
            listOf(
                SeriesItem(
                    name = "Fixture Series",
                    categoryName = "Fixture Drama",
                    seriesId = 3001,
                ),
            ),
            setOf(3001),
        )

        val roomJson = Gson().toJson(roomStore.loadItems())
        assertEquals(setOf(3001), store.loadWatchlistIds())
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
                  "seriesId": 3001,
                  "title": "http://provider.example/series/fake_user/fake_pass/3001",
                  "categoryName": "Fixture Drama"
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val store = SeriesWatchlistStore(watchlistFile, roomStore)

        assertEquals(setOf(3001), store.loadWatchlistIds())

        val roomItems = roomStore.loadItems()
        assertEquals(1, roomItems.size)
        assertEquals("", roomItems.first().title)
        assertEquals("Fixture Drama", roomItems.first().categoryName)
        assertSafe(Gson().toJson(roomItems))
    }

    @Test
    fun clearRemovesRoomAndJsonWatchlist() = runBlocking {
        val store = SeriesWatchlistStore(watchlistFile, roomStore)
        store.saveWatchlist(
            listOf(SeriesItem("Fixture Series", "Fixture Drama", 3001)),
            setOf(3001),
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
        assertFalse(text.contains("/series/fake_user"))
        assertFalse(text.contains("/series/REDACTED"))
    }
}
