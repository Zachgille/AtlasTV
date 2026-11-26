package com.atlas.atlastv.ui.movies

fun MoviesCatalogState.Ready.withRestoredContext(
    categoryId: String?,
    streamId: Int?,
    query: String?,
    sortMode: MoviesSortMode,
    watchlist: Set<Int>? = null,
    maxSearchQueryLength: Int = DefaultMaxMoviesSearchQueryLength,
): MoviesCatalogState.Ready {
    val safeWatchlist = watchlist.orEmpty().filterTo(mutableSetOf()) { id -> movies.any { it.streamId == id } }
    val safeCategoryId = categoryId?.takeIf { id -> categories.any { it.id == id } } ?: MovieCategoryIds.All
    val safeQuery = query.orEmpty().take(maxSearchQueryLength)
    val withSort = copy(sortMode = sortMode, watchlistStreamIds = safeWatchlist).withWatchlistCategory()
    val visibleMovies = if (safeQuery.isBlank()) {
        withSort.moviesForCategory(safeCategoryId)
    } else {
        withSort.moviesForCategory(safeCategoryId).searchMovies(safeQuery, sortMode)
    }
    val safeStreamId = streamId?.takeIf { id -> visibleMovies.any { it.streamId == id } }
        ?: visibleMovies.firstOrNull()?.streamId
    return withSort.copy(
        selectedCategoryId = safeCategoryId,
        selectedMovieStreamId = safeStreamId,
        searchQuery = safeQuery,
    )
}
