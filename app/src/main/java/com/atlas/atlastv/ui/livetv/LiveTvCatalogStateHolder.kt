package com.atlas.atlastv.ui.livetv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.xtream.mapper.AtlasContentType
import com.atlas.atlastv.xtream.mapper.XtreamCatalogMapper
import com.atlas.atlastv.xtream.network.XtreamApiClient
import com.atlas.atlastv.xtream.network.XtreamApiResult
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LiveTvCatalogStateHolder(
    private val context: Context,
    private val mapper: LiveTvCatalogMapper = LiveTvCatalogMapper(),
    private val scope: CoroutineScope? = null,
    private val apiClient: XtreamApiClient = XtreamApiClient(),
    private val catalogMapper: XtreamCatalogMapper = XtreamCatalogMapper(),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
    private val cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
    private val favoritesStore: LiveTvFavoritesStore = LiveTvFavoritesStore(File(context.filesDir, LiveTvFavoritesStore.FileName)),
    private val preferencesStore: LiveTvPreferencesStore = LiveTvPreferencesStore(File(context.filesDir, LiveTvPreferencesStore.FileName)),
) {
    var state: LiveTvCatalogState by mutableStateOf(LiveTvCatalogState.Loading)
        private set

    private var refreshJob: Job? = null

    fun ensureCachedCatalog() {
        if (state is LiveTvCatalogState.Loading) {
            loadCachedCatalog()
        }
    }

    fun loadCachedCatalog() {
        val cacheJson = cacheStore.read()
        if (cacheJson == null) {
            state = LiveTvCatalogState.Error("No cached Live TV catalog found. Run the fast debug smoke or refresh command first.")
            return
        }

        state = runCatching {
            val previous = state as? LiveTvCatalogState.Ready
            val preferences = preferencesStore.load()
            val ready = mapper.mapCachedCatalogJson(cacheJson, DebugConfig.debugXtreamCredentials)
            ready.copy(cacheAgeMs = cacheStore.cacheAgeMs())
                .withRestoredContext(
                    categoryId = previous?.selectedCategoryId ?: preferences.selectedCategoryId,
                    streamId = previous?.selectedChannelStreamId ?: preferences.selectedChannelStreamId,
                    query = previous?.searchQuery ?: preferences.searchQuery,
                    favorites = previous?.favoriteStreamIds ?: favoritesStore.loadFavoriteIds(),
                    sortMode = previous?.sortMode ?: preferences.sortMode,
                )
        }.getOrElse { error ->
            LiveTvCatalogState.Error(error.message ?: "Cached Live TV catalog could not be loaded.")
        }
    }

    fun selectCategory(categoryId: String) {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        val nextSelectedChannel = ready.channelsForCategory(categoryId).firstOrNull()?.streamId
        state = ready.copy(
            selectedCategoryId = categoryId,
            selectedChannelStreamId = nextSelectedChannel,
            searchQuery = "",
            refreshStatus = null,
        ).withFavoriteCategories()
        savePreferences()
    }

    fun rememberChannel(channel: LiveTvChannel) {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        state = ready.copy(
            selectedChannelStreamId = channel.streamId,
            refreshStatus = null,
        )
        savePreferences()
    }

    fun cycleSortMode() {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        val nextSortMode = ready.sortMode.next()
        val sortedChannels = ready.copy(sortMode = nextSortMode).visibleChannels()
        val nextSelectedChannel = ready.selectedChannelStreamId?.takeIf { id -> sortedChannels.any { it.streamId == id } }
            ?: sortedChannels.firstOrNull()?.streamId
        state = ready.copy(
            sortMode = nextSortMode,
            selectedChannelStreamId = nextSelectedChannel,
            refreshStatus = null,
        )
        savePreferences()
    }

    fun toggleFavoriteForSelectedChannel() {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        val selectedStreamId = ready.selectedChannelStreamId ?: return
        val nextFavorites = if (selectedStreamId in ready.favoriteStreamIds) {
            ready.favoriteStreamIds - selectedStreamId
        } else {
            ready.favoriteStreamIds + selectedStreamId
        }
        val visibleAfterToggle = ready.copy(favoriteStreamIds = nextFavorites).visibleChannels()
        val nextSelectedStreamId = selectedStreamId.takeIf { id -> visibleAfterToggle.any { it.streamId == id } }
            ?: visibleAfterToggle.firstOrNull()?.streamId
        state = ready.copy(
            favoriteStreamIds = nextFavorites,
            selectedChannelStreamId = nextSelectedStreamId,
            refreshStatus = null,
        ).withFavoriteCategories()
        favoritesStore.saveFavorites(ready.channels, nextFavorites)
        savePreferences()
    }

    fun clearFavorites() {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        state = ready.copy(
            selectedCategoryId = LiveTvCategoryIds.Favorites,
            favoriteStreamIds = emptySet(),
            selectedChannelStreamId = null,
            refreshStatus = null,
        ).withFavoriteCategories()
        favoritesStore.clear()
        savePreferences()
    }

    fun onSearchQueryChanged(query: String) {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        val safeQuery = query.take(MaxSearchQueryLength)
        val nextSelectedChannel = if (safeQuery.isBlank()) {
            ready.channelsForCategory().firstOrNull { it.streamId == ready.selectedChannelStreamId }?.streamId
                ?: ready.channelsForCategory().firstOrNull()?.streamId
        } else {
            ready.channels.searchLiveTv(safeQuery, ready.favoriteStreamIds).firstOrNull()?.streamId
        }
        state = ready.copy(
            searchQuery = safeQuery,
            selectedChannelStreamId = nextSelectedChannel,
            refreshStatus = null,
        )
        savePreferences()
    }

    fun clearSearch() {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        if (ready.searchQuery.isBlank()) return
        val selectedChannel = ready.channelsForCategory().firstOrNull { it.streamId == ready.selectedChannelStreamId }
            ?: ready.channelsForCategory().firstOrNull()
        state = ready.copy(
            searchQuery = "",
            selectedChannelStreamId = selectedChannel?.streamId,
            refreshStatus = null,
        ).withFavoriteCategories()
        savePreferences()
    }

    fun resetLiveTvPreferences() {
        val ready = state as? LiveTvCatalogState.Ready
        preferencesStore.clear()
        if (ready != null) {
            val resetState = ready.copy(
                selectedCategoryId = LiveTvCategoryIds.All,
                selectedChannelStreamId = ready.channels.firstOrNull()?.streamId,
                sortMode = LiveTvSortMode.Provider,
                searchQuery = "",
                refreshStatus = null,
            ).withFavoriteCategories()
            state = resetState
        }
    }

    fun refreshRealCatalog() {
        val coroutineScope = scope ?: return
        val credentials = DebugConfig.debugXtreamCredentials
        if (credentials == null) {
            val previous = state as? LiveTvCatalogState.Ready
            state = previous?.copy(refreshStatus = "Refresh unavailable: missing debug config.")
                ?: LiveTvCatalogState.Error("Refresh unavailable: missing debug config.")
            return
        }

        refreshJob?.cancel()
        val previous = state as? LiveTvCatalogState.Ready
        state = LiveTvCatalogState.Refreshing(previous)
        refreshJob = coroutineScope.launch {
            state = when (val result = apiClient.loadCatalog(credentials)) {
                is XtreamApiResult.Failure -> previous?.copy(refreshStatus = "Refresh failed: ${result.category.name}.")
                    ?: LiveTvCatalogState.Error("Refresh failed: ${result.category.name}.")
                is XtreamApiResult.Success -> {
                    val catalog = catalogMapper.map(
                        liveCategories = result.liveCategories,
                        liveStreams = result.liveStreams,
                        vodCategories = result.vodCategories,
                        vodStreams = result.vodStreams,
                        seriesCategories = result.seriesCategories,
                        series = result.series,
                    )
                    val snapshot = LiveTvCacheSnapshot(
                        catalogSource = "real",
                        generatedAtEpochMs = System.currentTimeMillis(),
                        itemCounts = LiveTvCacheItemCounts(
                            liveCategories = catalog.summary.liveCategoryCount,
                            liveStreams = catalog.summary.liveStreamCount,
                            vodCategories = catalog.summary.vodCategoryCount,
                            vodStreams = catalog.summary.vodStreamCount,
                            seriesCategories = catalog.summary.seriesCategoryCount,
                            series = catalog.summary.seriesCount,
                            keptLiveStreams = catalog.summary.keptLiveStreamCount,
                            keptVodStreams = catalog.summary.keptVodStreamCount,
                            keptSeries = catalog.summary.keptSeriesCount,
                            excludedCategories = catalog.summary.excludedCategoryCount,
                            excludedItems = catalog.summary.excludedItemCount,
                        ),
                        redactedUrl = endpointBuilder.buildRedacted(credentials, com.atlas.atlastv.xtream.network.XtreamEndpoint.AccountInfo),
                        redactedHost = "REDACTED_HOST",
                        playableLiveItems = catalog.items
                            .asSequence()
                            .filter { it.contentType == AtlasContentType.Live }
                            .mapNotNull { item ->
                                val streamId = item.streamId ?: return@mapNotNull null
                                LiveTvCacheLiveItem(
                                    name = item.name.ifBlank { "Live stream $streamId" },
                                    categoryName = item.categoryName?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                                    streamId = streamId,
                                    containerExtension = DefaultLiveExtension,
                                    redactedUrl = endpointBuilder.buildRedactedLiveStream(credentials, streamId),
                                )
                            }
                            .take(LiveTvCatalogMapper.MaxLiveChannels)
                            .toList(),
                    )
                    val snapshotJson = cacheJson.toJson(snapshot)
                    cacheStore.write(snapshotJson)
                    val ready = mapper.mapCachedCatalogJson(snapshotJson, credentials)
                    ready.copy(cacheAgeMs = 0L, refreshStatus = "Refresh complete.")
                        .withRestoredContext(
                            categoryId = previous?.selectedCategoryId,
                            streamId = previous?.selectedChannelStreamId,
                            query = previous?.searchQuery,
                            favorites = previous?.favoriteStreamIds ?: favoritesStore.loadFavoriteIds(),
                            sortMode = previous?.sortMode ?: preferencesStore.load().sortMode,
                        )
                }
            }
        }
    }

    private fun savePreferences() {
        val ready = state as? LiveTvCatalogState.Ready ?: return
        preferencesStore.save(
            LiveTvPreferences(
                selectedCategoryId = ready.selectedCategoryId,
                selectedChannelStreamId = ready.selectedChannelStreamId,
                sortMode = ready.sortMode,
                searchQuery = ready.searchQuery,
            ),
        )
    }

    companion object {
        const val CacheFileName = LiveTvCachedCatalogStore.FileName
        private const val MaxSearchQueryLength = DefaultMaxSearchQueryLength
        private const val DefaultLiveExtension = "ts"
        private val cacheJson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

fun LiveTvCatalogState.Ready.channelsForCategory(categoryId: String = selectedCategoryId): List<LiveTvChannel> =
    when (categoryId) {
        LiveTvCategoryIds.All -> channels
        LiveTvCategoryIds.Favorites -> channels.filter { it.streamId in favoriteStreamIds }
        else -> channels.filter { it.categoryName == categoryId }
    }.sortedForLiveTv(sortMode, favoriteStreamIds)

fun LiveTvCatalogState.Ready.withFavoriteCategories(): LiveTvCatalogState.Ready =
    copy(categories = channels.toLiveTvCategories(favoriteStreamIds))

private data class LiveTvCacheSnapshot(
    val catalogSource: String,
    val generatedAtEpochMs: Long,
    val itemCounts: LiveTvCacheItemCounts,
    val redactedUrl: String?,
    val redactedHost: String?,
    val playableLiveItems: List<LiveTvCacheLiveItem>,
)

private data class LiveTvCacheItemCounts(
    val liveCategories: Int = 0,
    val liveStreams: Int = 0,
    val vodCategories: Int = 0,
    val vodStreams: Int = 0,
    val seriesCategories: Int = 0,
    val series: Int = 0,
    val keptLiveStreams: Int = 0,
    val keptVodStreams: Int = 0,
    val keptSeries: Int = 0,
    val excludedCategories: Int = 0,
    val excludedItems: Int = 0,
)

private data class LiveTvCacheLiveItem(
    val name: String,
    val categoryName: String,
    val streamId: Int,
    val containerExtension: String,
    val redactedUrl: String,
)
