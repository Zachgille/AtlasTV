package com.atlas.atlastv.data.db

import androidx.room.Embedded
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy
import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.ui.catalog.cleanProviderTitle

data class SeriesWatchlistEntry(
    @Embedded(prefix = "saved_") val saved: SeriesWatchlistEntity,
    @Embedded val catalog: SafeCatalogItemEntity?,
) {
    fun toSeriesItem(): SeriesItem = catalog?.toSeriesItem()
        ?: SeriesItem(
            name = saved.title?.takeIf { it.isNotBlank() && !EnglishCatalogPolicy.excludesTitle(it) && !PlaylistCategoryFilter().excludes(saved.categoryName.orEmpty()) }
                ?.let { cleanProviderTitle(it).cleanTitle } ?: "Unavailable show",
            categoryName = "No longer in your library", seriesId = saved.seriesId,
            available = false)
}
data class SeriesWatchlistWindow(val total: Int, val offset: Int, val anchor: Int, val items: List<SeriesWatchlistEntry>)
