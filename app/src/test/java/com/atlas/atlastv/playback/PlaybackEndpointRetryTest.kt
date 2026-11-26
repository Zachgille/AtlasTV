package com.atlas.atlastv.playback

import androidx.media3.common.PlaybackException
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackEndpointRetryTest {
    private val account = XtreamCredentials("https://primary.example/media", "opaque user/", "密碼 +?", listOf("https://backup.example/root", "http://last.example"))
    private val target = PlaybackEndpointTarget(WatchProgressContentType.Movie, 17, "mkv")
    private fun url(base: String, type: WatchProgressContentType? = target.type, id: Int = target.id): String {
        val physical = account.copy(baseUrl = base, alternateBaseUrls = emptyList())
        return when (type) {
            WatchProgressContentType.Movie -> XtreamEndpointBuilder().buildVodStream(physical, id, "mkv")
            WatchProgressContentType.SeriesEpisode -> XtreamEndpointBuilder().buildSeriesEpisodeStream(physical, id, "mkv")
            null -> XtreamEndpointBuilder().buildLiveStream(physical, id, "mkv")
        }
    }
    @Test fun onlyTransportTimeoutAndAvailabilityFailuresRotateEndpoints() {
        val network = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertTrue(canRecoverPlaybackEndpoint(network)); assertTrue(canRecoverPlaybackEndpoint(PlaybackException.ERROR_CODE_TIMEOUT))
        for (status in listOf(404, 410, 408, 500, 503, 504)) assertTrue(canRecoverPlaybackEndpoint(network, status))
        for (status in listOf(400, 401, 403, 429)) assertFalse(canRecoverPlaybackEndpoint(network, status))
        assertFalse(canRecoverPlaybackEndpoint(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(canRecoverPlaybackEndpoint(network, cause = java.io.IOException(javax.net.ssl.SSLHandshakeException("fixture"))))
    }
    @Test fun eachBackupIsTriedOnceAndSelectionAloneCannotPromoteAnEndpoint() {
        val routes = ProviderEndpointRecovery()
        val retry = PlaybackEndpointRetry(account, target, routes)
        val backup = retry.next(url(account.baseUrl), true, account)!!
        assertEquals(url(account.alternateBaseUrls[0]), backup)
        val last = retry.next(backup, true, account)!!
        assertEquals(url(account.alternateBaseUrls[1]), last)
        assertNull(retry.next(last, true, account)); assertNull(retry.next(last, true, account))
        assertEquals(account.baseUrl, routes.activeBaseUrl(account))
    }
    @Test fun typedIdentityOpaqueCredentialsAndEveryPathMustMatchExactly() {
        for (type in listOf(WatchProgressContentType.Movie, WatchProgressContentType.SeriesEpisode, null)) {
            val retry = PlaybackEndpointRetry(account, target.copy(type = type), ProviderEndpointRecovery())
            assertEquals(url(account.alternateBaseUrls[0], type), retry.next(url(account.baseUrl, type), true, account))
        }
        val retry = PlaybackEndpointRetry(account, target, ProviderEndpointRecovery())
        for (foreign in listOf("asset:///video.mp4", "https://unrelated.example/video", url(account.baseUrl, id = 18),
            url(account.baseUrl, WatchProgressContentType.SeriesEpisode), url(account.baseUrl) + "?extra=1"))
            assertNull(retry.next(foreign, true, account))
    }
    @Test fun accountChangesOrNoneligibleErrorsDoNotReplaceTheUrl() {
        val retry = PlaybackEndpointRetry(account, target, ProviderEndpointRecovery())
        assertNull(retry.next(url(account.baseUrl), false, account))
        assertNull(retry.next(url(account.baseUrl), true, null))
        assertNull(retry.next(url(account.baseUrl), true, account.copy(username = "another")))
        assertNull(retry.next(url(account.baseUrl), true, account.copy(password = "another")))
        assertNull(retry.next(url(account.baseUrl), true, account.copy(baseUrl = "https://different.example")))
    }
    @Test fun removingAnEndpointStopsNewAttemptsToItAndReturnsAnOwnedSessionToAnAllowedHost() {
        val retry = PlaybackEndpointRetry(account, target, ProviderEndpointRecovery())
        val backup = retry.next(url(account.baseUrl), true, account)!!
        val updated = account.copy(alternateBaseUrls = listOf(account.alternateBaseUrls[1]))
        assertEquals(url(account.alternateBaseUrls[1]), retry.next(backup, true, updated))
        val primaryOnly = account.copy(alternateBaseUrls = emptyList())
        assertEquals(url(account.baseUrl), retry.next(backup, true, primaryOnly))
    }
    @Test fun aRenderedBackupIsRememberedWithoutChangingTheCanonicalAccountOrCheckpoint() = runBlocking {
        val routes = ProviderEndpointRecovery(); var persisted: String? = null
        routes.persistUsing { _, base -> persisted = base }
        val retry = PlaybackEndpointRetry(account, target, routes)
        val recovery = PlaybackRecovery(255_000, seekable = true, requireFirstFrame = true)
        assertEquals(255_000L, recovery.beginAttempt()); recovery.markReady(); recovery.markFirstFrame()
        assertEquals(210_000L, recovery.checkpoint(210_000)) // A rewind must survive retry, too.
        val backup = retry.next(url(account.baseUrl), true, account)!!
        assertNull(persisted)
        assertEquals(210_000L, recovery.beginAttempt()); recovery.markReady()
        assertFalse(recovery.canSaveProgress); assertEquals(210_000L, recovery.checkpoint(0))
        recovery.markFirstFrame(); retry.rendered(backup, account)
        assertEquals(account.alternateBaseUrls[0], persisted)
        assertEquals(account.alternateBaseUrls[0], routes.activeBaseUrl(account)); assertEquals("https://primary.example/media", account.baseUrl)
        assertEquals(211_000L, recovery.checkpoint(211_000))
    }
    @Test fun invalidOrStaleRenderedCallbacksCannotPromoteAnUnapprovedRoute() = runBlocking {
        val routes = ProviderEndpointRecovery(); var writes = 0; routes.persistUsing { _, _ -> writes++ }
        val retry = PlaybackEndpointRetry(account, target, routes)
        retry.rendered("https://unlisted.example/video", account)
        retry.rendered(url(account.alternateBaseUrls[0]), account.copy(alternateBaseUrls = emptyList()))
        retry.rendered(url(account.alternateBaseUrls[0]), account.copy(password = "different"))
        assertEquals(0, writes); assertEquals(account.baseUrl, routes.activeBaseUrl(account))
    }
    @Test fun unsafeIdsAndExtensionsNeverCreateBackupRequests() {
        for (value in listOf(target.copy(id = 0), target.copy(extension = "../mp4")))
            assertNull(PlaybackEndpointRetry(account, value).next(url(account.baseUrl), true, account))
    }
}
