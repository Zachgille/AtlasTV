package com.atlas.atlastv.playlist.parser

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private val parser = M3uParser()

    @Test
    fun parsesValidSingleItem() {
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-id="espn.us" tvg-name="ESPN" tvg-logo="https://example.test/espn.png" group-title="USA Sports",ESPN
            https://example.test/live/espn.m3u8
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals(1, result.items.size)
        assertTrue(result.warnings.isEmpty())
        val item = result.items.single()
        assertEquals("espn.us", item.tvgId)
        assertEquals("ESPN", item.tvgName)
        assertEquals("https://example.test/espn.png", item.tvgLogo)
        assertEquals("USA Sports", item.groupTitle)
        assertEquals("ESPN", item.displayName)
        assertEquals("https://example.test/live/espn.m3u8", item.streamUrl)
        assertEquals(
            "#EXTINF:-1 tvg-id=\"espn.us\" tvg-name=\"ESPN\" tvg-logo=\"https://example.test/espn.png\" group-title=\"USA Sports\",ESPN",
            item.rawExtinf,
        )
    }

    @Test
    fun parsesValidMultipleItemsFromFixture() {
        val result = parser.parse(readPlaylist("small-good.m3u"))

        assertEquals(4, result.items.size)
        assertTrue(result.warnings.isEmpty())
        assertEquals("US News", result.items[0].displayName)
        assertEquals("UK Sports", result.items[1].displayName)
        assertEquals("Canada Movies", result.items[2].displayName)
        assertEquals("Australia Family", result.items[3].displayName)
    }

    @Test
    fun toleratesMissingOptionalAttributes() {
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-name="No Id" tvg-logo="https://cdn.example.test/no-id.png" group-title="Missing Optional",No Id
            https://streams.example.test/live/no-id.m3u8
            #EXTINF:-1 tvg-id="no.name" tvg-logo="https://cdn.example.test/no-name.png" group-title="Missing Optional",No Name
            https://streams.example.test/live/no-name.m3u8
            #EXTINF:-1 tvg-id="no.logo" tvg-name="No Logo" group-title="Missing Optional",No Logo
            https://streams.example.test/live/no-logo.m3u8
            #EXTINF:-1 tvg-id="no.group" tvg-name="No Group" tvg-logo="https://cdn.example.test/no-group.png",No Group
            https://streams.example.test/live/no-group.m3u8
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals(4, result.items.size)
        assertNull(result.items[0].tvgId)
        assertNull(result.items[1].tvgName)
        assertNull(result.items[2].tvgLogo)
        assertNull(result.items[3].groupTitle)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun toleratesExtraWhitespaceAroundAttributesAndDisplayName() {
        val input = """
            #EXTM3U
               #EXTINF:-1    tvg-id = "space.id"   tvg-name = "Space Name"   tvg-logo = "https://cdn.example.test/space.png"   group-title = "Space Group"   ,   Space Display   
               https://streams.example.test/live/space.m3u8   
        """.trimIndent()

        val result = parser.parse(input)

        val item = result.items.single()
        assertEquals("space.id", item.tvgId)
        assertEquals("Space Name", item.tvgName)
        assertEquals("https://cdn.example.test/space.png", item.tvgLogo)
        assertEquals("Space Group", item.groupTitle)
        assertEquals("Space Display", item.displayName)
        assertEquals("https://streams.example.test/live/space.m3u8", item.streamUrl)
    }

    @Test
    fun preservesDisplayNameCommasAfterFirstSeparatorComma() {
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-id="movie.test" group-title="Movies",Movie Title, The Sequel
            https://example.test/movie.m3u8
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals("Movie Title, The Sequel", result.items.single().displayName)
    }

    @Test
    fun parsesForeignHeavyFixtureWithoutFiltering() {
        val result = parser.parse(readPlaylist("foreign-heavy.m3u"))

        assertEquals(8, result.items.size)
        assertTrue(result.warnings.isEmpty())
        assertEquals("Hindi Movies", result.items[0].groupTitle)
        assertEquals("Portuguese Channels", result.items.last().groupTitle)
    }

    @Test
    fun malformedExtinfWithoutFollowingUrlProducesWarningAndIsSkipped() {
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-id="missing.url" group-title="Broken",Missing URL
            #EXTINF:-1 tvg-id="valid" group-title="Recovery",Valid
            https://streams.example.test/live/valid.m3u8
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals(1, result.items.size)
        assertEquals("Valid", result.items.single().displayName)
        assertEquals(1, result.warnings.size)
        assertEquals(2, result.warnings.single().lineNumber)
    }

    @Test
    fun urlWithoutPrecedingExtinfProducesWarningAndIsIgnored() {
        val result = parser.parse("https://streams.example.test/live/orphan.m3u8")

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().message.contains("preceding EXTINF"))
    }

    @Test
    fun emptyInputReturnsEmptyResult() {
        val result = parser.parse("")

        assertTrue(result.items.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun commentLinesAreIgnoredWhileWaitingForStreamUrl() {
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-id="comment.test" group-title="Comments",Comment Test
            # comment before stream url
            https://streams.example.test/live/comment-test.m3u8
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals(1, result.items.size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun preservesQueryParametersAndFakeCredentialsAsOpaqueText() {
        val url = "https://streams.example.test/live/channel.m3u8?username=fake_user&password=fake_password&token=fake_token"
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-id="query.test" group-title="Queries",Query Test
            $url
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals(url, result.items.single().streamUrl)
    }

    @Test
    fun malformedFixtureReturnsItemsAndWarningsWithoutCrashing() {
        val result = parser.parse(readPlaylist("malformed.m3u"))

        assertEquals(3, result.items.size)
        assertFalse(result.warnings.isEmpty())
        assertEquals("Valid After Broken", result.items[0].displayName)
        assertEquals("", result.items[1].displayName)
        assertEquals("No Id", result.items[2].displayName)
    }

    @Test
    fun parsesSeriesFixtureAsPlainDisplayNames() {
        val result = parser.parse(readPlaylist("series-basic.m3u"))

        assertEquals(3, result.items.size)
        assertEquals("Example Show S01E01", result.items[0].displayName)
        assertEquals("Example Show S01E02", result.items[1].displayName)
        assertEquals("Example Show S02E01", result.items[2].displayName)
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
}
