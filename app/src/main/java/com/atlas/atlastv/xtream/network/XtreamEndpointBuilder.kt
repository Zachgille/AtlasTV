package com.atlas.atlastv.xtream.network

import com.atlas.atlastv.core.security.UrlRedactor
import com.atlas.atlastv.xtream.model.XtreamCredentials
import java.net.URLEncoder

class XtreamEndpointBuilder {
    fun build(
        credentials: XtreamCredentials,
        endpoint: XtreamEndpoint,
        categoryId: String? = null,
        seriesId: Int? = null,
        vodId: Int? = null,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val query = buildList {
            add("username=${credentials.username.urlEncodeQuery()}")
            add("password=${credentials.password.urlEncodeQuery()}")
            endpoint.action?.let { add("action=${it.urlEncodeQuery()}") }
            categoryId?.trim()?.takeIf { it.isNotBlank() }?.let { add("category_id=${it.urlEncodeQuery()}") }
            seriesId?.let { add("series_id=$it") }
            vodId?.let { add("vod_id=$it") }
        }.joinToString("&")

        return "$baseUrl/player_api.php?$query"
    }

    fun buildRedacted(
        credentials: XtreamCredentials,
        endpoint: XtreamEndpoint,
        categoryId: String? = null,
        seriesId: Int? = null,
        vodId: Int? = null,
    ): String = UrlRedactor.redactHostAndSensitiveQuery(build(credentials, endpoint, categoryId, seriesId, vodId))

    fun buildLiveStream(
        credentials: XtreamCredentials,
        streamId: Int,
        extension: String = DEFAULT_LIVE_EXTENSION,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val safeExtension = extension.trim().trimStart('.').takeIf { it.isNotBlank() } ?: DEFAULT_LIVE_EXTENSION
        return "$baseUrl/live/${credentials.username.urlEncodePathSegment()}/${credentials.password.urlEncodePathSegment()}/$streamId.$safeExtension"
    }

    fun buildRedactedLiveStream(
        credentials: XtreamCredentials,
        streamId: Int,
        extension: String = DEFAULT_LIVE_EXTENSION,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val safeExtension = extension.trim().trimStart('.').takeIf { it.isNotBlank() } ?: DEFAULT_LIVE_EXTENSION
        val scheme = baseUrl.substringBefore("://", missingDelimiterValue = "http")
        return "$scheme://REDACTED_HOST/live/REDACTED/REDACTED/$streamId.$safeExtension"
    }

    fun buildVodStream(
        credentials: XtreamCredentials,
        streamId: Int,
        extension: String = DEFAULT_VOD_EXTENSION,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val safeExtension = extension.trim().trimStart('.').takeIf { it.isNotBlank() } ?: DEFAULT_VOD_EXTENSION
        return "$baseUrl/movie/${credentials.username.urlEncodePathSegment()}/${credentials.password.urlEncodePathSegment()}/$streamId.$safeExtension"
    }

    fun buildRedactedVodStream(
        credentials: XtreamCredentials,
        streamId: Int,
        extension: String = DEFAULT_VOD_EXTENSION,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val safeExtension = extension.trim().trimStart('.').takeIf { it.isNotBlank() } ?: DEFAULT_VOD_EXTENSION
        val scheme = baseUrl.substringBefore("://", missingDelimiterValue = "http")
        return "$scheme://REDACTED_HOST/movie/REDACTED/REDACTED/$streamId.$safeExtension"
    }

    fun buildSeriesEpisodeStream(
        credentials: XtreamCredentials,
        episodeId: Int,
        extension: String = DEFAULT_VOD_EXTENSION,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val safeExtension = extension.trim().trimStart('.').takeIf { it.isNotBlank() } ?: DEFAULT_VOD_EXTENSION
        return "$baseUrl/series/${credentials.username.urlEncodePathSegment()}/${credentials.password.urlEncodePathSegment()}/$episodeId.$safeExtension"
    }

    fun buildRedactedSeriesEpisodeStream(
        credentials: XtreamCredentials,
        episodeId: Int,
        extension: String = DEFAULT_VOD_EXTENSION,
    ): String {
        val baseUrl = ProviderEndpointRecovery.Shared.activeBaseUrl(credentials).trim().trimEnd('/')
        val safeExtension = extension.trim().trimStart('.').takeIf { it.isNotBlank() } ?: DEFAULT_VOD_EXTENSION
        val scheme = baseUrl.substringBefore("://", missingDelimiterValue = "http")
        return "$scheme://REDACTED_HOST/series/REDACTED/REDACTED/$episodeId.$safeExtension"
    }

    private fun String.urlEncodeQuery(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.urlEncodePathSegment(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        private const val DEFAULT_LIVE_EXTENSION = "ts"
        private const val DEFAULT_VOD_EXTENSION = "mp4"
    }
}
