package com.atlas.atlastv.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.playback.LivePlaybackRequestConfig
import com.atlas.atlastv.playback.DefaultNoFirstFrameTimeoutMs
import com.atlas.atlastv.playback.PlaybackTrackOption
import com.atlas.atlastv.playback.PlaybackTrackType
import com.atlas.atlastv.playback.PlaybackTrackPreferencesController
import com.atlas.atlastv.playback.TrackPreferences
import com.atlas.atlastv.playback.TrackPreferencesStateHolder
import com.atlas.atlastv.playback.playbackTrackOptions
import com.atlas.atlastv.playback.PlaybackDiagnostics
import com.atlas.atlastv.playback.PlaybackFailure
import com.atlas.atlastv.playback.PlaybackRecovery
import com.atlas.atlastv.playback.PlaybackEndpointRetry
import com.atlas.atlastv.playback.PlaybackEndpointTarget
import com.atlas.atlastv.playback.canRecoverPlaybackEndpoint
import kotlinx.coroutines.launch
import com.atlas.atlastv.playback.playbackFailure
import com.atlas.atlastv.playback.WatchProgressPolicy
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressCoordinator
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.playback.seekTargetMs
import com.atlas.atlastv.playback.shouldDirectionalSeek
import com.atlas.atlastv.playback.shouldShowNoFirstFrameTimeout
import com.atlas.atlastv.playback.shouldShowTrackOptions
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.series.displayEpisodeName
import com.atlas.atlastv.ui.series.shortEpisodeCode
import kotlinx.coroutines.yield

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DebugLivePlaybackScreen(
    stream: LiveTvChannel,
    streamUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPositionMs: Long? = null,
    progressRecord: WatchProgressRecord? = null,
    progressStore: WatchProgressStore? = null,
    showDiagnostics: Boolean = false,
    nextEpisodeState: com.atlas.atlastv.playback.NextEpisodeState = com.atlas.atlastv.playback.NextEpisodeState.Idle,
    onNextEpisode: ((com.atlas.atlastv.ui.series.SeriesEpisodeItem) -> Unit)? = null,
    onRetryNextEpisode: (() -> Unit)? = null,
    autoplayEnabled: Boolean = false,
    trackPreferences: TrackPreferencesStateHolder? = null,
    startedFromBeginning: Boolean = false,
    progressCoordinator: WatchProgressCoordinator? = null,
    providerCredentials: () -> com.atlas.atlastv.xtream.model.XtreamCredentials? = { null },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentCredentialsProvider by rememberUpdatedState(providerCredentials)
    var attemptUrl by remember(streamUrl) { mutableStateOf(streamUrl) }
    var endpointFailureEligible by remember(streamUrl) { mutableStateOf(false) }
    val endpointRetry = remember(streamUrl, progressRecord?.key) {
        PlaybackEndpointRetry(providerCredentials().takeIf { progressRecord == null || progressRecord.streamId == stream.streamId },
            PlaybackEndpointTarget(progressRecord?.contentType, stream.streamId, stream.containerExtension))
    }
    var diagnostics by remember { mutableStateOf(PlaybackDiagnostics(redactedUrl = stream.redactedStreamUrl)) }
    var overlayVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableStateOf(0L) }
    var subtitlesDisabled by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionTick by remember(streamUrl) { mutableStateOf(initialPositionMs?.coerceAtLeast(0L) ?: 0L) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var trackOptionsVisible by remember { mutableStateOf(false) }
    var failure by remember(streamUrl) { mutableStateOf<PlaybackFailure?>(null) }
    val recovery = remember(streamUrl) {
        PlaybackRecovery(initialPositionMs?.takeIf { it >= WatchProgressPolicy.MinResumePositionMs }, progressRecord != null,
            requireFirstFrame = progressRecord != null)
    }
    var playbackStartedAtMs by remember { mutableStateOf(0L) }
    var audioTrackOptions by remember { mutableStateOf<List<PlaybackTrackOption>>(emptyList()) }
    var subtitleTrackOptions by remember { mutableStateOf<List<PlaybackTrackOption>>(emptyList()) }
    val rootFocusRequester = remember { FocusRequester() }
    val progressWriter = remember(progressStore, progressCoordinator) { progressCoordinator ?: progressStore?.let(WatchProgressCoordinator::forStore) }
    val progressSession = remember(streamUrl, progressRecord?.key, progressWriter) {
        progressRecord?.let { progressWriter?.openSession(it.key) }
    }
    val saveFailed = progressWriter?.saveFailed?.collectAsState()?.value == true
    var closing by remember(streamUrl) { mutableStateOf(false) }
    var resumeAfterBackground by remember(streamUrl) { mutableStateOf(false) }
    var knownDuration by remember(streamUrl) { mutableStateOf(progressRecord?.durationMs) }
    var nextActionFocused by remember(streamUrl) { mutableStateOf(false) }
    var playbackEnded by remember(streamUrl) { mutableStateOf(false) }
    var playbackForeground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val autoplay = remember(streamUrl) { com.atlas.atlastv.playback.EpisodeAutoplayController() }
    val currentNextState by rememberUpdatedState(nextEpisodeState)
    val currentOnNext by rememberUpdatedState(onNextEpisode)
    val currentAutoplayEnabled by rememberUpdatedState(autoplayEnabled)
    val countdown = autoplay.countdown
    val player = remember(streamUrl) {
        ExoPlayer.Builder(context)
            .setRenderersFactory(LivePlaybackRequestConfig.renderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(LivePlaybackRequestConfig.dataSourceFactory(context)))
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").build()
            }
    }
    val currentTrackPreferences by rememberUpdatedState(trackPreferences)
    val trackController = remember(player) { PlaybackTrackPreferencesController(player) { type, preference ->
        currentTrackPreferences?.choose(type, preference)
    } }
    var trackChoiceNotice by remember(player) { mutableStateOf<String?>(null) }
    LaunchedEffect(player, trackPreferences?.state?.preferences) {
        trackController.update(trackPreferences?.state?.preferences ?: TrackPreferences())
        subtitlesDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
    }
    LaunchedEffect(trackChoiceNotice) {
        if (trackChoiceNotice != null) { kotlinx.coroutines.delay(4_000); trackChoiceNotice = null }
    }
    fun revealControls() { overlayVisible = true; controlsInteraction++ }
    fun saveProgress(forceCompleted: Boolean = false) {
        val base = progressRecord ?: return
        val writer = progressWriter ?: return
        val session = progressSession ?: return
        // Failed preparation must never overwrite the saved resume point with zero.
        if (closing || !recovery.canSaveProgress) return
        // stop()/release() reset the player. Their late callbacks cannot erase a captured checkpoint.
        if (player.playbackState == Player.STATE_IDLE && failure == null) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val position = if (failure != null) recovery.positionMs else recovery.checkpoint(player.currentPosition)
        knownDuration = duration ?: knownDuration
        val completed = forceCompleted || player.playbackState == Player.STATE_ENDED || WatchProgressPolicy.isCompleted(position, knownDuration)
        writer.submit(session,
            base.copy(
                durationMs = knownDuration,
                positionMs = position,
                updatedAtMs = System.currentTimeMillis(),
                completed = completed,
            ),
            renderedPlayback = recovery.hasRenderedPlayback,
        )
    }
    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = seekTargetMs(player.currentPosition, deltaMs, duration)
        player.seekTo(target)
        positionTick = target
        seekFeedback = if (deltaMs < 0L) "Rewind 10s" else "Forward 30s"
        saveProgress()
    }
    fun selectTrack(option: PlaybackTrackOption): Boolean {
        val remembered = trackController.select(option) ?: return false
        subtitlesDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        trackChoiceNotice = if (!remembered && trackPreferences != null) "Language unknown · for this video only" else null
        return true
    }
    fun subtitlesOff() {
        trackController.subtitlesOff()
        subtitlesDisabled = true
    }
    fun startPlayback() {
        failure = null
        endpointFailureEligible = false
        playbackEnded = false
        val seekPosition = recovery.beginAttempt()
        positionTick = seekPosition ?: 0L
        playbackStartedAtMs = System.currentTimeMillis()
        trackOptionsVisible = false
        trackController.apply()
        subtitlesDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        diagnostics = PlaybackDiagnostics(redactedUrl = stream.redactedStreamUrl)
        val mediaItem = MediaItem.fromUri(attemptUrl)
        if (seekPosition != null) {
            player.setMediaItem(mediaItem, seekPosition)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
    }
    fun retryPlayback() {
        if (failure == null || closing) return
        saveProgress()
        val next = endpointRetry.next(attemptUrl, endpointFailureEligible, currentCredentialsProvider())
        rootFocusRequester.requestFocus()
        player.stop()
        if (next != null) attemptUrl = next
        startPlayback()
    }
    fun startNextEpisode(episode: com.atlas.atlastv.ui.series.SeriesEpisodeItem) {
        val action = currentOnNext ?: return
        val available = currentNextState as? com.atlas.atlastv.playback.NextEpisodeState.Available ?: return
        if (closing || available.episode.episodeId != episode.episodeId) return
        autoplay.cancel()
        // Capture before stop resets position; disposal/late callbacks cannot overwrite it.
        saveProgress()
        closing = true
        player.stop()
        action(episode)
    }
    val handleBack = {
        autoplay.cancel()
        saveProgress()
        closing = true
        player.stop()
        onBack()
    }

    val navigateBack = {
        if (autoplay.countdown != null) {
            autoplay.cancel()
            revealControls()
        } else if (failure != null) {
            handleBack()
        } else if (trackOptionsVisible) {
            trackOptionsVisible = false
            revealControls()
        } else if (overlayVisible && !showDiagnostics) {
            overlayVisible = false
        } else {
            handleBack()
        }
    }
    BackHandler(onBack = navigateBack)

    DisposableEffect(lifecycleOwner, player, recovery, progressRecord, progressStore) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> playbackForeground = true
                Lifecycle.Event.ON_PAUSE -> { playbackForeground = false; autoplay.cancel() }
                Lifecycle.Event.ON_STOP -> {
                    playbackForeground = false
                    autoplay.cancel()
                    saveProgress()
                    resumeAfterBackground = player.playWhenReady && failure == null && player.playbackState != Player.STATE_ENDED
                    player.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeAfterBackground && !closing && failure == null) player.play()
                    resumeAfterBackground = false
                }
                Lifecycle.Event.ON_DESTROY -> {
                    autoplay.cancel()
                    saveProgress()
                    closing = true
                    player.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(player, recovery, progressRecord, progressStore) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                diagnostics = diagnostics.copy(playerState = when (state) {
                    Player.STATE_BUFFERING -> "Loading"
                    Player.STATE_READY -> {
                        playbackEnded = false
                        recovery.markReady()
                        "Ready"
                    }
                    Player.STATE_ENDED -> {
                        playbackEnded = true
                        saveProgress(forceCompleted = true)
                        revealControls()
                        "Ended"
                    }
                    Player.STATE_IDLE -> "Idle"
                    else -> "Unknown"
                })
            }

            override fun onIsPlayingChanged(isPlayingValue: Boolean) {
                isPlaying = isPlayingValue
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                diagnostics = diagnostics.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackController.apply()
                audioTrackOptions = player.currentTracks.playbackTrackOptions(PlaybackTrackType.Audio)
                subtitleTrackOptions = player.currentTracks.playbackTrackOptions(PlaybackTrackType.Subtitle)
                subtitlesDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
            }

            override fun onRenderedFirstFrame() {
                recovery.markFirstFrame()
                diagnostics = diagnostics.copy(firstFrameRendered = true)
                val renderedUrl = attemptUrl
                scope.launch { endpointRetry.rendered(renderedUrl, currentCredentialsProvider()) }
                if (startedFromBeginning) saveProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                autoplay.cancel()
                val responseCode = error.findInvalidResponseCode()
                diagnostics = diagnostics.copy(
                    httpStatus = responseCode,
                    error = buildString {
                    append(error.errorCodeName)
                    responseCode?.let { append(" HTTP ").append(it) }
                    append(": ")
                    append(error.message ?: "Playback failed")
                    },
                )
                // Media3 can reset currentPosition when reporting an error; keep the last ready checkpoint.
                if (player.currentPosition > 0L) recovery.checkpoint(player.currentPosition)
                failure = playbackFailure(error.errorCode, responseCode)
                endpointFailureEligible = canRecoverPlaybackEndpoint(error.errorCode, responseCode, error)
                trackOptionsVisible = false
                player.pause()
                revealControls()
                saveProgress()
            }
        }
        val analyticsListener = object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                diagnostics = diagnostics.copy(droppedFrames = diagnostics.droppedFrames + droppedFrames)
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        onDispose {
            saveProgress()
            closing = true
            player.removeListener(listener)
            player.removeAnalyticsListener(analyticsListener)
            player.release()
        }
    }

    LaunchedEffect(player, progressRecord, progressStore) {
        if (progressRecord == null || progressStore == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            saveProgress()
        }
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
    }

    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            positionTick = player.currentPosition
            if (!closing && failure == null && player.playbackState == Player.STATE_READY) {
                recovery.checkpoint(positionTick)
                knownDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownDuration
            }
        }
    }

    LaunchedEffect(overlayVisible, trackOptionsVisible, controlsInteraction, isPlaying) {
        if (overlayVisible && isPlaying && !trackOptionsVisible && failure == null && !showDiagnostics) {
            kotlinx.coroutines.delay(4_000L)
            overlayVisible = false
            trackOptionsVisible = false
        }
    }

    LaunchedEffect(playbackEnded, playbackForeground, autoplayEnabled, nextEpisodeState, closing, failure, trackOptionsVisible, diagnostics.firstFrameRendered) {
        autoplay.update(
            ended = playbackEnded && recovery.attemptReady,
            enabled = autoplayEnabled && !showDiagnostics,
            active = playbackForeground && !closing && failure == null && !trackOptionsVisible,
            next = (nextEpisodeState as? com.atlas.atlastv.playback.NextEpisodeState.Available)?.episode
                ?.takeIf { onNextEpisode != null && diagnostics.firstFrameRendered },
            nowMs = android.os.SystemClock.elapsedRealtime(),
        )
    }
    LaunchedEffect(countdown?.deadlineMs, countdown?.episode?.episodeId) {
        if (countdown == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(250)
            val next = autoplay.tick(android.os.SystemClock.elapsedRealtime())
            if (next != null) {
                if (currentAutoplayEnabled && playbackForeground && !closing && failure == null) startNextEpisode(next)
                break
            }
            if (autoplay.countdown == null) break
        }
    }

    LaunchedEffect(overlayVisible) {
        if (!overlayVisible) rootFocusRequester.requestFocus()
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            kotlinx.coroutines.delay(2_500L)
            seekFeedback = null
        }
    }

    LaunchedEffect(streamUrl, playbackStartedAtMs) {
        if (playbackStartedAtMs <= 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(DefaultNoFirstFrameTimeoutMs)
        val elapsed = System.currentTimeMillis() - playbackStartedAtMs
        val durationKnown = player.duration.let { it != C.TIME_UNSET && it > 0L }
        if (shouldShowNoFirstFrameTimeout(
                elapsedMs = elapsed,
                durationKnown = durationKnown,
                firstFrameRendered = diagnostics.firstFrameRendered,
                playbackErrorPresent = diagnostics.error != null,
            )
        ) {
            recovery.checkpoint(player.currentPosition)
            failure = PlaybackFailure.Timeout
            endpointFailureEligible = true
            revealControls()
            trackOptionsVisible = false
            diagnostics = diagnostics.copy(error = "Playback did not render video in time.")
            player.pause()
        }
    }

    LaunchedEffect(streamUrl) {
        saveProgress()
        player.stop()
        startPlayback()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("live_tv_player_screen")
            .semantics { contentDescription = "live_tv_player_screen" }
            .focusRequester(rootFocusRequester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) navigateBack()
                    true
                } else if (event.type != KeyEventType.KeyDown) false
                else if (autoplay.countdown != null) {
                    // The prompt owns arrows/Center. Media input cancels it before touching playback.
                    when (event.key) {
                        Key.MediaPause, Key.MediaPlay, Key.MediaPlayPause -> { autoplay.cancel(); revealControls(); true }
                        else -> false
                    }
                }
                else if (failure != null) {
                    // Let the focused recovery buttons own D-pad/Enter; media keys cannot restart a failed player.
                    event.key == Key.MediaPlayPause
                } else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (!overlayVisible) {
                            revealControls()
                            true
                        } else {
                            revealControls()
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        revealControls()
                        if (shouldDirectionalSeek(trackOptionsVisible, progressRecord != null)) {
                            seekBy(-10_000L)
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionRight -> {
                        revealControls()
                        if (shouldDirectionalSeek(trackOptionsVisible, progressRecord != null)) {
                            seekBy(30_000L)
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionUp -> {
                        if (trackOptionsVisible || nextActionFocused) false else {
                            revealControls()
                            if (com.atlas.atlastv.playback.shouldOpenTrackMenu(false, audioTrackOptions.size, subtitleTrackOptions.size)) trackOptionsVisible = true
                            true
                        }
                    }
                    Key.DirectionDown -> { revealControls(); false }
                    Key.MediaPlay -> { player.play(); revealControls(); true }
                    Key.MediaPause -> { player.pause(); revealControls(); true }
                    Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        revealControls()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Box(
            modifier = Modifier
                .testTag(if (showDiagnostics) "debug_diagnostics_visible" else "debug_diagnostics_hidden")
                .semantics { contentDescription = if (showDiagnostics) "debug_diagnostics_visible" else "debug_diagnostics_hidden" },
        )
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("playback_full_screen_surface")
                .semantics { contentDescription = "playback_full_screen_surface" },
        )

        if (failure == null && countdown == null && !trackOptionsVisible && (overlayVisible || showDiagnostics)) {
            NormalPlaybackOverlay(
                title = stream.name,
                isPlaying = isPlaying,
                isSeekable = progressRecord != null,
                positionMs = positionTick,
                durationMs = player.duration.takeIf { value -> value != C.TIME_UNSET && value > 0L } ?: progressRecord?.durationMs,
                seekFeedback = seekFeedback,
                audioTrackOptions = audioTrackOptions,
                subtitleTrackOptions = subtitleTrackOptions,
                trackOptionsVisible = trackOptionsVisible,
                onRewind = {
                    revealControls()
                    seekBy(-10_000L)
                },
                onPlayPause = {
                    revealControls()
                    if (player.isPlaying) player.pause() else player.play()
                },
                onForward = {
                    revealControls()
                    seekBy(30_000L)
                },
                onToggleTrackOptions = {
                    revealControls()
                    trackOptionsVisible = !trackOptionsVisible
                },
                nextEpisodeState = nextEpisodeState,
                onNextEpisode = onNextEpisode?.let { ::startNextEpisode },
                onRetryNextEpisode = onRetryNextEpisode?.let { retry -> { revealControls(); retry() } },
                onNextActionFocus = { nextActionFocused = it },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        countdown?.let { prompt ->
            EpisodeAutoplayPrompt(prompt, seriesTitle = progressRecord?.seriesTitle,
                onPlayNow = { autoplay.playNow()?.let(::startNextEpisode) },
                onCancel = { autoplay.cancel(); revealControls() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp))
        }

        if (failure == null && trackOptionsVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                com.atlas.atlastv.ui.components.PlaybackTrackMenu(
                    audio = audioTrackOptions, subtitles = subtitleTrackOptions, subtitlesDisabled = subtitlesDisabled,
                    onSelect = { if (selectTrack(it)) { trackOptionsVisible = false; revealControls() } },
                    onSubtitlesOff = { subtitlesOff(); trackOptionsVisible = false; revealControls() },
                    onDismiss = { trackOptionsVisible = false; revealControls() },
                    modifier = Modifier.padding(horizontal = 40.dp),
                    preferenceError = trackPreferences?.state?.errorLabel,
                    preferencePending = trackPreferences?.state?.let { it.loading || it.saving } == true,
                    onRetryPreferences = trackPreferences?.let { { it.retry() } },
                )
            }
        }

        trackChoiceNotice?.let { Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).testTag("playback_track_choice_notice")) }

        if (saveFailed && progressRecord != null) {
            Text(
                "Progress couldn't be saved. Retrying…",
                color = AtlasMutedText,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp).testTag("progress_save_retrying"),
            )
        }

        failure?.let { playbackFailure ->
            PlaybackFailureOverlay(
                failure = playbackFailure,
                onRetry = ::retryPlayback,
                onBack = handleBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (showDiagnostics) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(520.dp)
                    .background(AtlasSurface, RoundedCornerShape(8.dp))
                    .padding(16.dp)
                    .testTag("playback_debug_diagnostics")
                    .semantics { contentDescription = "playback_debug_diagnostics" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Playback state: ${diagnostics.playerState}", color = AtlasText, style = MaterialTheme.typography.titleMedium)
                Text(text = "HTTP status: ${diagnostics.httpStatus?.toString() ?: "None"}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Video size: ${diagnostics.videoWidth ?: 0}x${diagnostics.videoHeight ?: 0}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "First frame rendered: ${diagnostics.firstFrameRendered}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Dropped frames: ${diagnostics.droppedFrames}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Stream: ${diagnostics.redactedUrl}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Request: ${LivePlaybackRequestConfig.RequestShapeSummary}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                diagnostics.error?.let { error ->
                    Text(text = "Error: $error", color = AtlasText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun EpisodeAutoplayPrompt(
    countdown: com.atlas.atlastv.playback.EpisodeAutoplayCountdown,
    seriesTitle: String?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelRequester.requestFocus() }
    Column(modifier.width(440.dp).background(AtlasSurface.copy(alpha = .98f), RoundedCornerShape(16.dp))
        .padding(24.dp).focusGroup().testTag("playback_autoplay_prompt"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Next episode in ${countdown.secondsRemaining}s", color = AtlasText, style = MaterialTheme.typography.titleLarge,
            maxLines = 1, modifier = Modifier.testTag("playback_autoplay_countdown"))
        val episodeName = countdown.episode.displayEpisodeName(seriesTitle)
        val code = countdown.episode.shortEpisodeCode()
        val label = if (episodeName == "Episode ${countdown.episode.episodeNumber ?: countdown.episode.episodeId}") code.ifBlank { episodeName }
            else listOf(code, episodeName).filter { it.isNotBlank() }.joinToString(" · ")
        Text(label, color = AtlasMutedText,
            style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlaybackActionButton("Play now", onPlayNow, 166, "playback_autoplay_play_now", focusRequester = playRequester,
                onDirectionLeft = { cancelRequester.requestFocus() }, onDirectionRight = { cancelRequester.requestFocus() },
                onDirectionUp = {}, onDirectionDown = {})
            PlaybackActionButton("Cancel", onCancel, 166, "playback_autoplay_cancel", focusRequester = cancelRequester,
                onDirectionLeft = { playRequester.requestFocus() }, onDirectionRight = { playRequester.requestFocus() },
                onDirectionUp = {}, onDirectionDown = {})
        }
    }
}

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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NormalPlaybackOverlay(
    title: String,
    isPlaying: Boolean,
    isSeekable: Boolean,
    positionMs: Long,
    durationMs: Long?,
    seekFeedback: String?,
    audioTrackOptions: List<PlaybackTrackOption>,
    subtitleTrackOptions: List<PlaybackTrackOption>,
    trackOptionsVisible: Boolean,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onToggleTrackOptions: () -> Unit,
    nextEpisodeState: com.atlas.atlastv.playback.NextEpisodeState,
    onNextEpisode: ((com.atlas.atlastv.ui.series.SeriesEpisodeItem) -> Unit)?,
    onRetryNextEpisode: (() -> Unit)?,
    onNextActionFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseFocusRequester = remember { FocusRequester() }
    val nextRequester = remember { FocusRequester() }
    var nextFocused by remember { mutableStateOf(false) }
    var retryWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(nextEpisodeState) {
        when (nextEpisodeState) {
            com.atlas.atlastv.playback.NextEpisodeState.Unavailable -> retryWasVisible = true
            com.atlas.atlastv.playback.NextEpisodeState.Loading -> Unit
            else -> retryWasVisible = false
        }
    }
    val nextEpisode = (nextEpisodeState as? com.atlas.atlastv.playback.NextEpisodeState.Available)?.episode
    val nextAction: (() -> Unit)? = nextEpisode?.takeIf { onNextEpisode != null }?.let { episode -> ({ onNextEpisode?.invoke(episode); Unit }) }
        ?: onRetryNextEpisode?.takeIf { nextEpisodeState == com.atlas.atlastv.playback.NextEpisodeState.Unavailable ||
            (nextEpisodeState == com.atlas.atlastv.playback.NextEpisodeState.Loading && retryWasVisible) }?.let { retry ->
                { if (nextEpisodeState == com.atlas.atlastv.playback.NextEpisodeState.Unavailable) retry() }
            }
    DisposableEffect(Unit) { onDispose { onNextActionFocus(false) } }
    LaunchedEffect(trackOptionsVisible) {
        if (!trackOptionsVisible) playPauseFocusRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f))))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (!shouldDirectionalSeek(trackOptionsVisible, isSeekable)) return@onPreviewKeyEvent false
                            onRewind()
                            true
                        }
                        Key.DirectionRight -> {
                            if (!shouldDirectionalSeek(trackOptionsVisible, isSeekable)) return@onPreviewKeyEvent false
                            onForward()
                            true
                        }
                        Key.DirectionUp -> {
                            if (nextFocused) { playPauseFocusRequester.requestFocus(); return@onPreviewKeyEvent true }
                            if (trackOptionsVisible) return@onPreviewKeyEvent false
                            if (!shouldShowTrackOptions(audioTrackOptions.size, subtitleTrackOptions.size)) return@onPreviewKeyEvent false
                            onToggleTrackOptions()
                            true
                        }
                        Key.DirectionDown -> {
                            if (nextAction != null) { nextRequester.requestFocus(); true } else false
                        }
                        else -> false
                    }
                }
            }
            .padding(horizontal = 36.dp, vertical = 18.dp)
            .testTag("normal_playback_overlay"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .testTag("playback_controls_overlay")
                .semantics { contentDescription = "playback_controls_overlay" },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(com.atlas.atlastv.ui.catalog.playbackDisplayTitle(title), color = AtlasText, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${positionMs.formatTime()} / ${durationMs?.formatTime() ?: "--:--"}", color = AtlasMutedText,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("playback_progress_label"))
        }
        val seekHint = seekFeedback
        seekHint?.let {
            Text(
                text = it,
                color = AtlasAccent,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("playback_seek_feedback").semantics { contentDescription = "playback_seek_feedback" },
            )
        }
        PlaybackProgressBar(positionMs = positionMs, durationMs = durationMs)
        val seekLeft = if (!trackOptionsVisible && isSeekable) onRewind else null
        val seekRight = if (!trackOptionsVisible && isSeekable) onForward else null
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isSeekable) {
                PlaybackActionButton(
                    text = "-10s",
                    onClick = onRewind,
                    width = 116,
                    testTag = "playback_rewind_control",
                    onDirectionLeft = seekLeft,
                    onDirectionRight = seekRight,
                )
            }
            PlaybackActionButton(
                text = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                width = 132,
                testTag = "playback_play_pause_control",
                focusRequester = playPauseFocusRequester,
                onDirectionLeft = seekLeft,
                onDirectionRight = seekRight,
            )
            if (isSeekable) {
                PlaybackActionButton(
                    text = "+30s",
                    onClick = onForward,
                    width = 116,
                    testTag = "playback_fast_forward_control",
                    onDirectionLeft = seekLeft,
                    onDirectionRight = seekRight,
                )
            }
            if (shouldShowTrackOptions(audioTrackOptions.size, subtitleTrackOptions.size)) {
                PlaybackActionButton(
                    text = "Audio & Subtitles",
                    onClick = onToggleTrackOptions,
                    width = 210,
                    testTag = "playback_track_options_control",
                    onDirectionLeft = seekLeft,
                    onDirectionRight = seekRight,
                )
            }
            if (nextAction != null) PlaybackActionButton(
                text = if (nextEpisode != null) "Next Episode" else if (nextEpisodeState == com.atlas.atlastv.playback.NextEpisodeState.Loading) "Loading…" else "Retry Next",
                onClick = nextAction, width = 164, testTag = "playback_next_episode_control",
                focusRequester = nextRequester,
                onFocus = { nextFocused = it; onNextActionFocus(it) },
                onDirectionLeft = seekLeft, onDirectionRight = seekRight,
                onDirectionUp = { playPauseFocusRequester.requestFocus() },
                onDirectionDown = {},
            )
        }
    }
}

@Composable
private fun PlaybackFailureOverlay(
    failure: PlaybackFailure,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retryRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryRequester.requestFocus() }
    Column(
        modifier = modifier
            .width(480.dp)
            .background(AtlasSurface.copy(alpha = 0.98f), RoundedCornerShape(16.dp))
            .padding(28.dp)
            .testTag("playback_failure_overlay")
            .semantics { contentDescription = "playback_failure_overlay" }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failure == PlaybackFailure.Timeout) {
            Box(Modifier.testTag("playback_no_first_frame_overlay"))
        }
        Text(failure.title, color = AtlasText, style = MaterialTheme.typography.headlineSmall)
        Text(failure.message, color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaybackActionButton(
                text = "Retry",
                onClick = onRetry,
                width = 132,
                testTag = "playback_retry_action",
                focusedTestTag = "playback_retry_focused",
                focusRequester = retryRequester,
                onDirectionRight = { backRequester.requestFocus() },
            )
            PlaybackActionButton(
                text = "Back",
                onClick = onBack,
                width = 132,
                testTag = "playback_back_action",
                focusedTestTag = "playback_back_focused",
                focusRequester = backRequester,
                onDirectionLeft = { retryRequester.requestFocus() },
            )
        }
    }
}

@Composable
private fun PlaybackProgressBar(positionMs: Long, durationMs: Long?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .testTag("normal_playback_progress"),
    ) {
        Box(
            Modifier
                .testTag("playback_progress_bar")
                .semantics { contentDescription = "playback_progress_bar" },
        )
        val fraction = if (durationMs != null && durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(7.dp)
                .background(AtlasAccent, RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun PlaybackActionButton(
    text: String,
    onClick: () -> Unit,
    width: Int,
    testTag: String,
    focusRequester: FocusRequester? = null,
    focusedTestTag: String? = null,
    onDirectionLeft: (() -> Unit)? = null,
    onDirectionRight: (() -> Unit)? = null,
    onDirectionUp: (() -> Unit)? = null,
    onDirectionDown: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val effectiveTestTag = focusedTestTag?.takeIf { isFocused } ?: testTag

    Box(
        modifier = Modifier
            .width(width.dp)
            .height(44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (onDirectionLeft != null || onDirectionRight != null || onDirectionUp != null || onDirectionDown != null) {
                    Modifier.focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                } else {
                    Modifier
                },
            )
            .background(if (isFocused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { isFocused = it.isFocused; onFocus(it.isFocused) }
            .focusable()
            .onPreviewKeyEvent { event ->
                handlePlaybackButtonKey(
                    key = event.key,
                    type = event.type,
                    onClick = onClick,
                    onDirectionLeft = onDirectionLeft,
                    onDirectionRight = onDirectionRight,
                    onDirectionUp = onDirectionUp,
                    onDirectionDown = onDirectionDown,
                )
            }
            .onKeyEvent { event ->
                handlePlaybackButtonKey(
                    key = event.key,
                    type = event.type,
                    onClick = onClick,
                    onDirectionLeft = onDirectionLeft,
                    onDirectionRight = onDirectionRight,
                    onDirectionUp = onDirectionUp,
                    onDirectionDown = onDirectionDown,
                )
            }
            .clickable(onClick = onClick)
            .testTag(effectiveTestTag)
            .semantics { contentDescription = effectiveTestTag }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

private fun handlePlaybackButtonKey(
    key: Key,
    type: KeyEventType,
    onClick: () -> Unit,
    onDirectionLeft: (() -> Unit)?,
    onDirectionRight: (() -> Unit)?,
    onDirectionUp: (() -> Unit)?,
    onDirectionDown: (() -> Unit)?,
): Boolean {
    if (type != KeyEventType.KeyDown) return false
    return when (key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            onClick()
            true
        }
        Key.DirectionLeft -> {
            onDirectionLeft?.invoke()
            onDirectionLeft != null
        }
        Key.DirectionRight -> {
            onDirectionRight?.invoke()
            onDirectionRight != null
        }
        Key.DirectionUp -> {
            onDirectionUp?.invoke()
            onDirectionUp != null
        }
        Key.DirectionDown -> {
            onDirectionDown?.invoke()
            onDirectionDown != null
        }
        else -> false
    }
}

private fun Long.formatTime(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
