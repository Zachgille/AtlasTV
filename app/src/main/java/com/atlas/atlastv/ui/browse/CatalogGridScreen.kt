package com.atlas.atlastv.ui.browse

import com.atlas.atlastv.ui.catalog.displayTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.components.AtlasFallbackPoster
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.theme.*
import kotlinx.coroutines.flow.*

@Composable
fun <T : Any, K : Any> CatalogGridScreen(
    holder: KeyedCatalogGridStateHolder<T, K>, revision: Long, title: String, noun: String, plural: String, tags: String,
    nameOf: (T) -> String, categoryOf: (T) -> String, imageOf: (T) -> AtlasImageRef,
    onBack: () -> Unit, onItem: (T) -> Unit, catalogReady: Boolean = true,
    emptyMessage: String? = null,
    statusMessage: String? = null, onRetryStatus: (() -> Unit)? = null,
    headerActions: (@Composable () -> Unit)? = null,
    showJumpControls: Boolean = true,
    showSubtitle: Boolean = false,
    onNavigationChanged: () -> Unit = {},
) {
    val state by holder.state.collectAsState()
    val columns = 5
    val grid = rememberLazyGridState(holder.scrollIndex, holder.scrollOffset)
    com.atlas.atlastv.ui.media.NearbyArtworkPrefetch(remember(state.items) { state.items.map(imageOf) }) {
        grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.minus(state.offset) ?: -1
    }
    val requesters = remember { mutableStateMapOf<Int, FocusRequester>() }
    val back = remember { FocusRequester() }
    val options = remember { FocusRequester() }
    var optionsOpen by remember { mutableStateOf(false) }
    var restoreOptions by remember { mutableStateOf(false) }
    val hasOptions = showJumpControls && state.initialized && state.total > 1
    val retry = remember { FocusRequester() }
    val statusRetry = remember { FocusRequester() }
    var focusTarget by remember { mutableStateOf<Int?>(null) }
    var entered by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var focusSequence by remember { mutableIntStateOf(0) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var restoreAfterRefresh by remember { mutableStateOf(false) }
    fun focus(index: Int) {
        val visible = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val attached = requesters[index]
        if (attached != null && visible != null && visible.offset.y >= grid.layoutInfo.viewportStartOffset &&
            visible.offset.y + visible.size.height <= grid.layoutInfo.viewportEndOffset) {
            // Adjacent visible tiles are already laid out: do not wait for recomposition + a frame.
            focusTarget = null
            attached.requestFocus()
            holder.select(index)
        } else { holder.select(index); focusTarget = index; focusSequence++ }
    }
    BackHandler { onBack() }
    LaunchedEffect(holder, revision, catalogReady) {
        restoreAfterRefresh = entered && gridHasFocus
        holder.activate(revision, catalogReady)
    }
    LaunchedEffect(Unit) { back.requestFocus() }
    LaunchedEffect(state.initialized, state.loading, state.selectedIndex, state.total) {
        if (state.initialized && !state.loading && state.total > 0 && !entered) {
            entered = true
            if (focusTarget == null) focusTarget = state.selectedIndex
        } else if (entered && (gridHasFocus || restoreAfterRefresh) && !state.loading && state.error == null && state.total > 0 && focusTarget == null && (restoreAfterRefresh || state.selectedIndex != focusedIndex)) {
            focusTarget = state.selectedIndex
        }
        if (!state.loading && state.error == null) restoreAfterRefresh = false
    }
    LaunchedEffect(state.initialized, state.total, focusTarget, focusSequence) {
        val requested = focusTarget ?: return@LaunchedEffect
        if (!state.initialized || state.total == 0) return@LaunchedEffect
        val target = requested.coerceIn(0, state.total - 1)
        holder.ensureWindow(target)
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) grid.scrollToItem(target - target % columns)
        val requester = snapshotFlow { requesters[target] }.filterNotNull().first()
        withFrameNanos { }
        if (focusTarget != requested) return@LaunchedEffect
        requester.requestFocus()
        focusTarget = null
    }
    LaunchedEffect(state.initialized, state.total) {
        if (state.initialized && state.total == 0) back.requestFocus()
    }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            holder.scrollIndex = index; holder.scrollOffset = offset
            onNavigationChanged()
            if (focusTarget == null && holder.state.value.initialized) holder.ensureWindow(index)
        }
    }
    LaunchedEffect(state.initialized, state.offset, state.items, state.loading, focusTarget) {
        if (state.initialized && !state.loading && state.error == null && focusTarget == null) {
            holder.ensureWindow(grid.firstVisibleItemIndex)
        }
    }
    fun dismissOptions() { optionsOpen = false; restoreOptions = true }
    fun headerDown() {
        if (onRetryStatus != null) statusRetry.requestFocus()
        else if (state.total > 0) focus(state.selectedIndex)
        else if (state.error != null) retry.requestFocus()
    }
    LaunchedEffect(hasOptions, optionsOpen) {
        if (optionsOpen && !hasOptions) dismissOptions()
    }
    LaunchedEffect(restoreOptions, optionsOpen, hasOptions) {
        if (restoreOptions && !optionsOpen) {
            withFrameNanos { }
            if (hasOptions) options.requestFocus() else back.requestFocus()
            restoreOptions = false
        }
    }
    if (optionsOpen && hasOptions) GridBrowseDialog(tags,
        onFirst = { optionsOpen = false; restoreOptions = false; focus(0) },
        onLast = { optionsOpen = false; restoreOptions = false; focus(state.total - 1) }, onDismiss = ::dismissOptions)
    Column(Modifier.fillMaxSize().background(AtlasBackground).padding(horizontal = 56.dp, vertical = 28.dp)
        .semantics { testTagsAsResourceId = true }.testTag("${tags}_screen"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp).testTag("${tags}_header"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GridHeaderIcon("‹", "Back", "${tags}_back", back, onBack, ::headerDown,
                onFocused = { restoreAfterRefresh = false }, onHorizontal = { key ->
                    if (key == Key.DirectionRight && headerActions == null && hasOptions) { options.requestFocus(); true } else false
                })
            Text(title, color = AtlasText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("${tags}_title"))
            headerActions?.invoke()
            if (hasOptions) GridHeaderIcon("⋯", "Browse options", "${tags}_options", options,
                onClick = { optionsOpen = true }, onDown = ::headerDown,
                onHorizontal = { key -> if (key == Key.DirectionLeft) { back.requestFocus(); true } else false })
        }
        if (statusMessage != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onRetryStatus != null) BrowseSearchButton("Retry save", { back.requestFocus(); onRetryStatus() }, "${tags}_save_retry",
                Modifier.focusRequester(statusRetry).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.DirectionUp -> { back.requestFocus(); true }
                        Key.DirectionDown -> { if (state.total > 0) focus(state.selectedIndex) else back.requestFocus(); true }
                        else -> false
                    }
                })
            Text(statusMessage, color = AtlasMutedText, modifier = Modifier.testTag("${tags}_save_status"))
        }
        if (state.error != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowseSearchButton("Retry", { holder.retry() }, "${tags}_retry", Modifier.focusRequester(retry))
            Text(state.error!!, color = AtlasMutedText, modifier = Modifier.testTag("${tags}_error"))
        }
        if (state.initialized && state.total == 0) Text(emptyMessage ?: "No $plural yet. Refresh your library in Settings.", color = AtlasMutedText, modifier = Modifier.testTag("${tags}_empty"))
        if (!state.initialized && state.loading) Text("Loading $title…", color = AtlasMutedText, modifier = Modifier.testTag("${tags}_loading"))
        if (state.total > 0) {
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().onFocusChanged { gridHasFocus = it.hasFocus }.testTag("${tags}_grid"),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(4.dp)) {
                items(state.total, key = { it }) { index ->
                    val item = state.item(index)
                    val requester = remember(index) { FocusRequester() }
                    DisposableEffect(requester, index) {
                        requesters[index] = requester
                        onDispose { if (requesters[index] === requester) requesters.remove(index) }
                    }
                    var focused by remember { mutableStateOf(false) }
                    LaunchedEffect(item, focused) { if (focused && item != null && holder.state.value.selectedIndex == index) holder.select(index) }
                    val shape = RoundedCornerShape(8.dp)
                    Column(Modifier.height(252.dp).clip(shape).background(AtlasSurface)
                        .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
                        .focusRequester(requester).onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) { focusedIndex = index; holder.select(index); onNavigationChanged() }
                        }.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.Enter, Key.DirectionCenter -> {
                                    if (item != null) { holder.select(index); onNavigationChanged(); onItem(item) }
                                    else if (state.error != null) holder.retry()
                                    true
                                }
                                Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown -> {
                                    val direction = when (event.key) {
                                        Key.DirectionLeft -> CatalogGridDirection.Left; Key.DirectionRight -> CatalogGridDirection.Right
                                        Key.DirectionUp -> CatalogGridDirection.Up; Key.DirectionDown -> CatalogGridDirection.Down
                                        Key.PageUp -> CatalogGridDirection.PageUp; else -> CatalogGridDirection.PageDown
                                    }
                                    if (item == null && state.error != null && direction == CatalogGridDirection.Up) retry.requestFocus()
                                    else catalogGridTarget(index, state.total, columns, direction)?.let(::focus) ?: back.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        }.focusable().clickable {
                            if (item != null) { holder.select(index); onItem(item) } else if (state.error != null) holder.retry()
                        }.testTag("${tags}_tile_$index")
                        .semantics { contentDescription = item?.let(nameOf) ?: "Loading $noun ${index + 1}" }) {
                        if (item != null) AtlasFallbackPoster(nameOf(item), categoryOf(item), "", imageOf(item), null,
                            Modifier.weight(1f).fillMaxWidth(), showText = false, showAccent = false, artworkPadding = 0.dp, dimArtwork = false)
                        else Box(Modifier.weight(1f).fillMaxWidth().background(AtlasSurfaceFocused), contentAlignment = Alignment.Center) {
                            Text(if (state.error != null) "Select to retry" else "Loading…", color = AtlasMutedText, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(displayTitle(item?.let(nameOf) ?: ""), color = AtlasText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = if (showSubtitle) 1 else 2, overflow = TextOverflow.Ellipsis)
                            if (showSubtitle && item != null) Text(categoryOf(item), color = AtlasMutedText, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
