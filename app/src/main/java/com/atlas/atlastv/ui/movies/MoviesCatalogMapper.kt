package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import com.google.gson.JsonParser
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw

class MoviesCatalogMapper(
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    fun mapCachedCatalogJson(
        json: String,
        credentials: XtreamCredentials?,
        maxItems: Int = MaxMovies,
    ): MoviesCatalogState.Ready {
        val root = JsonParser.parseString(json).asJsonObject
        val source = root.get("catalogSource")?.asString?.ifBlank { "cached" } ?: "cached"
        val items = root.getAsJsonArray("playableVodItems") ?: error("Cached catalog has no VOD items.")
        val movies = items.asSequence()
            .mapNotNull { element ->
                val item = element.asJsonObject
                val streamId = item.get("streamId")?.asInt ?: return@mapNotNull null
                val extension = item.get("containerExtension")?.asString?.trim()?.trimStart('.')
                    ?.takeIf { it.isNotBlank() } ?: DefaultVodExtension
                val redactedUrl = item.get("redactedUrl")?.asString?.takeIf { it.isNotBlank() }
                    ?: credentials?.let { endpointBuilder.buildRedactedVodStream(it, streamId, extension) }
                    ?: "fixture://debug_catalog/movie/$streamId.$extension"
                MovieItem(
                    title = item.get("title")?.asString?.takeIf { it.isNotBlank() }
                        ?: item.get("name")?.asString?.takeIf { it.isNotBlank() }
                        ?: "Movie $streamId",
                    categoryName = item.get("categoryName")?.asString?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                    streamId = streamId,
                    containerExtension = extension,
                    redactedStreamUrl = redactedUrl,
                    posterImage = atlasImageRefFromRaw(item.firstString("posterImage", "streamIcon", "stream_icon", "iconUrl", "cover")),
                )
            }
            .take(maxItems)
            .toList()

        return MoviesCatalogState.Ready(
            source = source,
            cacheAgeMs = null,
            movies = movies,
            categories = movies.toMovieCategories(),
        )
    }

    companion object {
        const val MaxMovies = 120
        private const val DefaultVodExtension = "mp4"
    }
}

private fun com.google.gson.JsonObject.firstString(vararg names: String): String? {
    for (name in names) {
        val value = get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        if (value != null) return value
    }
    return null
}

fun List<MovieItem>.toMovieCategories(): List<MovieCategory> {
    return toMovieCategories(emptySet())
}

fun List<MovieItem>.toMovieCategories(watchlistStreamIds: Set<Int>): List<MovieCategory> {
    val grouped = groupBy { it.categoryName.ifBlank { "Uncategorized" } }
        .map { (name, movies) -> MovieCategory(id = name, name = name, itemCount = movies.size) }
        .sortedBy { it.name.lowercase() }
    return listOf(
        MovieCategory(id = MovieCategoryIds.All, name = "All", itemCount = size),
        MovieCategory(id = MovieCategoryIds.Watchlist, name = "Watchlist", itemCount = watchlistStreamIds.size),
    ) + grouped
}
