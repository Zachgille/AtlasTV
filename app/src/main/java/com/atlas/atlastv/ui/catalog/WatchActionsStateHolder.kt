package com.atlas.atlastv.ui.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Shared acknowledgement/retry/Undo for explicit Movie and episode progress edits. */
open class WatchActionsStateHolder(protected val writer: WatchProgressCoordinator, private val scope: CoroutineScope) {
    var target by mutableStateOf<WatchProgressRecord?>(null); private set
    var saving by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var undoEdit by mutableStateOf<EpisodeProgressEdit?>(null); private set
    var revision by mutableStateOf(0L); private set
    var targetCode by mutableStateOf("Title"); private set
    private var activeEdit: EpisodeProgressEdit? = null
    private var retryOperation: (suspend () -> EpisodeEditResult)? = null
    private var pendingSuccess = ""
    private var pendingUndo = false

    init { scope.launch { writer.revision.collect { revision = it } } }

    protected fun changeProgress(base: WatchProgressRecord, watched: Boolean, label: String) {
        if (saving || failed) return
        targetCode = label
        start(base, if (watched) "Marked watched" else "Marked unwatched", true) {
            when (base.contentType) {
                WatchProgressContentType.Movie -> writer.setMovieWatched(base, watched)
                WatchProgressContentType.SeriesEpisode -> writer.setEpisodeWatched(base, watched)
            }
        }
    }

    protected fun canUndoKey(key: String): Boolean = undoEdit?.let {
        it.applied.key == key && writer.canUndo(it) && !saving && !failed
    } == true

    fun undo() {
        if (saving || failed) return
        val edit = undoEdit ?: return
        start(edit.applied, "Undone", false) { writer.undoEpisodeEdit(edit) }
    }

    fun retry() {
        if (!failed || saving) return
        activeEdit?.let { writer.retryEdit(it) } ?: retryOperation?.let { operation ->
            val base = target ?: return
            start(base, pendingSuccess, pendingUndo, operation)
        }
    }

    private fun start(base: WatchProgressRecord, success: String, allowUndo: Boolean, operation: suspend () -> EpisodeEditResult) {
        target = base; saving = true; failed = false; message = "Saving…"; undoEdit = null; activeEdit = null
        retryOperation = operation; pendingSuccess = success; pendingUndo = allowUndo
        scope.launch {
            try {
                when (val result = operation()) {
                    EpisodeEditResult.Conflict -> { saving = false; message = "Progress changed. Choose another action."; retryOperation = null }
                    is EpisodeEditResult.Accepted -> {
                        activeEdit = result.edit
                        writer.revision.first {
                            when (writer.editStatus(result.edit)) {
                                EpisodeEditStatus.Saving -> {
                                    failed = writer.saveFailed.value; saving = !failed
                                    message = if (failed) "Couldn't save your change" else "Saving…"
                                    false
                                }
                                EpisodeEditStatus.Failed -> { saving = false; failed = true; message = "Couldn't save your change"; false }
                                EpisodeEditStatus.Saved -> {
                                    saving = false; failed = false; message = success
                                    undoEdit = result.edit.takeIf { allowUndo }; retryOperation = null; true
                                }
                                EpisodeEditStatus.Superseded -> { saving = false; failed = false; message = "Progress changed. Choose another action."; retryOperation = null; true }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saving = false; failed = true; message = "Couldn't save your change" }
        }
    }
}
