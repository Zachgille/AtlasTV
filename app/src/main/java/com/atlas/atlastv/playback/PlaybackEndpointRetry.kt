package com.atlas.atlastv.playback

import androidx.media3.common.PlaybackException
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.ProviderEndpointAddresses
import com.atlas.atlastv.xtream.network.ProviderEndpointRecovery
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder

data class PlaybackEndpointTarget(val type: WatchProgressContentType?, val id: Int, val extension: String)

/** Owns route attempts only. Player/session/checkpoint lifetime never depends on the selected host. */
class PlaybackEndpointRetry(private val initialAccount: XtreamCredentials?, private val target: PlaybackEndpointTarget,
    private val routes: ProviderEndpointRecovery = ProviderEndpointRecovery.Shared) {
    private val attempted = mutableSetOf<String>()
    private val builder = XtreamEndpointBuilder()
    private fun sameAccount(current: XtreamCredentials): Boolean = initialAccount?.let {
        ProviderEndpointAddresses.normalize(it.baseUrl) == ProviderEndpointAddresses.normalize(current.baseUrl) &&
            it.username == current.username && it.password == current.password
    } == true
    private fun urls(account: XtreamCredentials): Map<String, String> {
        if (target.id <= 0 || !target.extension.matches(Regex("[A-Za-z0-9]{1,12}"))) return emptyMap()
        return routes.candidates(account).associateWith { base ->
            val physical = account.copy(baseUrl = base, alternateBaseUrls = emptyList(), preferredBaseUrl = null)
            when (target.type) {
                WatchProgressContentType.Movie -> builder.buildVodStream(physical, target.id, target.extension)
                WatchProgressContentType.SeriesEpisode -> builder.buildSeriesEpisodeStream(physical, target.id, target.extension)
                null -> builder.buildLiveStream(physical, target.id, target.extension)
            }
        }
    }
    fun next(failedUrl: String, eligible: Boolean, account: XtreamCredentials?): String? {
        if (!eligible || account == null || !sameAccount(account)) return null
        val configured = urls(account)
        val failed = configured.entries.firstOrNull { it.value == failedUrl }?.key
            ?: initialAccount?.let { urls(it).entries.firstOrNull { entry -> entry.value == failedUrl }?.key } ?: return null
        synchronized(attempted) {
            attempted += failed
            val next = configured.entries.firstOrNull { it.key !in attempted }
                ?: configured.entries.firstOrNull()?.takeIf { failed !in configured } ?: return null
            attempted += next.key
            return next.value
        }
    }
    suspend fun rendered(url: String, account: XtreamCredentials?) {
        if (account == null || !sameAccount(account)) return
        val selected = urls(account).entries.firstOrNull { it.value == url }?.key ?: return
        synchronized(attempted) { attempted.clear() }
        routes.rememberVerified(account, selected)
    }
}

/** Account/rate-limit, certificate and codec failures keep ordinary Retry; they don't rotate servers. */
fun canRecoverPlaybackEndpoint(errorCode: Int, httpStatus: Int? = null, cause: Throwable? = null): Boolean {
    if (generateSequence(cause) { it.cause }.take(12).any { it is javax.net.ssl.SSLException }) return false
    if (httpStatus != null) return httpStatus in 500..599 || httpStatus in listOf(404, 410, 408)
    return errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT)
}
