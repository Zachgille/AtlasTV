package com.atlas.atlastv.playlist.indexer

import com.atlas.atlastv.playlist.parser.PlaylistItem

class PlaylistIndexer {
    fun index(items: List<PlaylistItem>): PlaylistIndexResult {
        val indexedItems = items.map { item -> indexItem(item) }
            .sortedBy { it.normalizedTitle.lowercase() }

        val liveItems = indexedItems.filter { it.contentType == PlaylistContentType.Live }
        val movieItems = indexedItems.filter { it.contentType == PlaylistContentType.Movie }
        val seriesItems = indexedItems.filter { it.contentType == PlaylistContentType.Series }
        val unknownItems = indexedItems.filter { it.contentType == PlaylistContentType.Unknown }

        return PlaylistIndexResult(
            allItems = indexedItems,
            liveItems = liveItems,
            movieItems = movieItems,
            seriesItems = seriesItems,
            unknownItems = unknownItems,
            liveGroups = groupItems(liveItems),
            movieGroups = groupItems(movieItems),
            seriesGroups = groupItems(seriesItems),
            unknownGroups = groupItems(unknownItems),
            totalCount = indexedItems.size,
            liveCount = liveItems.size,
            movieCount = movieItems.size,
            seriesCount = seriesItems.size,
            unknownCount = unknownItems.size,
        )
    }

    private fun indexItem(item: PlaylistItem): IndexedPlaylistItem {
        val seriesMetadata = detectSeries(item.displayName)
        val category = item.groupTitle?.trim().takeUnless { it.isNullOrBlank() }
        val contentType = when {
            seriesMetadata != null -> PlaylistContentType.Series
            category.matchesAnyKeyword(MovieCategoryKeywords) -> PlaylistContentType.Movie
            category.matchesAnyKeyword(LiveCategoryKeywords) -> PlaylistContentType.Live
            else -> PlaylistContentType.Unknown
        }

        return IndexedPlaylistItem(
            sourceItem = item,
            contentType = contentType,
            normalizedTitle = normalizeTitle(item.displayName),
            category = category,
            seriesName = seriesMetadata?.seriesName,
            seasonNumber = seriesMetadata?.seasonNumber,
            episodeNumber = seriesMetadata?.episodeNumber,
        )
    }

    private fun detectSeries(displayName: String): SeriesMetadata? {
        val normalizedTitle = normalizeTitle(displayName)
        for (pattern in SeriesPatterns) {
            val match = pattern.find(normalizedTitle) ?: continue
            val seriesName = normalizeSeriesName(normalizedTitle.substring(0, match.range.first))
            if (seriesName.isBlank()) continue

            return SeriesMetadata(
                seriesName = seriesName,
                seasonNumber = match.groupValues[1].toIntOrNull() ?: continue,
                episodeNumber = match.groupValues[2].toIntOrNull() ?: continue,
            )
        }
        return null
    }

    private fun groupItems(items: List<IndexedPlaylistItem>): List<PlaylistCategoryGroup> =
        items
            .groupBy { it.category ?: UNCATEGORIZED }
            .map { (name, groupItems) ->
                PlaylistCategoryGroup(
                    name = name,
                    items = groupItems.sortedBy { it.normalizedTitle.lowercase() },
                )
            }
            .sortedBy { it.name.lowercase() }

    private fun normalizeTitle(title: String): String =
        title.trim()
            .replace(RepeatedWhitespaceRegex, " ")

    private fun normalizeSeriesName(title: String): String =
        normalizeTitle(title)
            .trim(' ', '-', ':', '|', '/', '\\')
            .let(::normalizeTitle)

    private fun String?.matchesAnyKeyword(keywords: Set<String>): Boolean {
        if (this.isNullOrBlank()) return false
        val category = trim()
        return keywords.any { keyword -> category.containsKeyword(keyword) }
    }

    private fun String.containsKeyword(keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val escapedKeyword = Regex.escape(keyword.trim())
        val regex = Regex("(?i)(^|[^A-Za-z0-9])$escapedKeyword($|[^A-Za-z0-9])")
        return regex.containsMatchIn(this)
    }

    private data class SeriesMetadata(
        val seriesName: String,
        val seasonNumber: Int,
        val episodeNumber: Int,
    )

    private companion object {
        private const val UNCATEGORIZED = "Uncategorized"

        private val RepeatedWhitespaceRegex = Regex("\\s+")
        private val SeriesPatterns = listOf(
            Regex("(?i)\\bS(\\d{1,2})E(\\d{1,3})\\b"),
            Regex("(?i)\\bSeason\\s+(\\d{1,2})\\s+Episode\\s+(\\d{1,3})\\b"),
            Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b"),
        )

        private val MovieCategoryKeywords = setOf(
            "Movie",
            "Movies",
            "Film",
            "Films",
            "Cinema",
            "VOD",
        )

        private val LiveCategoryKeywords = setOf(
            "Live",
            "TV",
            "News",
            "Sports",
            "Entertainment",
            "Kids",
            "Documentary",
            "USA",
            "US",
            "UK",
            "Canada",
            "Australia",
            "English",
        )
    }
}
