package com.atlas.atlastv.ui.series

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.xtream.model.*
import com.atlas.atlastv.xtream.parser.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EpisodeMetadataTest {
    private fun json(info: String) = """{"episodes":{"1":[{"id":11,"episode_num":1,"title":"Pilot","info":$info}]}}"""
    private fun parse(info: String) = (XtreamJsonParser().parseSeriesInfo(json(info)) as XtreamParseResult.Success).value.episodes.single()
    @Test fun parsesNumericAndStringSecondsAndClockRuntime() {
        assertEquals(2_520_000L, parse("""{"duration_secs":2520,"duration":"00:01:00"}""").durationMs)
        assertEquals(2_520_000L, parse("""{"duration_secs":"2520"}""").durationMs)
        assertEquals(3_661_000L, parse("""{"duration":"01:01:01"}""").durationMs)
        assertEquals(2_520_000L, parse("""{"duration_secs":0,"duration":"00:42:00"}""").durationMs)
    }
    @Test fun malformedOptionalValuesCannotLoseAnEpisodeOrInventDuration() {
        for (info in listOf("null", "[]", "{}", """{"duration_secs":-1}""", """{"duration":"00:61:00"}""",
            """{"duration_secs":"9223372036854775807"}""", """{"duration":"9223372036854775807:00:00"}""",
            """{"duration_secs":{},"duration":false,"movie_image":[]}""")) {
            val parsed = parse(info); assertEquals(11, parsed.id); assertNull(parsed.durationMs)
        }
    }
    @Test fun implausibleSecondsFallBackToValidClockAndFractionalSecondsRetainPrecision() {
        assertEquals(2_520_000L, parse("""{"duration_secs":99999999,"duration":"00:42:00"}""").durationMs)
        assertEquals(2_520_500L, parse("""{"duration_secs":2520.5}""").durationMs)
        val invalid = parse("""{"duration_secs":86401,"duration":"25:00:00"}""")
        assertEquals(11, invalid.id); assertNull(invalid.durationMs)
    }
    @Test fun safeThumbnailTokensAreRegisteredAndSharedImagesAreDeduplicated() = runBlocking {
        val url = "https://images.example.test/episode.jpg"
        val entries = mutableListOf<Pair<String?, String?>>()
        val payload = """{"episodes":{"1":[{"id":11,"info":{"duration_secs":2520,"movie_image":"$url"}},
            {"id":12,"info":{"movie_image":"$url"}}]}}"""
        val loader = SeriesEpisodeLoader(downloader(payload), registerArtwork = { entries.addAll(it) })
        val result = loader.loadEpisodes(credentials, 7) as SeriesEpisodeLoadResult.Success
        assertEquals(2, result.episodes.size)
        assertEquals(2_520_000L, result.episodes.first().durationMs)
        assertEquals(1, entries.size); assertEquals(url, entries.single().second)
        assertTrue(result.episodes.all { it.thumbnail.providerImageToken == entries.single().first })
        assertFalse(result.toString().contains(url))
    }
    @Test fun unsafeImagesAndOptionalStorageFailureKeepEpisodeSelectionUsable() = runBlocking {
        val missing = SeriesEpisodeLoader(downloader(json("""{"movie_image":"file:///private/image.png"}"""))).loadEpisodes(credentials, 7) as SeriesEpisodeLoadResult.Success
        assertEquals(AtlasImageRef.Missing, missing.episodes.single().thumbnail)
        val failed = SeriesEpisodeLoader(downloader(json("""{"movie_image":"https://images.example.test/episode.jpg"}""")), registerArtwork = { error("private diagnostic") })
            .loadEpisodes(credentials, 7) as SeriesEpisodeLoadResult.Success
        assertEquals(11, failed.episodes.single().episodeId)
    }
    @Test fun canceledThumbnailRegistrationStillCancelsTheLoad() = runBlocking {
        val loader = SeriesEpisodeLoader(downloader(json("{}")), registerArtwork = { throw CancellationException() })
        try { loader.loadEpisodes(credentials, 7); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
    @Test fun missingTitlesUseStructuredEpisodeNumberAndNeverProviderIds() = runBlocking {
        val payload = """{"episodes":{"1":[{"id":901,"episode_num":1,"title":""},{"id":902}]}}"""
        val result = SeriesEpisodeLoader(downloader(payload)).loadEpisodes(credentials, 7) as SeriesEpisodeLoadResult.Success
        assertEquals(listOf("Episode 1", "Episode"), result.episodes.map { it.title })
    }
    private fun downloader(text: String) = object : TextDownloader {
        override suspend fun download(url: String) = TextDownloadResult.Success(text, text.length.toLong())
    }
    private val credentials = XtreamCredentials("https://provider.example.test", "opaque", "opaque")
}
