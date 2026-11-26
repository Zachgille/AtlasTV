package com.atlas.atlastv.ui.livetv

data class LiveTvChannel(
    val name: String,
    val categoryName: String,
    val streamId: Int,
    val containerExtension: String,
    val redactedStreamUrl: String,
)

data class LiveTvCategory(
    val id: String,
    val name: String,
    val channelCount: Int,
)

sealed interface LiveTvCatalogState {
    data object Loading : LiveTvCatalogState
    data class Ready(
        val source: String,
        val cacheAgeMs: Long?,
        val channels: List<LiveTvChannel>,
        val categories: List<LiveTvCategory>,
        val selectedCategoryId: String = LiveTvCategoryIds.All,
        val selectedChannelStreamId: Int? = channels.firstOrNull()?.streamId,
        val favoriteStreamIds: Set<Int> = emptySet(),
        val sortMode: LiveTvSortMode = LiveTvSortMode.Provider,
        val searchQuery: String = "",
        val refreshStatus: String? = null,
    ) : LiveTvCatalogState
    data class Refreshing(val previous: Ready?) : LiveTvCatalogState
    data class Error(val message: String) : LiveTvCatalogState
}

object LiveTvCategoryIds {
    const val All = "all"
    const val Favorites = "favorites"
}

enum class LiveTvSortMode(val storageKey: String, val displayName: String) {
    Provider("provider", "Provider order"),
    ChannelName("channel_name", "Name A-Z"),
    CategoryThenChannel("category_then_channel", "Category then name"),
    FavoritesFirst("favorites_first", "Favorites first");

    fun next(): LiveTvSortMode {
        val modes = entries
        return modes[(ordinal + 1) % modes.size]
    }

    companion object {
        fun fromStorageKey(value: String?): LiveTvSortMode =
            entries.firstOrNull { it.storageKey == value } ?: Provider
    }
}
