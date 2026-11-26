package com.atlas.atlastv.ui.search

import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.toMovieItem
import com.atlas.atlastv.data.db.toSeriesItem

/** Search reads the full safe Room catalog without loading either browse screen. */
class RoomUnifiedSearchSource(private val catalogRepository: SafeCatalogRepository) {
    suspend fun search(query: String): UnifiedSearchState.Ready {
        val movies = catalogRepository.searchMovieEntities(query, MaxItemsPerRow).map { entity ->
            val extension = entity.containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() } ?: "mp4"
            entity.toMovieItem("room://safe_catalog/vod/${entity.itemId}.$extension")
        }
        val series = catalogRepository.searchSeriesEntities(query, MaxItemsPerRow).map { it.toSeriesItem() }
        val movieCount = catalogRepository.countSearchMovies(query)
        val seriesCount = catalogRepository.countSearchSeries(query)
        return UnifiedSearchState.Ready(
            query = query,
            movieCount = movieCount,
            seriesCount = seriesCount,
            rows = listOfNotNull(
                movies.takeIf { it.isNotEmpty() }?.let { matches ->
                    UnifiedSearchRow("unified_search_movies", "Movies", matches.map(UnifiedSearchItem::Movie), movieCount)
                },
                series.takeIf { it.isNotEmpty() }?.let { matches ->
                    UnifiedSearchRow("unified_search_shows", "Shows", matches.map(UnifiedSearchItem::Show), seriesCount)
                },
            ),
        )
    }

    private companion object { const val MaxItemsPerRow = 18 }
}
