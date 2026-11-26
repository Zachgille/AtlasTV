package com.atlas.atlastv.xtream

import com.atlas.atlastv.core.network.TextDownloadFailureReason
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamApiClient
import com.atlas.atlastv.xtream.network.XtreamApiResult
import com.atlas.atlastv.xtream.network.XtreamFailureCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamApiClientTest {
    @Test
    fun non2xxResponseMapsToNon2xxStatus() = runBlocking {
        val client = XtreamApiClient(
            downloader = FakeDownloader(
                TextDownloadResult.Failure(
                    reason = TextDownloadFailureReason.HttpStatus,
                    message = "Request returned HTTP 403.",
                    httpStatus = 403,
                    responseByteCount = 22,
                ),
            ),
        )

        val result = client.loadCatalog(fakeCredentials()) as XtreamApiResult.Failure

        assertEquals(XtreamFailureCategory.Non2xxStatus, result.category)
        assertEquals(403, result.httpStatus)
        assertEquals(22L, result.responseByteCount)
    }

    @Test
    fun emptyResponseMapsToEmptyResponse() = runBlocking {
        val client = XtreamApiClient(
            downloader = FakeDownloader(
                TextDownloadResult.Failure(
                    reason = TextDownloadFailureReason.EmptyResponse,
                    message = "Response was empty.",
                    responseByteCount = 0,
                ),
            ),
        )

        val result = client.loadCatalog(fakeCredentials()) as XtreamApiResult.Failure

        assertEquals(XtreamFailureCategory.EmptyResponse, result.category)
        assertEquals(0L, result.responseByteCount)
    }

    @Test
    fun malformedJsonMapsToParseFailure() = runBlocking {
        val client = XtreamApiClient(
            downloader = FakeDownloader(TextDownloadResult.Success(text = "{broken", byteCount = 7)),
        )

        val result = client.loadCatalog(fakeCredentials()) as XtreamApiResult.Failure

        assertEquals(XtreamFailureCategory.MalformedJson, result.category)
        assertEquals("Loading account info", result.endpointLabel)
    }

    @Test
    fun diagnosticsRedactUsernameAndPassword() = runBlocking {
        val client = XtreamApiClient(
            downloader = FakeDownloader(
                TextDownloadResult.Failure(
                    reason = TextDownloadFailureReason.DnsFailure,
                    message = "DNS lookup failed.",
                ),
            ),
        )

        val result = client.loadCatalog(fakeCredentials()) as XtreamApiResult.Failure

        assertEquals(XtreamFailureCategory.DnsFailure, result.category)
        assertFalse(result.redactedUrl.orEmpty().contains("fake_user"))
        assertFalse(result.redactedUrl.orEmpty().contains("fake_password"))
    }

    @Test
    fun largeCatalogEndpointsFetchOnlyKeptCategoryIds() = runBlocking {
        val downloader = RecordingXtreamDownloader()
        val client = XtreamApiClient(downloader = downloader)

        val result = client.loadCatalog(fakeCredentials())

        assertTrue(result is XtreamApiResult.Success)
        assertTrue(downloader.urls.any { it.contains("action=get_live_streams") && !it.contains("category_id=") })
        assertTrue(downloader.urls.any { it.contains("action=get_vod_streams") && it.contains("category_id=3") })
        assertTrue(downloader.urls.any { it.contains("action=get_series") && it.contains("category_id=5") })
        assertFalse(downloader.urls.any { it.contains("category_id=4") })
        assertFalse(downloader.urls.any { it.contains("category_id=6") })
        assertFalse(downloader.urls.any { it.contains("category_id=7") })
        assertFalse(downloader.urls.any { it.contains("category_id=8") })
    }

    private fun fakeCredentials(): XtreamCredentials =
        XtreamCredentials(
            baseUrl = "http://example.test",
            username = "fake_user",
            password = "fake_password",
        )

    private class FakeDownloader(
        private val result: TextDownloadResult,
    ) : TextDownloader {
        override suspend fun download(url: String): TextDownloadResult = result
    }

    private class RecordingXtreamDownloader : TextDownloader {
        val urls = mutableListOf<String>()

        override suspend fun download(url: String): TextDownloadResult {
            urls += url
            val body = when {
                url.contains("action=get_live_categories") -> categoriesJson("1" to "USA News", "2" to "Hindi")
                url.contains("action=get_live_streams") -> """[{ "name": "News", "stream_id": 11, "category_id": "1" }]"""
                url.contains("action=get_vod_categories") -> categoriesJson("3" to "United States Movies", "4" to "Arabic", "7" to "General Movies")
                url.contains("action=get_vod_streams") -> """[{ "name": "Movie", "stream_id": 21, "category_id": "3" }]"""
                url.contains("action=get_series_categories") -> categoriesJson("5" to "English Series", "6" to "Korean", "8" to "General Series")
                url.contains("action=get_series") -> """[{ "name": "Series", "series_id": 31, "category_id": "5" }]"""
                else -> """{ "user_info": { "auth": 1, "status": "Active" }, "server_info": { "server_protocol": "http" } }"""
            }
            return TextDownloadResult.Success(text = body, byteCount = body.length.toLong())
        }

        private fun categoriesJson(vararg categories: Pair<String, String>): String =
            categories.joinToString(prefix = "[", postfix = "]") { (id, name) ->
                """{ "category_id": "$id", "category_name": "$name" }"""
            }
    }
}
