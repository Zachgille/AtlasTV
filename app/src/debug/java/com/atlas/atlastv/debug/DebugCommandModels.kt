package com.atlas.atlastv.debug

data class DebugCommandResult(
    val command: String,
    val status: String,
    val durationMs: Long,
    val currentScreen: String,
    val currentState: String,
    val catalogSource: String,
    val itemCounts: DebugItemCounts,
    val cacheAgeMs: Long?,
    val lastErrorCategory: String?,
    val redactedUrl: String?,
    val redactedHost: String?,
    val message: String?,
    val playbackProbe: DebugPlaybackProbeResult? = null,
    val playbackHealthSweep: DebugPlaybackHealthSweep? = null,
    val playbackDeepHealth: DebugPlaybackDeepHealth? = null,
    val liveTvState: DebugLiveTvStateDump? = null,
    val moviesState: DebugMoviesStateDump? = null,
    val watchProgressState: DebugWatchProgressStateDump? = null,
    val catalogCacheSnapshotState: DebugCatalogCacheSnapshotState? = null,
    val catalogImport: DebugCatalogImportState? = null,
    val catalogAudit: DebugCatalogCoverageAudit? = null,
    val searchReachability: DebugSearchReachabilityReport? = null,
)

data class DebugStateSnapshot(
    val currentScreen: String,
    val currentState: String,
    val catalogSource: String,
    val itemCounts: DebugItemCounts,
    val cacheAgeMs: Long?,
    val lastErrorCategory: String?,
    val redactedUrl: String?,
    val redactedHost: String?,
)

data class DebugCatalogSnapshot(
    val catalogSource: String,
    val generatedAtEpochMs: Long,
    val itemCounts: DebugItemCounts,
    val redactedUrl: String?,
    val redactedHost: String?,
    val playableLiveItems: List<DebugPlayableLiveItem> = emptyList(),
    val playableVodItems: List<DebugPlayableVodItem> = emptyList(),
    val playableSeriesItems: List<DebugPlayableSeriesItem> = emptyList(),
)

data class DebugSafeCatalogCacheSnapshot(
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val cacheAgeMs: Long?,
    val catalog: DebugCatalogSnapshot,
)

data class DebugCatalogCacheSnapshotState(
    val exists: Boolean,
    val schemaVersion: Int?,
    val exportedAtEpochMs: Long?,
    val cacheAgeMs: Long?,
    val catalogSource: String,
    val itemCounts: DebugItemCounts,
    val redactedUrl: String?,
    val redactedHost: String?,
)

data class DebugCatalogImportState(
    val status: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val durationMs: Long,
    val moviesImported: Int,
    val seriesImported: Int,
    val excludedCategories: Int,
    val excludedItems: Int,
    val errorCategory: String?,
    val totalDurationMs: Long = 0L,
    val accountInfoDurationMs: Long = 0L,
    val vodCategoriesDurationMs: Long = 0L,
    val vodStreamsDurationMs: Long = 0L,
    val seriesCategoriesDurationMs: Long = 0L,
    val seriesDurationMs: Long = 0L,
    val filteringDurationMs: Long = 0L,
    val roomWriteDurationMs: Long = 0L,
    val vodCategoryCount: Int = 0,
    val vodStreamCount: Int = 0,
    val seriesCategoryCount: Int = 0,
    val seriesCount: Int = 0,
)

data class DebugCatalogCoverageAudit(
    val status: String,
    val redactedHost: String?,
    val vodCategoriesAvailable: Int,
    val vodCategoriesAllowedForDownload: Int,
    val vodCategoriesImported: Int,
    val vodItemsFetched: Int,
    val vodItemsImported: Int,
    val seriesCategoriesAvailable: Int,
    val seriesCategoriesAllowedForDownload: Int,
    val seriesCategoriesImported: Int,
    val seriesItemsFetched: Int,
    val seriesItemsImported: Int,
    val excludedCategoryCount: Int,
    val excludedItemCount: Int,
    val skippedOrGenericCategoryCount: Int,
    val usefulEnglishSkippedCategoryCount: Int,
    val allowedVodCategoryExamples: List<String>,
    val allowedSeriesCategoryExamples: List<String>,
    val importedVodCategoryExamples: List<String>,
    val importedSeriesCategoryExamples: List<String>,
    val skippedCategoryExamples: List<String>,
    val usefulEnglishSkippedCategoryExamples: List<String>,
    val excludedCategoryExamples: List<String>,
    val duplicateOrVariantEnglishExamples: List<String>,
    val categories: List<DebugCatalogCategoryAuditEntry> = emptyList(),
)

data class DebugCatalogCategoryAuditEntry(
    val categoryId: String?,
    val categoryName: String,
    val contentType: String,
    val itemCount: Int,
    val decision: String,
    val reason: String,
)

data class DebugSearchReachabilityReport(
    val status: String,
    val movieTotal: Int,
    val seriesTotal: Int,
    val deepOffsetUsed: Int,
    val deepMovie: DebugSearchProbeItem?,
    val deepSeries: DebugSearchProbeItem?,
    val movieSearch: DebugSearchProbeResult?,
    val seriesSearch: DebugSearchProbeResult?,
    val unifiedSearch: DebugUnifiedSearchProbeResult,
)

data class DebugSearchProbeItem(
    val id: Int,
    val title: String,
    val categoryName: String,
)

data class DebugSearchProbeResult(
    val query: String,
    val totalMatches: Int,
    val boundedResultCount: Int,
    val foundSelectedItem: Boolean,
    val firstResultIds: List<Int>,
)

data class DebugUnifiedSearchProbeResult(
    val query: String,
    val movieMatches: Int,
    val seriesMatches: Int,
    val boundedMovieResultCount: Int,
    val boundedSeriesResultCount: Int,
)

data class DebugPlayableLiveItem(
    val name: String,
    val categoryName: String = "Uncategorized",
    val streamId: Int,
    val containerExtension: String,
    val redactedUrl: String,
)

data class DebugPlayableVodItem(
    val title: String,
    val categoryName: String = "Uncategorized",
    val streamId: Int,
    val containerExtension: String,
    val redactedUrl: String,
    val posterImage: String? = null,
)

data class DebugPlayableSeriesItem(
    val name: String,
    val categoryName: String = "Uncategorized",
    val seriesId: Int,
    val posterImage: String? = null,
)

data class DebugPlaybackProbeResult(
    val selectedItemName: String,
    val streamId: Int,
    val containerExtension: String,
    val redactedUrl: String,
    val requestShapeSummary: String,
    val httpStatus: Int?,
    val statusCategory: String,
    val media3ErrorCode: String?,
    val playerState: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val firstFrameRendered: Boolean? = null,
    val droppedFrames: Int? = null,
    val durationMs: Long,
    val message: String?,
    val comparisons: List<DebugPlaybackProbeComparison> = emptyList(),
)

data class DebugPlaybackProbeComparison(
    val name: String,
    val requestShapeSummary: String,
    val httpStatus: Int?,
    val statusCategory: String,
    val media3ErrorCode: String?,
    val playerState: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val firstFrameRendered: Boolean? = null,
    val droppedFrames: Int? = null,
    val durationMs: Long,
    val message: String?,
)

data class DebugPlaybackHealthSweep(
    val sampleAfterFirstFrameMs: Long,
    val results: List<DebugPlaybackHealthSectionResult>,
)

data class DebugPlaybackHealthSectionResult(
    val section: String,
    val selectedItemName: String?,
    val streamId: Int?,
    val containerExtension: String?,
    val redactedUrl: String?,
    val requestShapeSummary: String,
    val httpStatus: Int?,
    val statusCategory: String,
    val media3ErrorCode: String?,
    val playerState: String?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val firstFrameRendered: Boolean,
    val droppedFrames: Int,
    val bufferCount: Int,
    val playbackPositionStartMs: Long?,
    val playbackPositionEndMs: Long?,
    val playbackAdvanced: Boolean,
    val durationMs: Long,
    val message: String?,
)

data class DebugPlaybackDeepHealth(
    val sampleDurationSeconds: Int,
    val transportDurationSeconds: Int,
    val windowSeconds: Int,
    val device: DebugDeviceMediaSnapshot,
    val results: List<DebugPlaybackDeepSectionResult>,
)

data class DebugPlaybackDeepSectionResult(
    val section: String,
    val selectedItemName: String?,
    val streamId: Int?,
    val containerExtension: String?,
    val redactedUrl: String?,
    val transport: DebugTransportProbeResult?,
    val profiles: List<DebugPlaybackDeepProfileResult>,
    val bottleneck: String,
    val bottleneckReason: String,
)

data class DebugPlaybackDeepProfileResult(
    val profile: String,
    val requestShapeSummary: String,
    val surfaceSummary: String,
    val tunnelingRequested: Boolean,
    val largerBufferProfile: Boolean,
    val httpStatus: Int?,
    val statusCategory: String,
    val media3ErrorCode: String?,
    val playerState: String?,
    val firstFrameRendered: Boolean,
    val warmupToReadyMs: Long?,
    val firstFrameTimeMs: Long?,
    val durationMs: Long,
    val playbackPositionStartMs: Long?,
    val playbackPositionEndMs: Long?,
    val playbackAdvanced: Boolean,
    val rebufferCount: Int,
    val totalRebufferMs: Long,
    val video: DebugVideoFormatSnapshot?,
    val audio: DebugAudioFormatSnapshot?,
    val decoder: DebugDecoderSnapshot,
    val counters: DebugVideoDecoderCountersSnapshot,
    val windows: List<DebugPlaybackHealthWindow>,
    val renderedFramesPerSecond: Double?,
    val droppedFramesPerSecond: Double,
    val droppedRenderedRatio: Double?,
    val averageVideoFrameProcessingOffsetUs: Double?,
    val message: String?,
)

data class DebugPlaybackHealthWindow(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val playerState: String?,
    val playbackPositionMs: Long,
    val bufferedPositionMs: Long,
    val bufferedDurationMs: Long,
    val droppedFrames: Int,
    val renderedFrames: Int?,
    val droppedFramesPerSecond: Double,
)

data class DebugTransportProbeResult(
    val httpStatus: Int?,
    val statusCategory: String,
    val contentType: String?,
    val sanitizedHeaders: Map<String, String>,
    val timeToFirstByteMs: Long?,
    val bytesRead: Long,
    val averageMbps: Double,
    val minWindowMbps: Double,
    val maxWindowMbps: Double,
    val stalledIntervalCount: Int,
    val durationMs: Long,
    val exceptionCategory: String?,
)

data class DebugDeviceMediaSnapshot(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val displayRefreshRate: Float?,
    val displayMode: String?,
    val isEmulator: Boolean,
)

data class DebugVideoFormatSnapshot(
    val sampleMimeType: String?,
    val codecs: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    val bitrate: Int?,
    val availableDecoderNames: List<String>,
    val selectedDecoderAppearsHardware: Boolean?,
    val selectedSizeAndRateSupported: Boolean?,
)

data class DebugAudioFormatSnapshot(
    val sampleMimeType: String?,
    val codecs: String?,
    val channelCount: Int?,
    val sampleRate: Int?,
)

data class DebugDecoderSnapshot(
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val videoDecoderInitializationCount: Int,
    val audioDecoderInitializationCount: Int,
)

data class DebugVideoDecoderCountersSnapshot(
    val droppedBufferCount: Int,
    val droppedInputBufferCount: Int,
    val droppedToKeyframeCount: Int,
    val maxConsecutiveDroppedBufferCount: Int,
    val renderedOutputBufferCount: Int,
    val skippedInputBufferCount: Int,
    val skippedOutputBufferCount: Int,
    val totalVideoFrameProcessingOffsetUs: Long,
    val videoFrameProcessingOffsetCount: Int,
)

data class DebugLastError(
    val command: String,
    val category: String,
    val message: String,
    val redactedUrl: String?,
    val createdAtEpochMs: Long,
)

data class DebugLiveTvStateDump(
    val cacheSource: String,
    val cacheAgeMs: Long?,
    val cappedItemCount: Int,
    val selectedCategoryId: String,
    val selectedCategoryName: String,
    val selectedChannelStreamId: Int?,
    val selectedChannelName: String?,
    val selectedChannelCategory: String?,
    val selectedSortMode: String,
    val selectedSortModeLabel: String,
    val searchQuery: String?,
    val favoritesCount: Int,
    val selectedChannelIsFavorite: Boolean,
    val redactedStreamUrl: String?,
)

data class DebugMoviesStateDump(
    val cacheSource: String,
    val cacheAgeMs: Long?,
    val cappedItemCount: Int,
    val selectedCategoryId: String,
    val selectedCategoryName: String,
    val selectedMovieStreamId: Int?,
    val selectedMovieTitle: String?,
    val selectedMovieCategory: String?,
    val selectedSortMode: String,
    val selectedSortModeLabel: String,
    val searchQuery: String?,
    val watchlistCount: Int,
    val selectedMovieIsWatchlisted: Boolean,
    val containerExtension: String?,
    val redactedStreamUrl: String?,
)

data class DebugWatchProgressStateDump(
    val totalCount: Int,
    val continueWatchingCount: Int,
    val records: List<DebugWatchProgressRecordDump>,
)

data class DebugWatchProgressRecordDump(
    val contentType: String,
    val streamId: Int,
    val seriesId: Int?,
    val title: String,
    val seriesTitle: String?,
    val episodeTitle: String?,
    val categoryName: String?,
    val extension: String?,
    val durationMs: Long?,
    val positionMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
    val resumeEligible: Boolean,
)

data class DebugItemCounts(
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

object DebugCommandStatus {
    const val Success = "success"
    const val Failure = "failure"
}

object DebugCatalogSource {
    const val None = "none"
    const val Fixture = "fixture"
    const val Cached = "cached"
    const val Real = "real"
}

object DebugCommandFiles {
    const val CommandResult = "debug_command_result.json"
    const val State = "debug_state.json"
    const val CatalogSummary = "debug_catalog_summary.json"
    const val LastError = "debug_last_error.json"
    const val LiveTvState = "debug_live_tv_state.json"
    const val MoviesState = "debug_movies_state.json"
    const val WatchProgressState = "debug_watch_progress_state.json"
    const val SafeCatalogCacheSnapshot = "debug_safe_catalog_cache_snapshot.json"
    const val CatalogAudit = "debug_catalog_audit.json"
    const val SearchReachability = "debug_search_reachability.json"
}
