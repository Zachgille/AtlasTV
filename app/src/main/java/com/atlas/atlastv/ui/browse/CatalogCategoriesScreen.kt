package com.atlas.atlastv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.BrowseSearchButton
import com.atlas.atlastv.ui.browse.cleanBrowseRowTitle
import com.atlas.atlastv.ui.theme.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun CatalogCategoriesScreen(holder: CatalogCategoriesStateHolder, revision: Long, title: String, noun: String, plural: String, tags: String, onBack: () -> Unit, onCategory: (CatalogCategory) -> Unit, onNavigationChanged: () -> Unit = {}) {
    val state by holder.state.collectAsState()
    val list = rememberLazyListState(holder.scrollIndex, holder.scrollOffset)
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val back = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }
    var categoryHadFocus by remember { mutableStateOf(false) }
    var focusedName by remember { mutableStateOf<String?>(null) }
    var restoreAfterRefresh by remember { mutableStateOf(false) }
    fun focus(index: Int) { target = state.items.getOrNull(index)?.id }
    BackHandler { onBack() }
    LaunchedEffect(holder, revision) {
        restoreAfterRefresh = entered && categoryHadFocus
        holder.activate(revision)
    }
    LaunchedEffect(Unit) { back.requestFocus() }
    LaunchedEffect(state.items, state.initialized, state.loading) {
        if (state.loading) return@LaunchedEffect
        if (state.initialized && state.items.isNotEmpty() && !entered) {
            entered = true
            target = holder.selectedName?.takeIf { name -> state.items.any { it.id == name } } ?: state.items.first().id
        } else if ((restoreAfterRefresh || categoryHadFocus) && state.items.isNotEmpty() && state.items.none { it.id == focusedName }) {
            target = holder.selectedName?.takeIf { name -> state.items.any { it.id == name } } ?: state.items.first().id
        }
        if (state.error == null) restoreAfterRefresh = false
        if (state.initialized && state.items.isEmpty()) back.requestFocus()
    }
    LaunchedEffect(target, state.items) {
        val name = target ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.id == name }
        if (index < 0) { target = null; return@LaunchedEffect }
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        val requester = snapshotFlow { requesters[name] }.filterNotNull().first()
        withFrameNanos { }
        requester.requestFocus()
        target = null
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            holder.scrollIndex = index; holder.scrollOffset = offset; onNavigationChanged()
        }
    }
    Column(Modifier.fillMaxSize().background(AtlasBackground).padding(horizontal = 56.dp, vertical = 28.dp)
        .semantics { testTagsAsResourceId = true }.testTag("${tags}_categories_screen"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BrowseSearchButton("Back", onBack, "${tags}_categories_back", Modifier.focusRequester(back).onFocusChanged {
                if (it.isFocused) categoryHadFocus = false
            }.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                    if (state.items.isNotEmpty()) focus(state.items.indexOfFirst { row -> row.id == holder.selectedName }.coerceAtLeast(0))
                    else if (state.error != null) retry.requestFocus()
                    true
                } else false
            })
            Text(title, color = AtlasText, style = MaterialTheme.typography.headlineMedium)
        }
        if (state.loading) Text("Loading categories…", color = AtlasMutedText, modifier = Modifier.testTag("${tags}_categories_loading"))
        if (state.error != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrowseSearchButton("Retry", { restoreAfterRefresh = false; holder.retry() }, "${tags}_categories_retry", Modifier.focusRequester(retry).onFocusChanged {
                if (it.isFocused) categoryHadFocus = false
            }.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { back.requestFocus(); true } else false
            })
            Text(state.error!!, color = AtlasMutedText, modifier = Modifier.testTag("${tags}_categories_error"))
        }
        if (state.initialized && state.items.isEmpty()) Text("No $noun categories yet.", color = AtlasMutedText, modifier = Modifier.testTag("${tags}_categories_empty"))
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("${tags}_categories_list"),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            itemsIndexed(state.items, key = { _, category -> category.id }) { index, category ->
                val requester = remember(category.id) { FocusRequester() }
                DisposableEffect(category.id, requester) {
                    requesters[category.id] = requester
                    onDispose { if (requesters[category.id] === requester) requesters.remove(category.id) }
                }
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(10.dp)
                Row(Modifier.fillMaxWidth().height(64.dp).background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
                    .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
                    .focusRequester(requester).onFocusChanged {
                        focused = it.isFocused
                        if (focused) { holder.selectedName = category.id; focusedName = category.id; categoryHadFocus = true; onNavigationChanged() }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionUp -> { if (index == 0) { restoreAfterRefresh = false; back.requestFocus() } else focus(index - 1); true }
                            Key.DirectionDown -> { focus(minOf(index + 1, state.items.lastIndex)); true }
                            Key.PageDown -> { focus(minOf(index + 10, state.items.lastIndex)); true }
                            Key.PageUp -> { focus(maxOf(0, index - 10)); true }
                            Key.DirectionLeft -> { restoreAfterRefresh = false; back.requestFocus(); true }
                            Key.DirectionRight -> true
                            Key.Enter, Key.DirectionCenter -> { onCategory(category); true }
                            else -> false
                        }
                    }.focusable().clickable { onCategory(category) }.testTag("${tags}_category_choice_$index")
                    .semantics { contentDescription = category.name }.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(cleanBrowseRowTitle(category.name).ifBlank { "Other ${plural.replaceFirstChar { it.uppercase() }}" }, color = AtlasText,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${category.itemCount} ${if (category.itemCount == 1) noun else plural}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
