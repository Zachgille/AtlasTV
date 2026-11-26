package com.atlas.atlastv.xtream.mapper

import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.playlist.parser.PlaylistItem
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamLiveStream
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream

class XtreamCatalogMapper(
    private val categoryFilter: PlaylistCategoryFilter = PlaylistCategoryFilter(),
) {
    fun map(
        liveCategories: List<XtreamCategory>,
        liveStreams: List<XtreamLiveStream>,
        vodCategories: List<XtreamCategory>,
        vodStreams: List<XtreamVodStream>,
        seriesCategories: List<XtreamCategory>,
        series: List<XtreamSeries>,
    ): AtlasCatalog {
        val liveCategoryNames = liveCategories.categoryNameMap()
        val vodCategoryNames = vodCategories.categoryNameMap()
        val seriesCategoryNames = seriesCategories.categoryNameMap()

        val keptItems = mutableListOf<AtlasCatalogItem>()
        val keptCategories = mutableListOf<String>()
        val excludedCategories = mutableListOf<String>()
        var keptLiveCount = 0
        var keptVodCount = 0
        var keptSeriesCount = 0
        var excludedItemCount = 0

        liveStreams.forEach { stream ->
            val categoryName = liveCategoryNames[stream.categoryId]
            if (isExcluded(categoryName)) {
                excludedItemCount++
                excludedCategories += categoryName.safeCategory()
            } else {
                keptLiveCount++
                keptCategories += categoryName.safeCategory()
                keptItems += AtlasCatalogItem(
                    contentType = AtlasContentType.Live,
                    id = stream.streamId?.toString() ?: stream.name.orEmpty(),
                    name = stream.name.orEmpty(),
                    categoryId = stream.categoryId,
                    categoryName = categoryName,
                    iconUrl = stream.streamIcon,
                    streamId = stream.streamId,
                    seriesId = null,
                    containerExtension = null,
                )
            }
        }

        vodStreams.forEach { stream ->
            val categoryName = vodCategoryNames[stream.categoryId]
            if (isExcluded(categoryName)) {
                excludedItemCount++
                excludedCategories += categoryName.safeCategory()
            } else {
                keptVodCount++
                keptCategories += categoryName.safeCategory()
                keptItems += AtlasCatalogItem(
                    contentType = AtlasContentType.Movie,
                    id = stream.streamId?.toString() ?: stream.name.orEmpty(),
                    name = stream.name.orEmpty(),
                    categoryId = stream.categoryId,
                    categoryName = categoryName,
                    iconUrl = stream.streamIcon,
                    streamId = stream.streamId,
                    seriesId = null,
                    containerExtension = stream.containerExtension,
                )
            }
        }

        series.forEach { item ->
            val categoryName = seriesCategoryNames[item.categoryId]
            if (isExcluded(categoryName)) {
                excludedItemCount++
                excludedCategories += categoryName.safeCategory()
            } else {
                keptSeriesCount++
                keptCategories += categoryName.safeCategory()
                keptItems += AtlasCatalogItem(
                    contentType = AtlasContentType.Series,
                    id = item.seriesId?.toString() ?: item.name.orEmpty(),
                    name = item.name.orEmpty(),
                    categoryId = item.categoryId,
                    categoryName = categoryName,
                    iconUrl = item.cover,
                    streamId = null,
                    seriesId = item.seriesId,
                    containerExtension = null,
                )
            }
        }

        val excludedCategoryNames = (liveCategories + vodCategories + seriesCategories)
            .mapNotNull { it.categoryName?.trim()?.takeIf(String::isNotBlank) }
            .filter(::isExcluded)
            .distinctBy { it.lowercase() }

        return AtlasCatalog(
            items = keptItems,
            summary = AtlasCatalogSummary(
                liveCategoryCount = liveCategories.size,
                liveStreamCount = liveStreams.size,
                vodCategoryCount = vodCategories.size,
                vodStreamCount = vodStreams.size,
                seriesCategoryCount = seriesCategories.size,
                seriesCount = series.size,
                keptLiveStreamCount = keptLiveCount,
                keptVodStreamCount = keptVodCount,
                keptSeriesCount = keptSeriesCount,
                excludedCategoryCount = excludedCategoryNames.size,
                excludedItemCount = excludedItemCount,
                topKeptCategories = keptCategories.toCounts(),
                topExcludedCategories = excludedCategories.toCounts(),
            ),
        )
    }

    private fun isExcluded(categoryName: String?): Boolean {
        val filterResult = categoryFilter.filter(
            listOf(
                PlaylistItem(
                    tvgId = null,
                    tvgName = null,
                    tvgLogo = null,
                    groupTitle = categoryName,
                    displayName = categoryName.safeCategory(),
                    streamUrl = "",
                    rawExtinf = "",
                ),
            ),
        )
        return filterResult.excludedCount > 0
    }

    private fun List<XtreamCategory>.categoryNameMap(): Map<String?, String?> =
        associate { it.categoryId to it.categoryName }

    private fun String?.safeCategory(): String =
        this?.trim()?.takeIf(String::isNotBlank) ?: UNCATEGORIZED

    private fun List<String>.toCounts(): List<CategoryCount> =
        groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(8)
            .map { CategoryCount(category = it.key, count = it.value) }

    private companion object {
        private const val UNCATEGORIZED = "Uncategorized"
    }
}
