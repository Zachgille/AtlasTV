package com.atlas.atlastv.ui.catalog

private val releaseYear = Regex("\\s*\\((19\\d{2}|20\\d{2})\\)")
/** Presentation only: preserve provider identity and year-based search in the catalog. */
fun displayTitle(title: String): String = titleWithoutFormatSuffixes(title).replace(releaseYear, "").trim()
fun releaseYearLabel(title: String): String? = releaseYear.find(title)?.groupValues?.get(1)

private val formatToken = "(?:Dolby Vision|HDR10\\+|HDR10|HDR|HEVC|H\\.?26[45]|X26[45]|AV1|4320P|2160P|1080P|720P|8K|4K|UHD|FHD|HD|SD)"
private val formatGroup = "$formatToken(?:(?:\\s*[,/|+]\\s*|\\s+)$formatToken)*"
private val markedFormatSuffixes = listOf(
    Regex("\\s*\\[($formatGroup)\\]\\s*$", RegexOption.IGNORE_CASE),
    Regex("\\s*\\(($formatGroup)\\)\\s*$", RegexOption.IGNORE_CASE),
    Regex("\\s+[-–—|]\\s+($formatGroup)\\s*$", RegexOption.IGNORE_CASE),
)
// Bare words need the explicit release-year boundary: 'The Road to 4K' stays intact.
private val yearFormatSuffix = Regex("(\\((?:19|20)\\d{2}\\))\\s+($formatGroup)\\s*$", RegexOption.IGNORE_CASE)

private fun titleWithoutFormatSuffixes(title: String): String {
    var remaining = title.trim()
    while (true) {
        val marked = markedFormatSuffixes.firstNotNullOfOrNull { it.find(remaining) }
        val bare = if (marked == null) yearFormatSuffix.find(remaining) else null
        val match = marked ?: bare ?: break
        val cut = match.range.first + if (bare != null) match.groupValues[1].length else 0
        val next = remaining.substring(0, cut).trimEnd()
        // A format-looking title alone is still a title.
        if (next.replace(releaseYear, "").isBlank()) break
        remaining = next
    }
    return remaining
}

fun playbackDisplayTitle(title: String): String = displayTitle(title)
    .replace(Regex("\\s*[-–—]\\s*S0*(\\d+)E0*(\\d+)\\s*[-–—]\\s*", RegexOption.IGNORE_CASE)) { " · S${it.groupValues[1]} E${it.groupValues[2]} · " }
