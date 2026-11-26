package com.atlas.atlastv.ui.livetv

fun LiveTvCatalogState.Ready.withRestoredContext(
    categoryId: String?,
    streamId: Int?,
    query: String?,
    favorites: Set<Int>?,
    sortMode: LiveTvSortMode,
    maxSearchQueryLength: Int = DefaultMaxSearchQueryLength,
): LiveTvCatalogState.Ready {
    val safeFavorites = favorites.orEmpty().filterTo(mutableSetOf()) { id -> channels.any { it.streamId == id } }
    val safeCategoryId = categoryId?.takeIf { id -> categories.any { it.id == id } } ?: LiveTvCategoryIds.All
    val safeQuery = query.orEmpty().take(maxSearchQueryLength)
    val withFavorites = copy(favoriteStreamIds = safeFavorites, sortMode = sortMode).withFavoriteCategories()
    val visibleChannels = if (safeQuery.isBlank()) {
        withFavorites.channelsForCategory(safeCategoryId)
    } else {
        withFavorites.channels.searchLiveTv(safeQuery, safeFavorites)
    }
    val safeStreamId = streamId?.takeIf { id -> visibleChannels.any { it.streamId == id } }
        ?: visibleChannels.firstOrNull()?.streamId
    return withFavorites.copy(
        selectedCategoryId = safeCategoryId,
        selectedChannelStreamId = safeStreamId,
        searchQuery = safeQuery,
    )
}

const val DefaultMaxSearchQueryLength = 80
