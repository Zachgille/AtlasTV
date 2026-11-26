package com.atlas.atlastv.ui.livetv

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import com.google.gson.JsonParser

class LiveTvCatalogMapper(
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    fun mapCachedCatalogJson(
        json: String,
        credentials: XtreamCredentials?,
        maxItems: Int = MaxLiveChannels,
    ): LiveTvCatalogState.Ready {
        val root = JsonParser.parseString(json).asJsonObject
        val source = root.get("catalogSource")?.asString?.ifBlank { "cached" } ?: "cached"
        val items = root.getAsJsonArray("playableLiveItems") ?: error("Cached catalog has no live items.")
        val channels = items.asSequence()
            .mapNotNull { element ->
                val item = element.asJsonObject
                val streamId = item.get("streamId")?.asInt ?: return@mapNotNull null
                val extension = item.get("containerExtension")?.asString?.trim()?.trimStart('.')
                    ?.takeIf { it.isNotBlank() } ?: DefaultLiveExtension
                val redactedUrl = item.get("redactedUrl")?.asString?.takeIf { it.isNotBlank() }
                    ?: credentials?.let { endpointBuilder.buildRedactedLiveStream(it, streamId, extension) }
                    ?: "fixture://debug_catalog/live/$streamId.$extension"
                LiveTvChannel(
                    name = item.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Live stream $streamId",
                    categoryName = item.get("categoryName")?.asString?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                    streamId = streamId,
                    containerExtension = extension,
                    redactedStreamUrl = redactedUrl,
                )
            }
            .take(maxItems)
            .toList()

        return LiveTvCatalogState.Ready(
            source = source,
            cacheAgeMs = null,
            channels = channels,
            categories = channels.toLiveTvCategories(),
        )
    }

    companion object {
        const val MaxLiveChannels = 120
        private const val DefaultLiveExtension = "ts"
    }
}

fun List<LiveTvChannel>.toLiveTvCategories(favoriteStreamIds: Set<Int> = emptySet()): List<LiveTvCategory> {
    val groupedCategories = groupBy { it.categoryName.ifBlank { "Uncategorized" } }
        .map { (name, channels) -> LiveTvCategory(id = name, name = name, channelCount = channels.size) }
        .sortedBy { it.name.lowercase() }
    return listOf(
        LiveTvCategory(id = LiveTvCategoryIds.All, name = "All", channelCount = size),
        LiveTvCategory(id = LiveTvCategoryIds.Favorites, name = "Favorites", channelCount = count { it.streamId in favoriteStreamIds }),
    ) + groupedCategories
}
