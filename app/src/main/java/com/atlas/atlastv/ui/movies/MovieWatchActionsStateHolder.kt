package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.catalog.WatchActionsStateHolder
import com.atlas.atlastv.ui.catalog.displayTitle
import kotlinx.coroutines.CoroutineScope

class MovieWatchActionsStateHolder(writer: WatchProgressCoordinator, scope: CoroutineScope) : WatchActionsStateHolder(writer, scope) {
    fun change(movie: MovieItem, watched: Boolean) {
        if (!movie.available) return
        changeProgress(movie.manualProgressRecord(), watched, displayTitle(movie.title))
    }
    fun matches(id: Int): Boolean = target?.let { it.contentType == WatchProgressContentType.Movie && it.streamId == id } == true
    fun canUndo(id: Int): Boolean = canUndoKey(WatchProgressRecord.key(WatchProgressContentType.Movie, id))
}

fun MovieItem.manualProgressRecord() = WatchProgressRecord(
    contentType = WatchProgressContentType.Movie, streamId = streamId, title = title, categoryName = categoryName,
    extension = containerExtension, positionMs = 0, updatedAtMs = 0,
)
