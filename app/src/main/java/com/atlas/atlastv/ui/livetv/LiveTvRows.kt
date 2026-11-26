package com.atlas.atlastv.ui.livetv

data class LiveTvChannelRowGroup(
    val id: String,
    val title: String,
    val channels: List<LiveTvChannel>,
)

object LiveTvRowIds {
    const val Favorites = "favorites"
    const val Recent = "recent"
    const val Search = "search"
    const val CategoryPrefix = "category:"
}

fun LiveTvCatalogState.Ready.channelRows(maxRows: Int = 8, maxItemsPerRow: Int = 16): List<LiveTvChannelRowGroup> {
    if (searchQuery.isNotBlank()) {
        return listOf(
            LiveTvChannelRowGroup(
                id = LiveTvRowIds.Search,
                title = "Search Results",
                channels = visibleChannels().take(maxItemsPerRow),
            ),
        )
    }

    val rows = mutableListOf<LiveTvChannelRowGroup>()
    val favorites = channels
        .filter { it.streamId in favoriteStreamIds }
        .sortedForLiveTv(sortMode, favoriteStreamIds)
        .take(maxItemsPerRow)
    if (favorites.isNotEmpty()) {
        rows += LiveTvChannelRowGroup(LiveTvRowIds.Favorites, "Favorites", favorites)
    }

    val recent = channels
        .sortedForLiveTv(sortMode, favoriteStreamIds)
        .take(maxItemsPerRow)
    if (recent.isNotEmpty()) {
        rows += LiveTvChannelRowGroup(LiveTvRowIds.Recent, "Recent Channels", recent)
    }

    val selectedCategoryRows = categories
        .asSequence()
        .filter { it.id != LiveTvCategoryIds.All && it.id != LiveTvCategoryIds.Favorites }
        .sortedWith(
            compareByDescending<LiveTvCategory> { it.id == selectedCategoryId }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        .mapNotNull { category ->
            val rowChannels = channelsForCategory(category.id).take(maxItemsPerRow)
            if (rowChannels.isEmpty()) {
                null
            } else {
                LiveTvChannelRowGroup(
                    id = LiveTvRowIds.CategoryPrefix + category.id,
                    title = cleanLiveTvRowTitle(category.name),
                    channels = rowChannels,
                )
            }
        }
        .take(maxRows)
        .toList()

    return (rows + selectedCategoryRows).distinctBy { it.id }
}

fun cleanLiveTvRowTitle(raw: String): String {
    val normalized = raw
        .trim()
        .replace(Regex("""^\s*(EN|US|UK|CA|AU)\s*[-|:]\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .takeIf { it.isNotBlank() }
        ?: "Channels"
    return normalized
        .lowercase()
        .split(' ')
        .joinToString(" ") { word ->
            when {
                word.length <= 2 -> word.uppercase()
                else -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
}
