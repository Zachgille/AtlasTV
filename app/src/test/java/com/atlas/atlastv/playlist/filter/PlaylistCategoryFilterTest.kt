package com.atlas.atlastv.playlist.filter

import com.atlas.atlastv.playlist.parser.M3uParser
import com.atlas.atlastv.playlist.parser.PlaylistItem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCategoryFilterTest {
    private val filter = PlaylistCategoryFilter()

    @Test
    fun keepsAllowedUsaAndUsCategories() {
        val result = filter.filter(
            listOf(item("USA News"), item("US Sports"), item("United States Entertainment")),
        )

        assertEquals(3, result.keptCount)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun keepsUkCategories() {
        val result = filter.filter(
            listOf(item("UK Sports"), item("United Kingdom News")),
        )

        assertEquals(2, result.keptCount)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun keepsCanadaAndAustraliaCategories() {
        val result = filter.filter(
            listOf(item("Canada Movies"), item("CA Documentary"), item("Australia Kids"), item("AU News")),
        )

        assertEquals(4, result.keptCount)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun keepsEnglishCategories() {
        val result = filter.filter(
            listOf(item("English Movies"), item("EN Series")),
        )

        assertEquals(2, result.keptCount)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun keepsCommonEnglishProviderAndQualityBuckets() {
        val result = filter.filter(
            listOf(
                item("Recently Added Movies"),
                item("Netflix 4K"),
                item("Prime UHD"),
                item("Apple TV"),
                item("Disney Family"),
                item("Anime"),
            ),
        )

        assertEquals(6, result.keptCount)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun excludesObservedForeignProviderBucketsEvenWithAllowedWords() {
        val result = filter.filter(
            listOf(
                item("DE - AMAZON PRIME PRODUKTION"),
                item("BR - KIDS"),
                item("GR - Happy Feet"),
                item("BELGIUM KIDS (FR)"),
                item("ASIA MOVIES (MULTI-SUBS)"),
                item("DISNEY+ PODCAST"),
            ),
        )

        assertEquals(0, result.keptCount)
        assertEquals(6, result.excludedCount)
    }

    @Test
    fun excludesHindiCategory() {
        val result = filter.filter(listOf(item("Hindi Movies")))

        assertExcluded(result, "Hindi Movies")
    }

    @Test
    fun excludesArabicCategory() {
        val result = filter.filter(listOf(item("Arabic News")))

        assertExcluded(result, "Arabic News")
    }

    @Test
    fun excludesSpanishAndLatinoCategories() {
        val result = filter.filter(
            listOf(item("Spanish Entertainment"), item("Latino Movies")),
        )

        assertEquals(0, result.keptCount)
        assertEquals(2, result.excludedCount)
    }

    @Test
    fun excludesLeadingForeignLanguageCountryPrefixesEvenWithUsefulWords() {
        val result = filter.filter(
            listOf(
                item("ES - Kids Movies"),
                item("FR - Recently Added"),
                item("RU - 4K Movies"),
                item("JP - Anime"),
                item("PK - Family"),
                item("VP - Kids"),
            ),
        )

        assertEquals(0, result.keptCount)
        assertEquals(6, result.excludedCount)
    }

    @Test
    fun keepsEnglishProviderBucketsAndExcludesWorkoutCategories() {
        val result = filter.filter(
            listOf(
                item("NF - Kids 4K"),
                item("Prime Movies"),
                item("Apple TV"),
                item("Workout Movies"),
                item("Yoga Series"),
                item("Fitness 4K"),
            ),
        )

        assertEquals(listOf("NF - Kids 4K", "Prime Movies", "Apple TV"), result.keptItems.map { it.groupTitle })
        assertEquals(3, result.excludedCount)
    }

    @Test
    fun excludesFrenchAndGermanCategories() {
        val result = filter.filter(
            listOf(item("French Sports"), item("German TV")),
        )

        assertEquals(0, result.keptCount)
        assertEquals(2, result.excludedCount)
    }

    @Test
    fun excludesKoreanJapaneseAndChineseCategories() {
        val result = filter.filter(
            listOf(item("Korean Drama"), item("Japanese Movies"), item("Chinese News")),
        )

        assertEquals(0, result.keptCount)
        assertEquals(3, result.excludedCount)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val result = filter.filter(
            listOf(item("hInDi MoViEs"), item("uSa NeWs")),
        )

        assertEquals(1, result.keptCount)
        assertEquals(1, result.excludedCount)
        assertEquals("uSa NeWs", result.keptItems.single().groupTitle)
        assertEquals("hInDi MoViEs", result.excludedItems.single().item.groupTitle)
    }

    @Test
    fun matchingTrimsExtraWhitespace() {
        val result = filter.filter(
            listOf(item("  Arabic News  "), item("  USA News  ")),
        )

        assertEquals(1, result.keptCount)
        assertEquals(1, result.excludedCount)
        assertEquals(mapOf("Arabic News" to 1), result.excludedCategoryCounts)
    }

    @Test
    fun uncategorizedItemsAreKeptByDefault() {
        val result = filter.filter(
            listOf(item(null), item(""), item("   ")),
        )

        assertEquals(3, result.keptCount)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun uncategorizedItemsAreExcludedWhenConfigured() {
        val result = filter.filter(
            items = listOf(item(null), item(""), item("   ")),
            config = PlaylistFilterConfig(keepUncategorized = false),
        )

        assertEquals(0, result.keptCount)
        assertEquals(3, result.excludedCount)
        assertEquals(PlaylistFilterReason.UncategorizedExcluded, result.excludedItems.first().reason)
        assertEquals(mapOf("Uncategorized" to 3), result.excludedCategoryCounts)
    }

    @Test
    fun excludedKeywordWinsOverAllowedKeyword() {
        val result = filter.filter(
            listOf(item("USA Hindi Movies")),
        )

        assertEquals(0, result.keptCount)
        assertEquals(1, result.excludedCount)
        assertEquals(PlaylistFilterReason.ExcludedCategoryKeyword, result.excludedItems.single().reason)
    }

    @Test
    fun foreignHeavyFixtureParsesThenExcludesExpectedCategories() {
        val items = parseFixture("foreign-heavy.m3u")

        val result = filter.filter(items)

        assertEquals(8, result.totalCount)
        assertEquals(0, result.keptCount)
        assertEquals(8, result.excludedCount)
        assertEquals(
            mapOf(
                "Hindi Movies" to 1,
                "Arabic News" to 1,
                "Spanish Entertainment" to 1,
                "French Sports" to 1,
                "German TV" to 1,
                "Korean Drama" to 1,
                "Japanese Movies" to 1,
                "Portuguese Channels" to 1,
            ),
            result.excludedCategoryCounts,
        )
    }

    @Test
    fun smallGoodFixtureParsesThenKeepsExpectedCategories() {
        val items = parseFixture("small-good.m3u")

        val result = filter.filter(items)

        assertEquals(4, result.totalCount)
        assertEquals(4, result.keptCount)
        assertEquals(0, result.excludedCount)
        assertEquals(listOf("USA News", "UK Sports", "Canada Movies", "Australia Family"), result.keptItems.map { it.groupTitle })
    }

    @Test
    fun summaryCountsAreCorrectForMixedItems() {
        val result = filter.filter(
            listOf(
                item("USA News"),
                item("Hindi Movies"),
                item("Arabic News"),
                item("Hindi Movies"),
                item("Unknown Local"),
                item(null),
            ),
        )

        assertEquals(6, result.totalCount)
        assertEquals(3, result.keptCount)
        assertEquals(3, result.excludedCount)
        assertEquals(
            mapOf(
                "Hindi Movies" to 2,
                "Arabic News" to 1,
            ),
            result.excludedCategoryCounts,
        )
    }

    private fun assertExcluded(result: PlaylistFilterResult, category: String) {
        assertEquals(1, result.totalCount)
        assertEquals(0, result.keptCount)
        assertEquals(1, result.excludedCount)
        assertEquals(category, result.excludedItems.single().item.groupTitle)
        assertEquals(PlaylistFilterReason.ExcludedCategoryKeyword, result.excludedItems.single().reason)
    }

    private fun parseFixture(fileName: String): List<PlaylistItem> {
        val playlist = readPlaylist(fileName)
        val result = M3uParser().parse(playlist)
        assertTrue(result.warnings.isEmpty())
        return result.items
    }

    private fun readPlaylist(fileName: String): String {
        val projectRoot = findProjectRoot()
        return String(
            Files.readAllBytes(projectRoot.resolve("testdata/playlists/$fileName")),
            StandardCharsets.UTF_8,
        )
    }

    private fun findProjectRoot(): Path {
        val current = Paths.get("").toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    private fun item(groupTitle: String?): PlaylistItem =
        PlaylistItem(
            tvgId = null,
            tvgName = null,
            tvgLogo = null,
            groupTitle = groupTitle,
            displayName = groupTitle?.trim().takeUnless { it.isNullOrBlank() } ?: "Uncategorized",
            streamUrl = "https://streams.example.test/live/${(groupTitle ?: "uncategorized").trim().lowercase().replace(" ", "-")}.m3u8",
            rawExtinf = "#EXTINF:-1,${groupTitle ?: "Uncategorized"}",
        )
}
