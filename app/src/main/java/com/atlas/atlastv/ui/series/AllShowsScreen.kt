package com.atlas.atlastv.ui.series

import androidx.compose.runtime.Composable
import com.atlas.atlastv.ui.browse.CatalogGridScreen

@Composable
fun AllShowsScreen(holder: SeriesGridStateHolder, revision: Long, onBack: () -> Unit, onSeries: (SeriesItem) -> Unit, catalogReady: Boolean = true, onNavigationChanged: () -> Unit = {}) {
    CatalogGridScreen(holder, revision, "All Shows", "show", "shows", "all_shows", { it.name }, { it.categoryName }, { it.posterImage }, onBack, onSeries, catalogReady, onNavigationChanged = onNavigationChanged)
}

@Composable
fun SeriesCategoryGridScreen(holder: SeriesGridStateHolder, revision: Long, categoryName: String, onBack: () -> Unit, onSeries: (SeriesItem) -> Unit, catalogReady: Boolean = true, onNavigationChanged: () -> Unit = {}) {
    CatalogGridScreen(holder, revision, com.atlas.atlastv.ui.browse.cleanBrowseRowTitle(categoryName).ifBlank { "Other Shows" },
        "show", "shows", "show_category", { it.name }, { it.categoryName }, { it.posterImage }, onBack, onSeries, catalogReady,
        emptyMessage = "No shows remain in this category.", onNavigationChanged = onNavigationChanged)
}

@Composable
fun SeriesWatchlistGridScreen(holder: SeriesGridStateHolder, revision: Long, onBack: () -> Unit, onSeries: (SeriesItem) -> Unit,
    catalogReady: Boolean = true, statusMessage: String? = null, onRetrySave: (() -> Unit)? = null, onNavigationChanged: () -> Unit = {}) {
    CatalogGridScreen(holder, revision, "Show Watchlist", "show", "shows", "show_watchlist", { it.name }, { it.categoryName },
        { it.posterImage }, onBack, onSeries, catalogReady, emptyMessage = "Your watchlist is empty. Add shows from their details.",
        statusMessage = statusMessage, onRetryStatus = onRetrySave, onNavigationChanged = onNavigationChanged)
}
