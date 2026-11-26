package com.atlas.atlastv.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.atlas.atlastv.ui.livetv.LiveTvCatalogMapper
import com.atlas.atlastv.ui.livetv.LiveTvCategoryIds
import com.atlas.atlastv.ui.livetv.LiveTvSortMode
import com.atlas.atlastv.ui.livetv.withRestoredContext
import com.atlas.atlastv.ui.movies.MovieCategoryIds
import com.atlas.atlastv.ui.movies.MoviesCatalogMapper
import com.atlas.atlastv.ui.movies.MoviesSortMode
import com.atlas.atlastv.ui.movies.withRestoredContext

class DebugCommandJsonTest {
    @Test
    fun serializesCommandResultShape() {
        val result = DebugCommandResult(
            command = "health_check",
            status = DebugCommandStatus.Success,
            durationMs = 12L,
            currentScreen = "debug_command",
            currentState = "healthy",
            catalogSource = DebugCatalogSource.None,
            itemCounts = DebugItemCounts(liveStreams = 3, keptLiveStreams = 2),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = "http://REDACTED_HOST/player_api.php?username=REDACTED&password=REDACTED",
            redactedHost = "REDACTED_HOST",
            message = "ok",
        )

        val json = DebugCommandJson.resultToJson(result)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)

        assertEquals("health_check", parsed.command)
        assertEquals("success", parsed.status)
        assertEquals("debug_command", parsed.currentScreen)
        assertEquals(3, parsed.itemCounts.liveStreams)
        assertTrue(json.contains("\"durationMs\""))
    }

    @Test
    fun redactsCredentialsAndHost() {
        val redacted = DebugCommandSanitizer.redactedUrl(
            "http://example.test/player_api.php?username=fake_user&password=fake_pass&action=get_live_streams",
        )

        assertEquals(
            "http://REDACTED_HOST/player_api.php?username=REDACTED&password=REDACTED&action=get_live_streams",
            redacted,
        )
        assertFalse(redacted!!.contains("example.test"))
        assertFalse(redacted.contains("fake_user"))
        assertFalse(redacted.contains("fake_pass"))
    }

    @Test
    fun sanitizerRemovesRawStreamUrlsFromOutputs() {
        val raw = """{"live":"http://example.test/live/fake_user/fake_pass/123.ts","movie":"http://example.test/movie/fake_user/fake_pass/234.mp4","series":"http://example.test/series/fake_user/fake_pass/345.mkv"}"""

        val sanitized = DebugCommandSanitizer.sanitizeText(raw)

        assertEquals("""{"live":"REDACTED_STREAM_URL","movie":"REDACTED_STREAM_URL","series":"REDACTED_STREAM_URL"}""", sanitized)
        assertFalse(sanitized.contains("example.test"))
        assertFalse(sanitized.contains("fake_user"))
        assertFalse(sanitized.contains("fake_pass"))
    }

    @Test
    fun sanitizerPreservesAlreadyRedactedLiveUrls() {
        val redacted = """{"stream":"http://REDACTED_HOST/live/REDACTED/REDACTED/123.ts"}"""

        val sanitized = DebugCommandSanitizer.sanitizeText(redacted)

        assertEquals(redacted, sanitized)
    }

    @Test
    fun unknownCommandResultUsesFailureFormat() {
        val result = DebugCommandResult(
            command = "unknown",
            status = DebugCommandStatus.Failure,
            durationMs = 1L,
            currentScreen = "debug_command",
            currentState = "unknown_command",
            catalogSource = DebugCatalogSource.None,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = "UnknownCommand",
            redactedUrl = null,
            redactedHost = null,
            message = "Unknown debug command.",
        )

        val parsed = DebugCommandJson.gson.fromJson(DebugCommandJson.resultToJson(result), DebugCommandResult::class.java)

        assertEquals("failure", parsed.status)
        assertEquals("UnknownCommand", parsed.lastErrorCategory)
        assertEquals("unknown_command", parsed.currentState)
    }

    @Test
    fun commandFailureResultIncludesSafeErrorFields() {
        val result = DebugCommandResult(
            command = "refresh_real_catalog",
            status = DebugCommandStatus.Failure,
            durationMs = 9L,
            currentScreen = "debug_command",
            currentState = "real_catalog_failed",
            catalogSource = DebugCatalogSource.Real,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = 55L,
            lastErrorCategory = "Timeout",
            redactedUrl = "http://REDACTED_HOST/player_api.php?username=REDACTED&password=REDACTED",
            redactedHost = "REDACTED_HOST",
            message = "Network timeout.",
        )

        val json = DebugCommandJson.resultToJson(result)

        assertTrue(json.contains("\"status\": \"failure\""))
        assertTrue(json.contains("\"lastErrorCategory\": \"Timeout\""))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
    }

    @Test
    fun safeCatalogCacheSnapshotSerializesSafeCatalogOnly() {
        val snapshot = DebugSafeCatalogCacheSnapshot(
            schemaVersion = 1,
            exportedAtEpochMs = 100L,
            cacheAgeMs = 20L,
            catalog = DebugCatalogSnapshot(
                catalogSource = "real",
                generatedAtEpochMs = 90L,
                itemCounts = DebugItemCounts(liveStreams = 1, vodStreams = 1, series = 1, keptLiveStreams = 1, keptVodStreams = 1, keptSeries = 1),
                redactedUrl = "http://REDACTED_HOST",
                redactedHost = "REDACTED_HOST",
                playableLiveItems = listOf(DebugPlayableLiveItem("Live", "News", 1001, "ts", "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts")),
                playableVodItems = listOf(DebugPlayableVodItem("Movie", "Action", 2001, "mkv", "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mkv")),
                playableSeriesItems = listOf(DebugPlayableSeriesItem("Series", "Drama", 3001)),
            ),
        )

        val json = DebugCommandJson.safeCatalogCacheSnapshotToJson(snapshot)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugSafeCatalogCacheSnapshot::class.java)

        assertEquals(1, parsed.schemaVersion)
        assertEquals(1, parsed.catalog.playableLiveItems.size)
        assertEquals(3001, parsed.catalog.playableSeriesItems.single().seriesId)
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("example.test"))
        assertFalse(DebugCommandSanitizer.sanitizeText(json) != json)
    }

    @Test
    fun catalogSnapshotCommandResultsUseExpectedShapes() {
        val state = DebugCatalogCacheSnapshotState(
            exists = true,
            schemaVersion = 1,
            exportedAtEpochMs = 100L,
            cacheAgeMs = 20L,
            catalogSource = "real",
            itemCounts = DebugItemCounts(keptLiveStreams = 1, keptVodStreams = 1, keptSeries = 1),
            redactedUrl = "http://REDACTED_HOST",
            redactedHost = "REDACTED_HOST",
        )
        val success = DebugCommandResult(
            command = "export_safe_catalog_cache_snapshot",
            status = DebugCommandStatus.Success,
            durationMs = 1L,
            currentScreen = "debug_command",
            currentState = "safe_catalog_cache_snapshot_exported",
            catalogSource = "real",
            itemCounts = state.itemCounts,
            cacheAgeMs = 20L,
            lastErrorCategory = null,
            redactedUrl = "http://REDACTED_HOST",
            redactedHost = "REDACTED_HOST",
            message = "exported",
            catalogCacheSnapshotState = state,
        )
        val missing = success.copy(
            command = "import_safe_catalog_cache_snapshot",
            status = DebugCommandStatus.Failure,
            currentState = "catalog_snapshot_missing",
            catalogSource = "cached",
            lastErrorCategory = "SnapshotMissing",
            catalogCacheSnapshotState = null,
        )

        val successJson = DebugCommandJson.resultToJson(success)
        val missingJson = DebugCommandJson.resultToJson(missing)

        assertEquals("safe_catalog_cache_snapshot_exported", DebugCommandJson.gson.fromJson(successJson, DebugCommandResult::class.java).currentState)
        assertEquals("SnapshotMissing", DebugCommandJson.gson.fromJson(missingJson, DebugCommandResult::class.java).lastErrorCategory)
        assertTrue(successJson.contains("\"catalogCacheSnapshotState\""))
        assertFalse(successJson.contains("fake_user"))
        assertFalse(missingJson.contains("fake_pass"))
    }

    @Test
    fun playbackProbeResultSerializesOnlyRedactedStreamUrl() {
        val result = DebugCommandResult(
            command = "probe_live_stream",
            status = DebugCommandStatus.Success,
            durationMs = 25L,
            currentScreen = "debug_command",
            currentState = "live_stream_probed",
            catalogSource = DebugCatalogSource.Cached,
            itemCounts = DebugItemCounts(liveStreams = 1, keptLiveStreams = 1),
            cacheAgeMs = 10L,
            lastErrorCategory = "client_error",
            redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
            redactedHost = "REDACTED_HOST",
            message = "Live stream probe completed.",
            playbackProbe = DebugPlaybackProbeResult(
                selectedItemName = "Fixture Live One",
                streamId = 1001,
                containerExtension = "ts",
                redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
                requestShapeSummary = "userAgent=VLC-compatible; accept=*/*; icyMetaData=1; redirects=true",
                httpStatus = 458,
                statusCategory = "client_error",
                media3ErrorCode = "ERROR_CODE_IO_BAD_HTTP_STATUS",
                playerState = "Ready",
                videoWidth = 1280,
                videoHeight = 720,
                firstFrameRendered = true,
                droppedFrames = 1,
                durationMs = 20L,
                message = "status",
                comparisons = listOf(
                    DebugPlaybackProbeComparison(
                        name = "media3_default_mimic",
                        requestShapeSummary = "userAgent=AndroidXMedia3/1.10.1; acceptEncoding=identity; range=bytes=0-; redirects=true",
                        httpStatus = 458,
                        statusCategory = "client_error",
                        media3ErrorCode = "ERROR_CODE_IO_BAD_HTTP_STATUS",
                        playerState = "Ready",
                        videoWidth = 1280,
                        videoHeight = 720,
                        firstFrameRendered = true,
                        droppedFrames = 1,
                        durationMs = 10L,
                        message = "status",
                    ),
                ),
            ),
        )

        val json = DebugCommandJson.resultToJson(result)

        assertTrue(json.contains("\"playbackProbe\""))
        assertTrue(json.contains("\"requestShapeSummary\""))
        assertTrue(json.contains("media3_default_mimic"))
        assertTrue(json.contains("\"firstFrameRendered\": true"))
        assertTrue(json.contains("\"videoWidth\": 1280"))
        assertTrue(json.contains("\"httpStatus\": 458"))
        assertTrue(json.contains("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
    }

    @Test
    fun renderProbeFormatterUsesConciseSafeText() {
        val result = DebugCommandResult(
            command = "probe_live_render",
            status = DebugCommandStatus.Success,
            durationMs = 30L,
            currentScreen = "debug_command",
            currentState = "live_render_probed",
            catalogSource = DebugCatalogSource.Cached,
            itemCounts = DebugItemCounts(liveStreams = 1, keptLiveStreams = 1),
            cacheAgeMs = 5L,
            lastErrorCategory = null,
            redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
            redactedHost = "REDACTED_HOST",
            message = "Live render probe completed.",
            playbackProbe = DebugPlaybackProbeResult(
                selectedItemName = "Fixture Live One",
                streamId = 1001,
                containerExtension = "ts",
                redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
                requestShapeSummary = "userAgent=VLC-compatible; accept=*/*; icyMetaData=1; redirects=true",
                httpStatus = 200,
                statusCategory = "success",
                media3ErrorCode = null,
                playerState = "Ready",
                videoWidth = 1280,
                videoHeight = 720,
                firstFrameRendered = true,
                droppedFrames = 0,
                durationMs = 22L,
                message = "STATE_READY_FIRST_FRAME",
            ),
        )

        val text = DebugRenderProbeFormatter.format(result)

        assertTrue(text.contains("firstFrame=true"))
        assertTrue(text.contains("video=1280x720"))
        assertTrue(text.contains("url=http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts"))
        assertFalse(text.contains("fake_user"))
        assertFalse(text.contains("fake_pass"))
    }

    @Test
    fun playbackHealthSweepSerializesRedactedSectionResults() {
        val result = DebugCommandResult(
            command = "probe_playback_health_sweep",
            status = DebugCommandStatus.Success,
            durationMs = 36_000L,
            currentScreen = "debug_command",
            currentState = "playback_health_sweep_completed",
            catalogSource = DebugCatalogSource.Cached,
            itemCounts = DebugItemCounts(liveStreams = 1, vodStreams = 1, series = 1),
            cacheAgeMs = 10L,
            lastErrorCategory = "missing_item",
            redactedUrl = null,
            redactedHost = "REDACTED_HOST",
            message = "Playback health sweep completed.",
            playbackHealthSweep = DebugPlaybackHealthSweep(
                sampleAfterFirstFrameMs = 12_000L,
                results = listOf(
                    DebugPlaybackHealthSectionResult(
                        section = "live_tv",
                        selectedItemName = "Fixture Live",
                        streamId = 1001,
                        containerExtension = "ts",
                        redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
                        requestShapeSummary = "userAgent=VLC-compatible; accept=*/*; icyMetaData=1; redirects=true",
                        httpStatus = 200,
                        statusCategory = "success",
                        media3ErrorCode = null,
                        playerState = "Ready",
                        videoWidth = 1920,
                        videoHeight = 1080,
                        firstFrameRendered = true,
                        droppedFrames = 3,
                        bufferCount = 2,
                        playbackPositionStartMs = 100,
                        playbackPositionEndMs = 12_250,
                        playbackAdvanced = true,
                        durationMs = 12_900,
                        message = "HEALTH_SAMPLE_COMPLETE",
                    ),
                    DebugPlaybackHealthSectionResult(
                        section = "series_episode",
                        selectedItemName = null,
                        streamId = null,
                        containerExtension = null,
                        redactedUrl = null,
                        requestShapeSummary = "userAgent=VLC-compatible; accept=*/*; icyMetaData=1; redirects=true",
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
                        durationMs = 0,
                        message = "No cached Series item is available.",
                    ),
                ),
            ),
        )

        val json = DebugCommandJson.resultToJson(result)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)

        assertEquals("probe_playback_health_sweep", parsed.command)
        assertEquals(2, parsed.playbackHealthSweep!!.results.size)
        assertEquals(3, parsed.playbackHealthSweep.results.first().droppedFrames)
        assertEquals(2, parsed.playbackHealthSweep.results.first().bufferCount)
        assertTrue(parsed.playbackHealthSweep.results.first().playbackAdvanced)
        assertEquals("missing_item", parsed.playbackHealthSweep.results[1].statusCategory)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("http://example.test/live"))
    }

    @Test
    fun playbackHealthFormatterUsesConcisePerSectionRows() {
        val result = DebugCommandResult(
            command = "probe_playback_health_sweep",
            status = DebugCommandStatus.Success,
            durationMs = 1,
            currentScreen = "debug_command",
            currentState = "playback_health_sweep_completed",
            catalogSource = DebugCatalogSource.Cached,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = null,
            redactedHost = null,
            message = "ok",
            playbackHealthSweep = DebugPlaybackHealthSweep(
                sampleAfterFirstFrameMs = 12_000L,
                results = listOf(
                    DebugPlaybackHealthSectionResult(
                        section = "movies",
                        selectedItemName = "Movie",
                        streamId = 2001,
                        containerExtension = "mkv",
                        redactedUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mkv",
                        requestShapeSummary = "request",
                        httpStatus = 200,
                        statusCategory = "success",
                        media3ErrorCode = null,
                        playerState = "Ready",
                        videoWidth = 1280,
                        videoHeight = 720,
                        firstFrameRendered = true,
                        droppedFrames = 1,
                        bufferCount = 4,
                        playbackPositionStartMs = 0,
                        playbackPositionEndMs = 9000,
                        playbackAdvanced = true,
                        durationMs = 12,
                        message = "ok",
                    ),
                ),
            ),
        )

        val rows = DebugPlaybackHealthFormatter.rows(result)

        assertEquals("movies | 2001 | mkv | Ready | true | 1280x720 | 1 | 4 | true", rows.single())
    }

    @Test
    fun playbackDeepHealthSerializesRedactedMetricsAndFailedProfile() {
        val result = DebugCommandResult(
            command = "probe_playback_deep_health",
            status = DebugCommandStatus.Success,
            durationMs = 100,
            currentScreen = "debug_command",
            currentState = "playback_deep_health_completed",
            catalogSource = DebugCatalogSource.Cached,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = "missing_item",
            redactedUrl = null,
            redactedHost = "REDACTED_HOST",
            message = "ok",
            playbackDeepHealth = deepHealthFixture(),
        )

        val json = DebugCommandJson.resultToJson(result)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)

        assertEquals("probe_playback_deep_health", parsed.command)
        assertEquals(2, parsed.playbackDeepHealth!!.results.size)
        assertEquals("DECODER_TOO_SLOW", parsed.playbackDeepHealth.results.first().bottleneck)
        assertEquals("missing_item", parsed.playbackDeepHealth.results[1].profiles.first().statusCategory)
        assertTrue(json.contains("\"sampleDurationSeconds\": 45"))
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("http://example.test/series"))
    }

    @Test
    fun playbackDeepHealthFormatterUsesRequestedColumns() {
        val result = DebugCommandResult(
            command = "probe_playback_deep_health",
            status = DebugCommandStatus.Success,
            durationMs = 1,
            currentScreen = "debug_command",
            currentState = "playback_deep_health_completed",
            catalogSource = DebugCatalogSource.Cached,
            itemCounts = DebugItemCounts(),
            cacheAgeMs = null,
            lastErrorCategory = null,
            redactedUrl = null,
            redactedHost = null,
            message = "ok",
            playbackDeepHealth = deepHealthFixture(),
        )

        val row = DebugPlaybackDeepHealthFormatter.rows(result).first()

        assertTrue(row.contains("live_tv | baseline | 3840x2160 | avc1"))
        assertTrue(row.contains("DECODER_TOO_SLOW"))
    }

    @Test
    fun bottleneckClassifierDetectsNetworkJitterAndDecoderSlowness() {
        val transport = DebugTransportProbeResult(
            httpStatus = 200,
            statusCategory = "success",
            contentType = "video/mp2t",
            sanitizedHeaders = emptyMap(),
            timeToFirstByteMs = 20,
            bytesRead = 100,
            averageMbps = 25.0,
            minWindowMbps = 0.2,
            maxWindowMbps = 30.0,
            stalledIntervalCount = 2,
            durationMs = 1000,
            exceptionCategory = null,
        )
        val network = DebugPlaybackBottleneckClassifier.classify(
            transport,
            listOf(deepProfileFixture(rebufferCount = 2, dropped = 1, rendered = 100, maxConsecutiveDropped = 1)),
        )
        val decoder = DebugPlaybackBottleneckClassifier.classify(
            transport.copy(minWindowMbps = 20.0, stalledIntervalCount = 0),
            listOf(deepProfileFixture(rebufferCount = 0, dropped = 50, rendered = 100)),
        )

        assertEquals("NETWORK_JITTER", network.category)
        assertEquals("DECODER_TOO_SLOW", decoder.category)
    }

    @Test
    fun bottleneckClassifierDoesNotFlagHealthyPlaybackFromProcessingOffsetAlone() {
        val transport = DebugTransportProbeResult(
            httpStatus = 200,
            statusCategory = "success",
            contentType = "video/mp2t",
            sanitizedHeaders = emptyMap(),
            timeToFirstByteMs = 20,
            bytesRead = 100,
            averageMbps = 25.0,
            minWindowMbps = 10.0,
            maxWindowMbps = 30.0,
            stalledIntervalCount = 0,
            durationMs = 1000,
            exceptionCategory = null,
        )

        val bottleneck = DebugPlaybackBottleneckClassifier.classify(
            transport,
            listOf(deepProfileFixture(dropped = 0, rendered = 1200, maxConsecutiveDropped = 0, averageProcessingOffsetUs = 50_000.0)),
        )

        assertEquals("UNKNOWN", bottleneck.category)
    }

    @Test
    fun watchProgressDumpSerializesSafeRecordsOnly() {
        val dump = DebugWatchProgressStateDump(
            totalCount = 2,
            continueWatchingCount = 2,
            records = listOf(
                DebugWatchProgressRecordDump(
                    contentType = "movie",
                    streamId = 2001,
                    seriesId = null,
                    title = "Fixture Movie One",
                    seriesTitle = null,
                    episodeTitle = null,
                    categoryName = "Fixture Action",
                    extension = "mp4",
                    durationMs = 7_200_000L,
                    positionMs = 900_000L,
                    updatedAtMs = 100L,
                    completed = false,
                    resumeEligible = true,
                ),
                DebugWatchProgressRecordDump(
                    contentType = "series_episode",
                    streamId = 9001,
                    seriesId = 3001,
                    title = "Fixture Series One - Pilot",
                    seriesTitle = "Fixture Series One",
                    episodeTitle = "Pilot",
                    categoryName = "Fixture Series",
                    extension = "mp4",
                    durationMs = 3_600_000L,
                    positionMs = 600_000L,
                    updatedAtMs = 90L,
                    completed = false,
                    resumeEligible = true,
                ),
            ),
        )
        val result = DebugCommandResult(
            command = "dump_watch_progress_state",
            status = DebugCommandStatus.Success,
            durationMs = 4L,
            currentScreen = "debug_command",
            currentState = "watch_progress_state_dumped",
            catalogSource = "fixture",
            itemCounts = DebugItemCounts(vodStreams = 3, series = 3),
            cacheAgeMs = 10L,
            lastErrorCategory = null,
            redactedUrl = "fixture://debug_catalog",
            redactedHost = "fixture",
            message = "Watch progress state dumped.",
            watchProgressState = dump,
        )

        val json = DebugCommandJson.resultToJson(result)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)

        assertEquals(2, parsed.watchProgressState!!.continueWatchingCount)
        assertEquals("series_episode", parsed.watchProgressState.records[1].contentType)
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/"))
        assertFalse(json.contains("/series/"))
    }

    private fun deepHealthFixture(): DebugPlaybackDeepHealth =
        DebugPlaybackDeepHealth(
            sampleDurationSeconds = 45,
            transportDurationSeconds = 25,
            windowSeconds = 5,
            device = DebugDeviceMediaSnapshot("Google", "Television_4K", 35, 60f, "3840x2160@60.0", true),
            results = listOf(
                DebugPlaybackDeepSectionResult(
                    section = "live_tv",
                    selectedItemName = "Fixture Live",
                    streamId = 1001,
                    containerExtension = "ts",
                    redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
                    transport = DebugTransportProbeResult(200, "success", "video/mp2t", mapOf("Content-Type" to "video/mp2t"), 10, 50_000_000, 20.0, 18.0, 22.0, 0, 25_000, null),
                    profiles = listOf(deepProfileFixture(rebufferCount = 0, dropped = 40, rendered = 200)),
                    bottleneck = "DECODER_TOO_SLOW",
                    bottleneckReason = "Dropped/rendered ratio is high.",
                ),
                DebugPlaybackDeepSectionResult(
                    section = "series_episode",
                    selectedItemName = null,
                    streamId = null,
                    containerExtension = null,
                    redactedUrl = null,
                    transport = null,
                    profiles = listOf(deepProfileFixture(statusCategory = "missing_item", dropped = 0, rendered = 0)),
                    bottleneck = "UNKNOWN",
                    bottleneckReason = "No playable item.",
                ),
            ),
        )

    private fun deepProfileFixture(
        statusCategory: String = "success",
        rebufferCount: Int = 0,
        dropped: Int,
        rendered: Int,
        maxConsecutiveDropped: Int = 12,
        averageProcessingOffsetUs: Double = 10_000.0,
    ): DebugPlaybackDeepProfileResult =
        DebugPlaybackDeepProfileResult(
            profile = "baseline",
            requestShapeSummary = "userAgent=VLC-compatible; accept=*/*; icyMetaData=1; redirects=true",
            surfaceSummary = "offscreen_surface_1280x720",
            tunnelingRequested = false,
            largerBufferProfile = false,
            httpStatus = 200,
            statusCategory = statusCategory,
            media3ErrorCode = null,
            playerState = "Ready",
            firstFrameRendered = true,
            warmupToReadyMs = 500,
            firstFrameTimeMs = 700,
            durationMs = 45_000,
            playbackPositionStartMs = 0,
            playbackPositionEndMs = 44_000,
            playbackAdvanced = true,
            rebufferCount = rebufferCount,
            totalRebufferMs = 0,
            video = DebugVideoFormatSnapshot("video/avc", "avc1", 3840, 2160, 60f, 20_000_000, listOf("decoder.hw"), true, true),
            audio = DebugAudioFormatSnapshot("audio/mp4a-latm", "mp4a", 2, 48_000),
            decoder = DebugDecoderSnapshot("decoder.hw", "audio.hw", 1, 1),
            counters = DebugVideoDecoderCountersSnapshot(dropped, 0, 0, maxConsecutiveDropped, rendered, 0, 0, 1_000_000L, 100),
            windows = listOf(DebugPlaybackHealthWindow(0, 0, 5000, "Ready", 5000, 15_000, 10_000, dropped, rendered, dropped / 5.0)),
            renderedFramesPerSecond = rendered / 45.0,
            droppedFramesPerSecond = dropped / 45.0,
            droppedRenderedRatio = if (rendered > 0) dropped.toDouble() / rendered else null,
            averageVideoFrameProcessingOffsetUs = averageProcessingOffsetUs,
            message = "ok",
        )

    @Test
    fun liveTvStateDumpSerializesOnlyRedactedState() {
        val dump = DebugLiveTvStateDump(
            cacheSource = "cached",
            cacheAgeMs = 100L,
            cappedItemCount = 3,
            selectedCategoryId = "Sports",
            selectedCategoryName = "Sports",
            selectedChannelStreamId = 1002,
            selectedChannelName = "Fixture Live Two",
            selectedChannelCategory = "Sports",
            selectedSortMode = "favorites_first",
            selectedSortModeLabel = "Favorites first",
            searchQuery = "fixture",
            favoritesCount = 1,
            selectedChannelIsFavorite = true,
            redactedStreamUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1002.ts",
        )
        val result = DebugCommandResult(
            command = "dump_live_tv_state",
            status = DebugCommandStatus.Success,
            durationMs = 5L,
            currentScreen = "debug_command",
            currentState = "live_tv_state_dumped",
            catalogSource = "cached",
            itemCounts = DebugItemCounts(liveStreams = 3, keptLiveStreams = 3),
            cacheAgeMs = 100L,
            lastErrorCategory = null,
            redactedUrl = dump.redactedStreamUrl,
            redactedHost = "REDACTED_HOST",
            message = "Live TV state dumped.",
            liveTvState = dump,
        )

        val json = DebugCommandJson.resultToJson(result)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)

        assertEquals("dump_live_tv_state", parsed.command)
        assertEquals("Favorites first", parsed.liveTvState!!.selectedSortModeLabel)
        assertEquals(true, parsed.liveTvState.selectedChannelIsFavorite)
        assertTrue(json.contains("\"liveTvState\""))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("http://example.test/live"))
    }

    @Test
    fun liveTvStateDumpReflectsSelectedCategorySearchSortAndFavorite() {
        val ready = LiveTvCatalogMapper()
            .mapCachedCatalogJson(
                """
                    {
                      "catalogSource": "fixture",
                      "playableLiveItems": [
                        {"name":"News One","categoryName":"News","streamId":1001,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1001.ts"},
                        {"name":"Sports Two","categoryName":"Sports","streamId":1002,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1002.ts"}
                      ]
                    }
                """.trimIndent(),
                credentials = null,
            )
            .withRestoredContext(
                categoryId = "Sports",
                streamId = 1002,
                query = "sports",
                favorites = setOf(1002),
                sortMode = LiveTvSortMode.FavoritesFirst,
            )

        val dump = DebugLiveTvStateDumpBuilder.build(ready)

        assertEquals("fixture", dump.cacheSource)
        assertEquals("Sports", dump.selectedCategoryId)
        assertEquals("Sports Two", dump.selectedChannelName)
        assertEquals(1002, dump.selectedChannelStreamId)
        assertEquals("favorites_first", dump.selectedSortMode)
        assertEquals("sports", dump.searchQuery)
        assertEquals(1, dump.favoritesCount)
        assertTrue(dump.selectedChannelIsFavorite)
        assertEquals("fixture://debug_catalog/live/1002.ts", dump.redactedStreamUrl)
    }

    @Test
    fun missingOrCorruptedPreferencesFallbackKeepsDefaultSafeContext() {
        val ready = LiveTvCatalogMapper()
            .mapCachedCatalogJson(
                """
                    {
                      "catalogSource": "fixture",
                      "playableLiveItems": [
                        {"name":"News One","categoryName":"News","streamId":1001,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1001.ts"}
                      ]
                    }
                """.trimIndent(),
                credentials = null,
            )
            .withRestoredContext(
                categoryId = "missing",
                streamId = 9999,
                query = "",
                favorites = setOf(9999),
                sortMode = LiveTvSortMode.fromStorageKey("not_real"),
            )

        val dump = DebugLiveTvStateDumpBuilder.build(ready)

        assertEquals(LiveTvCategoryIds.All, dump.selectedCategoryId)
        assertEquals(1001, dump.selectedChannelStreamId)
        assertEquals("provider", dump.selectedSortMode)
        assertEquals(0, dump.favoritesCount)
    }

    @Test
    fun moviesStateDumpSerializesOnlyRedactedState() {
        val dump = DebugMoviesStateDump(
            cacheSource = "cached",
            cacheAgeMs = 100L,
            cappedItemCount = 3,
            selectedCategoryId = "Watchlist",
            selectedCategoryName = "Watchlist",
            selectedMovieStreamId = 2003,
            selectedMovieTitle = "Fixture Movie Three",
            selectedMovieCategory = "Fixture Action",
            selectedSortMode = "title",
            selectedSortModeLabel = "Title A-Z",
            searchQuery = "fixture",
            watchlistCount = 1,
            selectedMovieIsWatchlisted = true,
            containerExtension = "mkv",
            redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2003.mkv",
        )
        val result = DebugCommandResult(
            command = "dump_movies_state",
            status = DebugCommandStatus.Success,
            durationMs = 5L,
            currentScreen = "debug_command",
            currentState = "movies_state_dumped",
            catalogSource = "cached",
            itemCounts = DebugItemCounts(vodStreams = 3, keptVodStreams = 3),
            cacheAgeMs = 100L,
            lastErrorCategory = null,
            redactedUrl = dump.redactedStreamUrl,
            redactedHost = "REDACTED_HOST",
            message = "Movies state dumped.",
            moviesState = dump,
        )

        val json = DebugCommandJson.resultToJson(result)
        val parsed = DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)

        assertEquals("dump_movies_state", parsed.command)
        assertEquals("Title A-Z", parsed.moviesState!!.selectedSortModeLabel)
        assertEquals(true, parsed.moviesState.selectedMovieIsWatchlisted)
        assertEquals("mkv", parsed.moviesState.containerExtension)
        assertTrue(json.contains("\"moviesState\""))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("http://example.test/movie"))
    }

    @Test
    fun moviesStateDumpReflectsSelectedWatchlistSearchAndSort() {
        val ready = MoviesCatalogMapper()
            .mapCachedCatalogJson(
                """
                    {
                      "catalogSource": "fixture",
                      "playableVodItems": [
                        {"title":"Alpha Movie","categoryName":"Drama","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4"},
                        {"title":"Zeta Movie","categoryName":"Action","streamId":2002,"containerExtension":"mkv","redactedUrl":"fixture://debug_catalog/movie/2002.mkv"}
                      ]
                    }
                """.trimIndent(),
                credentials = null,
            )
            .withRestoredContext(
                categoryId = MovieCategoryIds.Watchlist,
                streamId = 2002,
                query = "zeta",
                sortMode = MoviesSortMode.Title,
                watchlist = setOf(2002),
            )

        val dump = DebugMoviesStateDumpBuilder.build(ready)

        assertEquals("fixture", dump.cacheSource)
        assertEquals(MovieCategoryIds.Watchlist, dump.selectedCategoryId)
        assertEquals("Zeta Movie", dump.selectedMovieTitle)
        assertEquals(2002, dump.selectedMovieStreamId)
        assertEquals("title", dump.selectedSortMode)
        assertEquals("zeta", dump.searchQuery)
        assertEquals(1, dump.watchlistCount)
        assertTrue(dump.selectedMovieIsWatchlisted)
        assertEquals("mkv", dump.containerExtension)
        assertEquals("fixture://debug_catalog/movie/2002.mkv", dump.redactedStreamUrl)
    }
}
