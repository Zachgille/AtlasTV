package com.atlas.atlastv.playlist.filter

/** Explicit provider language signals. A brand or quality label never overrides them. */
// Eligibility changes must also bump CatalogLanguageVerification.Revision to recheck saved catalogs.
object EnglishCatalogPolicy {
    private val foreignCodes = setOf("ES", "FR", "RU", "JP", "PK", "DE", "IT", "PT", "BR", "KR", "CN", "TR", "AR", "IN", "VP", "NL", "PL", "GR", "BE", "QC", "IL", "LA", "SE", "SV", "NO", "DA", "DK", "FI", "IS")
    private val foreignWords = setOf(
        "NORDIC", "VIAPLAY", "SWEDISH", "SWEDEN", "SVENSKA", "NORWEGIAN", "NORWAY", "DANISH", "DENMARK", "FINNISH", "FINLAND", "ICELANDIC",
        "ESPAÑA", "ESPANA", "SPAIN", "FRANCE", "NETHERLANDS", "ITALY", "GREECE", "HEBREW", "TURKSIH", "SOMALIA",
    )
    private val words = Regex("[^\\p{L}\\p{N}]+")
    fun excludesCategory(category: String): Boolean {
        val tokens = category.uppercase().split(words).filter { it.isNotBlank() }
        return tokens.firstOrNull() in foreignCodes || tokens.any { it in foreignWords } || hasForeignLeadingTag(category)
    }
    private val foreignPrefix = Regex("^\\s*\\[?(?:${foreignCodes.joinToString("|")})\\]?\\s*[-:|]", RegexOption.IGNORE_CASE)
    // Exact labels only. Names/accents and words inside an ordinary title are not language evidence.
    private val extendedCodes = setOf("SPA", "FRA", "FRE", "DEU", "GER", "ITA", "POR", "RUS", "JPN", "KOR", "ZHO", "CHI", "ARA", "HIN", "URD", "TUR", "NLD", "DUT", "POL", "ELL", "GRE", "HEB", "SWE", "NOR", "DAN", "FIN", "ISL", "ICE")
    private val languageNames = setOf("SPANISH", "FRENCH", "GERMAN", "ITALIAN", "PORTUGUESE", "RUSSIAN", "JAPANESE", "KOREAN", "CHINESE", "MANDARIN", "CANTONESE", "ARABIC", "HINDI", "URDU", "TURKISH", "DUTCH", "POLISH", "GREEK", "HEBREW", "SWEDISH", "NORWEGIAN", "DANISH", "FINNISH", "ICELANDIC")
    private val languageLabels = foreignCodes + extendedCodes + languageNames
    private val neutralLabels = setOf("EN", "ENG", "ENGLISH", "US", "USA", "UK", "GB", "CA", "CAN", "AU", "AUS",
        "4K", "FHD", "HD", "SD", "UHD", "HDR", "HEVC", "1080P", "2160P", "3840P",
        "NF", "NETFLIX", "AMZ", "AMAZON", "PRIME", "D+", "DISNEY", "MAX", "HULU", "P+", "APPLE")
    private fun Char.labelSpace() = this == ' ' || this in '\t'..'\r'
    private fun Char.labelSeparator() = this == ':' || this == '|' || this == '-' || this == '/'
    private fun label(raw: String) = raw.uppercase(java.util.Locale.ROOT).replace('İ', 'I')

    /** Bounded leading tag chain, stopping at the first real title word. */
    private fun hasForeignLeadingTag(title: String): Boolean {
        var cursor = 0
        repeat(8) {
            while (cursor < title.length && title[cursor].labelSpace()) cursor++
            if (cursor >= title.length) return false
            val closing = when (title[cursor]) { '[' -> ']'; '(' -> ')'; else -> null }
            if (closing != null) cursor++
            while (cursor < title.length && title[cursor].labelSpace()) cursor++
            val start = cursor
            while (cursor < title.length && (title[cursor].isLetterOrDigit() || title[cursor] == '+')) {
                if (cursor - start >= 18) return false
                cursor++
            }
            if (cursor == start) return false
            val tokenEnd = cursor
            val token = label(title.substring(start, tokenEnd))
            if (token !in languageLabels && token !in neutralLabels) return false
            while (cursor < title.length && title[cursor].labelSpace()) cursor++
            if (closing != null) {
                if (cursor >= title.length || title[cursor] != closing) return false
                cursor++
            } else {
                if (cursor >= title.length || !title[cursor].labelSeparator()) return false
                // French-Canadian / German-American can be part of an ordinary English title.
                if (title[cursor] == '-' && token in languageNames && tokenEnd == cursor &&
                    cursor + 1 < title.length && !title[cursor + 1].labelSpace()) return false
            }
            if (token in languageLabels) return true
            while (cursor < title.length && (title[cursor].labelSpace() || title[cursor].labelSeparator())) cursor++
        }
        return false
    }

    /** Trailing bracketed language tags, optionally followed by neutral quality tags. */
    private fun hasForeignTrailingTag(title: String): Boolean {
        var end = title.length
        repeat(8) {
            while (end > 0 && title[end - 1].labelSpace()) end--
            if (end == 0) return false
            val opening = when (title[end - 1]) { ']' -> '['; ')' -> '('; else -> return false }
            val start = title.lastIndexOf(opening, end - 2)
            if (start < 0 || end - start > 22) return false
            val token = label(title.substring(start + 1, end - 1).trim())
            if (token in languageLabels) return true
            if (token !in neutralLabels) return false
            end = start
        }
        return false
    }
    private val displayScripts = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON)
    // An ASCII pair outside the language-code set cannot match the anchored prefix regex.
    // Non-ASCII starts retain regex matching, including Unicode case folding (e.g. long s / dotted I).
    private val foreignPrefixPairs = foreignCodes.map { (it[0].code shl 8) or it[1].code }.toSet()
    private fun mayHaveForeignPrefix(title: String): Boolean {
        var start = 0
        while (start < title.length && (title[start] == ' ' || title[start].code in 9..13)) start++
        if (start < title.length && title[start] == '[') start++
        if (start + 1 >= title.length) return false
        val first = title[start]
        val second = title[start + 1]
        if (first.code > 0x7f || second.code > 0x7f) return true
        return ((first.uppercaseChar().code shl 8) or second.uppercaseChar().code) in foreignPrefixPairs
    }
    fun excludesTitle(title: String): Boolean {
        if (mayHaveForeignPrefix(title) && foreignPrefix.containsMatchIn(title)) return true
        if (hasForeignLeadingTag(title) || hasForeignTrailingTag(title)) return true
        // Ordinary ASCII titles need neither a code-point stream nor Unicode-script classification.
        if (title.none { it.code > 0x7f }) return false
        return title.codePoints().anyMatch { it > 0x7f && Character.isLetter(it) && Character.UnicodeScript.of(it) !in displayScripts }
    }
}
