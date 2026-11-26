package com.atlas.atlastv.ui.series

fun SeriesCatalogState.Ready.seriesForCategory(categoryId: String = selectedCategoryId): List<SeriesItem> =
    when (categoryId) {
        SeriesCategoryIds.All -> series
        SeriesCategoryIds.Watchlist -> series.filter { it.seriesId in watchlistSeriesIds }
        else -> series.filter { it.categoryName == categoryId }
    }.sortedForSeries(sortMode)

fun SeriesCatalogState.Ready.withWatchlistCategory(): SeriesCatalogState.Ready =
    copy(categories = series.toSeriesCategories(watchlistSeriesIds))

fun SeriesCatalogState.Ready.visibleSeries(): List<SeriesItem> =
    if (searchQuery.isBlank()) {
        seriesForCategory()
    } else {
        seriesForCategory().searchSeries(searchQuery, sortMode)
    }

fun List<SeriesItem>.searchSeries(query: String, sortMode: SeriesSortMode = SeriesSortMode.Title): List<SeriesItem> {
    val tokens = query.normalizedTokens()
    if (tokens.isEmpty()) return sortedForSeries(sortMode)
    return filter { item ->
        val haystack = "${item.name} ${item.categoryName}".normalizedTokens().toSet()
        tokens.all { token -> haystack.any { it.contains(token) } }
    }.sortedForSeries(sortMode)
}

fun List<SeriesItem>.sortedForSeries(sortMode: SeriesSortMode): List<SeriesItem> =
    when (sortMode) {
        SeriesSortMode.Provider -> this
        SeriesSortMode.Title -> sortedWith(compareBy<SeriesItem> { it.name.lowercase() }.thenBy { it.seriesId })
        SeriesSortMode.CategoryThenTitle -> sortedWith(
            compareBy<SeriesItem> { it.categoryName.lowercase() }
                .thenBy { it.name.lowercase() }
                .thenBy { it.seriesId },
        )
    }

private fun String.normalizedTokens(): List<String> =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() }
