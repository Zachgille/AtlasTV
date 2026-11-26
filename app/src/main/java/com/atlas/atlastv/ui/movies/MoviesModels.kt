package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchResult
import com.atlas.atlastv.ui.media.AtlasImageRef

data class MovieItem(
    val title: String,
    val categoryName: String,
    val streamId: Int,
    val containerExtension: String,
    val redactedStreamUrl: String,
    val posterImage: AtlasImageRef = AtlasImageRef.Missing,
    val available: Boolean = true,
)

typealias MovieCategory = com.atlas.atlastv.ui.browse.CatalogCategory

enum class MoviesSortMode(
    val storageKey: String,
    val displayName: String,
) {
    Provider("provider", "Provider order"),
    Title("title", "Title A-Z"),
    CategoryThenTitle("category_then_title", "Category then title");

    fun next(): MoviesSortMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorageKey(value: String?): MoviesSortMode =
            entries.firstOrNull { it.storageKey == value } ?: Provider
    }
}

sealed interface MoviesCatalogState {
    data object Loading : MoviesCatalogState
    data class Ready(
        val source: String,
        val cacheAgeMs: Long?,
        val movies: List<MovieItem>,
        val categories: List<MovieCategory>,
        val selectedCategoryId: String = MovieCategoryIds.All,
        val selectedMovieStreamId: Int? = movies.firstOrNull()?.streamId,
        val searchQuery: String = "",
        val sortMode: MoviesSortMode = MoviesSortMode.Provider,
        val watchlistStreamIds: Set<Int> = emptySet(),
        val watchProgress: Map<Int, WatchProgressRecord> = emptyMap(),
        val browseRows: List<BrowseRow<MovieItem>> = emptyList(),
        val searchResult: BrowseSearchResult<MovieItem>? = null,
    ) : MoviesCatalogState
    data class Error(val message: String) : MoviesCatalogState
}

object MovieCategoryIds {
    const val All = "all"
    const val Watchlist = "watchlist"
}
