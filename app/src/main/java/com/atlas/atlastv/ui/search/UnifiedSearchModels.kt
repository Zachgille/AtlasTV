package com.atlas.atlastv.ui.search

import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.movies.searchMovies
import com.atlas.atlastv.ui.series.SeriesCatalogState
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.searchSeries

sealed interface UnifiedSearchState {
    data object Loading : UnifiedSearchState
    data class Ready(
        val query: String,
        val movieCount: Int,
        val seriesCount: Int,
        val rows: List<UnifiedSearchRow>,
    ) : UnifiedSearchState {
        val totalCount: Int = movieCount + seriesCount
    }
    data class Error(val message: String) : UnifiedSearchState
}

data class UnifiedSearchRow(
    val id: String,
    val title: String,
    val items: List<UnifiedSearchItem>,
    val totalCount: Int = items.size,
)

sealed interface UnifiedSearchItem {
    val id: String
    val title: String
    val subtitle: String
    val badge: String
    val imageRef: AtlasImageRef

    data class Movie(val movie: MovieItem) : UnifiedSearchItem {
        override val id: String = "movie-${movie.streamId}"
        override val title: String = movie.title
        override val subtitle: String = movie.categoryName
        override val badge: String = "Movie"
        override val imageRef: AtlasImageRef = movie.posterImage
    }

    data class Show(val series: SeriesItem) : UnifiedSearchItem {
        override val id: String = "show-${series.seriesId}"
        override val title: String = series.name
        override val subtitle: String = series.categoryName
        override val badge: String = "Show"
        override val imageRef: AtlasImageRef = series.posterImage
    }
}

fun buildUnifiedSearchState(
    moviesState: MoviesCatalogState,
    seriesState: SeriesCatalogState,
    query: String,
    maxItemsPerRow: Int = 18,
): UnifiedSearchState {
    if (moviesState is MoviesCatalogState.Loading || seriesState is SeriesCatalogState.Loading) {
        return UnifiedSearchState.Loading
    }
    val moviesReady = moviesState as? MoviesCatalogState.Ready
    val seriesReady = seriesState as? SeriesCatalogState.Ready
    if (moviesReady == null && seriesReady == null) {
        val movieError = (moviesState as? MoviesCatalogState.Error)?.message
        val seriesError = (seriesState as? SeriesCatalogState.Error)?.message
        return UnifiedSearchState.Error(
            listOfNotNull(movieError, seriesError)
                .joinToString(" ")
                .ifBlank { "Cached Movies and Shows catalog unavailable." },
        )
    }

    val safeQuery = query.take(MaxUnifiedSearchQueryLength)
    if (safeQuery.isBlank()) {
        return UnifiedSearchState.Ready(query = safeQuery, movieCount = 0, seriesCount = 0, rows = emptyList())
    }

    val movieMatches = moviesReady?.movies.orEmpty().searchMovies(safeQuery).take(maxItemsPerRow)
    val seriesMatches = seriesReady?.series.orEmpty().searchSeries(safeQuery).take(maxItemsPerRow)
    return UnifiedSearchState.Ready(
        query = safeQuery,
        movieCount = moviesReady?.movies.orEmpty().searchMovies(safeQuery).size,
        seriesCount = seriesReady?.series.orEmpty().searchSeries(safeQuery).size,
        rows = listOfNotNull(
            movieMatches.takeIf { it.isNotEmpty() }?.let { matches ->
                UnifiedSearchRow(
                    id = "unified_search_movies",
                    title = "Movies",
                    items = matches.map(UnifiedSearchItem::Movie),
                    totalCount = moviesReady?.movies.orEmpty().searchMovies(safeQuery).size,
                )
            },
            seriesMatches.takeIf { it.isNotEmpty() }?.let { matches ->
                UnifiedSearchRow(
                    id = "unified_search_shows",
                    title = "Shows",
                    items = matches.map(UnifiedSearchItem::Show),
                    totalCount = seriesReady?.series.orEmpty().searchSeries(safeQuery).size,
                )
            },
        ),
    )
}

const val MaxUnifiedSearchQueryLength = 80
