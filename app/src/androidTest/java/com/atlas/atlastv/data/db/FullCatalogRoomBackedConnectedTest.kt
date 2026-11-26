package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.home.HomeRowIds
import com.atlas.atlastv.ui.home.HomeShellState
import com.atlas.atlastv.ui.home.HomeShellStateHolder
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.movies.MoviesCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.search.UnifiedSearchItem
import com.atlas.atlastv.ui.search.UnifiedSearchState
import com.atlas.atlastv.ui.search.UnifiedSearchStateHolder
import com.atlas.atlastv.ui.series.SeriesCatalogState
import com.atlas.atlastv.ui.series.SeriesCatalogStateHolder
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.SeriesPreferences
import com.atlas.atlastv.ui.series.SeriesPreferencesStore
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FullCatalogRoomBackedConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AtlasDatabase
    private lateinit var repository: SafeCatalogRepository
    private lateinit var tempDir: File
    private lateinit var cacheStore: LiveTvCachedCatalogStore
    private lateinit var movieWatchlistStore: MoviesWatchlistStore
    private lateinit var seriesWatchlistStore: SeriesWatchlistStore
    private lateinit var progressStore: WatchProgressStore
    private lateinit var moviesPreferencesStore: MoviesPreferencesStore
    private lateinit var seriesPreferencesStore: SeriesPreferencesStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(targetContext, AtlasDatabase::class.java).build()
        repository = SafeCatalogRepository(database.safeCatalogDao()) { 50_000L }
        tempDir = File(targetContext.cacheDir, "full-catalog-room-${System.nanoTime()}").also { it.mkdirs() }
        cacheStore = LiveTvCachedCatalogStore(tempDir)
        File(tempDir, LiveTvCachedCatalogStore.FileName).delete()
        movieWatchlistStore = MoviesWatchlistStore(
            File(tempDir, MoviesWatchlistStore.FileName),
            MoviesWatchlistRoomStore(database.movieWatchlistDao()),
        )
        seriesWatchlistStore = SeriesWatchlistStore(
            File(tempDir, SeriesWatchlistStore.FileName),
            SeriesWatchlistRoomStore(database.seriesWatchlistDao()),
        )
        progressStore = WatchProgressStore(
            File(tempDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(database.watchProgressDao()),
        )
        moviesPreferencesStore = MoviesPreferencesStore(File(tempDir, MoviesPreferencesStore.FileName))
        seriesPreferencesStore = SeriesPreferencesStore(File(tempDir, SeriesPreferencesStore.FileName))
        seedLargeRoomCatalog()
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun moviesBrowseAndSearchUseRoomRowsBeyondOldCap() {
        val holder = moviesHolder()

        kotlinx.coroutines.runBlocking { holder.loadCachedCatalog() }

        val ready = holder.state as MoviesCatalogState.Ready
        assertEquals("room", ready.source)
        assertTrue("UI working set should stay bounded.", ready.movies.size < 120)
        assertTrue(ready.browseRows.any { row ->
            row.title == "Deep Movies" && row.items.any { it.streamId == DeepMovieId }
        })

        holder.onSearchQueryChanged("Needle Movie 151")

        val searched = holder.state as MoviesCatalogState.Ready
        assertEquals(1, searched.searchResult?.totalCount)
        assertEquals(DeepMovieId, searched.searchResult?.rows?.single()?.items?.single()?.streamId)
        assertFalse(searched.movies.any { it.title.contains("Movie 1") && it.streamId < DeepMovieId })
    }

    @Test
    fun seriesBrowseRestoreWatchlistAndSearchUseRoomRowsBeyondOldCap() {
        seriesPreferencesStore.save(
            SeriesPreferences(
                selectedCategoryId = DeepSeriesCategory,
                selectedCategoryName = DeepSeriesCategory,
                selectedSeriesId = DeepSeriesId,
                selectedSeriesTitle = "Needle Show 151",
            ),
        )
        seriesWatchlistStore.saveWatchlist(
            series = listOf(SeriesItem("Needle Show 151", DeepSeriesCategory, DeepSeriesId)),
            watchlistSeriesIds = setOf(DeepSeriesId),
        )
        val holder = seriesHolder()

        kotlinx.coroutines.runBlocking { holder.loadCachedCatalog() }

        val ready = holder.state as SeriesCatalogState.Ready
        assertEquals("room", ready.source)
        assertEquals(DeepSeriesId, ready.selectedSeriesId)
        assertEquals("series_watchlist", ready.browseRows.first().id)
        assertEquals(DeepSeriesId, ready.browseRows.first().items.single().seriesId)
        assertTrue(ready.browseRows.any { row ->
            row.title == "Deep Shows" && row.items.any { it.seriesId == DeepSeriesId }
        })

        holder.onSearchQueryChanged("Needle Show 151")

        val searched = holder.state as SeriesCatalogState.Ready
        assertEquals(1, searched.searchResult?.totalCount)
        assertEquals(DeepSeriesId, searched.searchResult?.rows?.single()?.items?.single()?.seriesId)
    }

    @Test
    fun unifiedSearchQueriesFullRoomCatalogBeyondOldCap() = runBlocking {
        val holder = UnifiedSearchStateHolder(targetContext, this, repository)

        holder.search("Needle")
        while (holder.state is UnifiedSearchState.Loading) delay(10L)

        val ready = holder.state as UnifiedSearchState.Ready
        assertEquals(2, ready.totalCount)
        assertTrue(ready.rows.any { row ->
            row.items.any { item -> item is UnifiedSearchItem.Movie && item.movie.streamId == DeepMovieId }
        })
        assertTrue(ready.rows.any { row ->
            row.items.any { item -> item is UnifiedSearchItem.Show && item.series.seriesId == DeepSeriesId }
        })
        assertTrue(ready.rows.all { it.items.size <= 18 })
    }

    @Test
    fun homeRowsUseBoundedRoomWatchlistAndContinueWatchingBeyondOldCap() {
        movieWatchlistStore.saveWatchlist(
            movies = listOf(MovieItem("Needle Movie 151", DeepMovieCategory, DeepMovieId, "mp4", "room://movie/$DeepMovieId")),
            watchlistStreamIds = setOf(DeepMovieId),
        )
        seriesWatchlistStore.saveWatchlist(
            series = listOf(SeriesItem("Needle Show 151", DeepSeriesCategory, DeepSeriesId)),
            watchlistSeriesIds = setOf(DeepSeriesId),
        )
        progressStore.saveRecord(
            WatchProgressRecord(
                contentType = WatchProgressContentType.Movie,
                streamId = DeepMovieId,
                title = "Needle Movie 151",
                categoryName = DeepMovieCategory,
                extension = "mp4",
                durationMs = 7_200_000L,
                positionMs = 900_000L,
                updatedAtMs = 10_000L,
            ),
        )
        val holder = homeHolder()

        runBlocking { holder.loadHome() }
        val ready = holder.state as HomeShellState.Ready
        val watchlist = ready.rows.first { it.id == HomeRowIds.Watchlist }
        val continueWatching = ready.rows.first { it.id == HomeRowIds.ContinueWatching }
        assertTrue(watchlist.cards.any { it.streamId == DeepMovieId })
        assertTrue(watchlist.cards.any { it.seriesId == DeepSeriesId })
        assertTrue(continueWatching.cards.any { it.streamId == DeepMovieId })
        assertTrue(ready.rows.all { it.cards.size <= 12 })
    }

    private fun moviesHolder(): MoviesCatalogStateHolder =
        MoviesCatalogStateHolder(
            context = targetContext,
            cacheStore = cacheStore,
            preferencesStore = moviesPreferencesStore,
            watchlistStore = movieWatchlistStore,
            progressStore = progressStore,
            catalogRepository = repository,
        )

    private fun seriesHolder(): SeriesCatalogStateHolder =
        SeriesCatalogStateHolder(
            context = targetContext,
            cacheStore = cacheStore,
            preferencesStore = seriesPreferencesStore,
            progressStore = progressStore,
            watchlistStore = seriesWatchlistStore,
            credentialsProvider = { null },
            catalogRepository = repository,
        )

    private fun homeHolder(): HomeShellStateHolder =
        HomeShellStateHolder(
            context = targetContext,
            cacheStore = cacheStore,
            watchlistStore = movieWatchlistStore,
            seriesWatchlistStore = seriesWatchlistStore,
            progressStore = progressStore,
            catalogRepository = repository,
        )

    private fun seedLargeRoomCatalog() {
        runBlocking {
            repository.replaceMovies(
                (1..180).map { index ->
                    val deep = index >= 151
                    MovieItem(
                        title = if (index == 151) "Needle Movie 151" else "Catalog Movie $index",
                        categoryName = if (deep) DeepMovieCategory else "General Movies",
                        streamId = MovieBaseId + index,
                        containerExtension = "mp4",
                        redactedStreamUrl = "room://movie/${MovieBaseId + index}",
                    )
                },
            )
            repository.replaceSeries(
                (1..180).map { index ->
                    val deep = index >= 151
                    SeriesItem(
                        name = if (index == 151) "Needle Show 151" else "Catalog Show $index",
                        categoryName = if (deep) DeepSeriesCategory else "General Shows",
                        seriesId = SeriesBaseId + index,
                    )
                },
            )
        }
    }

    private companion object {
        private const val MovieBaseId = 10_000
        private const val SeriesBaseId = 20_000
        private const val DeepMovieId = MovieBaseId + 151
        private const val DeepSeriesId = SeriesBaseId + 151
        private const val DeepMovieCategory = "Deep Movies"
        private const val DeepSeriesCategory = "Deep Shows"
    }
}

