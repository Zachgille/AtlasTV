package com.atlas.atlastv.ui.search

import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.series.SeriesCatalogState
import com.atlas.atlastv.ui.series.SeriesItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchModelsTest {
    @Test
    fun blankQueryShowsPromptWithoutRows() {
        val state = buildUnifiedSearchState(moviesState(), seriesState(), "   ") as UnifiedSearchState.Ready

        assertEquals(0, state.totalCount)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun searchesMoviesAndShowsTogetherByTitleAndCategoryTokens() {
        val state = buildUnifiedSearchState(moviesState(), seriesState(), "fixture drama") as UnifiedSearchState.Ready

        assertEquals(2, state.totalCount)
        assertEquals(listOf("Movies", "Shows"), state.rows.map { it.title })
        assertEquals("Fixture Movie Two", state.rows[0].items.single().title)
        assertEquals("Fixture Series Two", state.rows[1].items.single().title)
    }

    @Test
    fun resultRowsAreCappedForTvPerformance() {
        val movies = (1..30).map { index ->
            MovieItem("Action Movie $index", "Fixture Action", index, "mp4", "fixture://debug_catalog/movie/$index.mp4")
        }
        val state = buildUnifiedSearchState(
            moviesState = MoviesCatalogState.Ready("fixture", 0L, movies, emptyList()),
            seriesState = seriesState(),
            query = "action",
            maxItemsPerRow = 18,
        ) as UnifiedSearchState.Ready

        assertEquals(30, state.movieCount)
        assertEquals(18, state.rows.single { it.id == "unified_search_movies" }.items.size)
        assertEquals(30, state.rows.single { it.id == "unified_search_movies" }.totalCount)
    }

    @Test
    fun displayedSearchItemsDoNotExposeUrlsOrCredentials() {
        val state = buildUnifiedSearchState(moviesState(), seriesState(), "fixture") as UnifiedSearchState.Ready

        val displayText = state.rows.flatMap { row ->
            row.items.flatMap { item -> listOf(item.title, item.subtitle, item.badge) }
        }.joinToString(" ")

        assertTrue(!displayText.contains("fixture://"))
        assertTrue(!displayText.contains("/movie/"))
        assertTrue(!displayText.contains("/series/"))
        assertTrue(!displayText.contains("fake_user"))
        assertTrue(!displayText.contains("fake_pass"))
    }

    private fun moviesState(): MoviesCatalogState.Ready =
        MoviesCatalogState.Ready(
            source = "fixture",
            cacheAgeMs = 0L,
            movies = listOf(
                MovieItem("Fixture Movie One", "Fixture Action", 2001, "mp4", "fixture://debug_catalog/movie/2001.mp4"),
                MovieItem("Fixture Movie Two", "Fixture Drama", 2002, "mp4", "fixture://debug_catalog/movie/2002.mp4"),
            ),
            categories = emptyList(),
        )

    private fun seriesState(): SeriesCatalogState.Ready =
        SeriesCatalogState.Ready(
            source = "fixture",
            cacheAgeMs = 0L,
            series = listOf(
                SeriesItem("Fixture Series One", "Fixture Series", 3001),
                SeriesItem("Fixture Series Two", "Fixture Drama", 3002),
            ),
            categories = emptyList(),
        )
}
