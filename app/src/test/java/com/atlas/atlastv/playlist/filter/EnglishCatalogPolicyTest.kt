package com.atlas.atlastv.playlist.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCatalogPolicyTest {
    @Test fun momRemainsAnEligibleEnglishShowWhileExplicitForeignVariantsStayExcluded() {
        listOf("Mom","Mom (2013)","EN | Mom (US)","Momma Named Me Sheriff","Workin' Moms")
            .forEach { assertFalse(it,EnglishCatalogPolicy.excludesTitle(it)) }
        assertFalse(PlaylistCategoryFilter().excludes("ENGLISH SERIES"))
        listOf("FR: Mom","[KR] - Mom","RU | Mom","Mom العربية")
            .forEach { assertTrue(it,EnglishCatalogPolicy.excludesTitle(it)) }
        assertTrue(PlaylistCategoryFilter().excludes("FR - ENGLISH SERIES"))
    }
    @Test fun asciiFastPathPreservesUnicodeAndExplicitForeignPrefixChecks() {
        (0..127).forEach { assertFalse(EnglishCatalogPolicy.excludesTitle("Ordinary title ${it.toChar()}")) }
        listOf("Amélie", "Pokémon", "English — title", "Café 😀").forEach {assertFalse(it,EnglishCatalogPolicy.excludesTitle(it))}
        listOf("FR: English title", "[KR] - English title", "English Ελληνικά", "English 中文", "English العربية", "English Русский", "English "+String(Character.toChars(0x10400)))
            .forEach {assertTrue(it,EnglishCatalogPolicy.excludesTitle(it))}
    }
    @Test fun titleFastPathsMatchTheExistingPrefixAndScriptContract() {
        val originalPrefix = Regex("^\\s*\\[?(?:ES|FR|RU|JP|PK|DE|IT|PT|BR|KR|CN|TR|AR|IN|VP|NL|PL|GR|BE|QC|IL|LA|SE|SV|NO|DA|DK|FI|IS)\\]?\\s*[-:|]",RegexOption.IGNORE_CASE)
        fun original(title: String) = originalPrefix.containsMatchIn(title) || title.codePoints().anyMatch {
            Character.isLetter(it) && Character.UnicodeScript.of(it) !in setOf(Character.UnicodeScript.LATIN,Character.UnicodeScript.COMMON)
        }
        val starts=listOf("", "[", " \t[", "\n", "\u2003", "[[")
        val endings=listOf(" - English", "] : English", "|English", "ontier")
        val alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ []\t0éİſ"
        for (first in alphabet) for (second in alphabet) for (start in starts) for (end in endings) {
            val title="$start$first$second$end"
            assertEquals(title,original(title),EnglishCatalogPolicy.excludesTitle(title))
        }
        for(title in listOf("", "[", "F", "[F", "Frontier", "Gravity", "Deadpool", "Last year", " \tFR : Film", "ſV: Film", "İN: Film", "English Ελληνικά", "English 中文", "English العربية", "English Русский", "English "+String(Character.toChars(0x10400))))
            assertEquals(title,original(title),EnglishCatalogPolicy.excludesTitle(title))
    }
    @Test fun rejectsRealLeakedCategoriesEvenWithBrandOrEnglishTokens() {
        val filter = PlaylistCategoryFilter()
        listOf("VIAPLAY KIDS", "NORDIC FILM NEW RELEASE", "ESPAÑA NETFLIX", "FRANCE ANIME", "NL - AMAZON PRIME", "PL - FILMY 4K", "QC - NETFLIX", "GREECE KIDS", "ITALY DISNEY+", "HEBREW EN SERIES", "TURKSIH SERIES (SUB EN)", "SOMALIA ENGLISH SERIES").forEach {
            assertTrue(it, filter.excludes(it))
        }
    }
    @Test fun preservesOrdinaryEnglishBucketsAndAvoidsSubstringCodeMatches() {
        val filter = PlaylistCategoryFilter()
        listOf("ENGLISH SERIES", "NF - Kids 4K", "NETFLIX SERIES", "APPLE+ SERIES", "UK - Movies", "Drama", "Disney+ Family", "Science Fiction", "New Releases").forEach { assertFalse(it, filter.excludes(it)) }
    }
    @Test fun foreignTitleSignalsCannotSlipThroughAMixedBrandBucket() {
        assertTrue(EnglishCatalogPolicy.excludesTitle("Takki - تكي"))
        assertTrue(EnglishCatalogPolicy.excludesTitle("FR - Pilot"))
        assertFalse(EnglishCatalogPolicy.excludesTitle("It (2017)"))
        assertFalse(EnglishCatalogPolicy.excludesTitle("Amélie")) // Latin proper names alone are not a language guarantee.
        assertFalse(EnglishCatalogPolicy.excludesTitle("2001: A Space Odyssey"))
    }
    @Test fun explicitFullNamesAndThreeLetterLanguageCodesAreExcluded() {
        listOf("[FRENCH] Mom", "(French) Mom", "GER | Mom", "DEU: Mom", "FRA - Mom", "SPA/Mom",
            "[RUS] Movie", "(JPN) Movie", "DUT: Movie", "[PORTUGUESE] Movie", "[ ARABIC ] Movie",
            "french - Movie", "French:Movie", "GER-Movie").forEach { assertTrue(it, EnglishCatalogPolicy.excludesTitle(it)) }
    }
    @Test fun neutralLeadingLabelsCannotMaskAnExplicitForeignTag() {
        listOf("4K | GER | Movie", "NF | [FRENCH] Movie", "[EN] (FR) Movie", "ENGLISH: HD: SPA: Movie",
            "[UK] [1080P] [GERMAN] Movie", "PRIME | EN | (ITA) Movie", "HD | FR: Mom")
            .forEach { assertTrue(it, EnglishCatalogPolicy.excludesTitle(it)) }
        listOf("4K | English Patient", "NF | French Kiss", "[EN] German Shepherd", "English: Mom", "[UK] [HD] Mom")
            .forEach { assertFalse(it, EnglishCatalogPolicy.excludesTitle(it)) }
    }
    @Test fun trailingBracketedLabelsAreRecognizedWithoutGuessingFromTitleWords() {
        listOf("Mom [FRENCH]", "Movie (GER)", "Movie [FRA] [4K]", "Movie (ITA) [EN] [HD]")
            .forEach { assertTrue(it, EnglishCatalogPolicy.excludesTitle(it)) }
        listOf("The French", "French Kiss (1995)", "German-American Story", "French-Canadian Affair",
            "Movie [France trip]", "Movie (English)", "Movie [GERMAN SHEPHERD]", "Movie - French Kiss")
            .forEach { assertFalse(it, EnglishCatalogPolicy.excludesTitle(it)) }
    }
    @Test fun ordinaryNamesAndTitleSubstringsNeverActAsLanguageTags() {
        listOf("Mom", "Mom (2013)", "FROM", "IT", "It Follows", "French Kiss", "The French Connection", "Germania",
            "Polish Wedding", "Spanish Affair", "English Patient", "Café Society", "Pokémon", "Amélie",
            "NETFLIX Presents French Kiss", "[FRENCH", "[FRACTURE]", "[GERMANIA]")
            .forEach { assertFalse(it, EnglishCatalogPolicy.excludesTitle(it)) }
    }
    @Test fun categoryTagsShareTheExplicitLanguageRuleBehindBrandLabels() {
        val filter = PlaylistCategoryFilter()
        listOf("GER | Movies", "NF | FRA | SERIES", "[DEU] Movies", "4K | [FRENCH] Movies")
            .forEach { assertTrue(it, filter.excludes(it)) }
        listOf("NF | EN | Movies", "Drama", "UK | Movies")
            .forEach { assertFalse(it, filter.excludes(it)) }
    }
    @Test fun longOrMalformedTagChainsStopWithoutScanningOrdinaryTitleWords() {
        listOf("[" + "X".repeat(20000) + "] Mom", "X".repeat(20000) + ": Mom", "NF | [GERMANIA] Mom")
            .forEach { assertFalse(EnglishCatalogPolicy.excludesTitle(it)) }
    }
}
