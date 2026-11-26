package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.catalog.displayTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.*

/** A visible remote-friendly cleanup journey; opening it never starts playback. */
@Composable
fun ContinueWatchingEditor(cards: List<HomeCard>, onRemove: (HomeCard) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, actions: ContinueWatchingActionsStateHolder? = null) {
    val done = remember { FocusRequester() }
    val recovery = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cards.map { it.id }) {
        if (selected != null && cards.none { it.id == selected }) done.requestFocus()
    }
    LaunchedEffect(Unit) { done.requestFocus() }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().testTag("continue_watching_editor"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CleanupButton("Done", "continue_watching_done", Modifier.focusRequester(done), onBack,
                onDown = { if (actions?.message != null) recovery.requestFocus() else cards.firstOrNull()?.let { requesters[it.id]?.requestFocus() } })
            Text("Continue Watching", color = AtlasText, style = MaterialTheme.typography.headlineMedium)
        }
        Text("Remove titles to reset their unfinished resume points.", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
        actions?.let { ContinueWatchingNotice(it, recovery,
            onUp = { done.requestFocus() },
            onDown = { cards.firstOrNull()?.let { requesters[it.id]?.requestFocus() } ?: done.requestFocus() }) }
        if (cards.isEmpty()) Text("You’re all caught up.", color = AtlasMutedText)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { it.id }) { card ->
                val requester = requesters.getOrPut(card.id) { FocusRequester() }
                Row(Modifier.fillMaxWidth().background(AtlasSurface, RoundedCornerShape(8.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayTitle(card.title), color = AtlasText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (card.cardProgress?.summary ?: card.progressLabel)?.let { Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodySmall) }
                    }
                    CleanupButton("Remove", "continue_watching_remove_${card.id}",
                        Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) selected = card.id },
                        { onRemove(card) })
                }
            }
        }
    }
}

@Composable
private fun CleanupButton(text: String, tag: String, modifier: Modifier, onClick: () -> Unit, onDown: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.width(120.dp).height(48.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
        .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
        .onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.Enter, Key.DirectionCenter -> { if (it.nativeKeyEvent.repeatCount == 0) onClick(); true }
                Key.DirectionDown -> { onDown?.invoke(); onDown != null }
                else -> false
            }
        }.focusable().clickable(onClick = onClick).testTag(tag), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

/** One compact, recoverable action; async status changes never request focus. */
@Composable
fun ContinueWatchingNotice(actions: ContinueWatchingActionsStateHolder, requester: FocusRequester,
    onUp: () -> Unit, onDown: () -> Unit) {
    val message = actions.message ?: return
    Row(Modifier.fillMaxWidth().background(AtlasSurface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        CleanupButton(actions.actionLabel, "continue_watching_recovery_action",
            Modifier.focusRequester(requester).semantics { contentDescription = actions.actionLabel }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { onUp(); true } else false
                }, { actions.act(); if (actions.message == null) onUp() }, onDown)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(message, color = AtlasText, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("continue_watching_edit_status"))
            Text(actions.title, color = AtlasMutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
