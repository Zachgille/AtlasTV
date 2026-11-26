package com.atlas.atlastv.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.ui.series.SeriesEpisodeItem
import com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult
import com.atlas.atlastv.ui.series.SeriesEpisodeState
import com.atlas.atlastv.ui.series.episodesForSeason
import com.atlas.atlastv.ui.series.seasons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

sealed interface NextEpisodeState {
    data object Idle : NextEpisodeState
    data object Loading : NextEpisodeState
    data class Available(val episode: SeriesEpisodeItem) : NextEpisodeState
    data object End : NextEpisodeState
    data object Unavailable : NextEpisodeState
}

/** Follow the same season/episode order shown in details. Unknown numbering uses its deterministic fallback. */
fun resolveNextEpisode(episodes: List<SeriesEpisodeItem>, currentEpisodeId: Int): NextEpisodeState {
    val ready = SeriesEpisodeState.Ready(episodes.filter { it.episodeId > 0 }.distinctBy { it.episodeId })
    val ordered = ready.seasons().flatMap { ready.episodesForSeason(it.seasonNumber) }
    val index = ordered.indexOfFirst { it.episodeId == currentEpisodeId }
    if (index < 0) return NextEpisodeState.Unavailable
    return ordered.getOrNull(index + 1)?.let(NextEpisodeState::Available) ?: NextEpisodeState.End
}

/** Optional, screen-owned lookup. Keeps only one show's full list and never delays current video. */
class NextEpisodeStateHolder(
    private val loadEpisodes: suspend (Int) -> SeriesEpisodeLoadResult,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val contextKey: () -> String? = { null },
    private val refreshEpisodes: suspend (Int) -> SeriesEpisodeLoadResult = loadEpisodes,
) {
    var state: NextEpisodeState by mutableStateOf(NextEpisodeState.Idle)
        private set
    var retryVersion by mutableStateOf(0L)
        private set
    private var version = 0L
    private var identity: Pair<Int, Int>? = null
    private var cachedSeriesId: Int? = null
    private var cachedEpisodes: SeriesEpisodeState.Ready? = null
    private var ignoreSeed = false
    private var activeContext: String? = null
    private var cachedContext: String? = null

    fun loadedFor(seriesId: Int): SeriesEpisodeState.Ready? = cachedEpisodes.takeIf { cachedSeriesId == seriesId && cachedContext == contextKey() }

    suspend fun ensureNext(seriesId: Int, episodeId: Int, seed: SeriesEpisodeState.Ready? = null) {
        val key = seriesId to episodeId
        val context = contextKey()
        if (identity == key && activeContext == context && (state is NextEpisodeState.Available || state == NextEpisodeState.End)) return
        val request = ++version
        identity = key
        activeContext = context
        state = NextEpisodeState.Loading
        try {
            val loaded = withContext(dispatcher) {
                seed?.takeUnless { ignoreSeed } ?: loadedFor(seriesId) ?: when (val result = if (ignoreSeed) refreshEpisodes(seriesId) else loadEpisodes(seriesId)) {
                    is SeriesEpisodeLoadResult.Success -> SeriesEpisodeState.Ready(result.episodes, result.metadata, saved = result.fromSaved)
                    is SeriesEpisodeLoadResult.Failure -> null
                }
            }
            coroutineContext.ensureActive()
            if (request != version || identity != key || context != contextKey()) return
            if (loaded == null || loaded.episodes.isEmpty()) {
                state = NextEpisodeState.Unavailable
            } else {
                cachedSeriesId = seriesId
                cachedContext = context
                cachedEpisodes = loaded
                ignoreSeed = false
                state = resolveNextEpisode(loaded.episodes, episodeId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutineContext.ensureActive()
            if (request == version && identity == key && context == contextKey()) state = NextEpisodeState.Unavailable
        } finally {
            if (request == version && state == NextEpisodeState.Loading) state = NextEpisodeState.Idle
        }
    }

    fun retry() {
        // A missing current ID may reflect stale provider metadata, so explicitly retry the source.
        cachedSeriesId = null; cachedEpisodes = null
        ignoreSeed = true
        version++; state = NextEpisodeState.Loading; retryVersion++
    }

    fun deactivate(clearCache: Boolean = false) {
        version++; identity = null; state = NextEpisodeState.Idle
        if (clearCache) {
            cachedSeriesId = null; cachedEpisodes = null; ignoreSeed = true
            // An import can finish while playback is open. Wake its effect and reject the old details seed.
            retryVersion++
        }
    }
}
