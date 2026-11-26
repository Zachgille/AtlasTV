package com.atlas.atlastv.debug

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.atlas.atlastv.data.catalog.CatalogSyncRepository
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.catalog.XtreamCatalogImporter
import com.atlas.atlastv.data.db.SafeCatalogItemEntity
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.playlist.filter.DEFAULT_EXCLUDED_CATEGORY_KEYWORDS
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressPolicy
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.playback.LivePlaybackRequestConfig
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogMapper
import com.atlas.atlastv.ui.livetv.LiveTvFavoritesStore
import com.atlas.atlastv.ui.livetv.LiveTvPreferencesStore
import com.atlas.atlastv.ui.livetv.selectedChannelDetails
import com.atlas.atlastv.ui.livetv.withRestoredContext
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.movies.MovieCategoryIds
import com.atlas.atlastv.ui.movies.MoviesCatalogMapper
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.movies.withRestoredContext
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import com.atlas.atlastv.ui.settings.ProviderSettingsSnapshot
import com.atlas.atlastv.ui.settings.ProviderSyncStatusSnapshot
import com.atlas.atlastv.ui.settings.toProviderSyncStatus
import com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult
import com.atlas.atlastv.ui.series.SeriesEpisodeLoader
import com.atlas.atlastv.xtream.mapper.AtlasContentType
import com.atlas.atlastv.xtream.mapper.XtreamCatalogMapper
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream
import com.atlas.atlastv.xtream.network.XtreamApiClient
import com.atlas.atlastv.xtream.network.XtreamApiResult
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import okhttp3.OkHttpClient
import okhttp3.Headers
import okhttp3.Request
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class DebugCommandExecutor(
    context: Context,
    private val apiClient: XtreamApiClient = XtreamApiClient(),
    private val mapper: XtreamCatalogMapper = XtreamCatalogMapper(),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val store = DebugCommandFileStore(context.applicationContext)
    private val appContext = context.applicationContext

    suspend fun execute(command: String, streamId: Int? = null, durationSeconds: Int? = null): DebugCommandResult {
        val normalizedCommand = command.trim().lowercase()
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "Starting command=$normalizedCommand")

        val result = runCatching {
            when (normalizedCommand) {
                "health_check" -> healthCheck(startedAt)
                "load_fixture_catalog" -> loadFixtureCatalog(startedAt)
                "load_cached_catalog" -> loadCachedCatalog(startedAt)
                "refresh_real_catalog" -> refreshRealCatalog(startedAt)
                "seed_saved_provider_from_debug_config" -> seedSavedProviderFromDebugConfig(startedAt)
                "sync_saved_provider_catalog" -> syncSavedProviderCatalog(startedAt)
                "audit_english_catalog_coverage" -> auditEnglishCatalogCoverage(startedAt)
                "probe_full_catalog_search" -> probeFullCatalogSearch(startedAt)
                "clear_room_catalog" -> clearRoomCatalog(startedAt)
                "run_fixture_catalog_import" -> runFixtureCatalogImport(startedAt)
                "export_safe_catalog_cache_snapshot" -> exportSafeCatalogCacheSnapshot(startedAt)
                "import_safe_catalog_cache_snapshot" -> importSafeCatalogCacheSnapshot(startedAt)
                "dump_catalog_cache_snapshot_state" -> dumpCatalogCacheSnapshotState(startedAt)
                "clear_safe_catalog_cache_snapshot" -> clearSafeCatalogCacheSnapshot(startedAt)
                "probe_live_stream" -> probeLiveStream(startedAt, streamId, includeActualPlayback = false)
                "probe_media3_live_stream" -> probeLiveStream(startedAt, streamId, includeActualPlayback = true)
                "probe_live_render" -> probeLiveRender(startedAt, streamId)
                "probe_vod_render" -> probeVodRender(startedAt, streamId)
                "probe_series_episode_render" -> probeSeriesEpisodeRender(startedAt, streamId)
                "probe_playback_health_sweep" -> probePlaybackHealthSweep(startedAt)
                "probe_playback_deep_health" -> probePlaybackDeepHealth(startedAt, durationSeconds)
                "clear_debug_cache" -> clearDebugCache(startedAt)
                "dump_current_state" -> dumpCurrentState(startedAt)
                "dump_live_tv_state" -> dumpLiveTvState(startedAt)
                "dump_movies_state" -> dumpMoviesState(startedAt)
                "dump_watch_progress_state" -> dumpWatchProgressState(startedAt)
                "seed_fixture_watch_progress" -> seedFixtureWatchProgress(startedAt)
                "clear_watch_progress" -> clearWatchProgress(startedAt)
                else -> failure(
                    command = normalizedCommand.ifBlank { "unknown" },
                    startedAt = startedAt,
                    currentState = "unknown_command",
                    catalogSource = DebugCatalogSource.None,
                    lastErrorCategory = "UnknownCommand",
                    message = "Unknown debug command.",
                )
            }
        }.getOrElse { error ->
            failure(
                command = normalizedCommand.ifBlank { "unknown" },
                startedAt = startedAt,
                currentState = "exception",
                catalogSource = DebugCatalogSource.None,
                lastErrorCategory = "CommandException",
                message = error.message ?: error::class.java.simpleName,
            )
        }

        store.writeResult(result)
        if (result.status == DebugCommandStatus.Failure) {
            store.writeLastError(
                DebugLastError(
                    command = result.command,
                    category = result.lastErrorCategory ?: "Unknown",
                    message = result.message ?: "Command failed.",
                    redactedUrl = result.redactedUrl,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        Log.i(TAG, "Finished command=${result.command} status=${result.status} durationMs=${result.durationMs}")
        return result
    }

    private fun healthCheck(startedAt: Long): DebugCommandResult {
        val now = System.currentTimeMillis()
        val cacheAgeMs = store.cacheAgeMs(now)
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "healthy",
            catalogSource = store.readCatalog()?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = store.readCatalog()?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = cacheAgeMs,
            lastErrorCategory = null,
            redactedUrl = DebugConfig.debugXtreamConfigStatus.redactedBaseUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(DebugConfig.debugXtreamConfigStatus.redactedBaseUrl),
        )
        store.writeState(state)
        return success("health_check", startedAt, state, "Debug command receiver is available.")
    }

    private fun loadFixtureCatalog(startedAt: Long): DebugCommandResult {
        val now = System.currentTimeMillis()
        val snapshot = DebugCatalogSnapshot(
            catalogSource = DebugCatalogSource.Fixture,
            generatedAtEpochMs = now,
            itemCounts = DebugItemCounts(
                liveCategories = 2,
                liveStreams = 3,
                vodCategories = 2,
                vodStreams = 3,
                seriesCategories = 2,
                series = 3,
                keptLiveStreams = 3,
                keptVodStreams = 3,
                keptSeries = 3,
                excludedCategories = 1,
                excludedItems = 1,
            ),
            redactedUrl = "fixture://debug_catalog",
            redactedHost = "fixture",
            playableLiveItems = listOf(
                DebugPlayableLiveItem(
                    name = "Fixture Live One",
                    categoryName = "Fixture News",
                    streamId = 1001,
                    containerExtension = DEFAULT_LIVE_EXTENSION,
                    redactedUrl = "fixture://debug_catalog/live/1001.ts",
                ),
                DebugPlayableLiveItem(
                    name = "Fixture Live Two",
                    categoryName = "Fixture Sports",
                    streamId = 1002,
                    containerExtension = DEFAULT_LIVE_EXTENSION,
                    redactedUrl = "fixture://debug_catalog/live/1002.ts",
                ),
                DebugPlayableLiveItem(
                    name = "Fixture Live Three",
                    categoryName = "Fixture Movies",
                    streamId = 1003,
                    containerExtension = DEFAULT_LIVE_EXTENSION,
                    redactedUrl = "fixture://debug_catalog/live/1003.ts",
                ),
            ),
            playableVodItems = listOf(
                DebugPlayableVodItem(
                    title = "Fixture Movie One",
                    categoryName = "Fixture Action",
                    streamId = 2001,
                    containerExtension = DEFAULT_VOD_EXTENSION,
                    redactedUrl = "fixture://debug_catalog/movie/2001.mp4",
                    posterImage = "fixture://poster/fixture-movie-one",
                ),
                DebugPlayableVodItem(
                    title = "Fixture Movie Two",
                    categoryName = "Fixture Drama",
                    streamId = 2002,
                    containerExtension = DEFAULT_VOD_EXTENSION,
                    redactedUrl = "fixture://debug_catalog/movie/2002.mp4",
                    posterImage = "fixture://poster/fixture-movie-two",
                ),
                DebugPlayableVodItem(
                    title = "Fixture Movie Three",
                    categoryName = "Fixture Action",
                    streamId = 2003,
                    containerExtension = "mkv",
                    redactedUrl = "fixture://debug_catalog/movie/2003.mkv",
                    posterImage = null,
                ),
            ),
            playableSeriesItems = listOf(
                DebugPlayableSeriesItem(
                    name = "Fixture Series One",
                    categoryName = "Fixture Series",
                    seriesId = 3001,
                    posterImage = "fixture://poster/fixture-series-one",
                ),
                DebugPlayableSeriesItem(
                    name = "Fixture Series Two",
                    categoryName = "Fixture Drama",
                    seriesId = 3002,
                    posterImage = "fixture://poster/fixture-series-two",
                ),
                DebugPlayableSeriesItem(
                    name = "Fixture Series Three",
                    categoryName = "Fixture Series",
                    seriesId = 3003,
                    posterImage = null,
                ),
            ),
        )
        store.writeCatalog(snapshot)
        val state = snapshot.toState("fixture_catalog_loaded", cacheAgeMs = 0L)
        store.writeState(state)
        return success("load_fixture_catalog", startedAt, state, "Fixture catalog loaded.")
    }

    private fun loadCachedCatalog(startedAt: Long): DebugCommandResult {
        val now = System.currentTimeMillis()
        val snapshot = store.readCatalog() ?: return failure(
            command = "load_cached_catalog",
            startedAt = startedAt,
            currentState = "cache_missing",
            catalogSource = DebugCatalogSource.Cached,
            lastErrorCategory = "CacheMissing",
            message = "No debug catalog cache exists.",
        )
        val state = snapshot.copy(catalogSource = DebugCatalogSource.Cached)
            .toState("cached_catalog_loaded", store.cacheAgeMs(now))
        store.writeState(state)
        return success("load_cached_catalog", startedAt, state, "Cached catalog loaded.")
    }

    private suspend fun clearRoomCatalog(startedAt: Long): DebugCommandResult {
        withContext(Dispatchers.IO) {
            SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao()).clearCatalog()
        }
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "room_catalog_cleared",
            catalogSource = "room",
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = null,
            redactedHost = null,
        )
        store.writeState(state)
        return success("clear_room_catalog", startedAt, state, "Room catalog cleared.")
    }

    private suspend fun runFixtureCatalogImport(startedAt: Long): DebugCommandResult {
        val repository = SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao())
        val importer = XtreamCatalogImporter(repository)
        val result = withContext(Dispatchers.IO) {
            importer.importMoviesAndSeries(
                vodCategories = fixtureImportVodCategories(),
                vodStreams = fixtureImportVodStreams(),
                seriesCategories = fixtureImportSeriesCategories(),
                series = fixtureImportSeries(),
            )
        }
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "room_catalog_imported",
            catalogSource = "room",
            itemCounts = DebugItemCounts(
                vodCategories = 3,
                vodStreams = LARGE_FIXTURE_MOVIE_COUNT + LARGE_FIXTURE_EXCLUDED_MOVIE_COUNT,
                seriesCategories = 3,
                series = LARGE_FIXTURE_SERIES_COUNT + LARGE_FIXTURE_EXCLUDED_SERIES_COUNT,
                keptVodStreams = result.moviesImported,
                keptSeries = result.seriesImported,
                excludedCategories = result.excludedCategories,
                excludedItems = result.excludedItems,
            ),
            cacheAgeMs = 0L,
            lastErrorCategory = result.errorCategory,
            redactedUrl = "room://safe_catalog/import",
            redactedHost = null,
        )
        store.writeState(state)
        return success(
            command = "run_fixture_catalog_import",
            startedAt = startedAt,
            state = state,
            message = "Fixture Movies/Series catalog imported into Room.",
            catalogImport = DebugCatalogImportState(
                status = result.status.name.lowercase(),
                startedAtMs = result.startedAtMs,
                completedAtMs = result.completedAtMs,
                durationMs = result.durationMs,
                moviesImported = result.moviesImported,
                seriesImported = result.seriesImported,
                excludedCategories = result.excludedCategories,
                excludedItems = result.excludedItems,
                errorCategory = result.errorCategory,
                totalDurationMs = result.timings.totalDurationMs.takeIf { it > 0L } ?: result.durationMs,
                accountInfoDurationMs = result.timings.accountInfoDurationMs,
                vodCategoriesDurationMs = result.timings.vodCategoriesDurationMs,
                vodStreamsDurationMs = result.timings.vodStreamsDurationMs,
                seriesCategoriesDurationMs = result.timings.seriesCategoriesDurationMs,
                seriesDurationMs = result.timings.seriesDurationMs,
                filteringDurationMs = result.timings.filteringDurationMs,
                roomWriteDurationMs = result.timings.roomWriteDurationMs,
                vodCategoryCount = result.timings.vodCategoryCount,
                vodStreamCount = result.timings.vodStreamCount,
                seriesCategoryCount = result.timings.seriesCategoryCount,
                seriesCount = result.timings.seriesCount,
            ),
        )
    }

    private suspend fun refreshRealCatalog(startedAt: Long): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "refresh_real_catalog",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Local Xtream debug config is not available.",
        )

        return when (val result = apiClient.loadCatalog(credentials)) {
            is XtreamApiResult.Failure -> failure(
                command = "refresh_real_catalog",
                startedAt = startedAt,
                currentState = "real_catalog_failed",
                catalogSource = DebugCatalogSource.Real,
                lastErrorCategory = result.category.name,
                redactedUrl = result.redactedUrl,
                message = result.message,
            )
            is XtreamApiResult.Success -> {
                val catalog = mapper.map(
                    liveCategories = result.liveCategories,
                    liveStreams = result.liveStreams,
                    vodCategories = result.vodCategories,
                    vodStreams = result.vodStreams,
                    seriesCategories = result.seriesCategories,
                    series = result.series,
                )
                val summary = catalog.summary
                val snapshot = DebugCatalogSnapshot(
                    catalogSource = DebugCatalogSource.Real,
                    generatedAtEpochMs = System.currentTimeMillis(),
                    itemCounts = DebugItemCounts(
                        liveCategories = summary.liveCategoryCount,
                        liveStreams = summary.liveStreamCount,
                        vodCategories = summary.vodCategoryCount,
                        vodStreams = summary.vodStreamCount,
                        seriesCategories = summary.seriesCategoryCount,
                        series = summary.seriesCount,
                        keptLiveStreams = summary.keptLiveStreamCount,
                        keptVodStreams = summary.keptVodStreamCount,
                        keptSeries = summary.keptSeriesCount,
                        excludedCategories = summary.excludedCategoryCount,
                        excludedItems = summary.excludedItemCount,
                    ),
                    redactedUrl = DebugCommandSanitizer.redactedUrl(credentials.baseUrl),
                    redactedHost = DebugCommandSanitizer.redactedHost(credentials.baseUrl),
                    playableLiveItems = catalog.items
                        .asSequence()
                        .filter { it.contentType == AtlasContentType.Live }
                        .mapNotNull { item ->
                            val streamId = item.streamId ?: return@mapNotNull null
                            DebugPlayableLiveItem(
                                name = item.name.ifBlank { "Live stream $streamId" },
                                categoryName = item.categoryName?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                                streamId = streamId,
                                containerExtension = DEFAULT_LIVE_EXTENSION,
                                redactedUrl = endpointBuilder.buildRedactedLiveStream(credentials, streamId),
                            )
                        }
                        .take(MAX_PLAYABLE_LIVE_ITEMS)
                        .toList(),
                    playableVodItems = catalog.items
                        .asSequence()
                        .filter { it.contentType == AtlasContentType.Movie }
                        .mapNotNull { item ->
                            val streamId = item.streamId ?: return@mapNotNull null
                            val extension = item.containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() }
                                ?: DEFAULT_VOD_EXTENSION
                            DebugPlayableVodItem(
                                title = item.name.ifBlank { "Movie $streamId" },
                                categoryName = item.categoryName?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                                streamId = streamId,
                                containerExtension = extension,
                                redactedUrl = endpointBuilder.buildRedactedVodStream(credentials, streamId, extension),
                                posterImage = item.iconUrl.safeDebugImageReference(),
                            )
                        }
                        .take(MAX_PLAYABLE_VOD_ITEMS)
                        .toList(),
                    playableSeriesItems = catalog.items
                        .asSequence()
                        .filter { it.contentType == AtlasContentType.Series }
                        .mapNotNull { item ->
                            val seriesId = item.seriesId ?: return@mapNotNull null
                            DebugPlayableSeriesItem(
                                name = item.name.ifBlank { "Series $seriesId" },
                                categoryName = item.categoryName?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                                seriesId = seriesId,
                                posterImage = item.iconUrl.safeDebugImageReference(),
                            )
                        }
                        .take(MAX_PLAYABLE_SERIES_ITEMS)
                        .toList(),
                )
                store.writeCatalog(snapshot)
                val state = snapshot.toState("real_catalog_loaded", cacheAgeMs = 0L)
                store.writeState(state)
                success("refresh_real_catalog", startedAt, state, "Real catalog refreshed.")
            }
        }
    }

    private suspend fun syncSavedProviderCatalog(startedAt: Long): DebugCommandResult {
        val settingsStore = providerSettingsStore()
        val credentials = settingsStore.load().settings.credentialsOrNull() ?: return failure(
            command = "sync_saved_provider_catalog",
            startedAt = startedAt,
            currentState = "missing_saved_provider",
            catalogSource = "room",
            lastErrorCategory = "MissingProviderSettings",
            message = "Saved provider settings are not available.",
        )
        val repository = SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao())
        val syncRepository = CatalogSyncRepository(
            apiClient = apiClient,
            importer = XtreamCatalogImporter(repository),
        )
        store.writeState(
            DebugStateSnapshot(
                currentScreen = "debug_command",
                currentState = "saved_provider_catalog_syncing",
                catalogSource = "room",
                itemCounts = DebugItemCounts(),
                cacheAgeMs = null,
                lastErrorCategory = null,
                redactedUrl = DebugCommandSanitizer.redactedUrl(credentials.baseUrl),
                redactedHost = DebugCommandSanitizer.redactedHost(credentials.baseUrl),
            ),
        )
        val previousStatus = settingsStore.load().syncStatus
        val loadingStatus = previousStatus.copy(
            status = CatalogSyncStatus.Loading,
            lastStartedAtMs = startedAt,
            errorCategory = null,
            currentStage = "Starting Movies/Shows sync",
        )
        settingsStore.saveSyncStatus(loadingStatus)
        val result = withTimeoutOrNull(SAVED_PROVIDER_SYNC_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { syncRepository.syncMoviesAndSeries(credentials) }
        }
        if (result == null) {
            val timeoutStatus = ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Failure,
                lastStartedAtMs = startedAt,
                lastCompletedAtMs = System.currentTimeMillis(),
                lastSuccessfulSyncAtMs = previousStatus.lastSuccessfulSyncAtMs,
                lastFailedSyncAtMs = System.currentTimeMillis(),
                moviesImported = previousStatus.moviesImported,
                seriesImported = previousStatus.seriesImported,
                excludedCategories = previousStatus.excludedCategories,
                excludedItems = previousStatus.excludedItems,
                errorCategory = "SyncTimeout",
                currentStage = "Timed out",
                totalDurationMs = System.currentTimeMillis() - startedAt,
            )
            settingsStore.saveSyncStatus(timeoutStatus)
            val timeoutState = DebugStateSnapshot(
                currentScreen = "debug_command",
                currentState = "saved_provider_catalog_failed",
                catalogSource = "room",
                itemCounts = DebugItemCounts(
                    keptVodStreams = timeoutStatus.moviesImported,
                    keptSeries = timeoutStatus.seriesImported,
                    excludedCategories = timeoutStatus.excludedCategories,
                    excludedItems = timeoutStatus.excludedItems,
                ),
                cacheAgeMs = null,
                lastErrorCategory = "SyncTimeout",
                redactedUrl = DebugCommandSanitizer.redactedUrl(credentials.baseUrl),
                redactedHost = DebugCommandSanitizer.redactedHost(credentials.baseUrl),
            )
            store.writeState(timeoutState)
            return success(
                command = "sync_saved_provider_catalog",
                startedAt = startedAt,
                state = timeoutState,
                message = "Saved provider Movies/Series catalog sync timed out.",
                catalogImport = DebugCatalogImportState(
                    status = "failure",
                    startedAtMs = startedAt,
                    completedAtMs = timeoutStatus.lastCompletedAtMs ?: System.currentTimeMillis(),
                    durationMs = timeoutStatus.totalDurationMs,
                    moviesImported = timeoutStatus.moviesImported,
                    seriesImported = timeoutStatus.seriesImported,
                    excludedCategories = timeoutStatus.excludedCategories,
                    excludedItems = timeoutStatus.excludedItems,
                    errorCategory = "SyncTimeout",
                    totalDurationMs = timeoutStatus.totalDurationMs,
                ),
            )
        }
        settingsStore.saveSyncStatus(result.toProviderSyncStatus())
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = if (result.status == CatalogSyncStatus.Success) "saved_provider_catalog_synced" else "saved_provider_catalog_failed",
            catalogSource = "room",
            itemCounts = DebugItemCounts(
                keptVodStreams = result.moviesImported,
                keptSeries = result.seriesImported,
                excludedCategories = result.excludedCategories,
                excludedItems = result.excludedItems,
            ),
            cacheAgeMs = 0L.takeIf { result.status == CatalogSyncStatus.Success },
            lastErrorCategory = result.errorCategory,
            redactedUrl = DebugCommandSanitizer.redactedUrl(credentials.baseUrl),
            redactedHost = DebugCommandSanitizer.redactedHost(credentials.baseUrl),
        )
        store.writeState(state)
        return success(
            command = "sync_saved_provider_catalog",
            startedAt = startedAt,
            state = state,
            message = "Saved provider Movies/Series catalog sync completed.",
            catalogImport = DebugCatalogImportState(
                status = result.status.name.lowercase(),
                startedAtMs = result.startedAtMs,
                completedAtMs = result.completedAtMs,
                durationMs = result.durationMs,
                moviesImported = result.moviesImported,
                seriesImported = result.seriesImported,
                excludedCategories = result.excludedCategories,
                excludedItems = result.excludedItems,
                errorCategory = result.errorCategory,
                totalDurationMs = result.timings.totalDurationMs.takeIf { it > 0L } ?: result.durationMs,
                accountInfoDurationMs = result.timings.accountInfoDurationMs,
                vodCategoriesDurationMs = result.timings.vodCategoriesDurationMs,
                vodStreamsDurationMs = result.timings.vodStreamsDurationMs,
                seriesCategoriesDurationMs = result.timings.seriesCategoriesDurationMs,
                seriesDurationMs = result.timings.seriesDurationMs,
                filteringDurationMs = result.timings.filteringDurationMs,
                roomWriteDurationMs = result.timings.roomWriteDurationMs,
                vodCategoryCount = result.timings.vodCategoryCount,
                vodStreamCount = result.timings.vodStreamCount,
                seriesCategoryCount = result.timings.seriesCategoryCount,
                seriesCount = result.timings.seriesCount,
            ),
        )
    }

    private fun seedSavedProviderFromDebugConfig(startedAt: Long): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "seed_saved_provider_from_debug_config",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = "settings",
            lastErrorCategory = "MissingConfig",
            message = "Local Xtream debug config is not available.",
        )
        providerSettingsStore().saveSettings(
            ProviderSettingsSnapshot(
                baseUrl = credentials.baseUrl,
                username = credentials.username,
                password = credentials.password,
            ),
        )
        val redactedUrl = DebugCommandSanitizer.redactedUrl(credentials.baseUrl)
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "saved_provider_seeded",
            catalogSource = "settings",
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = redactedUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(redactedUrl),
        )
        store.writeState(state)
        return success(
            command = "seed_saved_provider_from_debug_config",
            startedAt = startedAt,
            state = state,
            message = "Saved provider settings were seeded from local debug config.",
        )
    }

    private suspend fun auditEnglishCatalogCoverage(startedAt: Long): DebugCommandResult {
        val credentials = providerSettingsStore().load().settings.credentialsOrNull() ?: return failure(
            command = "audit_english_catalog_coverage",
            startedAt = startedAt,
            currentState = "missing_saved_provider",
            catalogSource = "room",
            lastErrorCategory = "MissingProviderSettings",
            message = "Saved provider settings are not available.",
        )
        val repository = SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao())
        val apiResult = withContext(Dispatchers.IO) { apiClient.loadCatalogCategories(credentials) }
        val audit = when (apiResult) {
            is XtreamApiResult.Failure -> DebugCatalogCoverageAudit(
                status = "failure",
                redactedHost = DebugCommandSanitizer.redactedHost(apiResult.redactedUrl),
                vodCategoriesAvailable = 0,
                vodCategoriesAllowedForDownload = 0,
                vodCategoriesImported = withContext(Dispatchers.IO) { repository.movieCategorySummaries().size },
                vodItemsFetched = 0,
                vodItemsImported = withContext(Dispatchers.IO) { repository.countSearchMovies("") },
                seriesCategoriesAvailable = 0,
                seriesCategoriesAllowedForDownload = 0,
                seriesCategoriesImported = withContext(Dispatchers.IO) { repository.seriesCategorySummaries().size },
                seriesItemsFetched = 0,
                seriesItemsImported = withContext(Dispatchers.IO) { repository.countSearchSeries("") },
                excludedCategoryCount = 0,
                excludedItemCount = 0,
                skippedOrGenericCategoryCount = 0,
                usefulEnglishSkippedCategoryCount = 0,
                allowedVodCategoryExamples = emptyList(),
                allowedSeriesCategoryExamples = emptyList(),
                importedVodCategoryExamples = emptyList(),
                importedSeriesCategoryExamples = emptyList(),
                skippedCategoryExamples = emptyList(),
                usefulEnglishSkippedCategoryExamples = emptyList(),
                excludedCategoryExamples = emptyList(),
                duplicateOrVariantEnglishExamples = emptyList(),
            )
            is XtreamApiResult.Success -> buildCatalogCoverageAudit(credentials.baseUrl, apiResult, repository)
        }
        store.writeCatalogAudit(audit)
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = if (audit.status == "success") "catalog_coverage_audited" else "catalog_coverage_audit_failed",
            catalogSource = "room",
            itemCounts = DebugItemCounts(
                vodCategories = audit.vodCategoriesAvailable,
                vodStreams = audit.vodItemsFetched,
                seriesCategories = audit.seriesCategoriesAvailable,
                series = audit.seriesItemsFetched,
                keptVodStreams = audit.vodItemsImported,
                keptSeries = audit.seriesItemsImported,
                excludedCategories = audit.excludedCategoryCount,
                excludedItems = audit.excludedItemCount,
            ),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = DebugCommandSanitizer.redactedUrl(credentials.baseUrl),
            redactedHost = audit.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "audit_english_catalog_coverage",
            startedAt = startedAt,
            state = state,
            message = "English catalog coverage audit completed.",
            catalogAudit = audit,
        )
    }

    private suspend fun probeFullCatalogSearch(startedAt: Long): DebugCommandResult {
        val repository = SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao())
        val report = withContext(Dispatchers.IO) {
            val movieTotal = repository.countSearchMovies("")
            val seriesTotal = repository.countSearchSeries("")
            val offset = listOf(movieTotal, seriesTotal)
                .filter { it > 0 }
                .minOrNull()
                ?.let { minOf(DEEP_SEARCH_OFFSET, (it - 1).coerceAtLeast(0)) }
                ?: 0
            val deepMovie = repository.movieEntityAtOffset(offset)?.toSearchProbeItem()
            val deepSeries = repository.seriesEntityAtOffset(offset)?.toSearchProbeItem()
            val movieSearch = deepMovie?.let { item -> searchProbeResult(repository, SafeCatalogContentTypes.Movie, item) }
            val seriesSearch = deepSeries?.let { item -> searchProbeResult(repository, SafeCatalogContentTypes.Series, item) }
            DebugSearchReachabilityReport(
                status = if (movieTotal > 0 || seriesTotal > 0) "success" else "empty_catalog",
                movieTotal = movieTotal,
                seriesTotal = seriesTotal,
                deepOffsetUsed = offset,
                deepMovie = deepMovie,
                deepSeries = deepSeries,
                movieSearch = movieSearch,
                seriesSearch = seriesSearch,
                unifiedSearch = DebugUnifiedSearchProbeResult(
                    query = unifiedProbeQuery(deepMovie, deepSeries),
                    movieMatches = movieSearch?.totalMatches ?: 0,
                    seriesMatches = seriesSearch?.totalMatches ?: 0,
                    boundedMovieResultCount = movieSearch?.boundedResultCount ?: 0,
                    boundedSeriesResultCount = seriesSearch?.boundedResultCount ?: 0,
                ),
            )
        }
        store.writeSearchReachability(report)
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "full_catalog_search_probed",
            catalogSource = "room",
            itemCounts = DebugItemCounts(
                keptVodStreams = report.movieTotal,
                keptSeries = report.seriesTotal,
            ),
            cacheAgeMs = null,
            lastErrorCategory = report.status.takeIf { it != "success" },
            redactedUrl = null,
            redactedHost = null,
        )
        store.writeState(state)
        return success(
            command = "probe_full_catalog_search",
            startedAt = startedAt,
            state = state,
            message = "Full catalog search reachability probe completed.",
            searchReachability = report,
        )
    }

    private fun exportSafeCatalogCacheSnapshot(startedAt: Long): DebugCommandResult {
        val now = System.currentTimeMillis()
        val catalog = store.readCatalog() ?: return failure(
            command = "export_safe_catalog_cache_snapshot",
            startedAt = startedAt,
            currentState = "catalog_cache_missing",
            catalogSource = DebugCatalogSource.Cached,
            lastErrorCategory = "CacheMissing",
            message = "No debug catalog cache exists to export.",
        )
        val snapshot = DebugSafeCatalogCacheSnapshot(
            schemaVersion = SAFE_CATALOG_SNAPSHOT_SCHEMA_VERSION,
            exportedAtEpochMs = now,
            cacheAgeMs = store.cacheAgeMs(now),
            catalog = catalog.copy(catalogSource = catalog.catalogSource.ifBlank { DebugCatalogSource.Cached }),
        )
        val validation = validateSafeCatalogCacheSnapshot(snapshot)
        if (validation != null) {
            return failure(
                command = "export_safe_catalog_cache_snapshot",
                startedAt = startedAt,
                currentState = "catalog_snapshot_unsafe",
                catalogSource = catalog.catalogSource,
                lastErrorCategory = validation,
                message = "Catalog cache snapshot was not exported because it did not pass safety validation.",
            )
        }
        store.writeSafeCatalogCacheSnapshot(snapshot)
        val state = catalog.toState("safe_catalog_cache_snapshot_exported", store.cacheAgeMs(now))
        store.writeState(state)
        return success(
            command = "export_safe_catalog_cache_snapshot",
            startedAt = startedAt,
            state = state,
            message = "Safe catalog cache snapshot exported.",
            catalogCacheSnapshotState = snapshot.toSnapshotState(exists = true),
        )
    }

    private suspend fun buildCatalogCoverageAudit(
        baseUrl: String,
        result: XtreamApiResult.Success,
        repository: SafeCatalogRepository,
    ): DebugCatalogCoverageAudit {
        val vodCategoryNames = result.vodCategories.mapNotNull { it.categoryName.safeAuditCategoryName() }
        val seriesCategoryNames = result.seriesCategories.mapNotNull { it.categoryName.safeAuditCategoryName() }
        val allCategories = (vodCategoryNames + seriesCategoryNames).distinctSafeNames()
        val excludedCategories = allCategories.filter(::isAuditExcludedCategory)
        val skippedCategories = allCategories.filter { !isAuditExcludedCategory(it) && !isAuditAllowedCategory(it) }
        val usefulSkipped = skippedCategories.filter(::looksUsefulEnglishCategory)
        val importedVod = withContext(Dispatchers.IO) { repository.movieCategorySummaries() }
        val importedSeries = withContext(Dispatchers.IO) { repository.seriesCategorySummaries() }
        val importedVodNames = importedVod.map { it.name }.distinctSafeNames()
        val importedSeriesNames = importedSeries.map { it.name }.distinctSafeNames()
        val importedCategoryNames = (importedVodNames + importedSeriesNames).distinctSafeNames()
        val importedVodItemCounts = importedVod.associateBy({ it.name.lowercase() }, { it.itemCount })
        val importedSeriesItemCounts = importedSeries.associateBy({ it.name.lowercase() }, { it.itemCount })
        val vodItemCounts = result.vodStreams.groupingBy { it.categoryId }.eachCount()
        val seriesItemCounts = result.series.groupingBy { it.categoryId }.eachCount()
        val categoryEntries =
            result.vodCategories.mapNotNull { category ->
                val name = category.categoryName.safeAuditCategoryName()
                category.toCategoryAuditEntry(
                    contentType = "movie",
                    itemCount = vodItemCounts[category.categoryId]
                        ?: name?.let { importedVodItemCounts[it.lowercase()] }
                        ?: 0,
                )
            } + result.seriesCategories.mapNotNull { category ->
                val name = category.categoryName.safeAuditCategoryName()
                category.toCategoryAuditEntry(
                    contentType = "series",
                    itemCount = seriesItemCounts[category.categoryId]
                        ?: name?.let { importedSeriesItemCounts[it.lowercase()] }
                        ?: 0,
                )
            }
        val importedMovieCount = withContext(Dispatchers.IO) { repository.countSearchMovies("") }
        val importedSeriesCount = withContext(Dispatchers.IO) { repository.countSearchSeries("") }
        val excludedItemCount = result.vodStreams.count { stream ->
            val categoryName = result.vodCategories.firstOrNull { it.categoryId == stream.categoryId }?.categoryName.safeAuditCategoryName()
            categoryName != null && isAuditExcludedCategory(categoryName)
        } + result.series.count { item ->
            val categoryName = result.seriesCategories.firstOrNull { it.categoryId == item.categoryId }?.categoryName.safeAuditCategoryName()
            categoryName != null && isAuditExcludedCategory(categoryName)
        }
        return DebugCatalogCoverageAudit(
            status = "success",
            redactedHost = DebugCommandSanitizer.redactedHost(baseUrl),
            vodCategoriesAvailable = vodCategoryNames.distinctSafeNames().size,
            vodCategoriesAllowedForDownload = vodCategoryNames.filter(::isAuditAllowedCategory).distinctSafeNames().size,
            vodCategoriesImported = importedVodNames.size,
            vodItemsFetched = result.vodStreams.size.takeIf { it > 0 } ?: importedMovieCount,
            vodItemsImported = importedMovieCount,
            seriesCategoriesAvailable = seriesCategoryNames.distinctSafeNames().size,
            seriesCategoriesAllowedForDownload = seriesCategoryNames.filter(::isAuditAllowedCategory).distinctSafeNames().size,
            seriesCategoriesImported = importedSeriesNames.size,
            seriesItemsFetched = result.series.size.takeIf { it > 0 } ?: importedSeriesCount,
            seriesItemsImported = importedSeriesCount,
            excludedCategoryCount = excludedCategories.size,
            excludedItemCount = excludedItemCount,
            skippedOrGenericCategoryCount = skippedCategories.size,
            usefulEnglishSkippedCategoryCount = usefulSkipped.size,
            allowedVodCategoryExamples = vodCategoryNames.filter(::isAuditAllowedCategory).distinctSafeNames().take(EXAMPLE_CATEGORY_LIMIT),
            allowedSeriesCategoryExamples = seriesCategoryNames.filter(::isAuditAllowedCategory).distinctSafeNames().take(EXAMPLE_CATEGORY_LIMIT),
            importedVodCategoryExamples = importedVodNames.take(EXAMPLE_CATEGORY_LIMIT),
            importedSeriesCategoryExamples = importedSeriesNames.take(EXAMPLE_CATEGORY_LIMIT),
            skippedCategoryExamples = skippedCategories.take(EXAMPLE_CATEGORY_LIMIT),
            usefulEnglishSkippedCategoryExamples = usefulSkipped.take(EXAMPLE_CATEGORY_LIMIT),
            excludedCategoryExamples = excludedCategories.take(EXAMPLE_CATEGORY_LIMIT),
            duplicateOrVariantEnglishExamples = importedCategoryNames
                .groupBy { it.normalizedAuditCategoryFamily() }
                .values
                .filter { it.size > 1 }
                .flatten()
                .take(EXAMPLE_CATEGORY_LIMIT),
            categories = categoryEntries.sortedWith(
                compareBy<DebugCatalogCategoryAuditEntry> { it.contentType }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.categoryName },
            ),
        )
    }

    private fun com.atlas.atlastv.xtream.model.XtreamCategory.toCategoryAuditEntry(
        contentType: String,
        itemCount: Int,
    ): DebugCatalogCategoryAuditEntry? {
        val safeName = categoryName.safeAuditCategoryName() ?: return null
        val safeId = categoryId?.safeDebugText(defaultValue = "")?.takeIf { it.isNotBlank() }
        val decision = when {
            isAuditExcludedCategory(safeName) -> "exclude"
            isAuditAllowedCategory(safeName) -> "keep"
            else -> "exclude"
        }
        val reason = when {
            isAuditExcludedCategory(safeName) -> "excluded_keyword_or_foreign_prefix"
            isAuditAllowedCategory(safeName) -> "allowed_english_or_branded_category"
            looksUsefulEnglishCategory(safeName) -> "review_needed_potentially_useful_english"
            else -> "generic_or_unmatched_category"
        }
        return DebugCatalogCategoryAuditEntry(
            categoryId = safeId,
            categoryName = safeName,
            contentType = contentType,
            itemCount = itemCount,
            decision = decision,
            reason = reason,
        )
    }

    private suspend fun searchProbeResult(
        repository: SafeCatalogRepository,
        contentType: String,
        item: DebugSearchProbeItem,
    ): DebugSearchProbeResult {
        val query = item.title.toSearchProbeQuery()
        val matches = if (contentType == SafeCatalogContentTypes.Movie) {
            repository.searchMovieEntities(query, SEARCH_PROBE_LIMIT)
        } else {
            repository.searchSeriesEntities(query, SEARCH_PROBE_LIMIT)
        }
        val total = if (contentType == SafeCatalogContentTypes.Movie) {
            repository.countSearchMovies(query)
        } else {
            repository.countSearchSeries(query)
        }
        return DebugSearchProbeResult(
            query = query,
            totalMatches = total,
            boundedResultCount = matches.size,
            foundSelectedItem = matches.any { it.itemId == item.id },
            firstResultIds = matches.take(SEARCH_RESULT_ID_LIMIT).map { it.itemId },
        )
    }

    private fun SafeCatalogItemEntity.toSearchProbeItem(): DebugSearchProbeItem =
        DebugSearchProbeItem(
            id = itemId,
            title = title.safeDebugText("Item $itemId"),
            categoryName = categoryName.safeDebugText("Uncategorized"),
        )

    private fun unifiedProbeQuery(movie: DebugSearchProbeItem?, series: DebugSearchProbeItem?): String =
        movie?.title?.toSearchProbeQuery()
            ?: series?.title?.toSearchProbeQuery()
            ?: ""

    private fun String.toSearchProbeQuery(): String =
        split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString(" ")
            .ifBlank { take(40) }

    private fun String?.safeAuditCategoryName(): String? =
        this?.safeDebugText(defaultValue = "")?.takeIf { it.isNotBlank() }

    private fun String.safeDebugText(defaultValue: String): String {
        val trimmed = trim()
        return when {
            trimmed.isBlank() -> defaultValue
            trimmed.contains("://") -> defaultValue
            trimmed.contains("/live/", ignoreCase = true) -> defaultValue
            trimmed.contains("/movie/", ignoreCase = true) -> defaultValue
            trimmed.contains("/series/", ignoreCase = true) -> defaultValue
            else -> trimmed.take(MAX_SAFE_DEBUG_TEXT_LENGTH)
        }
    }

    private fun List<String>.distinctSafeNames(): List<String> =
        map { it.safeDebugText("") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun isAuditExcludedCategory(categoryName: String): Boolean {
        val category = categoryName.lowercase()
        return DEFAULT_EXCLUDED_CATEGORY_KEYWORDS
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any(category::contains)
    }

    private fun isAuditAllowedCategory(categoryName: String): Boolean {
        val category = categoryName.lowercase()
        if (isAuditExcludedCategory(categoryName)) return false
        return AuditAllowedPhrases.any(category::contains) ||
            AuditAllowedCodes.any { code -> category.containsAuditToken(code) }
    }

    private fun looksUsefulEnglishCategory(categoryName: String): Boolean {
        val category = categoryName.lowercase()
        return PotentiallyUsefulEnglishPhrases.any(category::contains) ||
            PotentiallyUsefulEnglishCodes.any { code -> category.containsAuditToken(code) }
    }

    private fun String.containsAuditToken(token: String): Boolean {
        val pattern = Regex("(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|$)")
        return pattern.containsMatchIn(this)
    }

    private fun String.normalizedAuditCategoryFamily(): String =
        lowercase()
            .replace(Regex("""\b(4k|uhd|hd|fhd|new|latest|recently added)\b"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    private fun importSafeCatalogCacheSnapshot(startedAt: Long): DebugCommandResult {
        val snapshot = store.readSafeCatalogCacheSnapshot() ?: return failure(
            command = "import_safe_catalog_cache_snapshot",
            startedAt = startedAt,
            currentState = "catalog_snapshot_missing",
            catalogSource = DebugCatalogSource.Cached,
            lastErrorCategory = "SnapshotMissing",
            message = "No safe catalog cache snapshot exists to import.",
        )
        val validation = validateSafeCatalogCacheSnapshot(snapshot)
        if (validation != null) {
            return failure(
                command = "import_safe_catalog_cache_snapshot",
                startedAt = startedAt,
                currentState = "catalog_snapshot_invalid",
                catalogSource = snapshot.catalog.catalogSource,
                lastErrorCategory = validation,
                message = "Safe catalog cache snapshot is stale, invalid, or unsafe.",
            )
        }
        val restored = snapshot.catalog.copy(catalogSource = DebugCatalogSource.Cached)
        store.writeCatalog(restored)
        val state = restored.toState("safe_catalog_cache_snapshot_imported", cacheAgeMs = 0L)
        store.writeState(state)
        return success(
            command = "import_safe_catalog_cache_snapshot",
            startedAt = startedAt,
            state = state,
            message = "Safe catalog cache snapshot imported.",
            catalogCacheSnapshotState = snapshot.toSnapshotState(exists = true),
        )
    }

    private fun dumpCatalogCacheSnapshotState(startedAt: Long): DebugCommandResult {
        val snapshot = store.readSafeCatalogCacheSnapshot()
        if (snapshot == null) {
            val state = DebugStateSnapshot(
                currentScreen = "debug_command",
                currentState = "catalog_snapshot_missing",
                catalogSource = DebugCatalogSource.None,
                itemCounts = DebugItemCounts(),
                cacheAgeMs = null,
                lastErrorCategory = "SnapshotMissing",
                redactedUrl = null,
                redactedHost = null,
            )
            store.writeState(state)
            return success(
                command = "dump_catalog_cache_snapshot_state",
                startedAt = startedAt,
                state = state,
                message = "No safe catalog cache snapshot exists.",
                catalogCacheSnapshotState = DebugCatalogCacheSnapshotState(
                    exists = false,
                    schemaVersion = null,
                    exportedAtEpochMs = null,
                    cacheAgeMs = null,
                    catalogSource = DebugCatalogSource.None,
                    itemCounts = DebugItemCounts(),
                    redactedUrl = null,
                    redactedHost = null,
                ),
            )
        }
        val validation = validateSafeCatalogCacheSnapshot(snapshot)
        val state = snapshot.catalog.toState(
            currentState = if (validation == null) "catalog_snapshot_state_dumped" else "catalog_snapshot_invalid",
            cacheAgeMs = snapshot.cacheAgeMs,
        )
        store.writeState(state)
        return success(
            command = "dump_catalog_cache_snapshot_state",
            startedAt = startedAt,
            state = state,
            message = if (validation == null) "Safe catalog cache snapshot state dumped." else "Safe catalog cache snapshot exists but is invalid: $validation.",
            catalogCacheSnapshotState = snapshot.toSnapshotState(exists = true),
        )
    }

    private fun clearSafeCatalogCacheSnapshot(startedAt: Long): DebugCommandResult {
        store.clearSafeCatalogCacheSnapshot()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "catalog_snapshot_cleared",
            catalogSource = store.readCatalog()?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = store.readCatalog()?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = null,
            redactedUrl = store.readCatalog()?.redactedUrl,
            redactedHost = store.readCatalog()?.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "clear_safe_catalog_cache_snapshot",
            startedAt = startedAt,
            state = state,
            message = "Safe catalog cache snapshot cleared.",
            catalogCacheSnapshotState = DebugCatalogCacheSnapshotState(
                exists = false,
                schemaVersion = null,
                exportedAtEpochMs = null,
                cacheAgeMs = null,
                catalogSource = DebugCatalogSource.None,
                itemCounts = DebugItemCounts(),
                redactedUrl = null,
                redactedHost = null,
            ),
        )
    }

    private suspend fun probeLiveStream(
        startedAt: Long,
        requestedStreamId: Int?,
        includeActualPlayback: Boolean,
    ): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "probe_live_stream",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Local Xtream debug config is not available.",
        )
        val snapshot = store.readCatalog()
        val selectedItem = snapshot?.playableLiveItems
            ?.firstOrNull { item -> requestedStreamId == null || item.streamId == requestedStreamId }
            ?: requestedStreamId?.let { id ->
                DebugPlayableLiveItem(
                    name = "Requested live stream $id",
                    categoryName = "Uncategorized",
                    streamId = id,
                    containerExtension = DEFAULT_LIVE_EXTENSION,
                    redactedUrl = endpointBuilder.buildRedactedLiveStream(credentials, id),
                )
            }
            ?: return failure(
                command = "probe_live_stream",
                startedAt = startedAt,
                currentState = "no_live_item_available",
                catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.None,
                lastErrorCategory = "NoLiveItem",
                message = "No cached live stream item is available. Run load_fixture_catalog or refresh_real_catalog first.",
            )

        val probeStartedAt = System.currentTimeMillis()
        val streamUrl = endpointBuilder.buildLiveStream(
            credentials = credentials,
            streamId = selectedItem.streamId,
            extension = selectedItem.containerExtension,
        )
        val redactedUrl = endpointBuilder.buildRedactedLiveStream(
            credentials = credentials,
            streamId = selectedItem.streamId,
            extension = selectedItem.containerExtension,
        )
        val probeCommand = if (includeActualPlayback) "probe_media3_live_stream" else "probe_live_stream"
        val lightweight = runHttpProbe(
            name = "lightweight_range",
            selectedItem = selectedItem,
            streamUrl = streamUrl,
            redactedUrl = redactedUrl,
            startedAt = probeStartedAt,
            requestShapeSummary = LIGHTWEIGHT_REQUEST_SHAPE,
            headers = mapOf("Range" to "bytes=0-0"),
        )
        val defaultMimic = runHttpProbe(
            name = "media3_default_mimic",
            selectedItem = selectedItem,
            streamUrl = streamUrl,
            redactedUrl = redactedUrl,
            startedAt = System.currentTimeMillis(),
            requestShapeSummary = MEDIA3_DEFAULT_MIMIC_REQUEST_SHAPE,
            headers = mapOf(
                "User-Agent" to "AndroidXMedia3/1.10.1",
                "Accept-Encoding" to "identity",
                "Range" to "bytes=0-",
            ),
        )
        val fixedMimic = runHttpProbe(
            name = "media3_fixed_mimic",
            selectedItem = selectedItem,
            streamUrl = streamUrl,
            redactedUrl = redactedUrl,
            startedAt = System.currentTimeMillis(),
            requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
            headers = mapOf(
                "User-Agent" to LivePlaybackRequestConfig.UserAgent,
                "Accept" to "*/*",
                "Icy-MetaData" to "1",
                "Accept-Encoding" to "identity",
                "Range" to "bytes=0-",
            ),
        )
        val comparisons = buildList {
            add(lightweight)
            add(defaultMimic)
            add(fixedMimic)
            if (includeActualPlayback) {
                add(runActualMedia3Probe(streamUrl, configured = false))
                add(runActualMedia3Probe(streamUrl, configured = true))
            }
        }
        val selectedProbe = comparisons.last()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "live_stream_probed",
            catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.Cached,
            itemCounts = snapshot?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = selectedProbe.statusCategory.takeIf { it != "success" },
            redactedUrl = redactedUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(redactedUrl),
        )
        store.writeState(state)
        return success(
            command = probeCommand,
            startedAt = startedAt,
            state = state,
            message = "Live stream probe completed.",
            playbackProbe = DebugPlaybackProbeResult(
                selectedItemName = selectedItem.name,
                streamId = selectedItem.streamId,
                containerExtension = selectedItem.containerExtension,
                redactedUrl = redactedUrl,
                requestShapeSummary = selectedProbe.requestShapeSummary,
                httpStatus = selectedProbe.httpStatus,
                statusCategory = selectedProbe.statusCategory,
                media3ErrorCode = selectedProbe.media3ErrorCode,
                durationMs = System.currentTimeMillis() - probeStartedAt,
                message = selectedProbe.message,
                comparisons = comparisons,
            ),
        )
    }

    private suspend fun probeLiveRender(
        startedAt: Long,
        requestedStreamId: Int?,
    ): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "probe_live_render",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Local Xtream debug config is not available.",
        )
        val snapshot = store.readCatalog()
        val selectedItem = snapshot?.playableLiveItems
            ?.firstOrNull { item -> requestedStreamId == null || item.streamId == requestedStreamId }
            ?: return failure(
                command = "probe_live_render",
                startedAt = startedAt,
                currentState = "no_live_item_available",
                catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.None,
                lastErrorCategory = "NoLiveItem",
                message = "No cached live stream item is available. Run refresh_real_catalog first.",
            )
        val streamUrl = endpointBuilder.buildLiveStream(credentials, selectedItem.streamId, selectedItem.containerExtension)
        val redactedUrl = endpointBuilder.buildRedactedLiveStream(credentials, selectedItem.streamId, selectedItem.containerExtension)
        val renderComparison = runActualMedia3RenderProbe(streamUrl)
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "live_render_probed",
            catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.Cached,
            itemCounts = snapshot?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = renderComparison.statusCategory.takeIf { it != "success" },
            redactedUrl = redactedUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(redactedUrl),
        )
        store.writeState(state)
        return success(
            command = "probe_live_render",
            startedAt = startedAt,
            state = state,
            message = "Live render probe completed.",
            playbackProbe = DebugPlaybackProbeResult(
                selectedItemName = selectedItem.name,
                streamId = selectedItem.streamId,
                containerExtension = selectedItem.containerExtension,
                redactedUrl = redactedUrl,
                requestShapeSummary = renderComparison.requestShapeSummary,
                httpStatus = renderComparison.httpStatus,
                statusCategory = renderComparison.statusCategory,
                media3ErrorCode = renderComparison.media3ErrorCode,
                playerState = renderComparison.playerState,
                videoWidth = renderComparison.videoWidth,
                videoHeight = renderComparison.videoHeight,
                firstFrameRendered = renderComparison.firstFrameRendered,
                droppedFrames = renderComparison.droppedFrames,
                durationMs = renderComparison.durationMs,
                message = renderComparison.message,
                comparisons = listOf(renderComparison),
            ),
        )
    }

    private suspend fun probeVodRender(
        startedAt: Long,
        requestedStreamId: Int?,
    ): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "probe_vod_render",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Local Xtream debug config is not available.",
        )
        val snapshot = store.readCatalog()
        val selectedItem = snapshot?.playableVodItems
            ?.let { items -> DebugPlaybackSampleSelector.selectVod(items, requestedStreamId) }
            ?: return failure(
                command = "probe_vod_render",
                startedAt = startedAt,
                currentState = "no_vod_item_available",
                catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.None,
                lastErrorCategory = "NoVodItem",
                message = "No cached VOD item is available. Run refresh_real_catalog first.",
            )
        val streamUrl = endpointBuilder.buildVodStream(credentials, selectedItem.streamId, selectedItem.containerExtension)
        val redactedUrl = endpointBuilder.buildRedactedVodStream(credentials, selectedItem.streamId, selectedItem.containerExtension)
        val renderComparison = runActualMedia3RenderProbe(streamUrl)
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "vod_render_probed",
            catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.Cached,
            itemCounts = snapshot?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = renderComparison.statusCategory.takeIf { it != "success" },
            redactedUrl = redactedUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(redactedUrl),
        )
        store.writeState(state)
        return success(
            command = "probe_vod_render",
            startedAt = startedAt,
            state = state,
            message = "VOD render probe completed.",
            playbackProbe = DebugPlaybackProbeResult(
                selectedItemName = selectedItem.title,
                streamId = selectedItem.streamId,
                containerExtension = selectedItem.containerExtension,
                redactedUrl = redactedUrl,
                requestShapeSummary = renderComparison.requestShapeSummary,
                httpStatus = renderComparison.httpStatus,
                statusCategory = renderComparison.statusCategory,
                media3ErrorCode = renderComparison.media3ErrorCode,
                playerState = renderComparison.playerState,
                videoWidth = renderComparison.videoWidth,
                videoHeight = renderComparison.videoHeight,
                firstFrameRendered = renderComparison.firstFrameRendered,
                droppedFrames = renderComparison.droppedFrames,
                durationMs = renderComparison.durationMs,
                message = renderComparison.message,
                comparisons = listOf(renderComparison),
            ),
        )
    }

    private suspend fun probeSeriesEpisodeRender(
        startedAt: Long,
        requestedSeriesId: Int?,
    ): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "probe_series_episode_render",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Local Xtream debug config is not available.",
        )
        val snapshot = store.readCatalog()
        val targets = selectSeriesEpisodeTargets(
            credentials = credentials,
            snapshot = snapshot,
            requestedSeriesId = requestedSeriesId,
        )
        if (targets.isEmpty()) return failure(
                command = "probe_series_episode_render",
                startedAt = startedAt,
                currentState = "no_series_item_available",
                catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.None,
                lastErrorCategory = "NoSeriesItem",
                message = "No cached Series item is available. Run refresh_real_catalog first.",
            )
        var selectedSeries = targets.first().series
        var episode = targets.first().episode
        var redactedUrl = endpointBuilder.buildRedactedSeriesEpisodeStream(credentials, episode.episodeId, episode.containerExtension)
        var renderComparison: DebugPlaybackProbeComparison? = null
        val comparisons = mutableListOf<DebugPlaybackProbeComparison>()
        for (candidate in targets.take(PLAYBACK_SAMPLE_RENDER_ATTEMPTS)) {
            val candidateEpisode = candidate.episode
            val streamUrl = endpointBuilder.buildSeriesEpisodeStream(credentials, candidateEpisode.episodeId, candidateEpisode.containerExtension)
            val candidateRedactedUrl = endpointBuilder.buildRedactedSeriesEpisodeStream(credentials, candidateEpisode.episodeId, candidateEpisode.containerExtension)
            val comparison = runActualMedia3RenderProbe(streamUrl).copy(
                name = "series_candidate_${candidate.series.seriesId}_${candidateEpisode.episodeId}",
            )
            comparisons += comparison
            selectedSeries = candidate.series
            episode = candidateEpisode
            redactedUrl = candidateRedactedUrl
            renderComparison = comparison
            if (comparison.statusCategory == "success" && comparison.firstFrameRendered == true) break
        }
        val finalComparison = renderComparison ?: failedProbeComparison("NoSeriesEpisodeCandidate")
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "series_episode_render_probed",
            catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.Cached,
            itemCounts = snapshot?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = finalComparison.statusCategory.takeIf { it != "success" },
            redactedUrl = redactedUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(redactedUrl),
        )
        store.writeState(state)
        return success(
            command = "probe_series_episode_render",
            startedAt = startedAt,
            state = state,
            message = "Series episode render probe completed.",
            playbackProbe = DebugPlaybackProbeResult(
                selectedItemName = "${selectedSeries.name} - ${episode.title}",
                streamId = episode.episodeId,
                containerExtension = episode.containerExtension,
                redactedUrl = redactedUrl,
                requestShapeSummary = finalComparison.requestShapeSummary,
                httpStatus = finalComparison.httpStatus,
                statusCategory = finalComparison.statusCategory,
                media3ErrorCode = finalComparison.media3ErrorCode,
                playerState = finalComparison.playerState,
                videoWidth = finalComparison.videoWidth,
                videoHeight = finalComparison.videoHeight,
                firstFrameRendered = finalComparison.firstFrameRendered,
                droppedFrames = finalComparison.droppedFrames,
                durationMs = finalComparison.durationMs,
                message = finalComparison.message,
                comparisons = comparisons,
            ),
        )
    }

    private suspend fun probePlaybackHealthSweep(startedAt: Long): DebugCommandResult {
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "probe_playback_health_sweep",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Real Xtream debug config is missing.",
        )
        val snapshot = store.readCatalog()
        val results = mutableListOf<DebugPlaybackHealthSectionResult>()

        val liveItem = snapshot?.playableLiveItems?.firstOrNull()
        results += if (liveItem == null) {
            missingHealthSection("live_tv", "No cached Live TV item is available.")
        } else {
            val streamUrl = endpointBuilder.buildLiveStream(credentials, liveItem.streamId, liveItem.containerExtension)
            val redactedUrl = endpointBuilder.buildRedactedLiveStream(credentials, liveItem.streamId, liveItem.containerExtension)
            runPlaybackHealthProbe(
                section = "live_tv",
                selectedItemName = liveItem.name,
                streamId = liveItem.streamId,
                containerExtension = liveItem.containerExtension,
                streamUrl = streamUrl,
                redactedUrl = redactedUrl,
            )
        }

        val vodItem = snapshot?.playableVodItems?.let { DebugPlaybackSampleSelector.selectVod(it, requestedStreamId = null) }
        results += if (vodItem == null) {
            missingHealthSection("movies", "No cached Movies/VOD item is available.")
        } else {
            val streamUrl = endpointBuilder.buildVodStream(credentials, vodItem.streamId, vodItem.containerExtension)
            val redactedUrl = endpointBuilder.buildRedactedVodStream(credentials, vodItem.streamId, vodItem.containerExtension)
            runPlaybackHealthProbe(
                section = "movies",
                selectedItemName = vodItem.title,
                streamId = vodItem.streamId,
                containerExtension = vodItem.containerExtension,
                streamUrl = streamUrl,
                redactedUrl = redactedUrl,
            )
        }

        val seriesTarget = selectSeriesEpisodeTarget(credentials, snapshot, requestedSeriesId = null)
        results += if (seriesTarget == null) {
            missingHealthSection("series_episode", "No cached Series item is available.")
        } else {
            val episode = seriesTarget.episode
            val streamUrl = endpointBuilder.buildSeriesEpisodeStream(credentials, episode.episodeId, episode.containerExtension)
            val redactedUrl = endpointBuilder.buildRedactedSeriesEpisodeStream(credentials, episode.episodeId, episode.containerExtension)
            runPlaybackHealthProbe(
                section = "series_episode",
                selectedItemName = "${seriesTarget.series.name} - ${episode.title}",
                streamId = episode.episodeId,
                containerExtension = episode.containerExtension,
                streamUrl = streamUrl,
                redactedUrl = redactedUrl,
            )
        }

        val lastError = results.firstOrNull { it.statusCategory != "success" }?.statusCategory
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "playback_health_sweep_completed",
            catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = snapshot?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = lastError,
            redactedUrl = null,
            redactedHost = snapshot?.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "probe_playback_health_sweep",
            startedAt = startedAt,
            state = state,
            message = "Playback health sweep completed.",
            playbackHealthSweep = DebugPlaybackHealthSweep(
                sampleAfterFirstFrameMs = HEALTH_SAMPLE_AFTER_FIRST_FRAME_MS,
                results = results,
            ),
        )
    }

    private suspend fun probePlaybackDeepHealth(startedAt: Long, requestedDurationSeconds: Int?): DebugCommandResult {
        val sampleDurationSeconds = (requestedDurationSeconds ?: DEFAULT_DEEP_SAMPLE_SECONDS)
            .coerceIn(MIN_DEEP_SAMPLE_SECONDS, MAX_DEEP_SAMPLE_SECONDS)
        val credentials = DebugConfig.debugXtreamCredentials ?: return failure(
            command = "probe_playback_deep_health",
            startedAt = startedAt,
            currentState = "missing_real_config",
            catalogSource = DebugCatalogSource.Real,
            lastErrorCategory = "MissingConfig",
            message = "Real Xtream debug config is missing.",
        )
        val snapshot = store.readCatalog()
        val sections = buildDeepHealthTargets(credentials, snapshot)
        val results = sections.map { target ->
            if (target.streamUrl == null || target.redactedUrl == null || target.streamId == null || target.containerExtension == null) {
                DebugPlaybackDeepSectionResult(
                    section = target.section,
                    selectedItemName = target.selectedItemName,
                    streamId = target.streamId,
                    containerExtension = target.containerExtension,
                    redactedUrl = target.redactedUrl,
                    transport = null,
                    profiles = listOf(missingDeepProfile(target.message ?: "No playable item.")),
                    bottleneck = "UNKNOWN",
                    bottleneckReason = target.message ?: "No playable item.",
                )
            } else {
                val transport = runTransportStreamingProbe(target.streamUrl, DEEP_TRANSPORT_SECONDS)
                val profiles = DEEP_PROFILES.map { profile ->
                    runPlaybackDeepProfileProbe(
                        streamUrl = target.streamUrl,
                        profile = profile,
                        sampleDurationSeconds = sampleDurationSeconds,
                    )
                }
                val bottleneck = DebugPlaybackBottleneckClassifier.classify(transport, profiles)
                DebugPlaybackDeepSectionResult(
                    section = target.section,
                    selectedItemName = target.selectedItemName,
                    streamId = target.streamId,
                    containerExtension = target.containerExtension,
                    redactedUrl = target.redactedUrl,
                    transport = transport,
                    profiles = profiles,
                    bottleneck = bottleneck.category,
                    bottleneckReason = bottleneck.reason,
                )
            }
        }
        val lastError = results.flatMap { it.profiles }.firstOrNull { it.statusCategory != "success" }?.statusCategory
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "playback_deep_health_completed",
            catalogSource = snapshot?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = snapshot?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = lastError,
            redactedUrl = null,
            redactedHost = snapshot?.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "probe_playback_deep_health",
            startedAt = startedAt,
            state = state,
            message = "Playback deep health probe completed.",
            playbackDeepHealth = DebugPlaybackDeepHealth(
                sampleDurationSeconds = sampleDurationSeconds,
                transportDurationSeconds = DEEP_TRANSPORT_SECONDS,
                windowSeconds = DEEP_WINDOW_SECONDS,
                device = buildDeviceMediaSnapshot(),
                results = results,
            ),
        )
    }

    private suspend fun buildDeepHealthTargets(
        credentials: com.atlas.atlastv.xtream.model.XtreamCredentials,
        snapshot: DebugCatalogSnapshot?,
    ): List<DeepHealthTarget> {
        val live = snapshot?.playableLiveItems?.firstOrNull()?.let { item ->
            DeepHealthTarget(
                section = "live_tv",
                selectedItemName = item.name,
                streamId = item.streamId,
                containerExtension = item.containerExtension,
                streamUrl = endpointBuilder.buildLiveStream(credentials, item.streamId, item.containerExtension),
                redactedUrl = endpointBuilder.buildRedactedLiveStream(credentials, item.streamId, item.containerExtension),
            )
        } ?: DeepHealthTarget(section = "live_tv", message = "No cached Live TV item is available.")

        val vod = snapshot?.playableVodItems?.let { DebugPlaybackSampleSelector.selectVod(it, requestedStreamId = null) }?.let { item ->
            DeepHealthTarget(
                section = "movies",
                selectedItemName = item.title,
                streamId = item.streamId,
                containerExtension = item.containerExtension,
                streamUrl = endpointBuilder.buildVodStream(credentials, item.streamId, item.containerExtension),
                redactedUrl = endpointBuilder.buildRedactedVodStream(credentials, item.streamId, item.containerExtension),
            )
        } ?: DeepHealthTarget(section = "movies", message = "No cached Movies/VOD item is available.")

        val series = selectSeriesEpisodeTarget(credentials, snapshot, requestedSeriesId = null)?.let { target ->
            val episode = target.episode
            DeepHealthTarget(
                section = "series_episode",
                selectedItemName = "${target.series.name} - ${episode.title}",
                streamId = episode.episodeId,
                containerExtension = episode.containerExtension,
                streamUrl = endpointBuilder.buildSeriesEpisodeStream(credentials, episode.episodeId, episode.containerExtension),
                redactedUrl = endpointBuilder.buildRedactedSeriesEpisodeStream(credentials, episode.episodeId, episode.containerExtension),
            )
        } ?: DeepHealthTarget(section = "series_episode", message = "No cached Series item is available.")

        return listOf(live, vod, series)
    }

    private suspend fun selectSeriesEpisodeTarget(
        credentials: com.atlas.atlastv.xtream.model.XtreamCredentials,
        snapshot: DebugCatalogSnapshot?,
        requestedSeriesId: Int?,
    ): DebugSeriesEpisodeTarget? =
        selectSeriesEpisodeTargets(credentials, snapshot, requestedSeriesId).firstOrNull()

    private suspend fun selectSeriesEpisodeTargets(
        credentials: com.atlas.atlastv.xtream.model.XtreamCredentials,
        snapshot: DebugCatalogSnapshot?,
        requestedSeriesId: Int?,
    ): List<DebugSeriesEpisodeTarget> {
        val seriesItems = snapshot?.playableSeriesItems.orEmpty()
        val candidates = if (requestedSeriesId != null) {
            seriesItems.filter { item -> item.seriesId == requestedSeriesId }
        } else {
            seriesItems.take(PLAYBACK_SAMPLE_SERIES_LIMIT)
        }
        return candidates.mapNotNull { series ->
            when (val episodeResult = SeriesEpisodeLoader().loadEpisodes(credentials, series.seriesId, maxEpisodes = PLAYBACK_SAMPLE_EPISODE_LIMIT)) {
                is SeriesEpisodeLoadResult.Failure -> null
                is SeriesEpisodeLoadResult.Success -> DebugPlaybackSampleSelector.selectEpisode(episodeResult.episodes)
                    ?.let { episode -> DebugSeriesEpisodeTarget(series, episode) }
            }
        }.sortedBy { target -> DebugPlaybackSampleSelector.extensionRank(target.episode.containerExtension) }
    }

    private fun failedProbeComparison(category: String): DebugPlaybackProbeComparison =
        DebugPlaybackProbeComparison(
            name = "series_candidate_missing",
            requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
            httpStatus = null,
            statusCategory = "failure",
            media3ErrorCode = null,
            durationMs = 0L,
            message = category,
        )

    private fun runTransportStreamingProbe(streamUrl: String, durationSeconds: Int): DebugTransportProbeResult {
        val startedAt = System.currentTimeMillis()
        val perSecondBytes = mutableListOf<Long>()
        var currentWindowBytes = 0L
        var currentWindowStartedAt = startedAt
        var bytesRead = 0L
        var firstByteAt: Long? = null
        var httpStatus: Int? = null
        var contentType: String? = null
        var headers = emptyMap<String, String>()
        var exceptionCategory: String? = null
        try {
            httpClient.newCall(Request.Builder().url(streamUrl).get().build()).execute().use { response ->
                httpStatus = response.code
                contentType = response.header("Content-Type")
                headers = response.headers.toSanitizedHeaderMap()
                val source = response.body.byteStream()
                val buffer = ByteArray(32 * 1024)
                val deadline = startedAt + durationSeconds * 1000L
                while (System.currentTimeMillis() < deadline && bytesRead < DEEP_TRANSPORT_MAX_BYTES) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    val now = System.currentTimeMillis()
                    if (firstByteAt == null) firstByteAt = now
                    bytesRead += read
                    currentWindowBytes += read
                    while (now - currentWindowStartedAt >= 1_000L) {
                        perSecondBytes += currentWindowBytes
                        currentWindowBytes = 0L
                        currentWindowStartedAt += 1_000L
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            exceptionCategory = "timeout"
        } catch (error: Exception) {
            exceptionCategory = error::class.java.simpleName
        }
        if (currentWindowBytes > 0 || perSecondBytes.isEmpty()) perSecondBytes += currentWindowBytes
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val mbpsWindows = perSecondBytes.map { bytes -> bytes.toMbps(1_000L) }
        return DebugTransportProbeResult(
            httpStatus = httpStatus,
            statusCategory = httpStatus?.toStatusCategory() ?: if (exceptionCategory == null) "unknown" else "network_error",
            contentType = contentType,
            sanitizedHeaders = headers,
            timeToFirstByteMs = firstByteAt?.let { it - startedAt },
            bytesRead = bytesRead,
            averageMbps = bytesRead.toMbps(durationMs),
            minWindowMbps = mbpsWindows.minOrNull() ?: 0.0,
            maxWindowMbps = mbpsWindows.maxOrNull() ?: 0.0,
            stalledIntervalCount = perSecondBytes.count { it == 0L },
            durationMs = durationMs,
            exceptionCategory = exceptionCategory,
        )
    }

    private fun runHttpProbe(
        name: String,
        selectedItem: DebugPlayableLiveItem,
        streamUrl: String,
        redactedUrl: String,
        startedAt: Long,
        requestShapeSummary: String,
        headers: Map<String, String>,
    ): DebugPlaybackProbeComparison {
        val builder = Request.Builder()
            .url(streamUrl)
            .get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                DebugPlaybackProbeComparison(
                    name = name,
                    requestShapeSummary = requestShapeSummary,
                    httpStatus = response.code,
                    statusCategory = response.code.toStatusCategory(),
                    media3ErrorCode = if (response.code in 200..299) null else "ERROR_CODE_IO_BAD_HTTP_STATUS",
                    durationMs = System.currentTimeMillis() - startedAt,
                    message = response.message.takeIf { it.isNotBlank() },
                )
            }
        } catch (_: SocketTimeoutException) {
            selectedItem.toProbeFailure(name, requestShapeSummary, "timeout", "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", startedAt, "Timeout")
        } catch (error: Exception) {
            selectedItem.toProbeFailure(name, requestShapeSummary, "network_error", "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", startedAt, error::class.java.simpleName)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun runActualMedia3Probe(
        streamUrl: String,
        configured: Boolean,
    ): DebugPlaybackProbeComparison =
        withContext(Dispatchers.Main) {
            val startedAt = System.currentTimeMillis()
            val deferred = CompletableDeferred<DebugPlaybackProbeComparison>()
            val name = if (configured) "actual_media3_configured" else "actual_media3_default"
            val requestShape = if (configured) LivePlaybackRequestConfig.RequestShapeSummary else ACTUAL_MEDIA3_DEFAULT_REQUEST_SHAPE
            val builder = ExoPlayer.Builder(appContext)
            if (configured) {
                builder
                    .setRenderersFactory(LivePlaybackRequestConfig.renderersFactory(appContext))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(LivePlaybackRequestConfig.dataSourceFactory(appContext)))
            }
            val player = builder.build()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !deferred.isCompleted) {
                        deferred.complete(
                            DebugPlaybackProbeComparison(
                                name = name,
                                requestShapeSummary = requestShape,
                                httpStatus = 200,
                                statusCategory = "success",
                                media3ErrorCode = null,
                                durationMs = System.currentTimeMillis() - startedAt,
                                message = "STATE_READY",
                            ),
                        )
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!deferred.isCompleted) {
                        val responseCode = error.findInvalidResponseCode()
                        deferred.complete(
                            DebugPlaybackProbeComparison(
                                name = name,
                                requestShapeSummary = requestShape,
                                httpStatus = responseCode,
                                statusCategory = responseCode?.toStatusCategory() ?: "media3_error",
                                media3ErrorCode = error.errorCodeName,
                                durationMs = System.currentTimeMillis() - startedAt,
                                message = error.message ?: "Playback failed",
                            ),
                        )
                    }
                }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
            try {
                withTimeout(12_000L) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                DebugPlaybackProbeComparison(
                    name = name,
                    requestShapeSummary = requestShape,
                    httpStatus = null,
                    statusCategory = "timeout",
                    media3ErrorCode = "PROBE_TIMEOUT",
                    durationMs = System.currentTimeMillis() - startedAt,
                    message = "Timed out waiting for Media3 ready/error.",
                )
            } finally {
                player.removeListener(listener)
                player.release()
            }
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun runActualMedia3RenderProbe(streamUrl: String): DebugPlaybackProbeComparison =
        withContext(Dispatchers.Main) {
            val startedAt = System.currentTimeMillis()
            val deferred = CompletableDeferred<DebugPlaybackProbeComparison>()
            val surfaceTexture = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 720) }
            val surface = Surface(surfaceTexture)
            var playerState = "Loading"
            var videoWidth: Int? = null
            var videoHeight: Int? = null
            var firstFrameRendered = false
            var droppedFrameCount = 0
            val player = ExoPlayer.Builder(appContext)
                .setRenderersFactory(LivePlaybackRequestConfig.renderersFactory(appContext))
                .setMediaSourceFactory(DefaultMediaSourceFactory(LivePlaybackRequestConfig.dataSourceFactory(appContext)))
                .build()

            fun maybeCompleteSuccess() {
                if (!deferred.isCompleted && playerState == "Ready" && firstFrameRendered) {
                    deferred.complete(
                        DebugPlaybackProbeComparison(
                            name = "actual_media3_render_configured",
                            requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
                            httpStatus = 200,
                            statusCategory = "success",
                            media3ErrorCode = null,
                            playerState = playerState,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            firstFrameRendered = true,
                            droppedFrames = droppedFrameCount,
                            durationMs = System.currentTimeMillis() - startedAt,
                            message = "STATE_READY_FIRST_FRAME",
                        ),
                    )
                }
            }

            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    playerState = when (playbackState) {
                        Player.STATE_BUFFERING -> "Loading"
                        Player.STATE_READY -> "Ready"
                        Player.STATE_ENDED -> "Ended"
                        Player.STATE_IDLE -> "Idle"
                        else -> "Unknown"
                    }
                    maybeCompleteSuccess()
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    videoWidth = size.width
                    videoHeight = size.height
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                    maybeCompleteSuccess()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!deferred.isCompleted) {
                        val responseCode = error.findInvalidResponseCode()
                        deferred.complete(
                            DebugPlaybackProbeComparison(
                                name = "actual_media3_render_configured",
                                requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
                                httpStatus = responseCode,
                                statusCategory = responseCode?.toStatusCategory() ?: "media3_error",
                                media3ErrorCode = error.errorCodeName,
                                playerState = playerState,
                                videoWidth = videoWidth,
                                videoHeight = videoHeight,
                                firstFrameRendered = firstFrameRendered,
                                droppedFrames = droppedFrameCount,
                                durationMs = System.currentTimeMillis() - startedAt,
                                message = error.message ?: "Playback failed",
                            ),
                        )
                    }
                }
            }
            val analyticsListener = object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    droppedFrameCount += droppedFrames
                }
            }
            player.addListener(listener)
            player.addAnalyticsListener(analyticsListener)
            player.setVideoSurface(surface)
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
            try {
                withTimeout(15_000L) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                DebugPlaybackProbeComparison(
                    name = "actual_media3_render_configured",
                    requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
                    httpStatus = if (playerState == "Ready") 200 else null,
                    statusCategory = if (playerState == "Ready") "render_timeout" else "timeout",
                    media3ErrorCode = "PROBE_TIMEOUT",
                    playerState = playerState,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    firstFrameRendered = firstFrameRendered,
                    droppedFrames = droppedFrameCount,
                    durationMs = System.currentTimeMillis() - startedAt,
                    message = "Timed out waiting for Media3 first frame.",
                )
            } finally {
                player.removeListener(listener)
                player.removeAnalyticsListener(analyticsListener)
                player.clearVideoSurface(surface)
                player.release()
                surface.release()
                surfaceTexture.release()
            }
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun runPlaybackHealthProbe(
        section: String,
        selectedItemName: String,
        streamId: Int,
        containerExtension: String,
        streamUrl: String,
        redactedUrl: String,
    ): DebugPlaybackHealthSectionResult =
        withContext(Dispatchers.Main) {
            val startedAt = System.currentTimeMillis()
            val firstFrameDeferred = CompletableDeferred<Unit>()
            val surfaceTexture = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 720) }
            val surface = Surface(surfaceTexture)
            var playerState = "Loading"
            var videoWidth: Int? = null
            var videoHeight: Int? = null
            var firstFrameRendered = false
            var droppedFrameCount = 0
            var bufferCount = 0
            var media3ErrorCode: String? = null
            var httpStatus: Int? = null
            var statusCategory = "timeout"
            var message: String? = null
            var startPositionMs: Long? = null
            var endPositionMs: Long? = null
            val player = ExoPlayer.Builder(appContext)
                .setRenderersFactory(LivePlaybackRequestConfig.renderersFactory(appContext))
                .setMediaSourceFactory(DefaultMediaSourceFactory(LivePlaybackRequestConfig.dataSourceFactory(appContext)))
                .build()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    playerState = when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            bufferCount += 1
                            "Loading"
                        }
                        Player.STATE_READY -> "Ready"
                        Player.STATE_ENDED -> "Ended"
                        Player.STATE_IDLE -> "Idle"
                        else -> "Unknown"
                    }
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    videoWidth = size.width
                    videoHeight = size.height
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                    if (!firstFrameDeferred.isCompleted) {
                        firstFrameDeferred.complete(Unit)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    media3ErrorCode = error.errorCodeName
                    httpStatus = error.findInvalidResponseCode()
                    statusCategory = httpStatus?.toStatusCategory() ?: "media3_error"
                    message = error.message ?: "Playback failed"
                    if (!firstFrameDeferred.isCompleted) {
                        firstFrameDeferred.complete(Unit)
                    }
                }
            }
            val analyticsListener = object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    droppedFrameCount += droppedFrames
                }
            }
            player.addListener(listener)
            player.addAnalyticsListener(analyticsListener)
            player.setVideoSurface(surface)
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
            try {
                withTimeout(HEALTH_FIRST_FRAME_TIMEOUT_MS) { firstFrameDeferred.await() }
                if (firstFrameRendered && media3ErrorCode == null) {
                    httpStatus = 200
                    statusCategory = "success"
                    startPositionMs = player.currentPosition
                    delay(HEALTH_SAMPLE_AFTER_FIRST_FRAME_MS)
                    endPositionMs = player.currentPosition
                    playerState = if (player.playbackState == Player.STATE_READY) "Ready" else playerState
                    message = "HEALTH_SAMPLE_COMPLETE"
                }
            } catch (_: TimeoutCancellationException) {
                message = "Timed out waiting for first frame during health sample."
                statusCategory = if (firstFrameRendered) "sample_timeout" else "timeout"
                if (firstFrameRendered) {
                    httpStatus = 200
                }
            } finally {
                player.removeListener(listener)
                player.removeAnalyticsListener(analyticsListener)
                player.clearVideoSurface(surface)
                player.release()
                surface.release()
                surfaceTexture.release()
            }
            DebugPlaybackHealthSectionResult(
                section = section,
                selectedItemName = selectedItemName,
                streamId = streamId,
                containerExtension = containerExtension,
                redactedUrl = redactedUrl,
                requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
                httpStatus = httpStatus,
                statusCategory = statusCategory,
                media3ErrorCode = media3ErrorCode,
                playerState = playerState,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                firstFrameRendered = firstFrameRendered,
                droppedFrames = droppedFrameCount,
                bufferCount = bufferCount,
                playbackPositionStartMs = startPositionMs,
                playbackPositionEndMs = endPositionMs,
                playbackAdvanced = startPositionMs != null && endPositionMs != null && endPositionMs!! > startPositionMs!!,
                durationMs = System.currentTimeMillis() - startedAt,
                message = message,
            )
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun runPlaybackDeepProfileProbe(
        streamUrl: String,
        profile: DeepPlaybackProfile,
        sampleDurationSeconds: Int,
    ): DebugPlaybackDeepProfileResult =
        withContext(Dispatchers.Main) {
            val startedAt = System.currentTimeMillis()
            val firstFrameDeferred = CompletableDeferred<Unit>()
            val surfaceTexture = SurfaceTexture(0).apply { setDefaultBufferSize(profile.surfaceWidth, profile.surfaceHeight) }
            val surface = Surface(surfaceTexture)
            val trackSelector = DefaultTrackSelector(appContext).apply {
                if (profile.tunnelingRequested) {
                    parameters = buildUponParameters().setTunnelingEnabled(true).build()
                }
            }
            val builder = ExoPlayer.Builder(appContext)
                .setTrackSelector(trackSelector)
                .setRenderersFactory(LivePlaybackRequestConfig.renderersFactory(appContext))
                .setMediaSourceFactory(DefaultMediaSourceFactory(LivePlaybackRequestConfig.dataSourceFactory(appContext)))
            if (profile.largerBufferProfile) {
                builder.setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(30_000, 90_000, 2_500, 10_000)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build(),
                )
            }
            val player = builder.build()
            val stateTransitions = mutableListOf<Pair<Long, String>>()
            val windows = mutableListOf<DebugPlaybackHealthWindow>()
            var playerState = "Loading"
            var firstFrameRendered = false
            var droppedFrameCount = 0
            var warmupToReadyMs: Long? = null
            var firstFrameTimeMs: Long? = null
            var media3ErrorCode: String? = null
            var httpStatus: Int? = null
            var statusCategory = "timeout"
            var message: String? = null
            var rebufferCount = 0
            var rebufferStartedAt: Long? = null
            var totalRebufferMs = 0L
            var startPositionMs: Long? = null
            var endPositionMs: Long? = null
            var videoFormat: Format? = null
            var audioFormat: Format? = null
            var videoDecoderName: String? = null
            var audioDecoderName: String? = null
            var videoDecoderInitializationCount = 0
            var audioDecoderInitializationCount = 0
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val now = System.currentTimeMillis()
                    playerState = when (playbackState) {
                        Player.STATE_BUFFERING -> "Loading"
                        Player.STATE_READY -> "Ready"
                        Player.STATE_ENDED -> "Ended"
                        Player.STATE_IDLE -> "Idle"
                        else -> "Unknown"
                    }
                    stateTransitions += (now - startedAt) to playerState
                    if (playbackState == Player.STATE_BUFFERING && firstFrameRendered) {
                        rebufferCount += 1
                        rebufferStartedAt = now
                    }
                    if (playbackState == Player.STATE_READY) {
                        if (warmupToReadyMs == null) warmupToReadyMs = now - startedAt
                        rebufferStartedAt?.let { totalRebufferMs += now - it }
                        rebufferStartedAt = null
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    tracks.groups.asSequence()
                        .filter { it.isSelected }
                        .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) } }
                        .forEach { format ->
                            when {
                                format.sampleMimeType?.startsWith("video/") == true && videoFormat == null -> videoFormat = format
                                format.sampleMimeType?.startsWith("audio/") == true && audioFormat == null -> audioFormat = format
                            }
                        }
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                    if (firstFrameTimeMs == null) firstFrameTimeMs = System.currentTimeMillis() - startedAt
                    if (!firstFrameDeferred.isCompleted) firstFrameDeferred.complete(Unit)
                }

                override fun onPlayerError(error: PlaybackException) {
                    media3ErrorCode = error.errorCodeName
                    httpStatus = error.findInvalidResponseCode()
                    statusCategory = httpStatus?.toStatusCategory() ?: "media3_error"
                    message = error.message ?: "Playback failed"
                    if (!firstFrameDeferred.isCompleted) firstFrameDeferred.complete(Unit)
                }
            }
            val analyticsListener = object : AnalyticsListener {
                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    droppedFrameCount += droppedFrames
                }

                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    videoDecoderName = decoderName
                    videoDecoderInitializationCount += 1
                }

                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    audioDecoderName = decoderName
                    audioDecoderInitializationCount += 1
                }
            }
            player.addListener(listener)
            player.addAnalyticsListener(analyticsListener)
            player.setVideoSurface(surface)
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
            try {
                withTimeout(DEEP_FIRST_FRAME_TIMEOUT_MS) { firstFrameDeferred.await() }
                if (firstFrameRendered && media3ErrorCode == null) {
                    httpStatus = 200
                    statusCategory = "success"
                    startPositionMs = player.currentPosition
                    val sampleStartedAt = System.currentTimeMillis()
                    var lastDropped = droppedFrameCount
                    var lastRendered = player.videoDecoderCounters?.renderedOutputBufferCount
                    repeat((sampleDurationSeconds + DEEP_WINDOW_SECONDS - 1) / DEEP_WINDOW_SECONDS) { index ->
                        delay((DEEP_WINDOW_SECONDS * 1000L).coerceAtMost(sampleDurationSeconds * 1000L - (System.currentTimeMillis() - sampleStartedAt)).coerceAtLeast(0L))
                        val counters = player.videoDecoderCounters
                        val rendered = counters?.renderedOutputBufferCount
                        val droppedDelta = droppedFrameCount - lastDropped
                        val renderedDelta = if (rendered != null && lastRendered != null) rendered - lastRendered!! else null
                        windows += DebugPlaybackHealthWindow(
                            index = index,
                            startMs = (index * DEEP_WINDOW_SECONDS * 1000L),
                            endMs = ((index + 1) * DEEP_WINDOW_SECONDS * 1000L).coerceAtMost(sampleDurationSeconds * 1000L),
                            playerState = playerState,
                            playbackPositionMs = player.currentPosition,
                            bufferedPositionMs = player.bufferedPosition,
                            bufferedDurationMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
                            droppedFrames = droppedDelta,
                            renderedFrames = renderedDelta,
                            droppedFramesPerSecond = droppedDelta / DEEP_WINDOW_SECONDS.toDouble(),
                        )
                        lastDropped = droppedFrameCount
                        lastRendered = rendered
                    }
                    endPositionMs = player.currentPosition
                    message = "DEEP_SAMPLE_COMPLETE states=${stateTransitions.joinToString(";") { "${it.first}:${it.second}" }}"
                }
            } catch (_: TimeoutCancellationException) {
                statusCategory = if (firstFrameRendered) "sample_timeout" else "timeout"
                httpStatus = if (firstFrameRendered) 200 else httpStatus
                message = "Timed out during deep playback sample."
            } finally {
                rebufferStartedAt?.let { totalRebufferMs += System.currentTimeMillis() - it }
                player.removeListener(listener)
                player.removeAnalyticsListener(analyticsListener)
                player.clearVideoSurface(surface)
            }
            val counters = player.videoDecoderCounters.toSnapshot()
            val durationMs = System.currentTimeMillis() - startedAt
            val sampleSeconds = sampleDurationSeconds.toDouble().coerceAtLeast(1.0)
            val videoSnapshot = videoFormat?.toVideoSnapshot(videoDecoderName)
            val result = DebugPlaybackDeepProfileResult(
                profile = profile.name,
                requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
                surfaceSummary = profile.surfaceSummary,
                tunnelingRequested = profile.tunnelingRequested,
                largerBufferProfile = profile.largerBufferProfile,
                httpStatus = httpStatus,
                statusCategory = statusCategory,
                media3ErrorCode = media3ErrorCode,
                playerState = playerState,
                firstFrameRendered = firstFrameRendered,
                warmupToReadyMs = warmupToReadyMs,
                firstFrameTimeMs = firstFrameTimeMs,
                durationMs = durationMs,
                playbackPositionStartMs = startPositionMs,
                playbackPositionEndMs = endPositionMs,
                playbackAdvanced = startPositionMs != null && endPositionMs != null && endPositionMs!! > startPositionMs!!,
                rebufferCount = rebufferCount,
                totalRebufferMs = totalRebufferMs,
                video = videoSnapshot,
                audio = audioFormat?.toAudioSnapshot(),
                decoder = DebugDecoderSnapshot(
                    videoDecoderName = videoDecoderName,
                    audioDecoderName = audioDecoderName,
                    videoDecoderInitializationCount = videoDecoderInitializationCount,
                    audioDecoderInitializationCount = audioDecoderInitializationCount,
                ),
                counters = counters,
                windows = windows,
                renderedFramesPerSecond = counters.renderedOutputBufferCount.takeIf { it > 0 }?.let { it / sampleSeconds },
                droppedFramesPerSecond = counters.droppedBufferCount / sampleSeconds,
                droppedRenderedRatio = if (counters.renderedOutputBufferCount > 0) counters.droppedBufferCount.toDouble() / counters.renderedOutputBufferCount else null,
                averageVideoFrameProcessingOffsetUs = if (counters.videoFrameProcessingOffsetCount > 0) counters.totalVideoFrameProcessingOffsetUs.toDouble() / counters.videoFrameProcessingOffsetCount else null,
                message = message,
            )
            player.release()
            surface.release()
            surfaceTexture.release()
            result
        }

    private fun missingDeepProfile(message: String): DebugPlaybackDeepProfileResult =
        DebugPlaybackDeepProfileResult(
            profile = "baseline",
            requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
            surfaceSummary = "not_started",
            tunnelingRequested = false,
            largerBufferProfile = false,
            httpStatus = null,
            statusCategory = "missing_item",
            media3ErrorCode = null,
            playerState = null,
            firstFrameRendered = false,
            warmupToReadyMs = null,
            firstFrameTimeMs = null,
            durationMs = 0L,
            playbackPositionStartMs = null,
            playbackPositionEndMs = null,
            playbackAdvanced = false,
            rebufferCount = 0,
            totalRebufferMs = 0L,
            video = null,
            audio = null,
            decoder = DebugDecoderSnapshot(null, null, 0, 0),
            counters = DebugVideoDecoderCountersSnapshot(0, 0, 0, 0, 0, 0, 0, 0L, 0),
            windows = emptyList(),
            renderedFramesPerSecond = null,
            droppedFramesPerSecond = 0.0,
            droppedRenderedRatio = null,
            averageVideoFrameProcessingOffsetUs = null,
            message = message,
        )

    private fun buildDeviceMediaSnapshot(): DebugDeviceMediaSnapshot {
        val display = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                appContext.display
            } else {
                @Suppress("DEPRECATION")
                (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            }
        }.getOrNull()
        val mode = runCatching { display?.mode }.getOrNull()
        return DebugDeviceMediaSnapshot(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            displayRefreshRate = runCatching { display?.refreshRate }.getOrNull(),
            displayMode = mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" },
            isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                Build.MODEL.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true),
        )
    }

    private fun missingHealthSection(section: String, message: String): DebugPlaybackHealthSectionResult =
        DebugPlaybackHealthSectionResult(
            section = section,
            selectedItemName = null,
            streamId = null,
            containerExtension = null,
            redactedUrl = null,
            requestShapeSummary = LivePlaybackRequestConfig.RequestShapeSummary,
            httpStatus = null,
            statusCategory = "missing_item",
            media3ErrorCode = null,
            playerState = null,
            videoWidth = null,
            videoHeight = null,
            firstFrameRendered = false,
            droppedFrames = 0,
            bufferCount = 0,
            playbackPositionStartMs = null,
            playbackPositionEndMs = null,
            playbackAdvanced = false,
            durationMs = 0L,
            message = message,
        )

    private fun clearDebugCache(startedAt: Long): DebugCommandResult {
        store.clearDebugCache()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "cache_cleared",
            catalogSource = DebugCatalogSource.None,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = null,
            redactedHost = null,
        )
        store.writeState(state)
        return success("clear_debug_cache", startedAt, state, "Debug cache cleared.")
    }

    private fun dumpCurrentState(startedAt: Long): DebugCommandResult {
        val state = store.readState() ?: DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "no_state",
            catalogSource = DebugCatalogSource.None,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = null,
            redactedUrl = null,
            redactedHost = null,
        )
        store.writeState(state)
        return success("dump_current_state", startedAt, state, "Current debug state dumped.")
    }

    private fun dumpLiveTvState(startedAt: Long): DebugCommandResult {
        val cacheStore = LiveTvCachedCatalogStore(appContext.filesDir)
        val cachedJson = cacheStore.read() ?: return failure(
            command = "dump_live_tv_state",
            startedAt = startedAt,
            currentState = "cache_missing",
            catalogSource = DebugCatalogSource.Cached,
            lastErrorCategory = "CacheMissing",
            message = "No cached Live TV catalog exists.",
        )
        val preferencesStore = LiveTvPreferencesStore(java.io.File(appContext.filesDir, LiveTvPreferencesStore.FileName))
        val favoritesStore = LiveTvFavoritesStore(java.io.File(appContext.filesDir, LiveTvFavoritesStore.FileName))
        val preferences = preferencesStore.load()
        val liveTvMapper = LiveTvCatalogMapper(endpointBuilder)
        val ready = liveTvMapper
            .mapCachedCatalogJson(cachedJson, DebugConfig.debugXtreamCredentials)
            .copy(cacheAgeMs = cacheStore.cacheAgeMs())
            .withRestoredContext(
                categoryId = preferences.selectedCategoryId,
                streamId = preferences.selectedChannelStreamId,
                query = preferences.searchQuery,
                favorites = favoritesStore.loadFavoriteIds(),
                sortMode = preferences.sortMode,
            )
        val dump = DebugLiveTvStateDumpBuilder.build(ready)
        store.writeLiveTvState(dump)
        val catalog = store.readCatalog()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "live_tv_state_dumped",
            catalogSource = ready.source,
            itemCounts = catalog?.itemCounts ?: DebugItemCounts(liveStreams = ready.channels.size, keptLiveStreams = ready.channels.size),
            cacheAgeMs = ready.cacheAgeMs,
            lastErrorCategory = null,
            redactedUrl = dump.redactedStreamUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(dump.redactedStreamUrl),
        )
        store.writeState(state)
        return success(
            command = "dump_live_tv_state",
            startedAt = startedAt,
            state = state,
            message = "Live TV state dumped.",
            liveTvState = dump,
        )
    }

    private fun dumpMoviesState(startedAt: Long): DebugCommandResult {
        val cacheStore = LiveTvCachedCatalogStore(appContext.filesDir)
        val cachedJson = cacheStore.read() ?: return failure(
            command = "dump_movies_state",
            startedAt = startedAt,
            currentState = "cache_missing",
            catalogSource = DebugCatalogSource.Cached,
            lastErrorCategory = "CacheMissing",
            message = "No cached Movies catalog exists.",
        )
        val preferencesStore = MoviesPreferencesStore(java.io.File(appContext.filesDir, MoviesPreferencesStore.FileName))
        val watchlistStore = MoviesWatchlistStore(java.io.File(appContext.filesDir, MoviesWatchlistStore.FileName))
        val preferences = preferencesStore.load()
        val moviesMapper = MoviesCatalogMapper(endpointBuilder)
        val ready = moviesMapper
            .mapCachedCatalogJson(cachedJson, DebugConfig.debugXtreamCredentials)
            .copy(cacheAgeMs = cacheStore.cacheAgeMs())
            .withRestoredContext(
                categoryId = preferences.selectedCategoryId ?: MovieCategoryIds.All,
                streamId = preferences.selectedMovieStreamId,
                query = preferences.searchQuery,
                sortMode = preferences.sortMode,
                watchlist = watchlistStore.loadWatchlistIds(),
            )
        val dump = DebugMoviesStateDumpBuilder.build(ready)
        store.writeMoviesState(dump)
        val catalog = store.readCatalog()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "movies_state_dumped",
            catalogSource = ready.source,
            itemCounts = catalog?.itemCounts ?: DebugItemCounts(vodStreams = ready.movies.size, keptVodStreams = ready.movies.size),
            cacheAgeMs = ready.cacheAgeMs,
            lastErrorCategory = null,
            redactedUrl = dump.redactedStreamUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(dump.redactedStreamUrl),
        )
        store.writeState(state)
        return success(
            command = "dump_movies_state",
            startedAt = startedAt,
            state = state,
            message = "Movies state dumped.",
            moviesState = dump,
        )
    }

    private fun dumpWatchProgressState(startedAt: Long): DebugCommandResult {
        val dump = buildWatchProgressDump()
        store.writeWatchProgressState(dump)
        val catalog = store.readCatalog()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "watch_progress_state_dumped",
            catalogSource = catalog?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = catalog?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = null,
            redactedUrl = catalog?.redactedUrl,
            redactedHost = catalog?.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "dump_watch_progress_state",
            startedAt = startedAt,
            state = state,
            message = "Watch progress state dumped.",
            watchProgressState = dump,
        )
    }

    private fun providerSettingsStore(): ProviderSettingsStore =
        ProviderSettingsStore(File(appContext.filesDir, ProviderSettingsStore.FileName))

    private fun seedFixtureWatchProgress(startedAt: Long): DebugCommandResult {
        val now = System.currentTimeMillis()
        watchProgressStore().saveRecords(
            listOf(
                WatchProgressRecord(
                    contentType = WatchProgressContentType.Movie,
                    streamId = 2001,
                    title = "Fixture Movie One",
                    categoryName = "Fixture Action",
                    extension = "mp4",
                    durationMs = 7_200_000L,
                    positionMs = 900_000L,
                    updatedAtMs = now,
                    completed = false,
                ),
                WatchProgressRecord(
                    contentType = WatchProgressContentType.SeriesEpisode,
                    streamId = 9001,
                    seriesId = 3001,
                    title = "Fixture Series One - Pilot",
                    seriesTitle = "Fixture Series One",
                    episodeTitle = "Pilot",
                    categoryName = "Fixture Series",
                    extension = "mp4",
                    durationMs = 3_600_000L,
                    positionMs = 600_000L,
                    updatedAtMs = now - 1_000L,
                    completed = false,
                ),
            ),
        )
        val dump = buildWatchProgressDump()
        store.writeWatchProgressState(dump)
        val catalog = store.readCatalog()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "fixture_watch_progress_seeded",
            catalogSource = catalog?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = catalog?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = null,
            redactedUrl = catalog?.redactedUrl,
            redactedHost = catalog?.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "seed_fixture_watch_progress",
            startedAt = startedAt,
            state = state,
            message = "Fixture watch progress seeded.",
            watchProgressState = dump,
        )
    }

    private fun clearWatchProgress(startedAt: Long): DebugCommandResult {
        watchProgressStore().clear()
        val dump = buildWatchProgressDump()
        store.writeWatchProgressState(dump)
        val catalog = store.readCatalog()
        val state = DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = "watch_progress_cleared",
            catalogSource = catalog?.catalogSource ?: DebugCatalogSource.None,
            itemCounts = catalog?.itemCounts ?: DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = null,
            redactedUrl = catalog?.redactedUrl,
            redactedHost = catalog?.redactedHost,
        )
        store.writeState(state)
        return success(
            command = "clear_watch_progress",
            startedAt = startedAt,
            state = state,
            message = "Watch progress cleared.",
            watchProgressState = dump,
        )
    }

    private fun buildWatchProgressDump(): DebugWatchProgressStateDump {
        val snapshot = watchProgressStore().load()
        return DebugWatchProgressStateDump(
            totalCount = snapshot.records.size,
            continueWatchingCount = snapshot.continueWatching().size,
            records = snapshot.records.sortedByDescending { it.updatedAtMs }.map { record ->
                DebugWatchProgressRecordDump(
                    contentType = record.contentType.storageKey,
                    streamId = record.streamId,
                    seriesId = record.seriesId,
                    title = record.title,
                    seriesTitle = record.seriesTitle,
                    episodeTitle = record.episodeTitle,
                    categoryName = record.categoryName,
                    extension = record.extension,
                    durationMs = record.durationMs,
                    positionMs = record.positionMs,
                    updatedAtMs = record.updatedAtMs,
                    completed = record.completed,
                    resumeEligible = WatchProgressPolicy.isResumeEligible(record),
                )
            },
        )
    }

    private fun watchProgressStore(): WatchProgressStore =
        WatchProgressStore(
            File(appContext.filesDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(AtlasDatabaseProvider.database(appContext).watchProgressDao()),
        )

    private fun validateSafeCatalogCacheSnapshot(snapshot: DebugSafeCatalogCacheSnapshot): String? {
        if (snapshot.schemaVersion != SAFE_CATALOG_SNAPSHOT_SCHEMA_VERSION) return "UnsupportedSnapshotVersion"
        if (snapshot.exportedAtEpochMs <= 0L) return "InvalidSnapshotTimestamp"
        val catalog = snapshot.catalog
        if (catalog.playableLiveItems.isEmpty() && catalog.playableVodItems.isEmpty() && catalog.playableSeriesItems.isEmpty()) {
            return "EmptySnapshot"
        }
        val json = DebugCommandJson.safeCatalogCacheSnapshotToJson(snapshot)
        if (DebugCommandSanitizer.sanitizeText(json) != json) return "UnsafeStreamUrl"
        if (json.contains("fake_user", ignoreCase = true) || json.contains("fake_pass", ignoreCase = true)) return "UnsafeCredentials"
        if (json.contains("example.test", ignoreCase = true)) return "UnsafeHost"
        val urlFields = listOfNotNull(catalog.redactedUrl, catalog.redactedHost) +
            catalog.playableLiveItems.map { it.redactedUrl } +
            catalog.playableVodItems.map { it.redactedUrl }
        if (urlFields.any { it.contains("://") && !it.contains("REDACTED_HOST") && !it.startsWith("fixture://") }) {
            return "UnsafeHost"
        }
        return null
    }

    private fun DebugSafeCatalogCacheSnapshot.toSnapshotState(exists: Boolean): DebugCatalogCacheSnapshotState =
        DebugCatalogCacheSnapshotState(
            exists = exists,
            schemaVersion = schemaVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            cacheAgeMs = cacheAgeMs,
            catalogSource = catalog.catalogSource,
            itemCounts = catalog.itemCounts,
            redactedUrl = catalog.redactedUrl,
            redactedHost = catalog.redactedHost,
        )

    private fun fixtureImportVodCategories(): List<XtreamCategory> =
        listOf(
            XtreamCategory(categoryId = "movie-action", categoryName = "US Action", parentId = null),
            XtreamCategory(categoryId = "movie-drama", categoryName = "UK Drama", parentId = null),
            XtreamCategory(categoryId = "movie-excluded", categoryName = "French Movies", parentId = null),
        )

    private fun fixtureImportSeriesCategories(): List<XtreamCategory> =
        listOf(
            XtreamCategory(categoryId = "series-drama", categoryName = "US Series", parentId = null),
            XtreamCategory(categoryId = "series-comedy", categoryName = "English Comedy", parentId = null),
            XtreamCategory(categoryId = "series-excluded", categoryName = "French Series", parentId = null),
        )

    private fun fixtureImportVodStreams(): List<XtreamVodStream> {
        val kept = (1..LARGE_FIXTURE_MOVIE_COUNT).map { index ->
            val categoryId = if (index % 2 == 0) "movie-drama" else "movie-action"
            XtreamVodStream(
                num = index,
                name = "Imported Movie $index",
                streamType = "movie",
                streamId = 20_000 + index,
                streamIcon = if (index <= 16) "fixture://poster/imported-movie-$index" else null,
                rating = null,
                rating5Based = null,
                added = (1_700_000_000L + index).toString(),
                categoryId = categoryId,
                containerExtension = if (index % 5 == 0) "mkv" else DEFAULT_VOD_EXTENSION,
            )
        }
        val excluded = (1..LARGE_FIXTURE_EXCLUDED_MOVIE_COUNT).map { index ->
            XtreamVodStream(
                num = LARGE_FIXTURE_MOVIE_COUNT + index,
                name = "Excluded Movie $index",
                streamType = "movie",
                streamId = 90_000 + index,
                streamIcon = null,
                rating = null,
                rating5Based = null,
                added = null,
                categoryId = "movie-excluded",
                containerExtension = DEFAULT_VOD_EXTENSION,
            )
        }
        return kept + excluded
    }

    private fun fixtureImportSeries(): List<XtreamSeries> {
        val kept = (1..LARGE_FIXTURE_SERIES_COUNT).map { index ->
            val categoryId = if (index % 2 == 0) "series-comedy" else "series-drama"
            XtreamSeries(
                num = index,
                name = "Imported Series $index",
                seriesId = 30_000 + index,
                cover = if (index <= 16) "fixture://poster/imported-series-$index" else null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                releaseDate = null,
                lastModified = (1_700_500_000L + index).toString(),
                rating = null,
                rating5Based = null,
                categoryId = categoryId,
            )
        }
        val excluded = (1..LARGE_FIXTURE_EXCLUDED_SERIES_COUNT).map { index ->
            XtreamSeries(
                num = LARGE_FIXTURE_SERIES_COUNT + index,
                name = "Excluded Series $index",
                seriesId = 95_000 + index,
                cover = null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                releaseDate = null,
                lastModified = null,
                rating = null,
                rating5Based = null,
                categoryId = "series-excluded",
            )
        }
        return kept + excluded
    }

    private fun success(
        command: String,
        startedAt: Long,
        state: DebugStateSnapshot,
        message: String,
        playbackProbe: DebugPlaybackProbeResult? = null,
        playbackHealthSweep: DebugPlaybackHealthSweep? = null,
        playbackDeepHealth: DebugPlaybackDeepHealth? = null,
        liveTvState: DebugLiveTvStateDump? = null,
        moviesState: DebugMoviesStateDump? = null,
        watchProgressState: DebugWatchProgressStateDump? = null,
        catalogCacheSnapshotState: DebugCatalogCacheSnapshotState? = null,
        catalogImport: DebugCatalogImportState? = null,
        catalogAudit: DebugCatalogCoverageAudit? = null,
        searchReachability: DebugSearchReachabilityReport? = null,
    ): DebugCommandResult =
        DebugCommandResult(
            command = command,
            status = DebugCommandStatus.Success,
            durationMs = System.currentTimeMillis() - startedAt,
            currentScreen = state.currentScreen,
            currentState = state.currentState,
            catalogSource = state.catalogSource,
            itemCounts = state.itemCounts,
            cacheAgeMs = state.cacheAgeMs,
            lastErrorCategory = state.lastErrorCategory,
            redactedUrl = state.redactedUrl,
            redactedHost = state.redactedHost,
            message = message,
            playbackProbe = playbackProbe,
            playbackHealthSweep = playbackHealthSweep,
            playbackDeepHealth = playbackDeepHealth,
            liveTvState = liveTvState,
            moviesState = moviesState,
            watchProgressState = watchProgressState,
            catalogCacheSnapshotState = catalogCacheSnapshotState,
            catalogImport = catalogImport,
            catalogAudit = catalogAudit,
            searchReachability = searchReachability,
        )

    private fun failure(
        command: String,
        startedAt: Long,
        currentState: String,
        catalogSource: String,
        lastErrorCategory: String,
        message: String,
        redactedUrl: String? = null,
    ): DebugCommandResult =
        DebugCommandResult(
            command = command,
            status = DebugCommandStatus.Failure,
            durationMs = System.currentTimeMillis() - startedAt,
            currentScreen = "debug_command",
            currentState = currentState,
            catalogSource = catalogSource,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = store.cacheAgeMs(System.currentTimeMillis()),
            lastErrorCategory = lastErrorCategory,
            redactedUrl = redactedUrl,
            redactedHost = DebugCommandSanitizer.redactedHost(redactedUrl),
            message = message,
        )

    private fun DebugCatalogSnapshot.toState(currentState: String, cacheAgeMs: Long?): DebugStateSnapshot =
        DebugStateSnapshot(
            currentScreen = "debug_command",
            currentState = currentState,
            catalogSource = catalogSource,
            itemCounts = itemCounts,
            cacheAgeMs = cacheAgeMs,
            lastErrorCategory = null,
            redactedUrl = redactedUrl,
            redactedHost = redactedHost,
        )

    private companion object {
        private const val TAG = "AtlasTV/DebugCommand"
        private const val SAFE_CATALOG_SNAPSHOT_SCHEMA_VERSION = 1
        private const val DEFAULT_LIVE_EXTENSION = "ts"
        private const val DEFAULT_VOD_EXTENSION = "mp4"
        private const val MAX_PLAYABLE_LIVE_ITEMS = 120
        private const val MAX_PLAYABLE_VOD_ITEMS = 120
        private const val MAX_PLAYABLE_SERIES_ITEMS = 120
        private const val PLAYBACK_SAMPLE_SERIES_LIMIT = 8
        private const val PLAYBACK_SAMPLE_EPISODE_LIMIT = 12
        private const val PLAYBACK_SAMPLE_RENDER_ATTEMPTS = 4
        private const val SAVED_PROVIDER_SYNC_TIMEOUT_MS = 5L * 60L * 1000L
        private const val LARGE_FIXTURE_MOVIE_COUNT = 1_500
        private const val LARGE_FIXTURE_SERIES_COUNT = 1_300
        private const val LARGE_FIXTURE_EXCLUDED_MOVIE_COUNT = 25
        private const val LARGE_FIXTURE_EXCLUDED_SERIES_COUNT = 25
        private const val DEEP_SEARCH_OFFSET = 150
        private const val SEARCH_PROBE_LIMIT = 24
        private const val SEARCH_RESULT_ID_LIMIT = 8
        private const val EXAMPLE_CATEGORY_LIMIT = 16
        private const val MAX_SAFE_DEBUG_TEXT_LENGTH = 160
        private const val HEALTH_FIRST_FRAME_TIMEOUT_MS = 15_000L
        private const val HEALTH_SAMPLE_AFTER_FIRST_FRAME_MS = 12_000L
        private const val DEFAULT_DEEP_SAMPLE_SECONDS = 45
        private const val MIN_DEEP_SAMPLE_SECONDS = 10
        private const val MAX_DEEP_SAMPLE_SECONDS = 120
        private const val DEEP_TRANSPORT_SECONDS = 25
        private const val DEEP_WINDOW_SECONDS = 5
        private const val DEEP_FIRST_FRAME_TIMEOUT_MS = 20_000L
        private const val DEEP_TRANSPORT_MAX_BYTES = 128L * 1024L * 1024L
        private const val LIGHTWEIGHT_REQUEST_SHAPE = "range=bytes=0-0; default-okhttp-user-agent; redirects=true"
        private const val MEDIA3_DEFAULT_MIMIC_REQUEST_SHAPE = "userAgent=AndroidXMedia3/1.10.1; acceptEncoding=identity; range=bytes=0-; redirects=true"
        private const val ACTUAL_MEDIA3_DEFAULT_REQUEST_SHAPE = "actual Media3 default data source; default user-agent/request properties"
        private val AuditAllowedPhrases = setOf(
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
        private val AuditAllowedCodes = setOf("usa", "us", "uk", "ca", "en", "au", "4k", "uhd", "new", "kids", "anime", "max")
        private val PotentiallyUsefulEnglishPhrases = AuditAllowedPhrases + setOf("movie", "movies", "series", "shows", "cinema", "action", "comedy", "drama", "thriller", "sci-fi", "sci fi")
        private val PotentiallyUsefulEnglishCodes = AuditAllowedCodes
        private val DEEP_PROFILES = listOf(
            DeepPlaybackProfile("baseline", largerBufferProfile = false, tunnelingRequested = false, surfaceWidth = 1280, surfaceHeight = 720, surfaceSummary = "offscreen_surface_1280x720"),
            DeepPlaybackProfile("larger_buffer", largerBufferProfile = true, tunnelingRequested = false, surfaceWidth = 1280, surfaceHeight = 720, surfaceSummary = "offscreen_surface_1280x720"),
            DeepPlaybackProfile("surface_max_size", largerBufferProfile = false, tunnelingRequested = false, surfaceWidth = 3840, surfaceHeight = 2160, surfaceSummary = "offscreen_surface_3840x2160"),
            DeepPlaybackProfile("tunneling_requested", largerBufferProfile = false, tunnelingRequested = true, surfaceWidth = 1280, surfaceHeight = 720, surfaceSummary = "offscreen_surface_1280x720"),
        )
    }
}

private data class DeepHealthTarget(
    val section: String,
    val selectedItemName: String? = null,
    val streamId: Int? = null,
    val containerExtension: String? = null,
    val streamUrl: String? = null,
    val redactedUrl: String? = null,
    val message: String? = null,
)

private data class DebugSeriesEpisodeTarget(
    val series: DebugPlayableSeriesItem,
    val episode: com.atlas.atlastv.ui.series.SeriesEpisodeItem,
)

private data class DeepPlaybackProfile(
    val name: String,
    val largerBufferProfile: Boolean,
    val tunnelingRequested: Boolean,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val surfaceSummary: String,
)

data class DebugPlaybackBottleneck(val category: String, val reason: String)

object DebugPlaybackBottleneckClassifier {
    fun classify(transport: DebugTransportProbeResult?, profiles: List<DebugPlaybackDeepProfileResult>): DebugPlaybackBottleneck {
        val baseline = profiles.firstOrNull { it.profile == "baseline" } ?: profiles.firstOrNull()
            ?: return DebugPlaybackBottleneck("UNKNOWN", "No playback profiles were sampled.")
        val largerBuffer = profiles.firstOrNull { it.profile == "larger_buffer" }
        val surface = profiles.firstOrNull { it.profile == "surface_max_size" }
        val transportWeak = transport != null && (transport.stalledIntervalCount > 0 || transport.minWindowMbps < 2.0 || transport.exceptionCategory != null)
        val highDropRatio = (baseline.droppedRenderedRatio ?: 0.0) >= 0.03 || baseline.counters.maxConsecutiveDroppedBufferCount >= 10
        val highProcessingOffset = (baseline.averageVideoFrameProcessingOffsetUs ?: 0.0) > 20_000.0
        val hasMaterialPlaybackIssue = highDropRatio || baseline.rebufferCount > 0 || baseline.droppedFramesPerSecond > 1.0
        val largerBufferImproves = largerBuffer != null && baseline.rebufferCount > 0 && largerBuffer.rebufferCount < baseline.rebufferCount
        val surfaceDiffers = surface != null && baseline.droppedFramesPerSecond > 0.0 && surface.droppedFramesPerSecond < baseline.droppedFramesPerSecond * 0.5
        val video = baseline.video
        val encodingRisk = video?.let {
            (it.height ?: 0) >= 2160 || (it.frameRate ?: 0f) >= 50f || (it.bitrate ?: 0) >= 15_000_000
        } ?: false
        return when {
            highDropRatio || (highProcessingOffset && hasMaterialPlaybackIssue) -> DebugPlaybackBottleneck("DECODER_TOO_SLOW", "Dropped/rendered ratio, consecutive drops, or frame processing offset is high while playback remains at max quality.")
            transportWeak && baseline.rebufferCount > 0 -> DebugPlaybackBottleneck("NETWORK_JITTER", "Transport probe showed low/stalled throughput and playback rebuffered while decoder counters stayed relatively healthy.")
            surfaceDiffers -> DebugPlaybackBottleneck("RENDER_SURFACE_OVERHEAD", "Surface comparison reduced dropped frames materially.")
            largerBufferImproves -> DebugPlaybackBottleneck("NETWORK_JITTER", "Larger buffer reduced rebuffer events without changing quality.")
            encodingRisk && hasMaterialPlaybackIssue -> DebugPlaybackBottleneck("STREAM_ENCODING_RISK", "Selected stream metadata is heavy for the target device.")
            else -> DebugPlaybackBottleneck("UNKNOWN", "No single bottleneck dominated the collected metrics.")
        }
    }
}

private fun Long.toMbps(durationMs: Long): Double =
    if (durationMs <= 0L) 0.0 else (this * 8.0) / durationMs / 1000.0

private fun Headers.toSanitizedHeaderMap(): Map<String, String> {
    val allowed = setOf("Content-Type", "Content-Length", "Accept-Ranges", "Transfer-Encoding")
    return names()
        .filter { name -> allowed.any { it.equals(name, ignoreCase = true) } }
        .associateWith { name -> get(name).orEmpty().take(120) }
}

private fun DecoderCounters?.toSnapshot(): DebugVideoDecoderCountersSnapshot {
    this?.ensureUpdated()
    return DebugVideoDecoderCountersSnapshot(
        droppedBufferCount = this?.droppedBufferCount ?: 0,
        droppedInputBufferCount = this?.droppedInputBufferCount ?: 0,
        droppedToKeyframeCount = this?.droppedToKeyframeCount ?: 0,
        maxConsecutiveDroppedBufferCount = this?.maxConsecutiveDroppedBufferCount ?: 0,
        renderedOutputBufferCount = this?.renderedOutputBufferCount ?: 0,
        skippedInputBufferCount = this?.skippedInputBufferCount ?: 0,
        skippedOutputBufferCount = this?.skippedOutputBufferCount ?: 0,
        totalVideoFrameProcessingOffsetUs = this?.totalVideoFrameProcessingOffsetUs ?: 0L,
        videoFrameProcessingOffsetCount = this?.videoFrameProcessingOffsetCount ?: 0,
    )
}

private fun Format.toVideoSnapshot(selectedDecoderName: String?): DebugVideoFormatSnapshot {
    val decoders = sampleMimeType?.availableDecoderNames() ?: emptyList()
    return DebugVideoFormatSnapshot(
        sampleMimeType = sampleMimeType,
        codecs = codecs,
        width = width.takeIf { it > 0 },
        height = height.takeIf { it > 0 },
        frameRate = frameRate.takeIf { it > 0f },
        bitrate = bitrate.takeIf { it > 0 },
        availableDecoderNames = decoders,
        selectedDecoderAppearsHardware = selectedDecoderName?.appearsHardwareDecoder(),
        selectedSizeAndRateSupported = selectedDecoderName?.let { decoder ->
            sampleMimeType?.isSizeAndRateSupportedByDecoder(decoder, width, height, frameRate)
        },
    )
}

private fun Format.toAudioSnapshot(): DebugAudioFormatSnapshot =
    DebugAudioFormatSnapshot(
        sampleMimeType = sampleMimeType,
        codecs = codecs,
        channelCount = channelCount.takeIf { it > 0 },
        sampleRate = sampleRate.takeIf { it > 0 },
    )

private fun String.availableDecoderNames(): List<String> =
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(this, ignoreCase = true) } }
            .map { it.name }
            .take(12)
    }.getOrDefault(emptyList())

private fun String.appearsHardwareDecoder(): Boolean {
    val lower = lowercase()
    if (lower.contains("google") || lower.contains("sw") || lower.contains("software")) return false
    return true
}

private fun String.isSizeAndRateSupportedByDecoder(decoderName: String, width: Int, height: Int, frameRate: Float): Boolean? =
    runCatching {
        if (width <= 0 || height <= 0) return@runCatching null
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == decoderName } ?: return@runCatching null
        val caps = info.getCapabilitiesForType(this)
        val videoCaps = caps.videoCapabilities ?: return@runCatching null
        if (frameRate > 0f) videoCaps.areSizeAndRateSupported(width, height, frameRate.toDouble()) else videoCaps.isSizeSupported(width, height)
    }.getOrNull()

private fun Int.toStatusCategory(): String =
    when (this) {
        in 200..299 -> "success"
        in 300..399 -> "redirect"
        in 400..499 -> "client_error"
        in 500..599 -> "server_error"
        else -> "unexpected_status"
    }

private fun String?.safeDebugImageReference(): String? {
    val ref = atlasImageRefFromRaw(this)
    return when (ref.state) {
        AtlasImageState.SafeImage -> ref.safeUri
        AtlasImageState.ProviderImage -> ref.providerImageToken
        AtlasImageState.ProviderImageHidden,
        AtlasImageState.Missing -> null
    }
}

private fun DebugPlayableLiveItem.toProbeFailure(
    name: String,
    requestShapeSummary: String,
    statusCategory: String,
    media3ErrorCode: String,
    startedAt: Long,
    message: String,
): DebugPlaybackProbeComparison =
    DebugPlaybackProbeComparison(
        name = name,
        requestShapeSummary = requestShapeSummary,
        httpStatus = null,
        statusCategory = statusCategory,
        media3ErrorCode = media3ErrorCode,
        durationMs = System.currentTimeMillis() - startedAt,
        message = message,
    )

private fun Throwable.findInvalidResponseCode(): Int? {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode
        }
        current = current.cause
    }
    return null
}
