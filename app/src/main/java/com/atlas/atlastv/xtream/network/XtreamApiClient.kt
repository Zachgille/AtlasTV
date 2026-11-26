package com.atlas.atlastv.xtream.network

import com.atlas.atlastv.core.network.OkHttpTextDownloader
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloadFailureReason
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.playlist.filter.DEFAULT_EXCLUDED_CATEGORY_KEYWORDS
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.parser.XtreamJsonParser
import com.atlas.atlastv.xtream.parser.XtreamParseResult

class XtreamApiClient(
    private val downloader: TextDownloader = OkHttpTextDownloader(),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
    private val parser: XtreamJsonParser = XtreamJsonParser(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val recovery: ProviderEndpointRecovery = ProviderEndpointRecovery.Shared,
) {
    suspend fun testConnection(credentials: XtreamCredentials): XtreamApiResult = recovery.run(credentials,
        succeeded = { it is XtreamApiResult.Success },
        retryable = { it is XtreamApiResult.Failure && ProviderEndpointRecovery.unavailable(it.category, it.httpStatus) }) { resolved ->
        testConnectionOnce(resolved)
    }

    private suspend fun testConnectionOnce(credentials: XtreamCredentials): XtreamApiResult {
        val accountInfo = fetchAndParse(
            credentials = credentials,
            endpoint = XtreamEndpoint.AccountInfo,
            stage = XtreamLoadStage.AccountInfo,
            parse = parser::parseAccountInfo,
            onStage = {
                // No stage reporting needed for the lightweight Settings connection check.
            },
        ).valueOrFailure { return it }

        return XtreamApiResult.Success(
            accountInfo = accountInfo,
            liveCategories = emptyList(),
            liveStreams = emptyList(),
            vodCategories = emptyList(),
            vodStreams = emptyList(),
            seriesCategories = emptyList(),
            series = emptyList(),
        )
    }

    suspend fun loadCatalog(credentials: XtreamCredentials, onStage: (XtreamLoadStage) -> Unit = {}): XtreamApiResult =
        recovery.run(credentials, succeeded = { it is XtreamApiResult.Success },
            retryable = { it is XtreamApiResult.Failure && ProviderEndpointRecovery.unavailable(it.category, it.httpStatus) }) { resolved ->
            loadCatalogOnce(resolved, onStage)
        }

    private suspend fun loadCatalogOnce(
        credentials: XtreamCredentials,
        onStage: (XtreamLoadStage) -> Unit = {},
    ): XtreamApiResult {
        val accountInfo = fetchAndParse(credentials, XtreamEndpoint.AccountInfo, XtreamLoadStage.AccountInfo, parser::parseAccountInfo, onStage)
            .valueOrFailure { return it }
        val liveCategories = fetchAndParse(credentials, XtreamEndpoint.LiveCategories, XtreamLoadStage.LiveCategories, parser::parseCategories, onStage)
            .valueOrFailure { return it }
        val liveStreams = fetchAndParse(credentials, XtreamEndpoint.LiveStreams, XtreamLoadStage.LiveStreams, parser::parseLiveStreams, onStage)
            .valueOrFailure { return it }
        val vodCategories = fetchAndParse(credentials, XtreamEndpoint.VodCategories, XtreamLoadStage.VodCategories, parser::parseCategories, onStage)
            .valueOrFailure { return it }
        val vodStreams = fetchAndParseByCategory(credentials, XtreamEndpoint.VodStreams, XtreamLoadStage.VodStreams, vodCategories.keptPreDownloadCategoryIds(), parser::parseVodStreams, onStage)
            .valueOrFailure { return it }
        val seriesCategories = fetchAndParse(credentials, XtreamEndpoint.SeriesCategories, XtreamLoadStage.SeriesCategories, parser::parseCategories, onStage)
            .valueOrFailure { return it }
        val series = fetchAndParseByCategory(credentials, XtreamEndpoint.Series, XtreamLoadStage.Series, seriesCategories.keptPreDownloadCategoryIds(), parser::parseSeries, onStage)
            .valueOrFailure { return it }

        return XtreamApiResult.Success(
            accountInfo = accountInfo,
            liveCategories = liveCategories,
            liveStreams = liveStreams,
            vodCategories = vodCategories,
            vodStreams = vodStreams,
            seriesCategories = seriesCategories,
            series = series,
        )
    }

    suspend fun loadMoviesAndSeriesCatalog(
        credentials: XtreamCredentials,
        onStage: (XtreamLoadStage) -> Unit = {},
    ): XtreamApiResult = recovery.run(credentials,
        succeeded = { it is XtreamApiResult.Success },
        retryable = { it is XtreamApiResult.Failure && ProviderEndpointRecovery.unavailable(it.category, it.httpStatus) }) { resolved ->
        loadMoviesAndSeriesCatalogOnce(resolved, onStage)
    }

    private suspend fun loadMoviesAndSeriesCatalogOnce(
        credentials: XtreamCredentials,
        onStage: (XtreamLoadStage) -> Unit = {},
    ): XtreamApiResult {
        var timings = XtreamCatalogTimings()

        val accountInfoFetch = timedFetch(credentials, XtreamEndpoint.AccountInfo, XtreamLoadStage.AccountInfo, parser::parseAccountInfo, onStage)
        timings = timings.copy(accountInfoDurationMs = accountInfoFetch.durationMs)
        val accountInfo = accountInfoFetch.result.valueOrFailure { return it.copy(timings = timings) }

        val vodCategoryFetch = timedFetch(credentials, XtreamEndpoint.VodCategories, XtreamLoadStage.VodCategories, parser::parseCategories, onStage)
        val vodCategories = vodCategoryFetch.result.valueOrFailure { return it.copy(timings = timings.copy(vodCategoriesDurationMs = vodCategoryFetch.durationMs)) }
        timings = timings.copy(
            vodCategoriesDurationMs = vodCategoryFetch.durationMs,
            vodCategoryCount = vodCategories.size,
        )

        val vodStreamFetch = timedFetchByCategory(credentials, XtreamEndpoint.VodStreams, XtreamLoadStage.VodStreams, vodCategories.keptPreDownloadCategoryIds(), parser::parseVodStreams, onStage)
        val vodStreams = vodStreamFetch.result.valueOrFailure { return it.copy(timings = timings.copy(vodStreamsDurationMs = vodStreamFetch.durationMs)) }
        timings = timings.copy(
            vodStreamsDurationMs = vodStreamFetch.durationMs,
            vodStreamCount = vodStreams.size,
        )

        val seriesCategoryFetch = timedFetch(credentials, XtreamEndpoint.SeriesCategories, XtreamLoadStage.SeriesCategories, parser::parseCategories, onStage)
        val seriesCategories = seriesCategoryFetch.result.valueOrFailure { return it.copy(timings = timings.copy(seriesCategoriesDurationMs = seriesCategoryFetch.durationMs)) }
        timings = timings.copy(
            seriesCategoriesDurationMs = seriesCategoryFetch.durationMs,
            seriesCategoryCount = seriesCategories.size,
        )

        val seriesFetch = timedFetchByCategory(credentials, XtreamEndpoint.Series, XtreamLoadStage.Series, seriesCategories.keptPreDownloadCategoryIds(), parser::parseSeries, onStage)
        val series = seriesFetch.result.valueOrFailure { return it.copy(timings = timings.copy(seriesDurationMs = seriesFetch.durationMs)) }
        timings = timings.copy(
            seriesDurationMs = seriesFetch.durationMs,
            seriesCount = series.size,
        )

        return XtreamApiResult.Success(
            accountInfo = accountInfo,
            liveCategories = emptyList(),
            liveStreams = emptyList(),
            vodCategories = vodCategories,
            vodStreams = vodStreams,
            seriesCategories = seriesCategories,
            series = series,
            timings = timings,
        )
    }

    suspend fun loadCatalogCategories(
        credentials: XtreamCredentials,
        onStage: (XtreamLoadStage) -> Unit = {},
    ): XtreamApiResult = recovery.run(credentials,
        succeeded = { it is XtreamApiResult.Success },
        retryable = { it is XtreamApiResult.Failure && ProviderEndpointRecovery.unavailable(it.category, it.httpStatus) }) { resolved ->
        loadCatalogCategoriesOnce(resolved, onStage)
    }

    private suspend fun loadCatalogCategoriesOnce(
        credentials: XtreamCredentials,
        onStage: (XtreamLoadStage) -> Unit = {},
    ): XtreamApiResult {
        val accountInfo = fetchAndParse(credentials, XtreamEndpoint.AccountInfo, XtreamLoadStage.AccountInfo, parser::parseAccountInfo, onStage)
            .valueOrFailure { return it }
        val liveCategories = fetchAndParse(credentials, XtreamEndpoint.LiveCategories, XtreamLoadStage.LiveCategories, parser::parseCategories, onStage)
            .valueOrFailure { return it }
        val vodCategories = fetchAndParse(credentials, XtreamEndpoint.VodCategories, XtreamLoadStage.VodCategories, parser::parseCategories, onStage)
            .valueOrFailure { return it }
        val seriesCategories = fetchAndParse(credentials, XtreamEndpoint.SeriesCategories, XtreamLoadStage.SeriesCategories, parser::parseCategories, onStage)
            .valueOrFailure { return it }

        return XtreamApiResult.Success(
            accountInfo = accountInfo,
            liveCategories = liveCategories,
            liveStreams = emptyList(),
            vodCategories = vodCategories,
            vodStreams = emptyList(),
            seriesCategories = seriesCategories,
            series = emptyList(),
        )
    }

    private suspend fun <T> fetchAndParse(
        credentials: XtreamCredentials,
        endpoint: XtreamEndpoint,
        stage: XtreamLoadStage,
        parse: (String) -> XtreamParseResult<T>,
        onStage: (XtreamLoadStage) -> Unit,
        categoryId: String? = null,
    ): FetchResult<T> {
        onStage(stage)
        val url = endpointBuilder.build(credentials, endpoint, categoryId)
        val redactedUrl = endpointBuilder.buildRedacted(credentials, endpoint, categoryId)
        return when (val download = downloader.download(url)) {
            is TextDownloadResult.Failure -> FetchResult.Failure(
                XtreamApiResult.Failure(
                    message = download.message,
                    redactedUrl = redactedUrl,
                    endpointLabel = stage.label,
                    category = download.reason.toXtreamFailureCategory(),
                    httpStatus = download.httpStatus,
                    responseByteCount = download.responseByteCount,
                ),
            )
            is TextDownloadResult.Success -> when (val parsed = parse(download.text)) {
                is XtreamParseResult.Failure -> FetchResult.Failure(
                    XtreamApiResult.Failure(
                        message = parsed.message,
                        redactedUrl = redactedUrl,
                        endpointLabel = stage.label,
                        category = XtreamFailureCategory.MalformedJson,
                        responseByteCount = download.byteCount,
                    ),
                )
                is XtreamParseResult.Success -> {
                    val account = parsed.value as? com.atlas.atlastv.xtream.model.XtreamAccountInfo
                    if (endpoint == XtreamEndpoint.AccountInfo && (account?.auth != 1 ||
                        account.status?.let { !it.equals("Active", ignoreCase = true) } == true))
                        FetchResult.Failure(XtreamApiResult.Failure("Provider authentication failed.", redactedUrl,
                            stage.label, XtreamFailureCategory.Authentication))
                    else FetchResult.Success(parsed.value)
                }
            }
        }
    }

    private suspend fun <T> fetchAndParseByCategory(
        credentials: XtreamCredentials,
        endpoint: XtreamEndpoint,
        stage: XtreamLoadStage,
        categoryIds: List<String>,
        parse: (String) -> XtreamParseResult<List<T>>,
        onStage: (XtreamLoadStage) -> Unit,
    ): FetchResult<List<T>> {
        if (categoryIds.isEmpty()) return FetchResult.Success(emptyList())

        val items = mutableListOf<T>()
        categoryIds.forEach { categoryId ->
            when (val result = fetchAndParse(credentials, endpoint, stage, parse, onStage, categoryId)) {
                is FetchResult.Failure -> return result
                is FetchResult.Success -> items += result.value
            }
        }
        return FetchResult.Success(items)
    }

    private suspend fun <T> timedFetch(
        credentials: XtreamCredentials,
        endpoint: XtreamEndpoint,
        stage: XtreamLoadStage,
        parse: (String) -> XtreamParseResult<T>,
        onStage: (XtreamLoadStage) -> Unit,
        categoryId: String? = null,
    ): TimedFetch<T> {
        val startedAt = clockMs()
        val result = fetchAndParse(credentials, endpoint, stage, parse, onStage, categoryId)
        return TimedFetch(result, (clockMs() - startedAt).coerceAtLeast(0L))
    }

    private suspend fun <T> timedFetchByCategory(
        credentials: XtreamCredentials,
        endpoint: XtreamEndpoint,
        stage: XtreamLoadStage,
        categoryIds: List<String>,
        parse: (String) -> XtreamParseResult<List<T>>,
        onStage: (XtreamLoadStage) -> Unit,
    ): TimedFetch<List<T>> {
        val startedAt = clockMs()
        val result = fetchAndParseByCategory(credentials, endpoint, stage, categoryIds, parse, onStage)
        return TimedFetch(result, (clockMs() - startedAt).coerceAtLeast(0L))
    }

    private inline fun <T> FetchResult<T>.valueOrFailure(returnFailure: (XtreamApiResult.Failure) -> Nothing): T =
        when (this) {
            is FetchResult.Failure -> returnFailure(failure)
            is FetchResult.Success -> value
        }

    private sealed interface FetchResult<out T> {
        data class Success<T>(val value: T) : FetchResult<T>
        data class Failure(val failure: XtreamApiResult.Failure) : FetchResult<Nothing>
    }

    private data class TimedFetch<T>(
        val result: FetchResult<T>,
        val durationMs: Long,
    )

    private fun TextDownloadFailureReason.toXtreamFailureCategory(): XtreamFailureCategory =
        when (this) {
            TextDownloadFailureReason.InvalidUrl -> XtreamFailureCategory.InvalidUrl
            TextDownloadFailureReason.DnsFailure -> XtreamFailureCategory.DnsFailure
            TextDownloadFailureReason.CleartextBlocked -> XtreamFailureCategory.CleartextBlocked
            TextDownloadFailureReason.ConnectionRefused -> XtreamFailureCategory.ConnectionRefused
            TextDownloadFailureReason.TlsFailure -> XtreamFailureCategory.TlsFailure
            TextDownloadFailureReason.NetworkFailure -> XtreamFailureCategory.UnknownError
            TextDownloadFailureReason.Timeout -> XtreamFailureCategory.Timeout
            TextDownloadFailureReason.HttpStatus -> XtreamFailureCategory.Non2xxStatus
            TextDownloadFailureReason.EmptyResponse -> XtreamFailureCategory.EmptyResponse
        }

    private fun List<XtreamCategory>.keptPreDownloadCategoryIds(): List<String> =
        filter { category -> category.categoryName.isAllowedForPreDownload() }
            .mapNotNull { it.categoryId?.trim()?.takeIf(String::isNotBlank) }

    private fun String?.isAllowedForPreDownload(): Boolean {
        val category = this?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return false
        if (com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesCategory(category)) return false
        val isExcluded = DEFAULT_EXCLUDED_CATEGORY_KEYWORDS
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any(category::contains)
        if (isExcluded) return false

        return PreDownloadAllowedPhrases.any(category::contains) ||
            PreDownloadAllowedCodes.any { code -> category.containsToken(code) }
    }

    private fun String.containsToken(token: String): Boolean {
        val pattern = Regex("(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|$)")
        return pattern.containsMatchIn(this)
    }

    private companion object {
        private val PreDownloadAllowedPhrases = setOf(
            "united states",
            "united kingdom",
            "canada",
            "australia",
            "english",
            "recently added",
            "documentary",
            "children",
            "family",
            "netflix",
            "prime",
            "apple",
            "disney",
            "hulu",
            "hbo",
            "paramount",
            "peacock",
        )
        private val PreDownloadAllowedCodes = setOf("usa", "us", "uk", "ca", "en", "au", "4k", "uhd", "new", "kids", "anime", "max")
    }
}
