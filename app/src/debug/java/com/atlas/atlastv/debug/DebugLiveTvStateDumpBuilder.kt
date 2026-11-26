package com.atlas.atlastv.debug

import com.atlas.atlastv.ui.livetv.LiveTvCatalogState
import com.atlas.atlastv.ui.livetv.selectedChannelDetails

object DebugLiveTvStateDumpBuilder {
    fun build(ready: LiveTvCatalogState.Ready): DebugLiveTvStateDump {
        val selectedCategory = ready.categories.firstOrNull { it.id == ready.selectedCategoryId }
        val details = ready.selectedChannelDetails()
        return DebugLiveTvStateDump(
            cacheSource = ready.source,
            cacheAgeMs = ready.cacheAgeMs,
            cappedItemCount = ready.channels.size,
            selectedCategoryId = ready.selectedCategoryId,
            selectedCategoryName = selectedCategory?.name ?: "All",
            selectedChannelStreamId = details?.streamId,
            selectedChannelName = details?.channelName,
            selectedChannelCategory = details?.categoryName,
            selectedSortMode = ready.sortMode.storageKey,
            selectedSortModeLabel = ready.sortMode.displayName,
            searchQuery = ready.searchQuery.takeIf { it.isNotBlank() },
            favoritesCount = ready.favoriteStreamIds.size,
            selectedChannelIsFavorite = details?.isFavorite ?: false,
            redactedStreamUrl = details?.redactedStreamUrl,
        )
    }
}
