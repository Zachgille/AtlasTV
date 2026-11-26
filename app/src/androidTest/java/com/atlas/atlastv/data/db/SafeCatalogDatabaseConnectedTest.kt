package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeCatalogDatabaseConnectedTest {
    private lateinit var database: AtlasDatabase
    private lateinit var repository: SafeCatalogRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        repository = SafeCatalogRepository(database.safeCatalogDao()) { 123L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesAndRestoresSafeMoviesAndSeriesCatalogRows() = runBlocking {
        repository.replaceMovies(
            listOf(
                MovieItem(
                    title = "Fixture Movie",
                    categoryName = "Action",
                    streamId = 2001,
                    containerExtension = "mkv",
                    redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mkv",
                    posterImage = atlasImageRefFromRaw("http://provider.example/posters/movie.jpg"),
                ),
            ),
        )
        repository.replaceSeries(
            listOf(
                SeriesItem(
                    name = "Fixture Series",
                    categoryName = "Drama",
                    seriesId = 3001,
                    posterImage = atlasImageRefFromRaw("fixture://poster/series-one"),
                ),
            ),
        )

        val movieEntities = repository.movieEntities()
        val seriesEntities = repository.seriesEntities()
        val storedJson = Gson().toJson(movieEntities + seriesEntities)

        assertEquals(1, movieEntities.size)
        assertEquals(1, seriesEntities.size)
        assertEquals("Fixture Movie", movieEntities.first().title)
        assertEquals("Fixture Series", seriesEntities.first().title)
        assertEquals(1, database.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie))
        assertEquals(1, database.safeCatalogDao().countForType(SafeCatalogContentTypes.Series))
        assertFalse(storedJson.contains("provider.example"))
        assertFalse(storedJson.contains("fake_user"))
        assertFalse(storedJson.contains("fake_pass"))
        assertFalse(storedJson.contains("/movie/fake_user"))
        assertFalse(storedJson.contains("/series/fake_user"))
    }

    @Test
    fun replacingOneCatalogTypeDoesNotDeleteTheOtherType() = runBlocking {
        repository.replaceMovies(
            listOf(
                MovieItem(
                    title = "Fixture Movie",
                    categoryName = "Action",
                    streamId = 2001,
                    containerExtension = "mp4",
                    redactedStreamUrl = "fixture://debug_catalog/movie/2001.mp4",
                ),
            ),
        )
        repository.replaceSeries(
            listOf(
                SeriesItem(
                    name = "Fixture Series",
                    categoryName = "Drama",
                    seriesId = 3001,
                ),
            ),
        )

        repository.replaceMovies(emptyList())

        assertEquals(0, repository.movieEntities().size)
        assertEquals(1, repository.seriesEntities().size)
    }
}
