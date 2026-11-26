package com.atlas.atlastv.ui.livetv

fun LiveTvCatalogState.Ready.visibleChannels(): List<LiveTvChannel> =
    if (searchQuery.isBlank()) channelsForCategory() else channels.searchLiveTv(searchQuery, favoriteStreamIds).sortedForLiveTv(sortMode, favoriteStreamIds)

fun List<LiveTvChannel>.searchLiveTv(query: String, favoriteStreamIds: Set<Int> = emptySet()): List<LiveTvChannel> {
    val normalizedQuery = query.normalizedSearchText()
    if (normalizedQuery.isBlank()) return this
    val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
    return filter { channel ->
        val favoriteToken = if (channel.streamId in favoriteStreamIds) " favorite" else ""
        val searchable = "${channel.name} ${channel.categoryName}$favoriteToken".normalizedSearchText()
        searchable.contains(normalizedQuery) || queryTokens.all { token -> searchable.split(' ').contains(token) }
    }
}

fun String.normalizedSearchText(): String =
    lowercase()
        .map { char -> if (char.isLetterOrDigit()) char else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")

fun List<LiveTvChannel>.sortedForLiveTv(
    sortMode: LiveTvSortMode,
    favoriteStreamIds: Set<Int>,
): List<LiveTvChannel> =
    when (sortMode) {
        LiveTvSortMode.Provider -> this
        LiveTvSortMode.ChannelName -> sortedWith(compareBy<LiveTvChannel, String>(String.CASE_INSENSITIVE_ORDER) { it.name })
        LiveTvSortMode.CategoryThenChannel -> sortedWith(
            compareBy<LiveTvChannel, String>(String.CASE_INSENSITIVE_ORDER) { it.categoryName }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        LiveTvSortMode.FavoritesFirst -> sortedWith(
            compareBy<LiveTvChannel> { if (it.streamId in favoriteStreamIds) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }
