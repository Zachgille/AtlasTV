package com.atlas.atlastv.ui.catalog

internal fun cleanDetailDescription(value: String?, limit: Int = 4_000): String? = value
    ?.take(16_000)
    ?.replace(Regex("<[^>]*>"), " ")
    ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")
    ?.replace("&nbsp;", " ")?.replace("&lt;", "<")?.replace("&gt;", ">")
    ?.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
    ?.replace(Regex("[\\s\\p{Cc}]+"), " ")?.trim()?.take(limit)
    ?.takeUnless { it.isBlank() || it.lowercase() in setOf("null", "n/a", "none") }

internal fun detailReleaseYear(value: String?): String? =
    value?.trim()?.take(4)?.takeIf { it.length == 4 && it.toIntOrNull() in 1700..2099 }
