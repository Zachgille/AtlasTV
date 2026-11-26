package com.atlas.atlastv.ui.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    stateHolder: LiveTvCatalogStateHolder,
    onBack: () -> Unit,
    onChannelSelected: (LiveTvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val readyState = stateHolder.state as? LiveTvCatalogState.Ready
    BackHandler(enabled = readyState?.searchQuery?.isNotBlank() == true) {
        stateHolder.clearSearch()
    }

    LaunchedEffect(stateHolder) {
        stateHolder.ensureCachedCatalog()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AtlasBackground)
            .padding(horizontal = 56.dp, vertical = 24.dp)
            .testTag("live_tv_screen")
            .semantics { contentDescription = "live_tv_screen" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveTvButton(text = "Back", onClick = onBack, width = 112, testTag = "live_tv_back")
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Live TV", color = AtlasText, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(text = "Channels", color = AtlasMutedText, style = MaterialTheme.typography.titleMedium)
            }
            LiveTvButton(text = "Update", onClick = stateHolder::refreshRealCatalog, width = 140, testTag = "live_tv_refresh_action")
        }

        when (val state = stateHolder.state) {
            LiveTvCatalogState.Loading -> LiveTvStatus("Loading cached Live TV catalog", "live_tv_loading")
            is LiveTvCatalogState.Error -> LiveTvStatus(state.message, "live_tv_error")
            is LiveTvCatalogState.Refreshing -> {
                LiveTvStatus("Refreshing Live TV catalog", "live_tv_refresh_status")
                state.previous?.let { ready ->
                    LiveTvBrowser(
                        ready = ready.copy(refreshStatus = "Refreshing..."),
                        onCategorySelected = stateHolder::selectCategory,
                        onChannelFocused = stateHolder::rememberChannel,
                        onChannelSelected = onChannelSelected,
                        onSearchQueryChanged = stateHolder::onSearchQueryChanged,
                        onClearSearch = stateHolder::clearSearch,
                        onToggleFavorite = stateHolder::toggleFavoriteForSelectedChannel,
                        onClearFavorites = stateHolder::clearFavorites,
                        onCycleSort = stateHolder::cycleSortMode,
                        onResetPreferences = stateHolder::resetLiveTvPreferences,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is LiveTvCatalogState.Ready -> {
                val visibleChannels = state.visibleChannels()
                Text(
                    text = "${state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name ?: "All"}  /  ${visibleChannels.size} channels  /  ${state.sortMode.displayName}",
                    color = AtlasMutedText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("live_tv_metadata").semantics { contentDescription = "live_tv_metadata" },
                )
                state.refreshStatus?.let { status ->
                    Text(
                        text = status,
                        color = AtlasMutedText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("live_tv_refresh_status").semantics { contentDescription = "live_tv_refresh_status" },
                    )
                }
                LiveTvBrowser(
                    ready = state,
                    onCategorySelected = stateHolder::selectCategory,
                    onChannelFocused = stateHolder::rememberChannel,
                    onChannelSelected = onChannelSelected,
                    onSearchQueryChanged = stateHolder::onSearchQueryChanged,
                    onClearSearch = stateHolder::clearSearch,
                    onToggleFavorite = stateHolder::toggleFavoriteForSelectedChannel,
                    onClearFavorites = stateHolder::clearFavorites,
                    onCycleSort = stateHolder::cycleSortMode,
                    onResetPreferences = stateHolder::resetLiveTvPreferences,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiveTvBrowser(
    ready: LiveTvCatalogState.Ready,
    onCategorySelected: (String) -> Unit,
    onChannelFocused: (LiveTvChannel) -> Unit,
    onChannelSelected: (LiveTvChannel) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClearFavorites: () -> Unit,
    onCycleSort: () -> Unit,
    onResetPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categoryFocusRequester = remember { FocusRequester() }
    val channelFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val favoriteFocusRequester = remember { FocusRequester() }
    val channels = ready.visibleChannels()
    val details = ready.selectedChannelDetails()
    val rows = ready.channelRows()
    val selectedCategoryIndex = ready.categories.indexOfFirst { it.id == ready.selectedCategoryId }.coerceAtLeast(0)

    LaunchedEffect(ready.selectedCategoryId, ready.selectedChannelStreamId, channels.size) {
        if (channels.isNotEmpty()) {
            channelFocusRequester.requestFocus()
        } else {
            categoryFocusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        details?.let { LiveTvChannelDetailsPanel(details = it) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("live_tv_category_list")
                .semantics { contentDescription = "live_tv_category_list" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ready.categories.forEachIndexed { index, category ->
                LiveTvCategoryRow(
                    category = category,
                    selected = category.id == ready.selectedCategoryId,
                    modifier = if (index == selectedCategoryIndex) {
                        Modifier.focusRequester(categoryFocusRequester)
                    } else {
                        Modifier
                    },
                    onClick = { onCategorySelected(category.id) },
                    onMoveRight = { searchFocusRequester.requestFocus() },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveTvSearchField(
                query = ready.searchQuery,
                onQueryChanged = onSearchQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester),
                onMoveDown = { if (channels.isNotEmpty()) channelFocusRequester.requestFocus() },
                onMoveLeft = { categoryFocusRequester.requestFocus() },
            )
            LiveTvButton(text = "Clear", onClick = onClearSearch, width = 132, testTag = "live_tv_clear_search")
            LiveTvButton(
                text = if (details?.isFavorite == true) "Unfavorite" else "Favorite",
                onClick = onToggleFavorite,
                width = 176,
                testTag = "live_tv_favorite_toggle",
                modifier = Modifier.focusRequester(favoriteFocusRequester),
            )
            LiveTvButton(text = "Sort", onClick = onCycleSort, width = 112, testTag = "live_tv_sort_toggle")
            LiveTvButton(text = "Clear fav", onClick = onClearFavorites, width = 148, testTag = "live_tv_clear_favorites")
            LiveTvButton(text = "Reset", onClick = onResetPreferences, width = 124, testTag = "live_tv_reset_preferences")
        }
        val resultLabel = if (ready.searchQuery.isBlank()) {
            "Showing ${channels.size} channels"
        } else {
            "Search results: ${channels.size}"
        }
        Text(
            text = resultLabel,
            color = AtlasMutedText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .testTag("live_tv_search_result_count")
                .semantics { contentDescription = "live_tv_search_result_count" },
        )

        if (channels.isEmpty()) {
            LiveTvStatus(
                text = when {
                    ready.searchQuery.isNotBlank() -> "No channels match this search."
                    ready.selectedCategoryId == LiveTvCategoryIds.Favorites -> "No favorite channels yet."
                    else -> "No channels in this category."
                },
                testTag = when {
                    ready.searchQuery.isNotBlank() -> "live_tv_search_empty"
                    ready.selectedCategoryId == LiveTvCategoryIds.Favorites -> "live_tv_favorites_empty"
                    else -> "live_tv_empty"
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(if (ready.searchQuery.isBlank()) "live_tv_channel_list" else "live_tv_filtered_channel_list")
                    .semantics { contentDescription = if (ready.searchQuery.isBlank()) "live_tv_channel_list" else "live_tv_filtered_channel_list" },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val primarySelectedRowId = rows.firstOrNull { row ->
                    row.channels.any { it.streamId == ready.selectedChannelStreamId }
                }?.id
                items(rows, key = { it.id }) { row ->
                    LiveTvChannelRowGroupView(
                        row = row,
                        ready = ready,
                        channelFocusRequester = channelFocusRequester,
                        favoriteFocusRequester = favoriteFocusRequester,
                        onChannelFocused = onChannelFocused,
                        onChannelSelected = onChannelSelected,
                        primarySelectedRowId = primarySelectedRowId,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvChannelRowGroupView(
    row: LiveTvChannelRowGroup,
    ready: LiveTvCatalogState.Ready,
    channelFocusRequester: FocusRequester,
    favoriteFocusRequester: FocusRequester,
    onChannelFocused: (LiveTvChannel) -> Unit,
    onChannelSelected: (LiveTvChannel) -> Unit,
    primarySelectedRowId: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_tv_row_${row.id}")
            .semantics { contentDescription = "live_tv_row_${row.id}" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.title,
            color = AtlasText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(row.channels, key = { _, channel -> "${row.id}-${channel.streamId}" }) { index, channel ->
                val isSelected = channel.streamId == ready.selectedChannelStreamId
                LiveTvChannelCard(
                    channel = channel,
                    selected = isSelected,
                    favorite = channel.streamId in ready.favoriteStreamIds,
                    exposePrimaryStateTag = row.id == primarySelectedRowId,
                    modifier = if (isSelected || (row.id == LiveTvRowIds.Recent && index == 0)) {
                        Modifier.focusRequester(channelFocusRequester)
                    } else {
                        Modifier
                    },
                    onFocused = { onChannelFocused(channel) },
                    onClick = { onChannelSelected(channel) },
                    onMoveRight = { favoriteFocusRequester.requestFocus() },
                    searchActive = ready.searchQuery.isNotBlank(),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvChannelCard(
    channel: LiveTvChannel,
    selected: Boolean,
    favorite: Boolean,
    exposePrimaryStateTag: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveRight: () -> Unit,
    searchActive: Boolean,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val tag = if (favorite && exposePrimaryStateTag) {
        "live_tv_favorite_channel_row"
    } else if (selected && exposePrimaryStateTag) {
        if (searchActive) "live_tv_selected_search_result" else "live_tv_selected_channel"
    } else {
        "live_tv_channel_${channel.streamId}"
    }

    Column(
        modifier = modifier
            .width(360.dp)
            .height(150.dp)
            .background(if (isFocused || selected) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 4.dp else 1.dp, if (isFocused || selected) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(tag)
            .semantics { contentDescription = tag },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = channel.name,
            color = AtlasText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = channel.categoryName, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                text = if (favorite) "Favorite" else "Live channel",
                color = if (favorite) AtlasAccent else AtlasMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("live_tv_favorite_state"),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvChannelDetailsPanel(details: LiveTvChannelDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, AtlasSurfaceFocused), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("live_tv_channel_details_panel")
            .semantics { contentDescription = "live_tv_channel_details_panel" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = details.channelName,
            color = AtlasText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("live_tv_details_channel_name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Category: ${details.categoryName}",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("live_tv_details_category"),
            )
            Text(text = "Selected: ${details.selectedIndex}/${details.visibleCount}", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Sort: ${details.sortModeLabel}",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("live_tv_details_sort_mode"),
            )
            Text(
                text = if (details.isFavorite) "Favorite: Yes" else "Favorite: No",
                color = if (details.isFavorite) AtlasAccent else AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("live_tv_details_favorite_state"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Catalog details hidden",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("live_tv_details_stream_id"),
            )
            Text(
                text = "Diagnostics hidden in normal mode",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("live_tv_details_redacted_url"),
            )
            Text(text = details.playbackHint, color = AtlasAccent, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    BasicTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        textStyle = TextStyle(color = AtlasText, fontSize = 20.sp),
        cursorBrush = SolidColor(AtlasAccent),
        modifier = modifier
            .height(46.dp)
            .background(if (isFocused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when (event.key) {
                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }
                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("live_tv_search_field")
            .semantics { contentDescription = "live_tv_search_field" },
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isBlank()) {
                    Text(
                        text = "Search channels",
                        color = AtlasMutedText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvStatus(text: String, testTag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(20.dp)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
    ) {
        Text(text = text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvCategoryRow(
    category: LiveTvCategory,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onMoveRight: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .width(320.dp)
            .height(56.dp)
            .background(if (isFocused || selected) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused || selected) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when (event.key) {
                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag("live_tv_category_${category.id}")
            .semantics { contentDescription = "live_tv_category_${category.id}" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = category.name, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = category.channelCount.toString(),
            color = AtlasMutedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = if (category.id == LiveTvCategoryIds.Favorites) {
                Modifier.testTag("live_tv_favorites_count")
            } else {
                Modifier
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvChannelRow(
    channel: LiveTvChannel,
    selected: Boolean,
    favorite: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    searchActive: Boolean,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val tag = if (favorite) {
        "live_tv_favorite_channel_row"
    } else if (selected) {
        if (searchActive) "live_tv_selected_search_result" else "live_tv_selected_channel"
    } else {
        "live_tv_channel_${channel.streamId}"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFocused || selected) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused || selected) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                handleChannelKeyEvent(event.type, event.key, onMoveLeft, onMoveRight, onClick)
            }
            .onKeyEvent { event ->
                handleChannelKeyEvent(event.type, event.key, onMoveLeft, onMoveRight, onClick)
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(tag)
            .semantics { contentDescription = tag },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = channel.name, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = channel.categoryName, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (favorite) "Favorite" else "Not favorite",
                color = if (favorite) AtlasAccent else AtlasMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("live_tv_favorite_state"),
            )
        }
    }
}

private fun handleChannelKeyEvent(
    type: KeyEventType,
    key: Key,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onClick: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown) return false
    return when (key) {
        Key.DirectionLeft -> {
            onMoveLeft()
            true
        }
        Key.DirectionRight -> {
            onMoveRight()
            true
        }
        Key.DirectionCenter, Key.Enter -> {
            onClick()
            true
        }
        else -> false
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvButton(
    text: String,
    onClick: () -> Unit,
    width: Int,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .width(width.dp)
            .height(46.dp)
            .background(if (isFocused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}
