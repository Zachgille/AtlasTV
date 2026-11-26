package com.atlas.atlastv.data.db

import androidx.room.Embedded
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy
import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.ui.catalog.cleanProviderTitle

data class MovieWatchlistEntry(
    @Embedded(prefix = "saved_") val saved: MovieWatchlistEntity,
    @Embedded val catalog: SafeCatalogItemEntity?,
) {
    fun toMovieItem(): MovieItem = catalog?.toMovieItem("room://safe_catalog/vod/${saved.streamId}.${catalog.containerExtension ?: "mp4"}")
        ?: MovieItem(
            title = saved.title?.takeIf { it.isNotBlank() && !EnglishCatalogPolicy.excludesTitle(it) && !PlaylistCategoryFilter().excludes(saved.categoryName.orEmpty()) }
                ?.let { cleanProviderTitle(it).cleanTitle } ?: "Unavailable movie",
            categoryName = "No longer in your library", streamId = saved.streamId,
            containerExtension = saved.containerExtension ?: "mp4", redactedStreamUrl = "room://unavailable", available = false)
}
data class MovieWatchlistWindow(val total: Int, val offset: Int, val anchor: Int, val items: List<MovieWatchlistEntry>)
