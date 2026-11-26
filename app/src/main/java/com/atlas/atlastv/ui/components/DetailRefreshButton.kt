package com.atlas.atlastv.ui.components

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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.*

@Composable
fun DetailRefreshButton(requester: FocusRequester, refreshing: Boolean, onRefresh: () -> Unit,
    onDown: () -> Unit, onUp: () -> Unit, testTag: String, description: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.width(36.dp).height(36.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
        .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionDown -> { onDown(); true }; Key.DirectionUp, Key.DirectionLeft -> { onUp(); true }
                Key.Enter, Key.DirectionCenter -> { if (!refreshing) onRefresh(); true }; else -> false
            }
        }.focusable().clickable(enabled = !refreshing, onClick = onRefresh).testTag(testTag)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(if (refreshing) "…" else "↻", color = AtlasText, style = MaterialTheme.typography.titleLarge)
    }
}
