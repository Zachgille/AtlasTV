package com.atlas.atlastv.ui.home

import androidx.compose.runtime.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.catalog.displayTitle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One removable title at a time; acknowledgement/recovery survive closing the editor. */
class ContinueWatchingActionsStateHolder(private val writer: WatchProgressCoordinator, private val scope: CoroutineScope) {
    var saving by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    private var edit: ContinueWatchingRemoval? = null
    private var undoEdit: ContinueWatchingRemoval? = null
    private var retryOperation: (suspend () -> ContinueWatchingRemovalResult)? = null
    private var success = ""
    private var allowUndo = false
    var revision by mutableStateOf(0L); private set
    val canUndo: Boolean get() { revision; return undoEdit?.let { writer.canUndoRemoval(it) } == true && !saving && !failed }
    val actionLabel: String get() = when { saving -> "Saving…"; failed -> "Retry"; canUndo -> "Undo"; else -> "Done" }

    init { scope.launch { writer.revision.collect { revision = it } } }

    fun remove(card: HomeCard) {
        if (saving || failed) return
        title = displayTitle(card.title)
        val type = if (card.contentType == HomeContentType.Movie) WatchProgressContentType.Movie else WatchProgressContentType.SeriesEpisode
        start("Removed", true) { writer.removeFromContinueWatching(type, card.streamId, card.seriesId) }
    }
    fun undo() {
        if (!canUndo) return
        val original = undoEdit ?: return
        start("Restored", false) { writer.undoRemoval(original) }
    }
    fun retry() {
        if (!failed || saving) return
        edit?.let { writer.retryRemoval(it) } ?: retryOperation?.let { start(success, allowUndo, it) }
    }
    fun act() { when { saving -> Unit; failed -> retry(); canUndo -> undo(); else -> dismiss() } }
    fun dismiss() { if (!saving && !failed) { message = null; undoEdit = null; edit = null } }
    private fun start(successText: String, keepUndo: Boolean, operation: suspend () -> ContinueWatchingRemovalResult) {
        success = successText; allowUndo = keepUndo; retryOperation = operation
        saving = true; failed = false; message = "Saving…"; edit = null; undoEdit = null
        scope.launch {
            try {
                when (val result = operation()) {
                    ContinueWatchingRemovalResult.Conflict -> { saving = false; message = "Progress changed. Nothing was overwritten."; retryOperation = null }
                    is ContinueWatchingRemovalResult.Accepted -> {
                        edit = result.edit
                        writer.revision.first {
                            when (writer.removalStatus(result.edit)) {
                                EpisodeEditStatus.Saving -> { saving = true; failed = false; message = "Saving…"; false }
                                EpisodeEditStatus.Failed -> { saving = false; failed = true; message = "Couldn’t save your change"; false }
                                EpisodeEditStatus.Saved -> { saving = false; failed = false; message = successText
                                    undoEdit = result.edit.takeIf { keepUndo }; retryOperation = null; true }
                                EpisodeEditStatus.Superseded -> { saving = false; failed = false; message = "Progress changed. Nothing was overwritten."; retryOperation = null; true }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saving = false; failed = true; message = "Couldn’t save your change" }
        }
    }
}
