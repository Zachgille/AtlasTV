package com.atlas.atlastv.ui.movies

fun MoviesCatalogState.Ready.moviesForCategory(categoryId: String = selectedCategoryId): List<MovieItem> =
    when (categoryId) {
        MovieCategoryIds.All -> movies
        MovieCategoryIds.Watchlist -> movies.filter { it.streamId in watchlistStreamIds }
        else -> movies.filter { it.categoryName == categoryId }
    }.sortedForMovies(sortMode)

fun MoviesCatalogState.Ready.visibleMovies(): List<MovieItem> =
    if (searchQuery.isBlank()) {
        moviesForCategory()
    } else {
        moviesForCategory().searchMovies(searchQuery, sortMode)
    }

fun List<MovieItem>.searchMovies(query: String, sortMode: MoviesSortMode = MoviesSortMode.Provider): List<MovieItem> {
    val tokens = query.normalizedTokens()
    if (tokens.isEmpty()) return sortedForMovies(sortMode)
    return filter { movie ->
        val haystack = "${movie.title} ${movie.categoryName}".normalizedTokens().toSet()
        tokens.all { token -> haystack.any { it.contains(token) } }
    }.sortedForMovies(sortMode)
}

fun List<MovieItem>.sortedForMovies(sortMode: MoviesSortMode): List<MovieItem> =
    when (sortMode) {
        MoviesSortMode.Provider -> this
        MoviesSortMode.Title -> sortedBy { it.title.lowercase() }
        MoviesSortMode.CategoryThenTitle -> sortedWith(
            compareBy<MovieItem> { it.categoryName.lowercase() }
                .thenBy { it.title.lowercase() },
        )
    }

fun MoviesCatalogState.Ready.withWatchlistCategory(): MoviesCatalogState.Ready =
    copy(categories = movies.toMovieCategories(watchlistStreamIds))

private fun String.normalizedTokens(): List<String> =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() }
