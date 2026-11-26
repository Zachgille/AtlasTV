package com.atlas.atlastv.playlist.filter

import com.atlas.atlastv.playlist.parser.PlaylistItem

// Eligibility changes must also bump CatalogLanguageVerification.Revision to recheck saved catalogs.
class PlaylistCategoryFilter {
    fun excludes(category: String): Boolean = decideCategory(category, PlaylistFilterConfig()) == PlaylistFilterReason.ExcludedCategoryKeyword
    fun filter(
        items: List<PlaylistItem>,
        config: PlaylistFilterConfig = PlaylistFilterConfig(),
    ): PlaylistFilterResult {
        val keptItems = mutableListOf<PlaylistItem>()
        val excludedItems = mutableListOf<PlaylistFilterDecision>()

        items.forEach { item ->
            val category = item.groupTitle?.trim().orEmpty()
            val reason = decideCategory(category, config)

            when (reason) {
                PlaylistFilterReason.ExcludedCategoryKeyword,
                PlaylistFilterReason.UncategorizedExcluded,
                -> excludedItems += PlaylistFilterDecision(item = item, reason = reason)

                PlaylistFilterReason.AllowedCategoryKeyword,
                PlaylistFilterReason.UncategorizedKept,
                PlaylistFilterReason.NoRuleMatchedKept,
                -> keptItems += item
            }
        }

        return PlaylistFilterResult(
            keptItems = keptItems,
            excludedItems = excludedItems,
            totalCount = items.size,
            keptCount = keptItems.size,
            excludedCount = excludedItems.size,
            excludedCategoryCounts = excludedItems
                .groupingBy { it.item.groupTitle?.trim().takeUnless { category -> category.isNullOrBlank() } ?: UNCATEGORIZED }
                .eachCount(),
        )
    }

    private fun decideCategory(
        category: String,
        config: PlaylistFilterConfig,
    ): PlaylistFilterReason {
        if (category.isBlank()) {
            return if (config.keepUncategorized) {
                PlaylistFilterReason.UncategorizedKept
            } else {
                PlaylistFilterReason.UncategorizedExcluded
            }
        }

        val normalized = category.normalizedCategoryText()
        val leadingPrefix = normalized.substringBefore(" ", "").trim()
        if (EnglishCatalogPolicy.excludesCategory(category) || leadingPrefix in excludedLeadingPrefixes || normalized.containsWorkoutKeyword()) {
            return PlaylistFilterReason.ExcludedCategoryKeyword
        }

        if (category.matchesAnyKeyword(config.excludedCategoryKeywords)) {
            return PlaylistFilterReason.ExcludedCategoryKeyword
        }

        if (category.matchesAnyKeyword(config.allowedCategoryKeywords)) {
            return PlaylistFilterReason.AllowedCategoryKeyword
        }

        return PlaylistFilterReason.NoRuleMatchedKept
    }

    private fun String.matchesAnyKeyword(keywords: Set<String>): Boolean {
        val category = lowercase()
        return keywords
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { keyword -> category.contains(keyword) }
    }

    private fun String.normalizedCategoryText(): String =
        uppercase()
            .replace(Regex("""[^\p{L}\p{N}+]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.containsWorkoutKeyword(): Boolean =
        listOf("WORKOUT", "FITNESS", "EXERCISE", "YOGA", "PILATES", "GYM").any { contains(it) }

    private companion object {
        private const val UNCATEGORIZED = "Uncategorized"
        private val excludedLeadingPrefixes = setOf(
            "ES", "FR", "RU", "JP", "PK", "DE", "IT", "PT", "BR", "KR", "CN", "TR", "AR", "IN", "VP",
        )
    }
}
