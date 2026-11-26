package com.atlas.atlastv.ui.components

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
import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.theme.*

/** A separate modal focus surface; Cancel is the safe initial and Back action. */
@Composable
fun StartOverConfirmation(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { cancel.requestFocus() }
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().background(AtlasSurface, RoundedCornerShape(16.dp))
            .padding(24.dp).testTag("start_over_dialog"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Start over?", color = AtlasText, style = MaterialTheme.typography.headlineMedium)
            Text(displayTitle(title), color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("start_over_title"))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StartOverDialogButton("Cancel", "start_over_cancel", cancel, onDismiss, { confirm.requestFocus() })
                StartOverDialogButton("Start over", "start_over_confirm", confirm, onConfirm, { cancel.requestFocus() })
            }
        }
    }
}

@Composable
private fun RowScope.StartOverDialogButton(text: String, tag: String, requester: FocusRequester, action: () -> Unit, horizontal: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.weight(1f).height(48.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
        .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft, Key.DirectionRight -> { horizontal(); true }
                Key.Enter, Key.DirectionCenter -> { action(); true }
                else -> false
            }
        }.focusable().clickable(onClick = action).testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.bodyLarge)
    }
}
