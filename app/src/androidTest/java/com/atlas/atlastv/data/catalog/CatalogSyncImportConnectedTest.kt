package com.atlas.atlastv.data.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.ui.home.HomeShellState
import com.atlas.atlastv.ui.home.HomeShellStateHolder
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.movies.MoviesCatalogStateHolder
import com.atlas.atlastv.ui.search.UnifiedSearchState
import com.atlas.atlastv.ui.search.buildUnifiedSearchState
import com.atlas.atlastv.ui.series.SeriesCatalogState
import com.atlas.atlastv.ui.series.SeriesCatalogStateHolder
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream
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
class CatalogSyncImportConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = SafeCatalogRepository(AtlasDatabaseProvider.database(targetContext).safeCatalogDao()) { 10_000L }

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).delete()
        runBlocking {
            repository.clearCatalog()
            XtreamCatalogImporter(repository, clockMs = { 10_000L }).importMoviesAndSeries(
                vodCategories = fixtureVodCategories,
                vodStreams = fixtureVodStreams,
                seriesCategories = fixtureSeriesCategories,
                series = fixtureSeries,
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking { repository.clearCatalog() }
    }

    @Test
    fun roomImportKeepsNormalSurfacesWorkingWithoutDebugJson() {
        val homeHolder = HomeShellStateHolder(targetContext)
        val moviesHolder = MoviesCatalogStateHolder(targetContext)
        val seriesHolder = SeriesCatalogStateHolder(targetContext, credentialsProvider = { null })

        runBlocking { homeHolder.loadHome() }
        kotlinx.coroutines.runBlocking { moviesHolder.loadCachedCatalog() }
        kotlinx.coroutines.runBlocking { seriesHolder.loadCachedCatalog() }

        val home = homeHolder.state as HomeShellState.Ready
        val movies = moviesHolder.state as MoviesCatalogState.Ready
        val series = seriesHolder.state as SeriesCatalogState.Ready
        moviesHolder.onSearchQueryChanged("Imported Movie 150")
        seriesHolder.onSearchQueryChanged("Imported Series 150")
        val searchedMovies = moviesHolder.state as MoviesCatalogState.Ready
        val searchedSeries = seriesHolder.state as SeriesCatalogState.Ready
        val search = buildUnifiedSearchState(movies, series, "imported")

        assertEquals("room", home.source)
        assertEquals("room", movies.source)
        assertEquals("room", series.source)
        assertEquals(240, runBlocking { repository.movieEntities().size })
        assertEquals(220, runBlocking { repository.seriesEntities().size })
        assertTrue(movies.movies.size in 1..216)
        assertTrue(series.series.size in 1..216)
        assertTrue(movies.browseRows.isNotEmpty())
        assertTrue(series.browseRows.isNotEmpty())
        assertTrue(
            searchedMovies.searchResult?.rows.orEmpty()
                .flatMap { it.items }
                .any { it.title == "Imported Movie 150" },
        )
        assertTrue(
            searchedSeries.searchResult?.rows.orEmpty()
                .flatMap { it.items }
                .any { it.name == "Imported Series 150" },
        )
        assertTrue(home.rows.isNotEmpty())
        assertTrue(search is UnifiedSearchState.Ready)
        assertTrue((search as UnifiedSearchState.Ready).totalCount > 0)
        assertSafeJson(Gson().toJson(listOf(home, movies, series, search)))
    }

    @Test
    fun importedRoomRowsAreLargerThanUiCapsAndFiltered() = runBlocking {
        assertEquals(240, repository.movieEntities().size)
        assertEquals(220, repository.seriesEntities().size)
        assertEquals(120, repository.movieEntities(limit = 120).size)
        assertEquals(25, repository.searchMovieEntities("imported", limit = 25).size)
        assertFalse(repository.movieEntities().any { it.categoryName.contains("French", ignoreCase = true) })
        assertFalse(repository.seriesEntities().any { it.categoryName.contains("French", ignoreCase = true) })
        assertFalse(Gson().toJson(repository.movieEntities() + repository.seriesEntities()).contains("/movie/"))
        assertFalse(Gson().toJson(repository.movieEntities() + repository.seriesEntities()).contains("/series/"))
    }

    private fun assertSafeJson(json: String) {
        assertFalse(json.contains("provider.example"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
    }

    private companion object {
        val fixtureVodCategories = listOf(
            XtreamCategory("movies-us", "US Movies", null),
            XtreamCategory("movies-fr", "French Movies", null),
        )
        val fixtureSeriesCategories = listOf(
            XtreamCategory("series-us", "US Series", null),
            XtreamCategory("series-fr", "French Series", null),
        )
        val fixtureVodStreams = (1..260).map { index ->
            XtreamVodStream(
                num = index,
                name = if (index == 1) "http://provider.example/movie/fake_user/fake_pass/$index.mp4" else "Imported Movie $index",
                streamType = "movie",
                streamId = 40_000 + index,
                streamIcon = null,
                rating = null,
                rating5Based = null,
                added = "170000$index",
                categoryId = if (index <= 240) "movies-us" else "movies-fr",
                containerExtension = "mp4",
            )
        }
        val fixtureSeries = (1..240).map { index ->
            XtreamSeries(
                num = index,
                name = "Imported Series $index",
                seriesId = 50_000 + index,
                cover = null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                releaseDate = null,
                lastModified = "170500$index",
                rating = null,
                rating5Based = null,
                categoryId = if (index <= 220) "series-us" else "series-fr",
            )
        }
    }
}

