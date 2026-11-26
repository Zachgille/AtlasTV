package com.atlas.atlastv.playlist.debug

import com.atlas.atlastv.xtream.mapper.XtreamCatalogMapper
import com.atlas.atlastv.xtream.mapper.AtlasContentType
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamApiClient
import com.atlas.atlastv.xtream.network.XtreamApiResult
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder

class XtreamDebugLoader(
    private val apiClient: XtreamApiClient = XtreamApiClient(),
    private val mapper: XtreamCatalogMapper = XtreamCatalogMapper(),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    suspend fun load(
        credentials: XtreamCredentials,
        onState: (XtreamDebugLoadState) -> Unit,
    ) {
        val apiResult = apiClient.loadCatalog(credentials) { stage ->
            onState(XtreamDebugLoadState.Loading(stage.label))
        }

        when (apiResult) {
            is XtreamApiResult.Failure -> onState(
                XtreamDebugLoadState.Error(
                    message = apiResult.message,
                    redactedUrl = apiResult.redactedUrl,
                    endpointLabel = apiResult.endpointLabel,
                    failureCategory = apiResult.category.name,
                    httpStatus = apiResult.httpStatus,
                    responseByteCount = apiResult.responseByteCount,
                ),
            )
            is XtreamApiResult.Success -> {
                val catalog = mapper.map(
                    liveCategories = apiResult.liveCategories,
                    liveStreams = apiResult.liveStreams,
                    vodCategories = apiResult.vodCategories,
                    vodStreams = apiResult.vodStreams,
                    seriesCategories = apiResult.seriesCategories,
                    series = apiResult.series,
                )
                val summary = catalog.summary
                onState(
                    XtreamDebugLoadState.Success(
                        XtreamDebugSummary(
                            auth = apiResult.accountInfo.auth,
                            status = apiResult.accountInfo.status,
                            message = apiResult.accountInfo.message,
                            serverProtocol = apiResult.accountInfo.serverInfo?.serverProtocol,
                            serverPort = apiResult.accountInfo.serverInfo?.port,
                            liveCategoryCount = summary.liveCategoryCount,
                            liveStreamCount = summary.liveStreamCount,
                            vodCategoryCount = summary.vodCategoryCount,
                            vodStreamCount = summary.vodStreamCount,
                            seriesCategoryCount = summary.seriesCategoryCount,
                            seriesCount = summary.seriesCount,
                            keptLiveStreamCount = summary.keptLiveStreamCount,
                            keptVodStreamCount = summary.keptVodStreamCount,
                            keptSeriesCount = summary.keptSeriesCount,
                            excludedCategoryCount = summary.excludedCategoryCount,
                            excludedItemCount = summary.excludedItemCount,
                            topKeptCategories = summary.topKeptCategories,
                            topExcludedCategories = summary.topExcludedCategories,
                        ),
                        playableLiveStreams = catalog.items
                            .asSequence()
                            .filter { it.contentType == AtlasContentType.Live }
                            .mapNotNull { item ->
                                val streamId = item.streamId ?: return@mapNotNull null
                                DebugPlayableLiveStream(
                                    name = item.name.ifBlank { "Live stream $streamId" },
                                    categoryName = item.categoryName?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                                    streamId = streamId,
                                    streamUrl = endpointBuilder.buildLiveStream(credentials, streamId),
                                    redactedStreamUrl = endpointBuilder.buildRedactedLiveStream(credentials, streamId),
                                )
                            }
                            .take(MAX_PLAYABLE_LIVE_STREAMS)
                            .toList(),
                    ),
                )
            }
        }
    }

    private companion object {
        private const val MAX_PLAYABLE_LIVE_STREAMS = 12
    }
}
