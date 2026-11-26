package com.atlas.atlastv.ui.home

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeShellMapperTest {
    private val mapper = HomeShellMapper()

    @Test
    fun yearMetadataUsesTheReleaseYearRatherThanNumbersInTheMovieName() {
        val state = mapper.map("cached", null,
            listOf(MovieItem("1917 (2019)", "Movies", 1, "mp4", "")),
            emptyList(), emptySet(), continueWatching = emptyList())
        assertEquals("2019", state.rows.first().cards.first().year)
    }

    @Test
    fun rowsPutContinueWatchingAboveWatchlist() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = 10L,
            movies = movies,
            series = series,
            watchlistStreamIds = setOf(2002),
            continueWatching = listOf(movieProgress),
        )

        assertEquals(HomeRowIds.ContinueWatching, state.rows[0].id)
        assertEquals(HomeRowIds.Watchlist, state.rows[1].id)
        assertEquals(listOf("Fixture Movie Two"), state.rows[1].cards.map { it.title })
        assertEquals(listOf("Progress Movie"), state.rows[0].cards.map { it.title })
    }

    @Test
    fun continueWatchingIsInitialFocusWhenPresent() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = null,
            movies = movies,
            series = series,
            watchlistStreamIds = setOf(2002),
            continueWatching = listOf(movieProgress),
        )

        assertEquals("continue_watching-movie-2001", state.initialFocusCardId)
    }

    @Test
    fun popularIsInitialFocusWhenContinueWatchingIsEmpty() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = null,
            movies = movies,
            series = series,
            watchlistStreamIds = emptySet(),
            continueWatching = emptyList(),
        )

        assertEquals("popular-movie-2001", state.initialFocusCardId)
    }

    @Test
    fun emptyWatchlistAndContinueRowsCollapseWhenBrowseContentExists() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = null,
            movies = movies,
            series = series,
            watchlistStreamIds = emptySet(),
            continueWatching = emptyList(),
        )

        assertEquals(HomeRowIds.Popular, state.rows.first().id)
        assertFalse(state.rows.any { it.id == HomeRowIds.Watchlist })
        assertFalse(state.rows.any { it.id == HomeRowIds.ContinueWatching })
        assertEquals("Browse Movies", state.rows.first { it.id == HomeRowIds.RecentlyAddedMovies }.title)
        assertEquals("Browse Shows", state.rows.first { it.id == HomeRowIds.RecentlyAddedShows }.title)
    }

    @Test
    fun heroMetadataChangesWithFocusedCardData() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = null,
            movies = movies,
            series = series,
            watchlistStreamIds = emptySet(),
            continueWatching = listOf(movieProgress, episodeProgress),
        )
        val first = state.rows.first { it.id == HomeRowIds.ContinueWatching }.cards[0]
        val second = state.rows.first { it.id == HomeRowIds.ContinueWatching }.cards[1]

        assertEquals("Progress Movie", first.title)
        assertEquals(HomeContentType.Movie, first.contentType)
        assertEquals("Fixture Series One", second.title)
        assertEquals(movies.first().posterImage, first.posterImage)
        assertEquals(series.first().posterImage, second.posterImage)
        assertEquals(HomeContentType.SeriesEpisode, second.contentType)
        assertEquals("Resume at 10:00 (8%)", first.progressLabel)
    }

    @Test
    fun homeStateContainsNoRawCredentialsHostsOrPlaybackPaths() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = null,
            movies = listOf(
                MovieItem(
                    title = "http://example.test/movie/fake_user/fake_pass/1.mp4",
                    categoryName = "http://example.test/live/fake_user/fake_pass",
                    streamId = 1,
                    containerExtension = "mp4",
                    redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/1.mp4",
                    posterImage = atlasImageRefFromRaw("http://example.test/movie/fake_user/fake_pass/poster.jpg"),
                ),
            ),
            series = listOf(
                SeriesItem(
                    name = "http://example.test/series/fake_user/fake_pass",
                    categoryName = "Series",
                    seriesId = 3,
                    posterImage = atlasImageRefFromRaw("https://example.test/images/series-poster.jpg"),
                ),
            ),
            watchlistStreamIds = setOf(1),
            continueWatching = emptyList(),
        )
        val json = Gson().toJson(state)

        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("/live/"))
        assertFalse(json.contains("/movie/"))
        assertFalse(json.contains("/series/"))
    }

    @Test
    fun mapsMovieAndSeriesPosterRefsIntoSafeHomeCards() {
        val state = mapper.map(
            source = "fixture",
            cacheAgeMs = null,
            movies = movies,
            series = series,
            watchlistStreamIds = emptySet(),
            continueWatching = emptyList(),
        )

        val movieCard = state.rows.first { it.id == HomeRowIds.Popular }.cards.first()
        val seriesCard = state.rows.first { it.id == HomeRowIds.Popular }.cards.drop(1).first()

        assertEquals(AtlasImageState.SafeImage, movieCard.posterImage.state)
        assertEquals(AtlasImageState.SafeImage, movieCard.backdropImage.state)
        assertEquals(AtlasImageState.SafeImage, seriesCard.posterImage.state)
    }

    @Test
    fun mapsProviderImageTokensIntoSafeHomeCardsWithoutRawUrls() {
        val state = mapper.map(
            source = "cached",
            cacheAgeMs = null,
            movies = listOf(
                MovieItem(
                    title = "Provider Poster",
                    categoryName = "Movies",
                    streamId = 88,
                    containerExtension = "mp4",
                    redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/88.mp4",
                    posterImage = atlasImageRefFromRaw("https://example.test/movie/fake_user/fake_pass/poster.jpg"),
                ),
            ),
            series = emptyList(),
            watchlistStreamIds = emptySet(),
            continueWatching = emptyList(),
        )
        val card = state.rows.first { it.id == HomeRowIds.Popular }.cards.single()
        val json = Gson().toJson(state)

        assertEquals(AtlasImageState.ProviderImage, card.posterImage.state)
        assertEquals(AtlasImageState.ProviderImage, card.backdropImage.state)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/fake_user"))
    }

    @Test
    fun missingPosterUsesFallbackState() {
        val state = mapper.map(
            source = "fixture",
            cacheAgeMs = null,
            movies = listOf(MovieItem("No Poster", "Fixture", 9, "mp4", "fixture://debug_catalog/movie/9.mp4")),
            series = emptyList(),
            watchlistStreamIds = emptySet(),
            continueWatching = emptyList(),
        )

        val card = state.rows.first { it.id == HomeRowIds.Popular }.cards.single()

        assertEquals(AtlasImageState.Missing, card.posterImage.state)
    }

    private val movies = listOf(
        MovieItem("Fixture Movie One", "Fixture Action", 2001, "mp4", "fixture://debug_catalog/movie/2001.mp4", atlasImageRefFromRaw("fixture://poster/movie-one")),
        MovieItem("Fixture Movie Two", "Fixture Drama", 2002, "mp4", "fixture://debug_catalog/movie/2002.mp4", atlasImageRefFromRaw("fixture://poster/movie-two")),
    )

    private val series = listOf(
        SeriesItem("Fixture Series One", "Fixture Series", 3001, atlasImageRefFromRaw("fixture://poster/series-one")),
        SeriesItem("Fixture Series Two", "Fixture Drama", 3002, atlasImageRefFromRaw("fixture://poster/series-two")),
    )

    private val movieProgress = WatchProgressRecord(
        contentType = WatchProgressContentType.Movie,
        streamId = 2001,
        title = "Progress Movie",
        categoryName = "Fixture Action",
        extension = "mp4",
        durationMs = 7_200_000L,
        positionMs = 600_000L,
        updatedAtMs = 20L,
    )

    private val episodeProgress = WatchProgressRecord(
        contentType = WatchProgressContentType.SeriesEpisode,
        streamId = 4001,
        seriesId = 3001,
        title = "Progress Episode",
        seriesTitle = "Fixture Series One",
        episodeTitle = "Episode One",
        categoryName = "Fixture Series",
        extension = "mp4",
        durationMs = 3_600_000L,
        positionMs = 300_000L,
        updatedAtMs = 10L,
    )
}
