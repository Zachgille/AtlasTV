package com.atlas.atlastv.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.*

@Composable
internal fun WatchActionsDialog(title: String, subtitle: String?, watched: Boolean,
    actions: WatchActionsStateHolder, local: Boolean, undoAvailable: Boolean, tags: String,
    onChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val mark = remember { FocusRequester() }; val undo = remember { FocusRequester() }; val done = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    var undoRequested by remember { mutableStateOf(false) }
    val initiallyWatched = remember { watched }
    var restoredWatched by remember { mutableStateOf(watched) }
    // Undo remains a stable focus target during save/restore and after acknowledgement.
    val showUndo = requested || undoRequested || undoAvailable
    val restored = undoRequested && local && actions.message == "Undone"
    val currentWatched = if (restored) restoredWatched else initiallyWatched
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { mark.requestFocus() }
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().background(AtlasSurface, RoundedCornerShape(16.dp))
            .padding(24.dp).testTag("${tags}_actions_dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (subtitle != null) Text(subtitle, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
            Text(title, color = AtlasText, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("${tags}_actions_title"))
            if ((actions.saving || actions.failed) && !local) Text("Unsaved change: ${actions.targetCode}",
                color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            WatchActionButton(when {
                actions.failed -> "Retry save"
                actions.saving -> "Saving…"
                requested && local && !restored -> actions.message ?: "Saved"
                currentWatched -> "Mark unwatched"
                else -> "Mark watched"
            }, "${tags}_action_mark", mark, enabled = actions.failed || ((!requested || restored) && !actions.saving),
                onClick = { if (actions.failed) actions.retry() else { undoRequested = false; requested = true; onChange(!currentWatched) } },
                up = { done.requestFocus() }, down = { if (showUndo) undo.requestFocus() else done.requestFocus() })
            if (showUndo) WatchActionButton(if (undoRequested && local && actions.message == "Undone") "Undone" else "Undo",
                "${tags}_action_undo", undo, enabled = undoAvailable,
                onClick = { restoredWatched = actions.undoEdit?.restoresWatched == true; undoRequested = true; requested = true; actions.undo() },
                up = { mark.requestFocus() }, down = { done.requestFocus() })
            WatchActionButton("Done", "${tags}_action_done", done, onClick = onDismiss,
                up = { if (showUndo) undo.requestFocus() else mark.requestFocus() }, down = { mark.requestFocus() })
        }
    }
}

@Composable
private fun WatchActionButton(label: String, tag: String, requester: FocusRequester, enabled: Boolean = true,
    onClick: () -> Unit, up: () -> Unit, down: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().height(48.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
        .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionUp -> { up(); true }; Key.DirectionDown -> { down(); true }
                Key.Enter, Key.DirectionCenter -> { if (enabled) onClick(); true }; else -> false
            }
        }.focusable().clickable(enabled = enabled, onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled || focused) AtlasText else AtlasMutedText, style = MaterialTheme.typography.titleMedium)
    }
}
