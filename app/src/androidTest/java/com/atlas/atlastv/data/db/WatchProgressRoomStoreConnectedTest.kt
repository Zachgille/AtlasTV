package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatchProgressRoomStoreConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AtlasDatabase
    private lateinit var roomStore: WatchProgressRoomStore
    private lateinit var progressFile: File
    private lateinit var store: WatchProgressStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        roomStore = WatchProgressRoomStore(database.watchProgressDao())
        progressFile = File(context.cacheDir, "watch-progress-room-test-${System.nanoTime()}.json")
        progressFile.delete()
        store = WatchProgressStore(progressFile, roomStore)
    }

    @After
    fun tearDown() {
        progressFile.delete()
        database.close()
    }

    @Test
    fun saveRecordWritesRoomAndJsonSafely() {
        store.saveRecord(
            movieProgress(
                title = "http://provider.example/movie/fake_user/fake_pass/2001.mp4",
                categoryName = "Fixture Action",
            ),
        )

        val json = progressFile.readText(Charsets.UTF_8)
        val loaded = store.load().records.single()

        assertEquals("Untitled", loaded.title)
        assertEquals(1, kotlinx.coroutines.runBlocking { roomStore.count() })
        assertSafe(json)
        assertSafe(loaded.toString())
        assertSafe(kotlinx.coroutines.runBlocking { database.watchProgressDao().records() }.single().toString())
    }

    @Test
    fun legacyJsonFallbackSeedsRoomWhenRoomIsEmpty() {
        WatchProgressStore(progressFile).saveRecords(
            listOf(
                movieProgress(streamId = 2001, updatedAtMs = 200L),
                episodeProgress(streamId = 9001, updatedAtMs = 100L),
            ),
        )

        val snapshot = store.load()

        assertEquals(listOf(2001, 9001), snapshot.records.map { it.streamId })
        assertEquals(2, kotlinx.coroutines.runBlocking { roomStore.count() })
    }

    @Test
    fun roomFallbackRestoresProgressWhenJsonIsMissing() {
        kotlinx.coroutines.runBlocking {
            roomStore.saveRecords(
                listOf(
                    movieProgress(streamId = 2001, title = "Room Movie", updatedAtMs = 200L),
                    episodeProgress(streamId = 9001, updatedAtMs = 100L),
                ),
            )
        }

        val snapshot = store.load()

        assertEquals(listOf(2001, 9001), snapshot.records.map { it.streamId })
        assertEquals("Room Movie", snapshot.records.first().title)
        assertFalse(progressFile.exists())
    }

    @Test
    fun roomProgressWinsWhenLegacyJsonAlsoExists() {
        WatchProgressStore(progressFile).saveRecords(
            listOf(movieProgress(streamId = 2001, title = "Stale Json Movie", updatedAtMs = 100L)),
        )
        kotlinx.coroutines.runBlocking {
            roomStore.saveRecords(
                listOf(movieProgress(streamId = 2002, title = "Room Movie", updatedAtMs = 300L)),
            )
        }

        val snapshot = store.load()

        assertEquals(listOf(2002), snapshot.records.map { it.streamId })
        assertEquals("Room Movie", snapshot.records.single().title)
        assertEquals(1, kotlinx.coroutines.runBlocking { roomStore.count() })
    }

    @Test
    fun clearKeepsEmptyExportSoReopeningCannotReseedProgress() {
        store.saveRecords(listOf(movieProgress(), episodeProgress()))

        store.clear()

        assertTrue(progressFile.exists())
        assertEquals(0, kotlinx.coroutines.runBlocking { roomStore.count() })
        assertTrue(store.load().records.isEmpty())
        assertTrue(WatchProgressStore(progressFile).load().records.isEmpty())
        val reopened = WatchProgressStore(progressFile, WatchProgressRoomStore(database.watchProgressDao()))
        assertTrue(reopened.load().records.isEmpty())
        assertEquals(0, kotlinx.coroutines.runBlocking { roomStore.count() })
    }

    private fun movieProgress(
        streamId: Int = 2001,
        title: String = "Fixture Movie",
        categoryName: String? = "Fixture Action",
        updatedAtMs: Long = 100L,
    ): WatchProgressRecord =
        WatchProgressRecord(
            contentType = WatchProgressContentType.Movie,
            streamId = streamId,
            title = title,
            categoryName = categoryName,
            extension = "mp4",
            durationMs = 7_200_000L,
            positionMs = 900_000L,
            updatedAtMs = updatedAtMs,
        )

    private fun episodeProgress(
        streamId: Int = 9001,
        updatedAtMs: Long = 90L,
    ): WatchProgressRecord =
        WatchProgressRecord(
            contentType = WatchProgressContentType.SeriesEpisode,
            streamId = streamId,
            seriesId = 3001,
            title = "Fixture Series One - Pilot",
            seriesTitle = "Fixture Series One",
            episodeTitle = "Pilot",
            categoryName = "Fixture Series",
            extension = "mp4",
            durationMs = 3_600_000L,
            positionMs = 600_000L,
            updatedAtMs = updatedAtMs,
        )

    private fun assertSafe(text: String) {
        assertFalse(text.contains("provider.example", ignoreCase = true))
        assertFalse(text.contains("fake_user", ignoreCase = true))
        assertFalse(text.contains("fake_pass", ignoreCase = true))
        assertFalse(text.contains("/movie/", ignoreCase = true))
        assertFalse(text.contains("/series/", ignoreCase = true))
        assertFalse(text.contains("/live/", ignoreCase = true))
    }
}
