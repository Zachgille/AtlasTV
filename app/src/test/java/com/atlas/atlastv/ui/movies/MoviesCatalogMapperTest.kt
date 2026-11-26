package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviesCatalogMapperTest {
    private val mapper = MoviesCatalogMapper()
    private val credentials = XtreamCredentials("http://example.test", "fake_user", "fake_pass")

    @Test
    fun mapsCachedVodItemsToSafeCappedMovieState() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials, maxItems = 2)

        assertEquals("fixture", state.source)
        assertEquals(2, state.movies.size)
        assertEquals("All", state.categories.first().name)
        assertEquals(2, state.categories.first { it.id == MovieCategoryIds.All }.itemCount)
        assertEquals("Fixture Movie One", state.movies.first().title)
        assertEquals("Fixture Action", state.movies.first().categoryName)
        assertEquals("mp4", state.movies.first().containerExtension)
        assertEquals(AtlasImageState.SafeImage, state.movies.first().posterImage.state)
        assertFalse(Gson().toJson(state.movies.first()).contains("example.test"))
        assertFalse(Gson().toJson(state.movies.first()).contains("fake_user"))
        assertFalse(Gson().toJson(state.movies.first()).contains("fake_pass"))
    }

    @Test
    fun categoryFilteringAndSearchUseCappedSafeList() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)

        assertEquals(listOf("Fixture Movie One", "Fixture Movie Three"), state.copy(selectedCategoryId = "Fixture Action", sortMode = MoviesSortMode.Title).moviesForCategory().map { it.title })
        assertEquals(listOf("Fixture Movie Two"), state.copy(searchQuery = "drama two", sortMode = MoviesSortMode.Title).visibleMovies().map { it.title })
    }

    @Test
    fun selectedDetailsUseRedactedUrlOnly() {
        val details = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(selectedMovieStreamId = 2002)
            .selectedMovieDetails()!!

        assertEquals("Fixture Movie Two", details.title)
        assertEquals("Fixture Drama", details.categoryName)
        assertEquals(2002, details.streamId)
        assertEquals("mp4", details.containerExtension)
        assertEquals("fixture://debug_catalog/movie/2002.mp4", details.redactedStreamUrl)
        assertFalse(Gson().toJson(details).contains("fake_user"))
        assertFalse(Gson().toJson(details).contains("fake_pass"))
    }

    @Test
    fun selectedDetailsShowMovieProgress() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(
                selectedMovieStreamId = 2002,
                watchProgress = mapOf(
                    2002 to WatchProgressRecord(
                        contentType = WatchProgressContentType.Movie,
                        streamId = 2002,
                        title = "Fixture Movie Two",
                        categoryName = "Fixture Drama",
                        extension = "mp4",
                        durationMs = 7_200_000L,
                        positionMs = 900_000L,
                        updatedAtMs = 20L,
                    ),
                ),
            )

        val details = state.selectedMovieDetails()!!

        assertEquals("Resume at 15:00 (12%)", details.resumeLabel)
        assertEquals(12, details.progressPercent)
        assertEquals("Press Select to resume", details.playbackHint)
    }

    @Test
    fun normalBrowseDetailsResolveSelectedMovieOutsideOldVisibleScope() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(
                selectedCategoryId = "Fixture Drama",
                searchQuery = "two",
                selectedMovieStreamId = 2003,
                watchlistStreamIds = setOf(2003),
            )

        val details = state.selectedNormalBrowseMovieDetails()!!

        assertEquals("Fixture Movie Three", details.title)
        assertEquals("Fixture Action", details.categoryName)
        assertEquals(3, details.selectedIndex)
        assertEquals(3, details.visibleCount)
        assertEquals(true, details.isWatchlisted)
        assertFalse(Gson().toJson(details).contains("fake_user"))
        assertFalse(Gson().toJson(details).contains("fake_pass"))
    }

    @Test
    fun rememberedMovieOutsideCurrentRowsBecomesSelectedDetail() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials, maxItems = 1)
        val selected = MovieItem(
            title = "Exact Search Movie",
            categoryName = "Search Results",
            streamId = 2999,
            containerExtension = "mp4",
            redactedStreamUrl = "room://safe_catalog/vod/2999.mp4",
        )

        val next = state.withRememberedMovie(selected)
        val details = next.selectedNormalBrowseMovieDetails()!!

        assertEquals(2999, next.selectedMovieStreamId)
        assertEquals("Exact Search Movie", details.title)
        assertEquals(2, next.movies.size)
        assertFalse(Gson().toJson(next).contains("fake_user"))
        assertFalse(Gson().toJson(next).contains("fake_pass"))
    }

    @Test
    fun normalBrowseDetailsExposeResumeActionSafely() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(
                selectedMovieStreamId = 2001,
                watchProgress = mapOf(
                    2001 to WatchProgressRecord(
                        contentType = WatchProgressContentType.Movie,
                        streamId = 2001,
                        title = "Fixture Movie One",
                        categoryName = "Fixture Action",
                        extension = "mp4",
                        durationMs = 3_600_000L,
                        positionMs = 600_000L,
                        updatedAtMs = 20L,
                    ),
                ),
            )

        val details = state.selectedNormalBrowseMovieDetails()!!

        assertEquals("Resume at 10:00 (16%)", details.resumeLabel)
        assertEquals(16, details.progressPercent)
        assertEquals("Press Select to resume", details.playbackHint)
        assertFalse(Gson().toJson(details).contains("example.test"))
    }

    @Test
    fun categoryPreviewExcludesSelectedAndUnrelatedMovies() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(selectedMovieStreamId = 2001)

        val related = state.categoryMoviesFor(state.movies.single { it.streamId == 2001 })

        assertEquals(listOf("Fixture Movie Three"), related.map { it.title })
        assertFalse(related.any { it.streamId == 2001 })
        assertFalse(Gson().toJson(related).contains("fake_user"))
        assertFalse(Gson().toJson(related).contains("fake_pass"))
    }

    @Test
    fun categoryPreviewDoesNotFillASingletonWithUnrelatedMovies() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(selectedMovieStreamId = 2002)

        val related = state.categoryMoviesFor(state.movies.single { it.streamId == 2002 }, maxItems = 1)

        assertTrue(related.isEmpty())
        assertFalse(related.any { it.streamId == 2002 })
    }

    @Test
    fun rawProviderPosterUrlsBecomeOpaqueMovieImageTokens() {
        val state = mapper.mapCachedCatalogJson(
            """
                {
                  "catalogSource": "fixture",
                  "playableVodItems": [
                    {"title":"Unsafe Poster","categoryName":"Fixture","streamId":42,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/42.mp4","posterImage":"http://example.test/movie/fake_user/fake_pass/poster.jpg"}
                  ]
                }
            """.trimIndent(),
            credentials,
        )
        val json = Gson().toJson(state)

        assertEquals(AtlasImageState.ProviderImage, state.movies.single().posterImage.state)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/fake_user"))
    }

    @Test
    fun rawPlaybackUrlsAreNotAcceptedAsMoviePosterImages() {
        val state = mapper.mapCachedCatalogJson(
            """
                {
                  "catalogSource": "fixture",
                  "playableVodItems": [
                    {"title":"Unsafe Poster","categoryName":"Fixture","streamId":42,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/42.mp4","posterImage":"http://example.test/movie/fake_user/fake_pass/42.mp4"}
                  ]
                }
            """.trimIndent(),
            credentials,
        )

        assertEquals(AtlasImageState.ProviderImageHidden, state.movies.single().posterImage.state)
        assertFalse(Gson().toJson(state).contains("example.test"))
    }

    @Test
    fun preservesConfiguredCap() {
        val items = (1..130).joinToString(",") { id ->
            """{"title":"Movie $id","categoryName":"Bulk","streamId":$id,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/$id.mp4"}"""
        }

        val state = mapper.mapCachedCatalogJson("""{"catalogSource":"fixture","playableVodItems":[$items]}""", null)

        assertEquals(120, state.movies.size)
    }

    @Test
    fun providerSortKeepsCachedOrder() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)

        assertEquals(
            listOf("Fixture Movie One", "Fixture Movie Two", "Fixture Movie Three"),
            state.copy(sortMode = MoviesSortMode.Provider).visibleMovies().map { it.title },
        )
    }

    @Test
    fun titleSortOrdersMoviesByTitle() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson, credentials)

        assertEquals(
            listOf("Alpha Movie", "Beta Movie", "Zeta Movie"),
            state.copy(sortMode = MoviesSortMode.Title).visibleMovies().map { it.title },
        )
    }

    @Test
    fun categoryThenTitleSortOrdersByCategoryThenTitle() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson, credentials)

        assertEquals(
            listOf("Beta Movie", "Zeta Movie", "Alpha Movie"),
            state.copy(sortMode = MoviesSortMode.CategoryThenTitle).visibleMovies().map { it.title },
        )
    }

    @Test
    fun sortAppliesToCategoryAndSearchResults() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson, credentials)

        assertEquals(
            listOf("Beta Movie", "Zeta Movie"),
            state.copy(selectedCategoryId = "Fixture Action", sortMode = MoviesSortMode.Title)
                .moviesForCategory()
                .map { it.title },
        )
        assertEquals(
            listOf("Beta Movie", "Zeta Movie"),
            state.copy(searchQuery = "fixture action", sortMode = MoviesSortMode.Title)
                .visibleMovies()
                .map { it.title },
        )
    }

    @Test
    fun restoredContextFallsBackToSafeAvailableMovie() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson, credentials)

        val restored = state.withRestoredContext(
            categoryId = "Fixture Action",
            streamId = 9999,
            query = "zeta",
            sortMode = MoviesSortMode.Title,
        )

        assertEquals("Fixture Action", restored.selectedCategoryId)
        assertEquals(3001, restored.selectedMovieStreamId)
        assertEquals("zeta", restored.searchQuery)
        assertEquals(MoviesSortMode.Title, restored.sortMode)
    }

    @Test
    fun watchlistCategoryCountAndListUpdate() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, credentials)
            .copy(watchlistStreamIds = setOf(2003))
            .withWatchlistCategory()

        assertEquals(1, state.categories.first { it.id == MovieCategoryIds.Watchlist }.itemCount)
        assertEquals(listOf("Fixture Movie Three"), state.copy(selectedCategoryId = MovieCategoryIds.Watchlist).moviesForCategory().map { it.title })
        assertEquals(true, state.copy(selectedCategoryId = MovieCategoryIds.Watchlist, selectedMovieStreamId = 2003).selectedMovieDetails()!!.isWatchlisted)
    }

    @Test
    fun watchlistSearchAndSortStayScopedToWatchlist() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson, credentials)
            .copy(
                selectedCategoryId = MovieCategoryIds.Watchlist,
                watchlistStreamIds = setOf(3001, 3003),
                sortMode = MoviesSortMode.Title,
                searchQuery = "fixture action",
            )
            .withWatchlistCategory()

        assertEquals(listOf("Beta Movie", "Zeta Movie"), state.visibleMovies().map { it.title })
    }

    private val fixtureJson = """
        {
          "catalogSource": "fixture",
          "playableVodItems": [
            {"title":"Fixture Movie One","categoryName":"Fixture Action","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4","posterImage":"fixture://poster/movie-one"},
            {"title":"Fixture Movie Two","categoryName":"Fixture Drama","streamId":2002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2002.mp4"},
            {"title":"Fixture Movie Three","categoryName":"Fixture Action","streamId":2003,"containerExtension":"mkv","redactedUrl":"fixture://debug_catalog/movie/2003.mkv"}
          ]
        }
    """.trimIndent()

    private val sortFixtureJson = """
        {
          "catalogSource": "fixture",
          "playableVodItems": [
            {"title":"Zeta Movie","categoryName":"Fixture Action","streamId":3001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/3001.mp4"},
            {"title":"Alpha Movie","categoryName":"Fixture Drama","streamId":3002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/3002.mp4"},
            {"title":"Beta Movie","categoryName":"Fixture Action","streamId":3003,"containerExtension":"mkv","redactedUrl":"fixture://debug_catalog/movie/3003.mkv"}
          ]
        }
    """.trimIndent()
}
