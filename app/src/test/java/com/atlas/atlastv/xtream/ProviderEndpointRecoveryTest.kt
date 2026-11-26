package com.atlas.atlastv.xtream

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import com.atlas.atlastv.ui.movies.MovieInfoLoader
import com.atlas.atlastv.ui.series.SeriesEpisodeLoader
import com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProviderEndpointRecoveryTest {
    private val account = XtreamCredentials("http://primary.example", "opaque user", "opaque/密碼 +?", listOf("http://backup.example", "https://last.example"))
    private fun text(value: String) = TextDownloadResult.Success(value, value.length.toLong())
    private val active = """{"user_info":{"auth":1,"status":"Active"}}"""
    private fun unavailable() = TextDownloadResult.Failure(TextDownloadFailureReason.ConnectionRefused, "Unavailable")
    private class Download(private val reply: (String) -> TextDownloadResult) : TextDownloader {
        val urls = mutableListOf<String>()
        override suspend fun download(url: String): TextDownloadResult { urls += url; return reply(url) }
    }
    @Test fun addressesNormalizeOriginsAndRejectCredentialBearingOrUnsupportedInput() {
        assertEquals("http://example.test/path", ProviderEndpointAddresses.normalize(" HTTP://EXAMPLE.TEST:80/path/ "))
        assertEquals("https://example.test", ProviderEndpointAddresses.normalize("https://example.test:443/"))
        for (raw in listOf("", "file:///private", "http://user:pass@example.test", "http://example.test?password=x", "http://example.test/#secret", "example.test"))
            assertNull(raw, ProviderEndpointAddresses.normalize(raw))
    }
    @Test fun unavailablePrimaryRecoversAndNextRequestPrefersTheSuccessfulBackup() = runBlocking {
        val routes = ProviderEndpointRecovery(); val chosen = mutableListOf<String>()
        routes.persistUsing { credentials, base -> assertEquals(account, credentials); chosen += base }
        val download = Download { if (it.startsWith(account.baseUrl)) unavailable() else text(active) }
        val client = XtreamApiClient(download, recovery = routes)
        assertTrue(client.testConnection(account) is XtreamApiResult.Success)
        assertEquals(2, download.urls.size); assertEquals("http://backup.example", routes.activeBaseUrl(account))
        assertTrue(client.testConnection(account) is XtreamApiResult.Success)
        assertEquals(3, download.urls.size); assertTrue(download.urls.last().startsWith("http://backup.example/"))
        assertEquals("http://primary.example", account.baseUrl); assertTrue(chosen.all { it == "http://backup.example" })
    }
    @Test fun wholeCatalogRestartsOnBackupWithoutMixingPrimaryCategoryPages() = runBlocking {
        val download = Download { url ->
            val primary = url.startsWith(account.baseUrl)
            when {
                !url.contains("action=") -> text(active)
                primary && url.contains("action=get_series_categories") -> TextDownloadResult.Failure(TextDownloadFailureReason.HttpStatus, "Unavailable", 503)
                url.contains("action=get_vod_categories") -> text("""[{"category_id":"${if(primary) 1 else 10}","category_name":"English Movies"}]""")
                url.contains("action=get_series_categories") -> text("""[{"category_id":"20","category_name":"English Shows"}]""")
                url.contains("action=get_vod_streams") -> text("""[{"stream_id":${if(primary) 1 else 100},"name":"Movie","category_id":"${if(primary) 1 else 10}"}]""")
                else -> text("""[{"series_id":200,"name":"Show","category_id":"20"}]""")
            }
        }
        val result = XtreamApiClient(download, recovery = ProviderEndpointRecovery()).loadMoviesAndSeriesCatalog(account) as XtreamApiResult.Success
        assertEquals(listOf(100), result.vodStreams.map { it.streamId }); assertEquals(listOf(200), result.series.map { it.seriesId })
        assertEquals("10", result.vodCategories.single().categoryId)
        val backup = download.urls.filter { it.startsWith("http://backup.example") }
        assertFalse(backup.first().contains("action=")); assertTrue(backup.any { it.contains("category_id=10") })
        assertFalse(backup.any { it.contains("category_id=1&") || it.endsWith("category_id=1") })
    }
    @Test fun badAuthenticationConfigurationAndRateLimitsNeverCycleThroughBackups() = runBlocking {
        val failures = listOf(text("""{"user_info":{"auth":0,"status":"Active"}}"""),
            text("""{"user_info":{"auth":1,"status":"Expired"}}"""), text("{broken"),
            TextDownloadResult.Failure(TextDownloadFailureReason.HttpStatus, "Denied", 401),
            TextDownloadResult.Failure(TextDownloadFailureReason.HttpStatus, "Limited", 429),
            TextDownloadResult.Failure(TextDownloadFailureReason.TlsFailure, "TLS"))
        for (failure in failures) {
            val download = Download { failure }
            assertTrue(XtreamApiClient(download, recovery = ProviderEndpointRecovery()).testConnection(account) is XtreamApiResult.Failure)
            assertEquals(1, download.urls.size)
        }
    }
    @Test fun attemptsAreBoundedToDeclaredHostsAndCancellationCannotSelectABackup() = runBlocking {
        val routes = ProviderEndpointRecovery(); var writes = 0; routes.persistUsing { _, _ -> writes++ }
        val many = account.copy(alternateBaseUrls = account.alternateBaseUrls + "http://extra.example", preferredBaseUrl = "http://unlisted.example")
        val download = Download { unavailable() }
        assertTrue(XtreamApiClient(download, recovery = routes).testConnection(many) is XtreamApiResult.Failure)
        assertEquals(3, download.urls.size); assertFalse(download.urls.any { it.contains("extra.example") || it.contains("unlisted.example") })
        val entered = CompletableDeferred<Unit>(); var attempts = 0
        val job = launch { routes.run<Boolean>(account, { it }, { true }) { attempts++; entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin(); assertEquals(1, attempts); assertEquals(0, writes)
    }
    @Test fun metadataAndEpisodesRecoverAndPlaybackReusesTheVerifiedRouteWithoutChangingCacheIdentity() = runBlocking {
        val routes = ProviderEndpointRecovery.Shared
        val unique = account.copy(baseUrl = "http://metadata-primary.example")
        val download = Download { url ->
            if (url.startsWith(unique.baseUrl)) unavailable()
            else if (url.contains("get_vod_info")) text("""{"info":{"plot":"A synopsis","releasedate":"2020-01-01"}}""")
            else text("""{"info":{"plot":"A show"},"episodes":{"1":[{"id":77,"episode_num":1,"title":"Pilot","container_extension":"mp4"}]}}""")
        }
        val key = com.atlas.atlastv.ui.series.providerDetailsKey(unique)
        assertEquals("A synopsis", MovieInfoLoader(download, recovery = routes).load(unique, 1)!!.synopsis)
        val result = SeriesEpisodeLoader(download, recovery = routes).loadEpisodes(unique, 2) as SeriesEpisodeLoadResult.Success
        assertEquals(77, result.episodes.single().episodeId)
        assertEquals(key, com.atlas.atlastv.ui.series.providerDetailsKey(unique))
        val builder = XtreamEndpointBuilder()
        assertTrue(builder.buildVodStream(unique, 1).startsWith("http://backup.example/movie/"))
        assertTrue(builder.buildSeriesEpisodeStream(unique, 77).startsWith("http://backup.example/series/"))
        assertTrue(builder.buildLiveStream(unique, 1).startsWith("http://backup.example/live/"))
        assertFalse(builder.buildRedactedVodStream(unique, 1).contains(unique.password))
    }
    @Test fun optionalRoutePersistenceFailureDoesNotTurnValidContentIntoFailure() = runBlocking {
        val routes = ProviderEndpointRecovery().apply { persistUsing { _, _ -> error("Private storage unavailable") } }
        val download = Download { if (it.startsWith(account.baseUrl)) unavailable() else text(active) }
        assertTrue(XtreamApiClient(download, recovery = routes).testConnection(account) is XtreamApiResult.Success)
        assertEquals("http://backup.example", routes.activeBaseUrl(account))
    }
}
