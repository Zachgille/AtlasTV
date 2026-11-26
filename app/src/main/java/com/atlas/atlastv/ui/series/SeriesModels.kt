package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchResult
import com.atlas.atlastv.ui.media.AtlasImageRef

data class SeriesItem(
    val name: String,
    val categoryName: String,
    val seriesId: Int,
    val posterImage: AtlasImageRef = AtlasImageRef.Missing,
    val available: Boolean = true,
)

typealias SeriesCategory = com.atlas.atlastv.ui.browse.CatalogCategory

enum class SeriesSortMode(
    val storageKey: String,
    val displayName: String,
) {
    Provider("provider", "Provider order"),
    Title("title", "Title A-Z"),
    CategoryThenTitle("category_then_title", "Category then title");

    fun next(): SeriesSortMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorageKey(value: String?): SeriesSortMode =
            entries.firstOrNull { it.storageKey == value } ?: Provider
    }
}

data class SeriesEpisodeItem(
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeId: Int,
    val containerExtension: String,
    val redactedStreamUrl: String,
    val durationMs: Long? = null,
    val thumbnail: AtlasImageRef = AtlasImageRef.Missing,
)

sealed interface SeriesEpisodeState {
    data object Idle : SeriesEpisodeState
    data object Loading : SeriesEpisodeState
    data class Ready(val episodes: List<SeriesEpisodeItem>, val metadata: SeriesShowMetadata = SeriesShowMetadata(),
        val saved: Boolean = false, val refreshing: Boolean = false, val refreshFailed: Boolean = false) : SeriesEpisodeState
    data class Error(val message: String) : SeriesEpisodeState
}

sealed interface SeriesCatalogState {
    data object Loading : SeriesCatalogState
    data class Ready(
        val source: String,
        val cacheAgeMs: Long?,
        val series: List<SeriesItem>,
        val categories: List<SeriesCategory>,
        val selectedCategoryId: String = SeriesCategoryIds.All,
        val selectedSeriesId: Int? = series.firstOrNull()?.seriesId,
        val searchQuery: String = "",
        val sortMode: SeriesSortMode = SeriesSortMode.Provider,
        val episodeState: SeriesEpisodeState = SeriesEpisodeState.Idle,
        val selectedSeasonNumber: Int? = null,
        val selectedEpisodeId: Int? = null,
        val watchProgress: Map<Int, WatchProgressRecord> = emptyMap(),
        val watchlistSeriesIds: Set<Int> = emptySet(),
        val browseRows: List<BrowseRow<SeriesItem>> = emptyList(),
        val searchResult: BrowseSearchResult<SeriesItem>? = null,
    ) : SeriesCatalogState
    data class Error(val message: String) : SeriesCatalogState
}

object SeriesCategoryIds {
    const val All = "all"
    const val Watchlist = "watchlist"
}
