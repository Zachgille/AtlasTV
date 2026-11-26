package com.atlas.atlastv.ui.series

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.atlas.atlastv.ui.catalog.WatchActionsDialog

@Composable
internal fun EpisodeActionsDialog(show: SeriesItem, episode: SeriesEpisodeItem, watched: Boolean,
    actions: EpisodeWatchActionsStateHolder, onDismiss: () -> Unit) {
    val undoAvailable = remember(actions.revision, actions.saving, actions.failed, actions.undoEdit) {
        actions.canUndo(show.seriesId, episode.episodeId)
    }
    WatchActionsDialog(episode.displayEpisodeName(show.name), episode.shortEpisodeCode().ifBlank { "Episode" }, watched,
        actions, actions.matches(show.seriesId, episode.episodeId), undoAvailable, "episode",
        onChange = { actions.change(show, episode, it) }, onDismiss = onDismiss)
}
