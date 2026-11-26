package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.media.AtlasImageRef

sealed interface HomeShellState {
    data object Loading : HomeShellState
    data class Ready(
        val source: String,
        val cacheAgeMs: Long?,
        val rows: List<HomeContentRow>,
        val initialFocusCardId: String?,
    ) : HomeShellState
    data class Error(val message: String, val needsSetup: Boolean = false) : HomeShellState
}

data class HomeContentRow(
    val id: String,
    val title: String,
    val cards: List<HomeCard>,
    val collection: com.atlas.atlastv.data.discovery.DiscoveryCollectionSpec? = null,
)

data class HomeCard(
    val id: String,
    val title: String,
    val contentType: HomeContentType,
    val categoryLabel: String,
    val streamId: Int,
    val seriesId: Int? = null,
    val extension: String? = null,
    val year: String? = null,
    val progressLabel: String? = null,
    val statusText: String,
    val posterImage: AtlasImageRef = AtlasImageRef.Missing,
    val backdropImage: AtlasImageRef = AtlasImageRef.Missing,
    val cardProgress: com.atlas.atlastv.ui.catalog.CardWatchProgress? = null,
)

enum class HomeContentType(val displayName: String) {
    Movie("Movie"),
    Show("Show"),
    SeriesEpisode("Episode"),
}

object HomeRowIds {
    const val Watchlist = "watchlist"
    const val ContinueWatching = "continue_watching"
    const val Popular = "popular"
    const val RecentlyAddedMovies = "recently_added_movies"
    const val RecentlyAddedShows = "recently_added_shows"
}
