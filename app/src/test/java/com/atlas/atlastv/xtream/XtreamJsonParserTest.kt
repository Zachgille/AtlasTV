package com.atlas.atlastv.xtream

import com.atlas.atlastv.xtream.parser.XtreamJsonParser
import com.atlas.atlastv.xtream.parser.XtreamParseResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamJsonParserTest {
    private val parser = XtreamJsonParser()

    @Test
    fun accountServerJsonParses() {
        val result = parser.parseAccountInfo(fixture("account-info.json")) as XtreamParseResult.Success

        assertEquals(1, result.value.auth)
        assertEquals("Active", result.value.status)
        assertEquals(1, result.value.activeConnections)
        assertEquals(3, result.value.maxConnections)
        assertEquals("http", result.value.serverInfo?.serverProtocol)
    }

    @Test
    fun liveCategoriesJsonParses() {
        val result = parser.parseCategories(fixture("live-categories.json")) as XtreamParseResult.Success

        assertEquals(3, result.value.size)
        assertEquals("USA News", result.value.first().categoryName)
    }

    @Test
    fun liveStreamsJsonParsesWithStringAndNumberFields() {
        val result = parser.parseLiveStreams(fixture("live-streams.json")) as XtreamParseResult.Success

        assertEquals(3, result.value.size)
        assertEquals(101, result.value[0].streamId)
        assertEquals(2, result.value[1].num)
    }

    @Test
    fun vodCategoriesJsonParses() {
        val result = parser.parseCategories(fixture("vod-categories.json")) as XtreamParseResult.Success

        assertEquals(2, result.value.size)
        assertEquals("Canada Movies", result.value.first().categoryName)
    }

    @Test
    fun vodStreamsJsonParses() {
        val result = parser.parseVodStreams(fixture("vod-streams.json")) as XtreamParseResult.Success

        assertEquals(2, result.value.size)
        assertEquals("mp4", result.value.first().containerExtension)
    }

    @Test
    fun seriesCategoriesJsonParses() {
        val result = parser.parseCategories(fixture("series-categories.json")) as XtreamParseResult.Success

        assertEquals(2, result.value.size)
        assertEquals("English Series", result.value.first().categoryName)
    }

    @Test
    fun seriesJsonParses() {
        val result = parser.parseSeries(fixture("series.json")) as XtreamParseResult.Success

        assertEquals(2, result.value.size)
        assertEquals(301, result.value.first().seriesId)
    }

    @Test
    fun seriesInfoJsonParsesEpisodes() {
        val result = parser.parseSeriesInfo(
            """
                {
                  "episodes": {
                    "1": [
                      { "id": "901", "episode_num": "1", "title": "Pilot", "container_extension": "mkv" }
                    ],
                    "2": [
                      { "id": 902, "episode_num": 2, "title": "Second", "container_extension": "mp4" }
                    ]
                  }
                }
            """.trimIndent(),
        ) as XtreamParseResult.Success

        assertEquals(2, result.value.episodes.size)
        assertEquals(901, result.value.episodes.first().id)
        assertEquals(1, result.value.episodes.first().seasonNumber)
        assertEquals("mkv", result.value.episodes.first().containerExtension)
    }

    @Test
    fun missingNullableFieldsDoNotCrash() {
        val result = parser.parseLiveStreams("""[{ "name": "Minimal" }]""") as XtreamParseResult.Success

        assertEquals("Minimal", result.value.single().name)
        assertNull(result.value.single().streamId)
    }

    @Test
    fun lenientJsonParsesWhenProviderUsesSingleQuotedStrings() {
        val result = parser.parseVodStreams("""[{ 'name': 'Single Quote Movie', 'stream_id': '42' }]""") as XtreamParseResult.Success

        assertEquals("Single Quote Movie", result.value.single().name)
        assertEquals(42, result.value.single().streamId)
    }

    @Test
    fun emptyArraysAreValid() {
        val result = parser.parseCategories("[]") as XtreamParseResult.Success

        assertTrue(result.value.isEmpty())
    }

    @Test
    fun malformedJsonReturnsTypedFailure() {
        val result = parser.parseCategories(fixture("malformed.json"))

        assertTrue(result is XtreamParseResult.Failure)
    }

    private fun fixture(fileName: String): String =
        String(Files.readAllBytes(findProjectRoot().resolve("testdata/xtream/$fileName")), StandardCharsets.UTF_8)

    private fun findProjectRoot(): Path {
        val current = Paths.get("").toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }
}
