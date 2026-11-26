package com.atlas.atlastv.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaybackStartState {
    data object Idle : PlaybackStartState
    data object Loading : PlaybackStartState
    data object Failed : PlaybackStartState
    data class Ready(val record: WatchProgressRecord, val resumeMs: Long?, val playbackUrl: String? = null,
        val startedFromBeginning: Boolean = false) : PlaybackStartState
}

/** Opens playback only after a cancellable resume lookup succeeds. Raw storage errors stay private. */
class PlaybackStartStateHolder(
    private val scope: CoroutineScope,
    private val loadProgress: suspend () -> WatchProgressSnapshot,
) {
    private val _state = MutableStateFlow<PlaybackStartState>(PlaybackStartState.Idle)
    val state = _state.asStateFlow()
    private var version = 0L
    private var job: Job? = null
    private var requested: WatchProgressRecord? = null
    private var prepareRequestedUrl: suspend () -> String? = { null }
    private var requestedFromBeginning = false

    fun start(base: WatchProgressRecord, prepareUrl: suspend () -> String? = { null }) = request(base, false, prepareUrl)
    fun startOver(base: WatchProgressRecord, prepareUrl: suspend () -> String? = { null }) = request(base, true, prepareUrl)
    private fun request(base: WatchProgressRecord, fromBeginning: Boolean, prepareUrl: suspend () -> String?) {
        job?.cancel()
        requested = base
        prepareRequestedUrl = prepareUrl
        requestedFromBeginning = fromBeginning
        val request = ++version
        _state.value = PlaybackStartState.Loading
        job = scope.launch {
            try {
                val snapshot = loadProgress()
                val playbackUrl = prepareUrl()
                ensureActive()
                if (request != version) return@launch
                val existing = snapshot.recordFor(base.contentType, base.streamId)?.takeIf {
                    base.contentType != WatchProgressContentType.SeriesEpisode || it.seriesId == null || it.seriesId == base.seriesId
                }
                _state.value = PlaybackStartState.Ready(existing ?: base,
                    if (fromBeginning) 0L else WatchProgressPolicy.resumePositionMs(existing), playbackUrl, fromBeginning)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == version) _state.value = PlaybackStartState.Failed
            }
        }
    }

    fun retry() { requested?.let { request(it, requestedFromBeginning, prepareRequestedUrl) } }
    fun cancel() {
        version += 1
        job?.cancel()
        requested = null
        prepareRequestedUrl = { null }
        requestedFromBeginning = false
        _state.value = PlaybackStartState.Idle
    }
}
