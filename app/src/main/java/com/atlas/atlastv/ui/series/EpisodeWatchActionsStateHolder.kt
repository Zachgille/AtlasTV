package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.catalog.WatchActionsStateHolder
import kotlinx.coroutines.CoroutineScope

class EpisodeWatchActionsStateHolder(writer: WatchProgressCoordinator, scope: CoroutineScope) : WatchActionsStateHolder(writer, scope) {
    fun change(show: SeriesItem, episode: SeriesEpisodeItem, watched: Boolean) {
        if (!show.available) return
        changeProgress(episode.manualProgressRecord(show), watched, episode.shortEpisodeCode().ifBlank { "Episode" })
    }
    fun matches(showId: Int, episodeId: Int): Boolean = target?.let {
        it.contentType == WatchProgressContentType.SeriesEpisode && it.seriesId == showId && it.streamId == episodeId
    } == true
    fun canUndo(showId: Int, episodeId: Int): Boolean = undoEdit?.applied?.seriesId == showId &&
        canUndoKey(WatchProgressRecord.key(WatchProgressContentType.SeriesEpisode, episodeId))
}

fun SeriesEpisodeItem.manualProgressRecord(show: SeriesItem) = WatchProgressRecord(
    contentType = WatchProgressContentType.SeriesEpisode, streamId = episodeId, seriesId = show.seriesId,
    title = displayEpisodeName(show.name), seriesTitle = show.name, episodeTitle = displayEpisodeName(show.name),
    categoryName = show.categoryName, extension = containerExtension, durationMs = durationMs?.takeIf { it > 0 },
    positionMs = 0, updatedAtMs = 0,
)
