package com.atlas.atlastv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.atlas.atlastv.playback.PlaybackTrackOption
import com.atlas.atlastv.playback.PlaybackTrackType
import com.atlas.atlastv.ui.theme.*
import kotlinx.coroutines.launch

/** Full track lists, with scroll-before-focus navigation and stable provider track identities. */
@Composable
fun PlaybackTrackMenu(
    audio: List<PlaybackTrackOption>,
    subtitles: List<PlaybackTrackOption>,
    subtitlesDisabled: Boolean,
    onSelect: (PlaybackTrackOption) -> Unit,
    onSubtitlesOff: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    preferenceError: String? = null,
    preferencePending: Boolean = false,
    onRetryPreferences: (() -> Unit)? = null,
) {
    val columns = listOf(audio.map { TrackMenuRow(it.key(), it.label, it.selected, it) },
        listOf(TrackMenuRow("subtitle_off", "Off", subtitlesDisabled, null)) + subtitles.map { TrackMenuRow(it.key(), it.label, it.selected, it) })
    val audioScroll = rememberLazyListState()
    val subtitleScroll = rememberLazyListState()
    val scrolls = listOf(audioScroll, subtitleScroll)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val doneRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }
    var showPreferenceRecovery by remember { mutableStateOf(false) }
    LaunchedEffect(preferenceError) { if (preferenceError != null) showPreferenceRecovery = true }
    fun focus(column: Int, index: Int) {
        val rows = columns[column]
        if (rows.isEmpty()) return
        val targetIndex = index.coerceIn(rows.indices)
        val key = rows[targetIndex].key
        val requester = requesters.getOrPut(key) { FocusRequester() }
        scope.launch {
            if (scrolls[column].layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) scrolls[column].scrollToItem(targetIndex)
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    val identities = columns.map { rows -> rows.map { it.key } }
    val viewportHeight = minOf(240, columns.maxOf { it.size } * 50 - 6).coerceAtLeast(44)
    LaunchedEffect(identities) {
        if (focusedKey == null || columns.flatten().none { it.key == focusedKey }) {
            val column = if (audio.isNotEmpty()) 0 else 1
            focus(column, columns[column].indexOfFirst { it.selected }.coerceAtLeast(0))
        }
    }
    Column(modifier.widthIn(max = 660.dp).fillMaxWidth().background(AtlasSurface.copy(alpha = .98f), RoundedCornerShape(12.dp))
        .padding(20.dp).focusGroup().testTag("playback_track_options_panel")
        .semantics { contentDescription = "playback_track_options_panel" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Audio & Subtitles", color = AtlasText, style = MaterialTheme.typography.titleLarge)
            var doneFocused by remember { mutableStateOf(false) }
            Box(Modifier.width(90.dp).height(40.dp).background(if (doneFocused) AtlasSurfaceFocused else AtlasSurface, RoundedCornerShape(8.dp))
                .border(BorderStroke(if (doneFocused) 2.dp else 1.dp, if (doneFocused) AtlasAccent else AtlasSurfaceFocused), RoundedCornerShape(8.dp))
                .focusRequester(doneRequester).onFocusChanged { doneFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { if (showPreferenceRecovery) retryRequester.requestFocus(); showPreferenceRecovery }
                        Key.DirectionDown -> { val column = if (audio.isEmpty()) 1 else 0; focus(column, columns[column].indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)); true }
                        Key.Enter, Key.DirectionCenter -> { onDismiss(); true }
                        else -> false
                    }
                }.focusable().clickable(onClick = onDismiss).testTag("playback_track_done"), contentAlignment = Alignment.Center) {
                Text("Done", color = AtlasText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (showPreferenceRecovery && onRetryPreferences != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(preferenceError ?: if (preferencePending) "Remembering language choices…" else "Language choices remembered.",
                    Modifier.weight(1f).testTag("playback_language_preference_status"), color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                // Keep this target attached after Retry so asynchronous success cannot discard focus.
                var retryFocused by remember { mutableStateOf(false) }
                Box(Modifier.width(90.dp).height(40.dp).background(if (retryFocused) AtlasSurfaceFocused else AtlasSurface, RoundedCornerShape(8.dp))
                    .border(BorderStroke(if (retryFocused) 2.dp else 1.dp, if (retryFocused) AtlasAccent else AtlasSurfaceFocused), RoundedCornerShape(8.dp))
                    .focusRequester(retryRequester).onFocusChanged { retryFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionDown -> { doneRequester.requestFocus(); true }
                            Key.Enter, Key.DirectionCenter -> { if (preferenceError != null) onRetryPreferences(); true }
                            else -> false
                        }
                    }.focusable().clickable { if (preferenceError != null) onRetryPreferences() }.testTag("playback_language_preference_retry"),
                    contentAlignment = Alignment.Center) {
                    Text("Retry", color = AtlasText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            columns.forEachIndexed { column, rows ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (column == 0) "Audio" else "Subtitles", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                    if (rows.isEmpty()) Text("No alternate audio", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.height(viewportHeight.dp), state = scrolls[column], verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                            var focused by remember { mutableStateOf(false) }
                            val shape = RoundedCornerShape(8.dp)
                            val type = if (column == 0) "audio_track" else if (row.option == null) "subtitle_off" else "subtitle_track"
                            val action = { if (row.option == null) onSubtitlesOff() else onSelect(row.option) }
                            Row(Modifier.fillMaxWidth().height(44.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
                                .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
                                .focusRequester(requesters.getOrPut(row.key) { FocusRequester() })
                                .onFocusChanged { focused = it.isFocused; if (focused) focusedKey = row.key }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                        Key.DirectionUp -> { if (index > 0) focus(column, index - 1) else doneRequester.requestFocus(); true }
                                        Key.DirectionDown -> { if (index < rows.lastIndex) focus(column, index + 1); true }
                                        Key.DirectionLeft -> { if (column == 1) focus(0, index); true }
                                        Key.DirectionRight -> { if (column == 0) focus(1, index); true }
                                        Key.Enter, Key.DirectionCenter -> { action(); true }
                                        else -> false
                                    }
                                }.focusable().clickable(onClick = action).testTag("playback_track_${row.key}")
                                .semantics { contentDescription = "${if (focused) "focused_" else ""}playback_${type}_option" }
                                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row.label, Modifier.weight(1f), color = AtlasText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.selected) Text("✓", color = AtlasAccent, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class TrackMenuRow(val key: String, val label: String, val selected: Boolean, val option: PlaybackTrackOption?)
private fun PlaybackTrackOption.key() = "${type.name.lowercase()}_${groupIndex}_$trackIndex"
