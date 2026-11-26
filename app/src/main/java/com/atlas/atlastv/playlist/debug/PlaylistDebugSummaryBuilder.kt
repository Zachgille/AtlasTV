package com.atlas.atlastv.playlist.debug

import com.atlas.atlastv.playlist.filter.PlaylistFilterResult
import com.atlas.atlastv.playlist.indexer.PlaylistIndexResult
import com.atlas.atlastv.playlist.parser.PlaylistParseResult

class PlaylistDebugSummaryBuilder {
    fun build(
        redactedUrl: String,
        downloadedText: String,
        downloadedByteCount: Long?,
        parseResult: PlaylistParseResult,
        filterResult: PlaylistFilterResult,
        indexResult: PlaylistIndexResult,
    ): PlaylistDebugSummary =
        PlaylistDebugSummary(
            redactedUrl = redactedUrl,
            downloadedCharacterCount = downloadedText.length,
            downloadedByteCount = downloadedByteCount,
            parsedItemCount = parseResult.items.size,
            parseWarningCount = parseResult.warnings.size,
            keptItemCount = filterResult.keptCount,
            excludedItemCount = filterResult.excludedCount,
            liveCount = indexResult.liveCount,
            movieCount = indexResult.movieCount,
            seriesCount = indexResult.seriesCount,
            unknownCount = indexResult.unknownCount,
            topKeptCategories = parseCategoryCounts(filterResult.keptItems.map { it.groupTitle }),
            topExcludedCategories = filterResult.excludedCategoryCounts
                .toCategoryCounts()
                .take(MAX_CATEGORY_COUNTS),
        )

    private fun parseCategoryCounts(categories: List<String?>): List<PlaylistDebugCategoryCount> =
        categories
            .groupingBy { it?.trim().takeUnless { category -> category.isNullOrBlank() } ?: UNCATEGORIZED }
            .eachCount()
            .toCategoryCounts()
            .take(MAX_CATEGORY_COUNTS)

    private fun Map<String, Int>.toCategoryCounts(): List<PlaylistDebugCategoryCount> =
        entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { PlaylistDebugCategoryCount(category = it.key, count = it.value) }

    private companion object {
        private const val MAX_CATEGORY_COUNTS = 8
        private const val UNCATEGORIZED = "Uncategorized"
    }
}
