package com.atlas.atlastv.ui.series

fun SeriesCatalogState.Ready.withRestoredContext(
    categoryId: String?,
    seriesId: Int?,
    query: String?,
    sortMode: SeriesSortMode,
    watchlist: Set<Int>? = null,
    maxSearchQueryLength: Int = DefaultMaxSeriesSearchQueryLength,
): SeriesCatalogState.Ready {
    val safeWatchlist = watchlist.orEmpty().filterTo(mutableSetOf()) { id -> series.any { it.seriesId == id } }
    val safeCategoryId = categoryId?.takeIf { id -> categories.any { it.id == id } } ?: SeriesCategoryIds.All
    val safeQuery = query.orEmpty().take(maxSearchQueryLength)
    val withSort = copy(sortMode = sortMode, watchlistSeriesIds = safeWatchlist).withWatchlistCategory()
    val visibleSeries = if (safeQuery.isBlank()) {
        withSort.seriesForCategory(safeCategoryId)
    } else {
        withSort.seriesForCategory(safeCategoryId).searchSeries(safeQuery, sortMode)
    }
    val safeSeriesId = seriesId?.takeIf { id -> visibleSeries.any { it.seriesId == id } }
        ?: visibleSeries.firstOrNull()?.seriesId
    return withSort.copy(
        selectedCategoryId = safeCategoryId,
        selectedSeriesId = safeSeriesId,
        searchQuery = safeQuery,
        episodeState = SeriesEpisodeState.Idle,
        selectedSeasonNumber = null,
        selectedEpisodeId = null,
    )
}
