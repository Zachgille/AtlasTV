package com.atlas.atlastv.ui.browse

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowseRowsTest {
    @Test
    fun cleansProviderCategoryTitlesForNormalRows() {
        assertEquals("New Releases", cleanBrowseRowTitle("EN - NEW RELEASE"))
        assertEquals("English Series", cleanBrowseRowTitle("ENGLISH SERIES"))
        assertEquals("Sci-Fi", cleanBrowseRowTitle("SCI FI"))
    }

    @Test
    fun movieRowsSkipEmptyWatchlistAndContinueRows() {
        val rows = movieBrowseRows(movies, watchlistIds = emptySet(), progressRecords = emptyMap())

        assertEquals("Browse Movies", rows.first().title)
        assertFalse(rows.any { it.title == "Watchlist" })
        assertFalse(rows.any { it.title == "Continue Watching Movies" })
        assertEquals(true, rows.any { it.title == "More Movies" })
    }

    @Test
    fun movieRowsIncludeWatchlistAndProgressWhenAvailable() {
        val rows = movieBrowseRows(
            movies = movies,
            watchlistIds = setOf(2),
            progressRecords = mapOf(1 to progress(1)),
        )

        assertEquals("Watchlist", rows[0].title)
        assertEquals("Continue Watching Movies", rows[1].title)
    }

    @Test
    fun seriesRowsStartWithPopularWhenNoProgress() {
        val rows = seriesBrowseRows(series, progressRecords = emptyMap())

        assertEquals("Browse Shows", rows.first().title)
        assertEquals(true, rows.any { it.title == "More Shows" })
    }

    @Test
    fun seriesRowsIncludeWatchlistOnlyWhenAvailable() {
        val emptyRows = seriesBrowseRows(series, progressRecords = emptyMap(), watchlistIds = emptySet())
        val watchlistRows = seriesBrowseRows(series, progressRecords = emptyMap(), watchlistIds = setOf(12))

        assertFalse(emptyRows.any { it.title == "Watchlist" })
        assertEquals("Watchlist", watchlistRows.first().title)
        assertEquals(listOf(12), watchlistRows.first().items.map { it.seriesId })
    }

    @Test
    fun movieSearchRowsMatchTitleAndCategoryTokens() {
        val result = movieSearchBrowseRows(movies, "fixture action")

        assertEquals(2, result.totalCount)
        assertEquals("Search Results", result.rows.first().title)
        assertEquals(listOf(3, 4), result.rows.first().items.map { it.streamId })
    }

    @Test
    fun seriesSearchRowsMatchNormalizedTokens() {
        val result = seriesSearchBrowseRows(series, "english")

        assertEquals(2, result.totalCount)
        assertEquals(listOf(11, 12), result.rows.first().items.map { it.seriesId })
    }

    @Test
    fun searchRowsPreserveCappedChunking() {
        val manyMovies = (1..40).map { index ->
            MovieItem("Movie $index", "Action", index, "mp4", "fixture://debug_catalog/movie/$index.mp4")
        }

        val result = movieSearchBrowseRows(manyMovies, "action", maxRows = 2, maxItemsPerRow = 18)

        assertEquals(40, result.totalCount)
        assertEquals(2, result.rows.size)
        assertEquals(18, result.rows[0].items.size)
        assertEquals(18, result.rows[1].items.size)
    }

    private val movies = listOf(
        MovieItem("A", "EN - NEW RELEASE", 1, "mp4", "fixture://debug_catalog/movie/1.mp4"),
        MovieItem("B", "EN - NEW RELEASE", 2, "mp4", "fixture://debug_catalog/movie/2.mp4"),
        MovieItem("Fixture Action C", "ACTION", 3, "mp4", "fixture://debug_catalog/movie/3.mp4"),
        MovieItem("Fixture Action D", "ACTION", 4, "mp4", "fixture://debug_catalog/movie/4.mp4"),
    )

    private val series = listOf(
        SeriesItem("A", "ENGLISH SERIES", 11),
        SeriesItem("B", "ENGLISH SERIES", 12),
        SeriesItem("C", "DRAMA", 13),
        SeriesItem("D", "DRAMA", 14),
    )

    private fun progress(streamId: Int): WatchProgressRecord =
        WatchProgressRecord(
            contentType = WatchProgressContentType.Movie,
            streamId = streamId,
            title = "Progress",
            positionMs = 60_000L,
            durationMs = 600_000L,
            updatedAtMs = 1L,
        )
}
