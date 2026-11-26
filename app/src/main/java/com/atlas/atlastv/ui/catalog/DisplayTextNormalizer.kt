package com.atlas.atlastv.ui.catalog

data class DisplayTitle(
    val cleanTitle: String,
    val metadataLabel: String? = null,
)

fun cleanProviderTitle(rawTitle: String): DisplayTitle {
    val original = rawTitle.trim()
    if (original.isBlank()) return DisplayTitle(cleanTitle = "Untitled")

    val prefixTokens = mutableListOf<String>()
    var cleaned = original
    while (prefixTokens.size < 4) {
        val prefix = providerLeadingToken.find(cleaned) ?: break
        val token = prefix.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: break
        if (!token.isProviderPrefixToken()) break
        val next = cleaned.substring(prefix.range.last + 1).trim()
        // Even an explicit marker must leave an actual title, not just its release year.
        if (displayTitle(next).isBlank()) break
        prefixTokens += token
        cleaned = next
    }
    cleaned = cleaned
        .replace(providerTrailingRegion, "")
        .replace(providerWhitespace, " ")
        .trim(' ', '-', '|', '/')
        .ifBlank { original }

    val metadata = prefixTokens
        .filterNot { it.equals("NF", ignoreCase = true) }
        .joinToString(" / ")
        .takeIf { it.isNotBlank() }
    return DisplayTitle(cleanTitle = cleaned, metadataLabel = metadata)
}

fun cleanProviderCategory(rawCategory: String): String =
    rawCategory.trim()
        .replace(providerCategoryPrefix, "")
        .replace(providerWhitespace, " ")
        .trim(' ', '-', '|', '/')
        .ifBlank { rawCategory.trim().ifBlank { "Uncategorized" } }

fun searchableTitleText(originalTitle: String): String {
    val clean = cleanProviderTitle(originalTitle).cleanTitle
    return if (clean.equals(originalTitle, ignoreCase = true)) originalTitle else "$clean $originalTitle"
}

private fun String.isProviderPrefixToken(): Boolean {
    val upper = uppercase()
    return upper in languagePrefixTokens ||
        upper in qualityPrefixTokens ||
        upper in providerPrefixTokens
}

private val languagePrefixTokens = setOf(
    "EN", "US", "USA", "UK", "CA", "AU", "ES", "FR", "RU", "JP", "PK", "DE", "IT", "PT", "BR", "KR", "CN", "IN",
)
private val qualityPrefixTokens = setOf("4K", "FHD", "HD", "UHD", "1080P", "2160P", "3840P")
private val providerPrefixTokens = setOf("NF", "NETFLIX", "AMZ", "PRIME", "D+", "DISNEY", "MAX", "HULU", "P+", "APPLE", "VP")

// Shared compiled expressions keep full-library metadata passes inexpensive.
private val providerLeadingToken = Regex("""^\s*(?:\[([A-Za-z0-9+]{1,12})\]\s*(?:[-|/:]\s*)?|\(([A-Za-z0-9+]{1,12})\)\s*(?:[-|/:]\s*)?|([A-Za-z0-9+]{1,12})\s*[-|/:]\s*)""")
private val providerTrailingRegion = Regex("""\s*\((?:EN|US|USA|UK|CA|AU)\)\s*$""", RegexOption.IGNORE_CASE)
private val providerWhitespace = Regex("""\s+""")
private val providerCategoryPrefix = Regex("""^\s*(?:EN|US|USA|UK|CA|AU|4K|FHD|HD|UHD|1080P|2160P|3840P)\s*[-|/]\s*""", RegexOption.IGNORE_CASE)
