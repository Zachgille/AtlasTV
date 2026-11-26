package com.atlas.atlastv.ui.movies

import androidx.compose.runtime.Composable
import com.atlas.atlastv.ui.browse.CatalogGridScreen

@Composable
fun AllMoviesScreen(holder: MovieGridStateHolder, revision: Long, onBack: () -> Unit, onMovie: (MovieItem) -> Unit, catalogReady: Boolean = true, onNavigationChanged: () -> Unit = {}) {
    CatalogGridScreen(holder, revision, "All Movies", "movie", "movies", "all_movies", { it.title }, { it.categoryName }, { it.posterImage }, onBack, onMovie, catalogReady, onNavigationChanged = onNavigationChanged)
}

@Composable
fun MovieCategoryGridScreen(holder: MovieGridStateHolder, revision: Long, categoryName: String, onBack: () -> Unit,
    onMovie: (MovieItem) -> Unit, catalogReady: Boolean = true, onNavigationChanged: () -> Unit = {}) {
    CatalogGridScreen(holder, revision, com.atlas.atlastv.ui.browse.cleanBrowseRowTitle(categoryName).ifBlank { "Other Movies" },
        "movie", "movies", "movie_category", { it.title }, { it.categoryName }, { it.posterImage }, onBack, onMovie, catalogReady,
        emptyMessage = "No movies remain in this category.", onNavigationChanged = onNavigationChanged)
}

@Composable
fun MovieWatchlistGridScreen(holder: MovieGridStateHolder, revision: Long, onBack: () -> Unit, onMovie: (MovieItem) -> Unit, catalogReady: Boolean = true, statusMessage: String? = null, onRetrySave: (() -> Unit)? = null, onNavigationChanged: () -> Unit = {}) {
    CatalogGridScreen(holder, revision, "Movie Watchlist", "movie", "movies", "movie_watchlist", { it.title }, { it.categoryName }, { it.posterImage }, onBack, onMovie, catalogReady,
        emptyMessage = "Your watchlist is empty. Add movies from their details.", statusMessage = statusMessage, onRetryStatus = onRetrySave, onNavigationChanged = onNavigationChanged)
}
