package com.atlas.atlastv.xtream

import com.atlas.atlastv.xtream.mapper.AtlasContentType
import com.atlas.atlastv.xtream.mapper.XtreamCatalogMapper
import com.atlas.atlastv.xtream.parser.XtreamJsonParser
import com.atlas.atlastv.xtream.parser.XtreamParseResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamCatalogMapperTest {
    private val parser = XtreamJsonParser()
    private val mapper = XtreamCatalogMapper()

    @Test
    fun mappingJoinsStreamsToCategoryNamesAndFiltersCategories() {
        val catalog = mapper.map(
            liveCategories = parser.parseCategoriesSuccess("live-categories.json"),
            liveStreams = parser.parseLiveStreamsSuccess("live-streams.json"),
            vodCategories = parser.parseCategoriesSuccess("vod-categories.json"),
            vodStreams = parser.parseVodStreamsSuccess("vod-streams.json"),
            seriesCategories = parser.parseCategoriesSuccess("series-categories.json"),
            series = parser.parseSeriesSuccess("series.json"),
        )

        assertEquals("USA News", catalog.items.first { it.id == "101" }.categoryName)
        assertEquals(AtlasContentType.Live, catalog.items.first { it.id == "101" }.contentType)
        assertEquals(2, catalog.summary.keptLiveStreamCount)
        assertEquals(1, catalog.summary.keptVodStreamCount)
        assertEquals(1, catalog.summary.keptSeriesCount)
        assertEquals(3, catalog.summary.excludedCategoryCount)
        assertEquals(3, catalog.summary.excludedItemCount)
        assertEquals("Hindi Live", catalog.summary.topExcludedCategories.first().category)
    }

    @Test
    fun summaryCountsAreCorrect() {
        val catalog = mapper.map(
            liveCategories = parser.parseCategoriesSuccess("live-categories.json"),
            liveStreams = parser.parseLiveStreamsSuccess("live-streams.json"),
            vodCategories = parser.parseCategoriesSuccess("vod-categories.json"),
            vodStreams = parser.parseVodStreamsSuccess("vod-streams.json"),
            seriesCategories = parser.parseCategoriesSuccess("series-categories.json"),
            series = parser.parseSeriesSuccess("series.json"),
        )

        assertEquals(3, catalog.summary.liveCategoryCount)
        assertEquals(3, catalog.summary.liveStreamCount)
        assertEquals(2, catalog.summary.vodCategoryCount)
        assertEquals(2, catalog.summary.vodStreamCount)
        assertEquals(2, catalog.summary.seriesCategoryCount)
        assertEquals(2, catalog.summary.seriesCount)
    }

    private fun XtreamJsonParser.parseCategoriesSuccess(file: String) =
        (parseCategories(fixture(file)) as XtreamParseResult.Success).value

    private fun XtreamJsonParser.parseLiveStreamsSuccess(file: String) =
        (parseLiveStreams(fixture(file)) as XtreamParseResult.Success).value

    private fun XtreamJsonParser.parseVodStreamsSuccess(file: String) =
        (parseVodStreams(fixture(file)) as XtreamParseResult.Success).value

    private fun XtreamJsonParser.parseSeriesSuccess(file: String) =
        (parseSeries(fixture(file)) as XtreamParseResult.Success).value

    private fun fixture(fileName: String): String =
        String(Files.readAllBytes(findProjectRoot().resolve("testdata/xtream/$fileName")), StandardCharsets.UTF_8)

    private fun findProjectRoot(): Path {
        val current = Paths.get("").toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }
}
