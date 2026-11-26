package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.google.gson.JsonParser

class SeriesCatalogMapper {
    fun mapCachedCatalogJson(
        json: String,
        maxItems: Int = MaxSeries,
    ): SeriesCatalogState.Ready {
        val root = JsonParser.parseString(json).asJsonObject
        val source = root.get("catalogSource")?.asString?.ifBlank { "cached" } ?: "cached"
        val items = root.getAsJsonArray("playableSeriesItems") ?: error("Cached catalog has no Series items.")
        val series = items.asSequence()
            .mapNotNull { element ->
                val item = element.asJsonObject
                val seriesId = item.get("seriesId")?.asInt ?: return@mapNotNull null
                SeriesItem(
                    name = item.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Series $seriesId",
                    categoryName = item.get("categoryName")?.asString?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                    seriesId = seriesId,
                    posterImage = atlasImageRefFromRaw(item.firstString("posterImage", "cover", "iconUrl", "seriesCover")),
                )
            }
            .take(maxItems)
            .toList()

        return SeriesCatalogState.Ready(
            source = source,
            cacheAgeMs = null,
            series = series,
            categories = series.toSeriesCategories(),
        )
    }

    companion object {
        const val MaxSeries = 120
    }
}

private fun com.google.gson.JsonObject.firstString(vararg names: String): String? {
    for (name in names) {
        val value = get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        if (value != null) return value
    }
    return null
}

fun List<SeriesItem>.toSeriesCategories(): List<SeriesCategory> {
    val grouped = groupBy { it.categoryName.ifBlank { "Uncategorized" } }
        .map { (name, items) -> SeriesCategory(id = name, name = name, itemCount = items.size) }
        .sortedBy { it.name.lowercase() }
    return listOf(SeriesCategory(id = SeriesCategoryIds.All, name = "All", itemCount = size)) + grouped
}

fun List<SeriesItem>.toSeriesCategories(watchlistSeriesIds: Set<Int>): List<SeriesCategory> =
    listOf(SeriesCategory(id = SeriesCategoryIds.Watchlist, name = "Watchlist", itemCount = watchlistSeriesIds.size)) +
        toSeriesCategories()
