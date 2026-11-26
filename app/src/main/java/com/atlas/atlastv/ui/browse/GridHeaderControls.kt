package com.atlas.atlastv.ui.browse

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.*

@Composable
internal fun GridHeaderIcon(glyph: String, description: String, tag: String, requester: FocusRequester,
    onClick: () -> Unit, onDown: () -> Unit, onHorizontal: (Key) -> Boolean = { false }, onFocused: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(42.dp).background(if (focused) AtlasSurfaceFocused else AtlasBackground, shape)
        .border(BorderStroke(2.dp, if (focused) AtlasAccent else AtlasBackground), shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionDown -> { onDown(); true }
                Key.DirectionLeft, Key.DirectionRight -> onHorizontal(event.key)
                Key.Enter, Key.DirectionCenter -> { if (event.nativeKeyEvent.repeatCount == 0) onClick(); true }
                else -> false
            }
        }.focusable().clickable(onClick = onClick).testTag(tag)
        .semantics { contentDescription = description; role = Role.Button }, contentAlignment = Alignment.Center) {
        Text(glyph, color = AtlasText, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
internal fun GridBrowseDialog(tags: String, onFirst: () -> Unit, onLast: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }; val last = remember { FocusRequester() }; val cancel = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { first.requestFocus() }
        Column(Modifier.width(280.dp).background(AtlasSurface, RoundedCornerShape(12.dp)).padding(18.dp)
            .testTag("${tags}_options_dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Jump to", color = AtlasMutedText, style = MaterialTheme.typography.titleMedium)
            GridMenuButton("First title", "${tags}_first", first, onFirst, { cancel.requestFocus() }, { last.requestFocus() })
            GridMenuButton("Last title", "${tags}_last", last, onLast, { first.requestFocus() }, { cancel.requestFocus() })
            GridMenuButton("Cancel", "${tags}_options_cancel", cancel, onDismiss, { last.requestFocus() }, { first.requestFocus() })
        }
    }
}

@Composable
private fun GridMenuButton(label: String, tag: String, requester: FocusRequester, onClick: () -> Unit,
    up: () -> Unit, down: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().height(44.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
        .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionUp -> { up(); true }; Key.DirectionDown -> { down(); true }
                Key.Enter, Key.DirectionCenter -> { if (event.nativeKeyEvent.repeatCount == 0) onClick(); true }; else -> false
            }
        }.focusable().clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.CenterStart) {
        Text(label, color = AtlasText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
