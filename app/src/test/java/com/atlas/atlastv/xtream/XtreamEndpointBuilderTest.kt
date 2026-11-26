package com.atlas.atlastv.xtream

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpoint
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XtreamEndpointBuilderTest {
    private val builder = XtreamEndpointBuilder()

    @Test
    fun trimsTrailingSlash() {
        val url = builder.build(credentials(baseUrl = "http://example.test/"), XtreamEndpoint.LiveCategories)

        assertEquals("http://example.test/player_api.php?username=fake_user&password=fake_pass&action=get_live_categories", url)
    }

    @Test
    fun doesNotDuplicateSlashesWhenBaseHasMultipleTrailingSlashes() {
        val url = builder.build(credentials(baseUrl = "http://example.test///"), XtreamEndpoint.AccountInfo)

        assertEquals("http://example.test/player_api.php?username=fake_user&password=fake_pass", url)
    }

    @Test
    fun includesActionForActionEndpoints() {
        val url = builder.build(credentials(), XtreamEndpoint.Series)

        assertEquals("http://example.test/player_api.php?username=fake_user&password=fake_pass&action=get_series", url)
    }

    @Test
    fun includesCategoryIdWhenProvided() {
        val url = builder.build(credentials(), XtreamEndpoint.VodStreams, categoryId = "10")

        assertEquals("http://example.test/player_api.php?username=fake_user&password=fake_pass&action=get_vod_streams&category_id=10", url)
    }

    @Test
    fun encodesUsernameAndPassword() {
        val url = builder.build(
            XtreamCredentials(
                baseUrl = "http://example.test",
                username = "fake user@example",
                password = "fake pass&word",
            ),
            XtreamEndpoint.LiveStreams,
        )

        assertEquals("http://example.test/player_api.php?username=fake+user%40example&password=fake+pass%26word&action=get_live_streams", url)
    }

    @Test
    fun buildsLiveStreamUrlWithEncodedOpaqueCredentials() {
        val url = builder.buildLiveStream(
            XtreamCredentials(
                baseUrl = "https://example.test/",
                username = "fake user@example",
                password = "fake/pass word",
            ),
            streamId = 1234,
        )

        assertEquals("https://example.test/live/fake%20user%40example/fake%2Fpass%20word/1234.ts", url)
    }

    @Test
    fun trimsCustomLiveStreamExtension() {
        val url = builder.buildLiveStream(credentials(), streamId = 55, extension = ".m3u8")

        assertEquals("http://example.test/live/fake_user/fake_pass/55.m3u8", url)
    }

    @Test
    fun redactedLiveStreamUrlDoesNotExposeHostOrCredentials() {
        val redacted = builder.buildRedactedLiveStream(
            XtreamCredentials("https://example.test", "fake_user", "fake_pass"),
            streamId = 99,
        )

        assertEquals("https://REDACTED_HOST/live/REDACTED/REDACTED/99.ts", redacted)
        assertFalse(redacted.contains("example.test"))
        assertFalse(redacted.contains("fake_user"))
        assertFalse(redacted.contains("fake_pass"))
    }

    @Test
    fun buildsAndRedactsVodStreamUrl() {
        val url = builder.buildVodStream(credentials(), streamId = 77, extension = ".mkv")
        val redacted = builder.buildRedactedVodStream(credentials(), streamId = 77, extension = ".mkv")

        assertEquals("http://example.test/movie/fake_user/fake_pass/77.mkv", url)
        assertEquals("http://REDACTED_HOST/movie/REDACTED/REDACTED/77.mkv", redacted)
        assertFalse(redacted.contains("example.test"))
        assertFalse(redacted.contains("fake_user"))
        assertFalse(redacted.contains("fake_pass"))
    }

    @Test
    fun buildsSeriesInfoEndpointAndEpisodeStreamUrl() {
        val infoUrl = builder.build(credentials(), XtreamEndpoint.SeriesInfo, seriesId = 301)
        val episodeUrl = builder.buildSeriesEpisodeStream(credentials(), episodeId = 901, extension = "mkv")
        val redactedEpisodeUrl = builder.buildRedactedSeriesEpisodeStream(credentials(), episodeId = 901, extension = "mkv")

        assertEquals("http://example.test/player_api.php?username=fake_user&password=fake_pass&action=get_series_info&series_id=301", infoUrl)
        assertEquals("http://example.test/series/fake_user/fake_pass/901.mkv", episodeUrl)
        assertEquals("http://REDACTED_HOST/series/REDACTED/REDACTED/901.mkv", redactedEpisodeUrl)
        assertFalse(redactedEpisodeUrl.contains("example.test"))
        assertFalse(redactedEpisodeUrl.contains("fake_user"))
        assertFalse(redactedEpisodeUrl.contains("fake_pass"))
    }

    @Test
    fun redactedUrlDoesNotExposeCredentials() {
        val redacted = builder.buildRedacted(
            XtreamCredentials("http://example.test", "fake_user", "fake_pass"),
            XtreamEndpoint.AccountInfo,
        )

        assertEquals("http://REDACTED_HOST/player_api.php?username=REDACTED&password=REDACTED", redacted)
        assertFalse(redacted.contains("example.test"))
        assertFalse(redacted.contains("fake_user"))
        assertFalse(redacted.contains("fake_pass"))
    }

    private fun credentials(baseUrl: String = "http://example.test"): XtreamCredentials =
        XtreamCredentials(baseUrl = baseUrl, username = "fake_user", password = "fake_pass")
}
