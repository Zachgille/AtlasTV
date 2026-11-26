package com.atlas.atlastv.ui.search

import com.atlas.atlastv.ui.catalog.displayTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.components.AtlasPosterCard
import com.atlas.atlastv.ui.components.AtlasFallbackPoster
import androidx.compose.ui.draw.clip
import com.atlas.atlastv.ui.browse.CatalogGridScreen
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText
import kotlinx.coroutines.launch

@Composable
fun UnifiedSearchScreen(
    onBack: () -> Unit,
    onMovieSelected: (MovieItem) -> Unit,
    onSeriesSelected: (SeriesItem) -> Unit,
    modifier: Modifier = Modifier,
    searchStateHolder: UnifiedSearchStateHolder? = null,
) {
    val searchRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val context = LocalContext.current.applicationContext
    val actualSearchStateHolder = searchStateHolder ?: remember(context, scope) { UnifiedSearchStateHolder(context, scope) }
    val query = actualSearchStateHolder.query
    var resultsRequested by remember { mutableStateOf(false) }
    DisposableEffect(actualSearchStateHolder) {
        onDispose { actualSearchStateHolder.checkpoint(); actualSearchStateHolder.cancel() }
    }
    LaunchedEffect(actualSearchStateHolder, actualSearchStateHolder.catalogVersion, actualSearchStateHolder.activeGrid,
        actualSearchStateHolder.restorationReady, actualSearchStateHolder.contextKey()) {
        actualSearchStateHolder.activate()
        if (actualSearchStateHolder.restorationReady && actualSearchStateHolder.activeGrid == null && !actualSearchStateHolder.restoreResult && actualSearchStateHolder.restoreEntryRowId == null) searchRequester.requestFocus()
    }
    BackHandler(enabled = actualSearchStateHolder.activeGrid == null) {
        if (query.isNotBlank()) {
            resultsRequested = false
            actualSearchStateHolder.search("")
            searchRequester.requestFocus()
        } else {
            onBack()
        }
    }
    val state = actualSearchStateHolder.state
    val firstResultKey = (state as? UnifiedSearchState.Ready)
        ?.rows
        ?.firstOrNull()
        ?.items
        ?.firstOrNull()
        ?.id
    val firstResultRequester = remember(firstResultKey) { FocusRequester() }
    LaunchedEffect(state, resultsRequested) {
        if (actualSearchStateHolder.activeGrid == null && state is UnifiedSearchState.Ready && state.rows.isEmpty() && actualSearchStateHolder.restorationReady) searchRequester.requestFocus()
        if (actualSearchStateHolder.activeGrid == null && state is UnifiedSearchState.Error && (resultsRequested || actualSearchStateHolder.restoreResult || actualSearchStateHolder.restoreEntryRowId != null)) {
            firstResultRequester.requestFocus()
            resultsRequested = false
            actualSearchStateHolder.restoreResult = false
            actualSearchStateHolder.restoreEntryRowId = null
        }
    }

    actualSearchStateHolder.activeGrid?.let { grid ->
        androidx.compose.runtime.key(actualSearchStateHolder.contextKey(), grid.query, grid.rowId) {
        CatalogGridScreen(grid.holder, actualSearchStateHolder.catalogVersion,
            "${grid.title}: ${grid.query}", "result", "matches", "search_all",
            { it.title }, { it.subtitle }, { it.imageRef },
            onBack = actualSearchStateHolder::closeAll, onNavigationChanged = actualSearchStateHolder::checkpoint, onItem = { item ->
                actualSearchStateHolder.selectResult(item)
                keyboard?.hide()
                focusManager.clearFocus(force = true)
                when (item) {
                    is UnifiedSearchItem.Movie -> onMovieSelected(item.movie)
                    is UnifiedSearchItem.Show -> onSeriesSelected(item.series)
                }
            })
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AtlasBackground)
            .padding(horizontal = 56.dp, vertical = 32.dp)
            .semantics { testTagsAsResourceId = true }
            .testTag("unified_search_screen")
            .semantics { contentDescription = "unified_search_screen" },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchButton("Back", onBack, "unified_search_back", Modifier.focusRequester(backRequester).focusProperties { down = searchRequester; right = clearRequester })
            Column {
                Text("Search", color = AtlasText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
        UnifiedSearchField(
            query = query,
            backRequester = backRequester,
            clearRequester = clearRequester,
            focusRequester = searchRequester,
            firstResultRequester = firstResultRequester,
            hasResults = (state as? UnifiedSearchState.Ready)?.rows?.isNotEmpty() == true,
            canRequestResults = query.isNotBlank() && (state !is UnifiedSearchState.Ready || state.rows.isNotEmpty()),
            onRequestResults = {
                keyboard?.hide()
                resultsRequested = true
            },
            onQueryChange = {
                resultsRequested = false
                actualSearchStateHolder.search(it)
            },
            onClear = {
                resultsRequested = false
                actualSearchStateHolder.search("")
                searchRequester.requestFocus()
            },
        )
        when (state) {
            UnifiedSearchState.Loading -> SearchStatus("Searching Movies and Shows…", "unified_search_loading")
            is UnifiedSearchState.Error -> {
                SearchStatus(state.message, "unified_search_error")
                SearchButton("Retry", {
                    searchRequester.requestFocus()
                    resultsRequested = true
                    actualSearchStateHolder.search(query)
                }, "unified_search_retry",
                    Modifier.focusRequester(firstResultRequester))
            }
            is UnifiedSearchState.Ready -> when {
                state.query.isBlank() -> Unit
                state.rows.isEmpty() -> SearchStatus("No matches. Try a shorter title or a different word.", "unified_search_empty")
                else -> UnifiedSearchRows(
                    rows = state.rows,
                    searchRequester = searchRequester,
                    firstResultRequester = firstResultRequester,
                    holder = actualSearchStateHolder,
                    enterFirst = resultsRequested,
                    onFirstEntered = { resultsRequested = false },
                    onItemSelected = { item ->
                        actualSearchStateHolder.selectResult(item)
                        keyboard?.hide()
                        focusManager.clearFocus(force = true)
                        when (item) {
                            is UnifiedSearchItem.Movie -> onMovieSelected(item.movie)
                            is UnifiedSearchItem.Show -> onSeriesSelected(item.series)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UnifiedSearchField(
    query: String,
    backRequester: FocusRequester,
    clearRequester: FocusRequester,
    focusRequester: FocusRequester,
    firstResultRequester: FocusRequester,
    hasResults: Boolean,
    canRequestResults: Boolean,
    onRequestResults: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    var fieldFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(10.dp)
            .testTag("unified_search_bar")
            .semantics { contentDescription = "unified_search_bar" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .background(if (fieldFocused) AtlasSurfaceFocused else AtlasBackground, RoundedCornerShape(8.dp))
                .border(BorderStroke(if (fieldFocused) 3.dp else 1.dp, if (fieldFocused) AtlasAccent else AtlasSurfaceFocused), RoundedCornerShape(8.dp))
                .testTag("unified_search_input_outline")
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { backRequester.requestFocus(); true }
                        Key.DirectionDown -> if (canRequestResults) {
                            onRequestResults()
                            true
                        } else {
                            false
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (canRequestResults) onRequestResults() }),
                cursorBrush = SolidColor(AtlasAccent),
                textStyle = TextStyle(
                    color = AtlasText,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .focusProperties {
                        up = backRequester
                        right = clearRequester
                        if (hasResults) {
                            down = firstResultRequester
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionDown -> if (canRequestResults) {
                                onRequestResults()
                                true
                            } else {
                                false
                            }
                            else -> false
                        }
                    }
                    .testTag("unified_search_field")
                    .semantics { contentDescription = "unified_search_field" },
                decorationBox = { innerTextField ->
                    if (query.isBlank()) {
                        Text("Search titles or categories", color = AtlasMutedText, style = MaterialTheme.typography.titleMedium)
                    }
                    innerTextField()
                },
            )
        }
        SearchButton("Clear", onClear, "unified_search_clear", Modifier.height(48.dp).focusRequester(clearRequester).focusProperties { left = focusRequester; up = backRequester; if (hasResults) down = firstResultRequester })
    }
}

@Composable
private fun UnifiedSearchRows(
    rows: List<UnifiedSearchRow>, searchRequester: FocusRequester, firstResultRequester: FocusRequester,
    holder: UnifiedSearchStateHolder, enterFirst: Boolean, onFirstEntered: () -> Unit,
    onItemSelected: (UnifiedSearchItem) -> Unit, modifier: Modifier,
) {
    val listState = rememberLazyListState(holder.listIndex, holder.listOffset)
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    var target by remember { mutableStateOf<String?>(null) }
    var sequence by remember { mutableStateOf(0) }
    var preserveScroll by remember { mutableStateOf(false) }
    val ownerQuery = holder.query
    fun request(key: String) { preserveScroll = false; target = key; sequence++ }
    LaunchedEffect(enterFirst, rows) {
        if (enterFirst) rows.firstOrNull()?.items?.firstOrNull()?.let { request(it.id) }
    }
    LaunchedEffect(Unit) {
        val entry = holder.restoreEntryRowId
        if (entry != null) request("entry:$entry")
        else if (holder.restoreResult) {
            request(holder.selectedResultId?.takeIf { id -> rows.any { row -> row.items.any { it.id == id } } }
                ?: rows.first().items.first().id)
            preserveScroll = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (holder.query == ownerQuery) { holder.listIndex = index; holder.listOffset = offset; holder.checkpoint() }
        }
    }
    LaunchedEffect(target, sequence) {
        val key = target ?: return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { row -> key == "entry:${row.id}" || row.items.any { it.id == key } }
        if (rowIndex < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.filter { it }.first()
        if (!preserveScroll || listState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) listState.scrollToItem(rowIndex)
        val requester = snapshotFlow { requesters[key] }.filterNotNull().first()
        withFrameNanos { }
        requester.requestFocus()
        target = null; holder.restoreResult = false; holder.restoreEntryRowId = null; onFirstEntered()
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().testTag("unified_search_results")
        .semantics { contentDescription = "unified_search_results" },
        verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        itemsIndexed(rows, key = { _, row -> row.id }) { rowIndex, row ->
            val moveUp: () -> Unit = { if (rowIndex == 0) searchRequester.requestFocus() else request(rows[rowIndex - 1].items.first().id) }
            val moveDown: () -> Unit = { if (rowIndex < rows.lastIndex) request(rows[rowIndex + 1].items.first().id) }
            UnifiedSearchRowView(row, firstResultRequester.takeIf { rowIndex == 0 }, holder,
                target, requesters, ::request, moveUp, moveDown, onItemSelected)
        }
    }
}

@Composable
private fun UnifiedSearchRowView(
    row: UnifiedSearchRow, firstFocusRequester: FocusRequester?, holder: UnifiedSearchStateHolder,
    target: String?, requesters: MutableMap<String, FocusRequester>, request: (String) -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit, onItemSelected: (UnifiedSearchItem) -> Unit,
) {
    val savedScroll = holder.rowScroll[row.id] ?: (0 to 0)
    val scroll = rememberLazyListState(savedScroll.first, savedScroll.second)
    val canExpand = holder.supportsContinuation && row.totalCount > row.items.size
    val entryKey = "entry:${row.id}"
    val entryRequester = remember { FocusRequester() }
    val ownerQuery = holder.query
    val keyboard = LocalSoftwareKeyboardController.current
    if (canExpand) DisposableEffect(entryRequester) {
        requesters[entryKey] = entryRequester
        onDispose { requesters.remove(entryKey) }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.collect {
            if (holder.query == ownerQuery) { holder.rowScroll[row.id] = it; holder.checkpoint() }
        }
    }
    LaunchedEffect(target) {
        val index = row.items.indexOfFirst { it.id == target }
        if (index >= 0) {
            snapshotFlow { scroll.layoutInfo.visibleItemsInfo.isNotEmpty() }.filter { it }.first()
            if (scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
        }
    }
    Column(Modifier.fillMaxWidth().testTag(row.id).semantics { contentDescription = row.id },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, color = AtlasText, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("unified_search_row_title"))
            if (canExpand) SearchButton("See All", { keyboard?.hide(); holder.openAll(row.id) }, "${row.id}_all",
                Modifier.height(42.dp).focusRequester(entryRequester).onFocusChanged { if (it.isFocused) holder.rememberEntryFocus(row.id) }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionDown, Key.DirectionRight -> { request(row.items.first().id); true }
                        Key.DirectionUp -> { onMoveUp(); true }
                        else -> false
                    }
                })
        }
        LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                val requester = remember(item.id) { if (index == 0 && firstFocusRequester != null) firstFocusRequester else FocusRequester() }
                DisposableEffect(item.id, requester) {
                    requesters[item.id] = requester
                    onDispose { if (requesters[item.id] === requester) requesters.remove(item.id) }
                }
                UnifiedSearchCard(item, Modifier.focusRequester(requester).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && canExpand &&
                        ((event.key == Key.DirectionLeft && index == 0) || (event.key == Key.DirectionRight && index == row.items.lastIndex))) {
                        request(entryKey); true
                    } else false
                }, { onItemSelected(item) }, onMoveUp, onMoveDown, onFocused = { holder.rememberResultFocus(item) })
            }
        }
    }
}

@Composable
private fun UnifiedSearchCard(
    item: UnifiedSearchItem,
    modifier: Modifier,
    onSelected: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val resultTag = if (focused) "focused_unified_search_result_${item.id}" else "unified_search_result_${item.id}"
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(156.dp)
            .height(240.dp)
            .clip(shape)
            .background(AtlasSurface)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { onMoveUp(); true }
                    Key.DirectionDown -> { onMoveDown(); true }
                    Key.DirectionCenter, Key.Enter -> { onSelected(); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onSelected)
            .testTag(resultTag)
            .semantics { contentDescription = resultTag },
    ) {
        Column(Modifier.fillMaxSize()) {
            AtlasFallbackPoster(item.title, item.subtitle, item.badge, item.imageRef, null,
                Modifier.weight(1f).fillMaxWidth(), showText = false, showAccent = false, artworkPadding = 0.dp, dimArtwork = false)
            Text(displayTitle(item.title), color = AtlasText, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun SearchStatus(text: String, testTag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(20.dp)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SearchButton(text: String, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(118.dp)
            .height(54.dp)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (event.nativeKeyEvent.repeatCount == 0) onClick()
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
