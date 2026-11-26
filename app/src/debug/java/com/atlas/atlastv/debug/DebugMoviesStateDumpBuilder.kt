package com.atlas.atlastv.debug

import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.movies.selectedMovieDetails

object DebugMoviesStateDumpBuilder {
    fun build(ready: MoviesCatalogState.Ready): DebugMoviesStateDump {
        val selectedCategory = ready.categories.firstOrNull { it.id == ready.selectedCategoryId }
        val details = ready.selectedMovieDetails()
        return DebugMoviesStateDump(
            cacheSource = ready.source,
            cacheAgeMs = ready.cacheAgeMs,
            cappedItemCount = ready.movies.size,
            selectedCategoryId = ready.selectedCategoryId,
            selectedCategoryName = selectedCategory?.name ?: "All",
            selectedMovieStreamId = details?.streamId,
            selectedMovieTitle = details?.title,
            selectedMovieCategory = details?.categoryName,
            selectedSortMode = ready.sortMode.storageKey,
            selectedSortModeLabel = ready.sortMode.displayName,
            searchQuery = ready.searchQuery.takeIf { it.isNotBlank() },
            watchlistCount = ready.watchlistStreamIds.size,
            selectedMovieIsWatchlisted = details?.isWatchlisted ?: false,
            containerExtension = details?.containerExtension,
            redactedStreamUrl = details?.redactedStreamUrl,
        )
    }
}
