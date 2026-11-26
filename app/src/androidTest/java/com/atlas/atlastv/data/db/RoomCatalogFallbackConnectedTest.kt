package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.home.HomeShellState
import com.atlas.atlastv.ui.home.HomeShellStateHolder
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.movies.MovieCategoryIds
import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.movies.MoviesCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesCatalogState
import com.atlas.atlastv.ui.series.SeriesCatalogStateHolder
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
class RoomCatalogFallbackConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AtlasDatabase
    private lateinit var repository: SafeCatalogRepository
    private lateinit var tempDir: File
    private lateinit var cacheStore: LiveTvCachedCatalogStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(targetContext, AtlasDatabase::class.java).build()
        repository = SafeCatalogRepository(database.safeCatalogDao()) { 1_000L }
        tempDir = File(targetContext.cacheDir, "room-catalog-fallback-${System.nanoTime()}").also { it.mkdirs() }
        cacheStore = LiveTvCachedCatalogStore(tempDir)
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun moviesJsonLoadSeedsRoomAndRoomStateSurvivesJsonDeletion() {
        cacheStore.write(fixtureCatalogJson)
        val firstHolder = moviesHolder()

        kotlinx.coroutines.runBlocking { firstHolder.loadCachedCatalog() }

        val cachedState = firstHolder.state as MoviesCatalogState.Ready
        assertEquals("room", cachedState.source)
        assertEquals(2, cachedState.movies.size)
        assertEquals(2, runBlocking { database.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie) })

        deleteJsonCache()
        val fallbackHolder = moviesHolder()
        kotlinx.coroutines.runBlocking { fallbackHolder.loadCachedCatalog() }

        val fallbackState = fallbackHolder.state as MoviesCatalogState.Ready
        assertEquals("room", fallbackState.source)
        assertEquals(2, fallbackState.movies.size)
        assertEquals(MovieCategoryIds.All, fallbackState.selectedCategoryId)
        assertEquals("Fixture Movie One", fallbackState.movies.first().title)
        assertSafeJson(Gson().toJson(fallbackState))
    }

    @Test
    fun seriesJsonLoadSeedsRoomAndRoomStateSurvivesJsonDeletion() {
        cacheStore.write(fixtureCatalogJson)
        val firstHolder = seriesHolder()

        kotlinx.coroutines.runBlocking { firstHolder.loadCachedCatalog() }

        val cachedState = firstHolder.state as SeriesCatalogState.Ready
        assertEquals("room", cachedState.source)
        assertEquals(2, cachedState.series.size)
        assertEquals(2, runBlocking { database.safeCatalogDao().countForType(SafeCatalogContentTypes.Series) })

        deleteJsonCache()
        val fallbackHolder = seriesHolder()
        kotlinx.coroutines.runBlocking { fallbackHolder.loadCachedCatalog() }

        val fallbackState = fallbackHolder.state as SeriesCatalogState.Ready
        assertEquals("room", fallbackState.source)
        assertEquals(2, fallbackState.series.size)
        assertEquals("Fixture Series One", fallbackState.series.first().name)
        assertSafeJson(Gson().toJson(fallbackState))
    }

    @Test
    fun homeJsonLoadSeedsRoomAndHomeUsesRoomRows() {
        cacheStore.write(fixtureCatalogJson)
        val firstHolder = homeHolder()

        runBlocking { firstHolder.loadHome() }
        val cachedState = firstHolder.state as HomeShellState.Ready
        assertEquals("room", cachedState.source)
        assertTrue(cachedState.rows.isNotEmpty())

        deleteJsonCache()
        val fallbackHolder = homeHolder()
        runBlocking { fallbackHolder.loadHome() }
        val fallbackState = fallbackHolder.state as HomeShellState.Ready
        assertEquals("room", fallbackState.source)
        assertTrue(fallbackState.rows.isNotEmpty())
        assertSafeJson(Gson().toJson(fallbackState))
    }

    private fun moviesHolder(): MoviesCatalogStateHolder =
        MoviesCatalogStateHolder(
            context = targetContext,
            cacheStore = cacheStore,
            preferencesStore = MoviesPreferencesStore(File(tempDir, "movies-preferences.json")),
            watchlistStore = MoviesWatchlistStore(File(tempDir, "movies-watchlist.json")),
            progressStore = WatchProgressStore(File(tempDir, "watch-progress.json")),
            catalogRepository = repository,
        )

    private fun seriesHolder(): SeriesCatalogStateHolder =
        SeriesCatalogStateHolder(
            context = targetContext,
            cacheStore = cacheStore,
            progressStore = WatchProgressStore(File(tempDir, "watch-progress.json")),
            credentialsProvider = { null },
            catalogRepository = repository,
        )

    private fun homeHolder(): HomeShellStateHolder =
        HomeShellStateHolder(
            context = targetContext,
            cacheStore = cacheStore,
            watchlistStore = MoviesWatchlistStore(File(tempDir, "movies-watchlist.json")),
            progressStore = WatchProgressStore(File(tempDir, "watch-progress.json")),
            catalogRepository = repository,
        )

    private fun deleteJsonCache() {
        File(tempDir, LiveTvCachedCatalogStore.FileName).delete()
    }

    private fun assertSafeJson(json: String) {
        assertFalse(json.contains("provider.example"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/fake_user"))
        assertFalse(json.contains("/series/fake_user"))
    }

    private val fixtureCatalogJson = """
        {
          "catalogSource": "fixture",
          "playableVodItems": [
            {
              "streamId": 2001,
              "title": "Fixture Movie One",
              "categoryName": "Fixture Action",
              "containerExtension": "mp4",
              "redactedUrl": "fixture://debug_catalog/movie/2001.mp4",
              "posterImage": "http://provider.example/posters/movie-one.jpg"
            },
            {
              "streamId": 2002,
              "title": "Fixture Movie Two",
              "categoryName": "Fixture Drama",
              "containerExtension": "mkv",
              "redactedUrl": "fixture://debug_catalog/movie/2002.mkv",
              "posterImage": "fixture://poster/movie-two"
            }
          ],
          "playableSeriesItems": [
            {
              "seriesId": 3001,
              "name": "Fixture Series One",
              "categoryName": "Fixture Series",
              "posterImage": "http://provider.example/posters/series-one.jpg"
            },
            {
              "seriesId": 3002,
              "name": "Fixture Series Two",
              "categoryName": "Fixture Drama",
              "posterImage": "fixture://poster/series-two"
            }
          ]
        }
    """.trimIndent()
}

