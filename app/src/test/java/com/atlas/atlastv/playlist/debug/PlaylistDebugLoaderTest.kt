package com.atlas.atlastv.playlist.debug

import com.atlas.atlastv.core.network.TextDownloadFailureReason
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDebugLoaderTest {
    @Test
    fun summaryCountsParserFilterAndIndexerResults() = runBlocking {
        val loader = PlaylistDebugLoader(
            downloader = FakeDownloader(
                TextDownloadResult.Success(
                    text = """
                        #EXTM3U
                        #EXTINF:-1 tvg-id="news.us" group-title="USA News",US News
                        https://streams.example.test/news.m3u8
                        #EXTINF:-1 tvg-id="movie.ca" group-title="Canada Movies",Canada Movie
                        https://streams.example.test/movie.m3u8
                        #EXTINF:-1 tvg-id="show" group-title="Example Series",Example Show S01E01
                        https://streams.example.test/show-s01e01.m3u8
                        #EXTINF:-1 tvg-id="hindi" group-title="Hindi Movies",Hindi Movie
                        https://streams.example.test/hindi.m3u8
                    """.trimIndent(),
                    byteCount = 500,
                ),
            ),
        )

        val state = loader.load("https://example.test/get.php?username=abc&password=def")

        val summary = (state as PlaylistDebugLoadState.Success).summary
        assertEquals("https://example.test/get.php?username=REDACTED&password=REDACTED", summary.redactedUrl)
        assertFalse(summary.redactedUrl.contains("abc"))
        assertFalse(summary.redactedUrl.contains("def"))
        assertEquals(4, summary.parsedItemCount)
        assertEquals(0, summary.parseWarningCount)
        assertEquals(3, summary.keptItemCount)
        assertEquals(1, summary.excludedItemCount)
        assertEquals(1, summary.liveCount)
        assertEquals(1, summary.movieCount)
        assertEquals(1, summary.seriesCount)
        assertEquals(0, summary.unknownCount)
        assertTrue(summary.downloadedCharacterCount > 0)
        assertEquals(500L, summary.downloadedByteCount)
    }

    @Test
    fun emptyPlaylistResponseProducesSafeError() = runBlocking {
        val loader = PlaylistDebugLoader(
            downloader = FakeDownloader(TextDownloadResult.Success(text = "", byteCount = 0)),
        )

        val state = loader.load("https://example.test/get.php?token=abc")

        val error = state as PlaylistDebugLoadState.Error
        assertEquals("Playlist response was empty.", error.message)
        assertEquals("https://example.test/get.php?token=REDACTED", error.redactedUrl)
    }

    @Test
    fun parserWarningsAreCountedInSummary() = runBlocking {
        val loader = PlaylistDebugLoader(
            downloader = FakeDownloader(
                TextDownloadResult.Success(
                    text = """
                        #EXTM3U
                        #EXTINF:-1 group-title="USA News",Missing URL
                        #EXTINF:-1 group-title="USA News",Valid News
                        https://streams.example.test/valid-news.m3u8
                    """.trimIndent(),
                    byteCount = null,
                ),
            ),
        )

        val state = loader.load("https://example.test/list?auth=abc")

        val summary = (state as PlaylistDebugLoadState.Success).summary
        assertEquals(1, summary.parsedItemCount)
        assertEquals(1, summary.parseWarningCount)
        assertEquals(1, summary.keptItemCount)
    }

    @Test
    fun downloadFailureReturnsSafeError() = runBlocking {
        val loader = PlaylistDebugLoader(
            downloader = FakeDownloader(
                TextDownloadResult.Failure(
                    reason = TextDownloadFailureReason.NetworkFailure,
                    message = "Playlist request failed.",
                ),
            ),
        )

        val state = loader.load("https://example.test/list?key=secret")

        val error = state as PlaylistDebugLoadState.Error
        assertEquals("Playlist request failed.", error.message)
        assertEquals("https://example.test/list?key=REDACTED", error.redactedUrl)
    }

    private class FakeDownloader(
        private val result: TextDownloadResult,
    ) : TextDownloader {
        override suspend fun download(url: String): TextDownloadResult = result
    }
}
