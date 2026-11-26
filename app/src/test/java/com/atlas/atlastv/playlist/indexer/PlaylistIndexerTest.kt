package com.atlas.atlastv.playlist.indexer

import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.playlist.parser.M3uParser
import com.atlas.atlastv.playlist.parser.PlaylistItem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistIndexerTest {
    private val indexer = PlaylistIndexer()

    @Test
    fun emptyInputReturnsEmptyIndex() {
        val result = indexer.index(emptyList())

        assertTrue(result.allItems.isEmpty())
        assertTrue(result.liveGroups.isEmpty())
        assertTrue(result.movieGroups.isEmpty())
        assertTrue(result.seriesGroups.isEmpty())
        assertEquals(0, result.totalCount)
        assertEquals(0, result.liveCount)
        assertEquals(0, result.movieCount)
        assertEquals(0, result.seriesCount)
        assertEquals(0, result.unknownCount)
    }

    @Test
    fun classifiesLiveCategories() {
        val result = indexer.index(
            listOf(
                item(displayName = "Daily News", groupTitle = "USA News"),
                item(displayName = "Match Day", groupTitle = "UK Sports"),
            ),
        )

        assertEquals(2, result.liveCount)
        assertEquals(PlaylistContentType.Live, result.liveItems.first().contentType)
    }

    @Test
    fun classifiesMovieCategories() {
        val result = indexer.index(
            listOf(
                item(displayName = "Example Movie", groupTitle = "Movies"),
                item(displayName = "Example Film", groupTitle = "Cinema"),
            ),
        )

        assertEquals(2, result.movieCount)
        assertEquals(0, result.liveCount)
    }

    @Test
    fun classifiesSeriesUsingS01E01() {
        val indexed = indexer.index(listOf(item("Example Show S01E01", "Series"))).seriesItems.single()

        assertEquals(PlaylistContentType.Series, indexed.contentType)
        assertEquals("Example Show", indexed.seriesName)
        assertEquals(1, indexed.seasonNumber)
        assertEquals(1, indexed.episodeNumber)
    }

    @Test
    fun classifiesSeriesUsingS1E1() {
        val indexed = indexer.index(listOf(item("Example Show S1E1", "Series"))).seriesItems.single()

        assertEquals("Example Show", indexed.seriesName)
        assertEquals(1, indexed.seasonNumber)
        assertEquals(1, indexed.episodeNumber)
    }

    @Test
    fun classifiesSeriesUsingSeasonEpisodeText() {
        val indexed = indexer.index(listOf(item("Example Show Season 1 Episode 1", "Series"))).seriesItems.single()

        assertEquals("Example Show", indexed.seriesName)
        assertEquals(1, indexed.seasonNumber)
        assertEquals(1, indexed.episodeNumber)
    }

    @Test
    fun classifiesSeriesUsingNxEpisodePattern() {
        val indexed = indexer.index(listOf(item("Example Show 1x01", "Series"))).seriesItems.single()

        assertEquals("Example Show", indexed.seriesName)
        assertEquals(1, indexed.seasonNumber)
        assertEquals(1, indexed.episodeNumber)
    }

    @Test
    fun removesLeadingAndTrailingSeparatorsAfterSeriesParsing() {
        val indexed = indexer.index(listOf(item("Example Show - S02E03", "Movies"))).seriesItems.single()

        assertEquals("Example Show", indexed.seriesName)
        assertEquals(2, indexed.seasonNumber)
        assertEquals(3, indexed.episodeNumber)
    }

    @Test
    fun seriesDetectionWinsOverMovieAndLiveCategory() {
        val result = indexer.index(
            listOf(
                item("Example Show S01E01", "Movies"),
                item("News Show S01E02", "USA News"),
            ),
        )

        assertEquals(2, result.seriesCount)
        assertEquals(0, result.movieCount)
        assertEquals(0, result.liveCount)
    }

    @Test
    fun movieCategoryWinsOverLiveCategory() {
        val result = indexer.index(
            listOf(item("Example Movie", "USA Movies")),
        )

        assertEquals(1, result.movieCount)
        assertEquals(0, result.liveCount)
    }

    @Test
    fun unknownFallbackWhenNoRuleMatches() {
        val result = indexer.index(
            listOf(item("Mystery Item", "Miscellaneous")),
        )

        assertEquals(1, result.unknownCount)
        assertEquals(PlaylistContentType.Unknown, result.unknownItems.single().contentType)
    }

    @Test
    fun groupsByCategory() {
        val result = indexer.index(
            listOf(
                item("Alpha", "USA News"),
                item("Beta", "USA News"),
                item("Gamma", "UK Sports"),
            ),
        )

        assertEquals(listOf("UK Sports", "USA News"), result.liveGroups.map { it.name })
        assertEquals(2, result.liveGroups.single { it.name == "USA News" }.items.size)
    }

    @Test
    fun groupsUncategorizedItems() {
        val result = indexer.index(
            listOf(item("No Category", null)),
        )

        assertEquals(1, result.unknownCount)
        assertEquals("Uncategorized", result.unknownGroups.single().name)
    }

    @Test
    fun groupsAreSortedAlphabetically() {
        val result = indexer.index(
            listOf(
                item("Second", "USA News"),
                item("First", "Canada News"),
                item("Third", "Australia News"),
            ),
        )

        assertEquals(listOf("Australia News", "Canada News", "USA News"), result.liveGroups.map { it.name })
    }

    @Test
    fun itemsInsideGroupsAreSortedAlphabeticallyByNormalizedTitle() {
        val result = indexer.index(
            listOf(
                item("  Zebra   News  ", "USA News"),
                item("Alpha News", "USA News"),
                item("Middle News", "USA News"),
            ),
        )

        assertEquals(
            listOf("Alpha News", "Middle News", "Zebra News"),
            result.liveGroups.single().items.map { it.normalizedTitle },
        )
    }

    @Test
    fun countsAreCorrect() {
        val result = indexer.index(
            listOf(
                item("Live Item", "USA News"),
                item("Movie Item", "Movies"),
                item("Example Show S01E01", "Movies"),
                item("Unknown Item", "Other"),
            ),
        )

        assertEquals(4, result.totalCount)
        assertEquals(1, result.liveCount)
        assertEquals(1, result.movieCount)
        assertEquals(1, result.seriesCount)
        assertEquals(1, result.unknownCount)
    }

    @Test
    fun seriesFixtureParsesFiltersAndIndexesAsSeries() {
        val filteredItems = parseFilterAndReturnKeptItems("series-basic.m3u")

        val result = indexer.index(filteredItems)

        assertEquals(3, result.totalCount)
        assertEquals(3, result.seriesCount)
        assertEquals(listOf(1, 1, 2), result.seriesItems.map { it.seasonNumber })
        assertEquals(listOf(1, 2, 1), result.seriesItems.map { it.episodeNumber })
    }

    @Test
    fun smallGoodFixtureParsesFiltersAndIndexesIntoExpectedCategories() {
        val filteredItems = parseFilterAndReturnKeptItems("small-good.m3u")

        val result = indexer.index(filteredItems)

        assertEquals(4, result.totalCount)
        assertEquals(3, result.liveCount)
        assertEquals(1, result.movieCount)
        assertEquals(listOf("Australia Family", "UK Sports", "USA News"), result.liveGroups.map { it.name })
        assertEquals(listOf("Canada Movies"), result.movieGroups.map { it.name })
    }

    @Test
    fun foreignHeavyFixtureParsesFiltersAndIndexesWithoutCrashing() {
        val filteredItems = parseFilterAndReturnKeptItems("foreign-heavy.m3u")

        val result = indexer.index(filteredItems)

        assertEquals(0, filteredItems.size)
        assertEquals(0, result.totalCount)
        assertTrue(result.allItems.isEmpty())
    }

    private fun parseFilterAndReturnKeptItems(fileName: String): List<PlaylistItem> {
        val parseResult = M3uParser().parse(readPlaylist(fileName))
        assertTrue(parseResult.warnings.isEmpty())
        return PlaylistCategoryFilter().filter(parseResult.items).keptItems
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

    private fun item(displayName: String, groupTitle: String?): PlaylistItem =
        PlaylistItem(
            tvgId = null,
            tvgName = null,
            tvgLogo = null,
            groupTitle = groupTitle,
            displayName = displayName,
            streamUrl = "https://streams.example.test/${displayName.trim().lowercase().replace(" ", "-")}.m3u8",
            rawExtinf = "#EXTINF:-1,$displayName",
        )
}
