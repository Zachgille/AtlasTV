package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.catalog.displayTitle
import kotlinx.coroutines.flow.first
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchButton
import com.atlas.atlastv.ui.browse.BrowseSearchOverlay
import com.atlas.atlastv.ui.browse.seriesBrowseRows
import com.atlas.atlastv.ui.browse.seriesSearchBrowseRows
import com.atlas.atlastv.ui.components.AtlasPosterCard
import com.atlas.atlastv.ui.components.AtlasBackdrop
import com.atlas.atlastv.ui.components.AtlasCardProgressBar
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesScreen(
    stateHolder: SeriesCatalogStateHolder,
    onBack: () -> Unit,
    onEpisodeSelected: (SeriesItem, SeriesEpisodeItem) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    openSelectedSeriesDetailsRequest: Int = 0,
    selectedSeriesForDetails: SeriesItem? = null,
    gridHolder: SeriesGridStateHolder? = null,
    onDetailsClosed: (() -> Unit)? = null,
    selectedEpisodeForDetails: SeriesEpisodeItem? = null,
    onPlaybackEpisodeRestored: () -> Unit = {},
    categoryBrowser: SeriesCategoryBrowser? = null,
    watchlistBrowser: SeriesWatchlistBrowser? = null,
    onStartOver: ((SeriesItem, SeriesEpisodeItem) -> Unit)? = null,
    episodeWatchActions: EpisodeWatchActionsStateHolder? = null,
    browseSession: SeriesBrowseSessionStateHolder? = null,
) {
    if (browseSession != null) {
        LaunchedEffect(browseSession) { browseSession.restore(reopen = selectedSeriesForDetails == null) }
        androidx.compose.runtime.DisposableEffect(browseSession) { onDispose { browseSession.checkpoint() } }
        if (!browseSession.ready) {
            val back = remember { FocusRequester() }
            BackHandler { onBack() }
            LaunchedEffect(Unit) { back.requestFocus() }
            Column(Modifier.fillMaxSize().background(AtlasBackground).padding(48.dp)) {
                BrowseSearchButton("Back", onBack, "series_restore_back", Modifier.focusRequester(back))
            }
            return
        }
    }
    val activeGrid = watchlistBrowser?.grid?.takeIf { it.open } ?: categoryBrowser?.grid?.takeIf { it.open } ?: gridHolder
    var detailMode by remember { mutableStateOf(activeGrid?.detailSeries != null) }
    var gridOpen by remember { mutableStateOf(activeGrid?.open == true) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var handledSeriesDetailsRequest by remember { mutableStateOf(0) }
    var activePreferredSeriesRequest by remember { mutableStateOf(0) }
    var restoredPlaybackEpisode by remember { mutableStateOf<Int?>(null) }
    val searchButtonRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val firstRowRequester = remember { FocusRequester() }
    val shelfNavigation = categoryBrowser?.shelfNavigation ?: remember { com.atlas.atlastv.ui.browse.BrowseShelfNavigation() }
    val searchShelves = remember(searchQuery) { com.atlas.atlastv.ui.browse.BrowseShelfNavigation() }
    val retryRequester = remember { FocusRequester() }
    val detailActionRequester = remember { FocusRequester() }
    var restoreBrowseFocus by remember { mutableStateOf(false) }
    var browseEntryRequest by remember { mutableStateOf(0) }
    val allShowsRequester = remember { FocusRequester() }
    var restoreAllShowsFocus by remember { mutableStateOf(false) }
    val categoriesRequester = remember { FocusRequester() }
    var restoreCategoriesFocus by remember { mutableStateOf(false) }
    var restoreCategoryRow by remember { mutableStateOf<String?>(null) }
    val watchlistRequester = remember { FocusRequester() }
    var restoreWatchlistFocus by remember { mutableStateOf(false) }
    val backRequester = remember { FocusRequester() }
    fun openCategory(name: String, rowId: String? = null) {
        gridHolder?.open = false
        watchlistBrowser?.grid?.open = false
        categoryBrowser?.openCategory(name, rowId)
        gridOpen = categoryBrowser != null
    }
    LaunchedEffect(gridOpen, categoryBrowser?.categoriesOpen, activeGrid) { browseSession?.checkpoint() }
    val navigationChanged: () -> Unit = { browseSession?.checkpoint() }
    fun closeDetails() {
        activePreferredSeriesRequest = 0
        detailMode = false
        activeGrid?.detailSeries = null
        if (!gridOpen) restoreBrowseFocus = true
        onDetailsClosed?.invoke()
    }
    val catalogState = (stateHolder.state as? SeriesCatalogState.Ready)?.let(stateHolder::visibleEpisodeState) ?: stateHolder.state
    val episodeContextKey = stateHolder.episodeContextKey()
    val displayedSeries = activeGrid?.detailSeries ?: selectedSeriesForDetails.takeIf {
        openSelectedSeriesDetailsRequest > 0 && openSelectedSeriesDetailsRequest == handledSeriesDetailsRequest &&
            openSelectedSeriesDetailsRequest == activePreferredSeriesRequest
    } ?: (catalogState as? SeriesCatalogState.Ready)?.let { ready -> ready.series.firstOrNull { it.seriesId == ready.selectedSeriesId } }
    val displayedSeriesAvailable = displayedSeries?.let(stateHolder::availabilityFor) == true
    LaunchedEffect(stateHolder, stateHolder.catalogVersion) { stateHolder.ensureCachedCatalog() }
    LaunchedEffect(gridOpen, detailMode, categoryBrowser?.categoriesOpen) {
        if (!gridOpen && !detailMode && categoryBrowser?.categoriesOpen != true) {
            if (restoreAllShowsFocus) { allShowsRequester.requestFocus(); restoreAllShowsFocus = false }
            else if (restoreWatchlistFocus) { watchlistRequester.requestFocus(); restoreWatchlistFocus = false }
            else if (restoreCategoriesFocus) { categoriesRequester.requestFocus(); restoreCategoriesFocus = false }
            else searchButtonRequester.requestFocus()
        }
    }
    LaunchedEffect(detailMode, displayedSeries?.seriesId, displayedSeriesAvailable,
        (catalogState as? SeriesCatalogState.Ready)?.selectedSeriesId, stateHolder.episodeVersion, episodeContextKey) {
        if (detailMode && displayedSeriesAvailable) stateHolder.ensureEpisodesForSelectedSeries(displayedSeries?.seriesId)
    }
    LaunchedEffect(detailMode, catalogState, selectedEpisodeForDetails?.episodeId) {
        val ready = catalogState as? SeriesCatalogState.Ready
        val episode = selectedEpisodeForDetails
        val loaded = ready?.episodeState as? SeriesEpisodeState.Ready
        if (detailMode && episode != null && restoredPlaybackEpisode != episode.episodeId &&
            ready?.selectedSeriesId == selectedSeriesForDetails?.seriesId && loaded?.episodes?.any { it.episodeId == episode.episodeId } == true) {
            restoredPlaybackEpisode = episode.episodeId
            stateHolder.rememberEpisode(episode)
            onPlaybackEpisodeRestored()
        }
    }
    BackHandler(enabled = detailMode) {
        closeDetails()
    }
    BackHandler(enabled = !detailMode && (searchOpen || searchQuery.isNotBlank())) {
        searchOpen = false
        searchQuery = ""
        stateHolder.clearSearch()
        searchButtonRequester.requestFocus()
    }
    LaunchedEffect(searchOpen, detailMode, gridOpen, categoryBrowser?.categoriesOpen, catalogState is SeriesCatalogState.Ready) {
        if (searchOpen && !gridOpen && !detailMode && categoryBrowser?.categoriesOpen != true && catalogState is SeriesCatalogState.Ready) searchRequester.requestFocus()
    }

    LaunchedEffect(openSelectedSeriesDetailsRequest, selectedSeriesForDetails?.seriesId, catalogState) {
        if (
            openSelectedSeriesDetailsRequest > 0 &&
            openSelectedSeriesDetailsRequest != handledSeriesDetailsRequest &&
            catalogState is SeriesCatalogState.Ready
        ) {
            handledSeriesDetailsRequest = openSelectedSeriesDetailsRequest
            selectedSeriesForDetails?.let {
                stateHolder.rememberSeries(it)
                stateHolder.restoreWatchedEpisodeForDetails()
                if (gridOpen) activeGrid?.detailSeries = it
                activePreferredSeriesRequest = openSelectedSeriesDetailsRequest
                detailMode = true
            }
        }
    }

    if (categoryBrowser?.categoriesOpen == true && !gridOpen && !detailMode) {
        SeriesCategoriesScreen(categoryBrowser.categories, stateHolder.catalogVersion,
            onBack = { categoryBrowser.categoriesOpen = false; restoreCategoriesFocus = true },
            onCategory = { category -> openCategory(category.id) }, onNavigationChanged = navigationChanged)
        return
    }

    if (gridOpen && !detailMode && activeGrid != null) {
        val onGridSeries: (SeriesItem) -> Unit = { series -> stateHolder.rememberSeries(series); stateHolder.restoreWatchedEpisodeForDetails(); activeGrid.detailSeries = series; activePreferredSeriesRequest = 0; detailMode = true }
        if (watchlistBrowser?.grid === activeGrid) {
            SeriesWatchlistGridScreen(activeGrid, stateHolder.catalogVersion,
                catalogReady = catalogState is SeriesCatalogState.Ready,
                statusMessage = stateHolder.saveError ?: "Saving watchlist…".takeIf { stateHolder.watchlistSaving },
                onRetrySave = if (stateHolder.canRetryWatchlistSave) stateHolder::retryWatchlistSave else null,
                onBack = {
                    activeGrid.open = false; gridOpen = false
                    if (watchlistBrowser.originRow) restoreCategoryRow = "series_watchlist" else restoreWatchlistFocus = true
                }, onSeries = onGridSeries, onNavigationChanged = navigationChanged)
        } else if (categoryBrowser?.grid === activeGrid && categoryBrowser.categoryName != null) {
            SeriesCategoryGridScreen(activeGrid, stateHolder.catalogVersion, categoryBrowser.categoryName!!,
                catalogReady = catalogState is SeriesCatalogState.Ready,
                onBack = {
                    activeGrid.open = false; gridOpen = false
                    restoreCategoryRow = categoryBrowser.originRowId
                    categoryBrowser.categoriesOpen = categoryBrowser.originRowId == null
                }, onSeries = onGridSeries, onNavigationChanged = navigationChanged)
        } else AllShowsScreen(activeGrid, stateHolder.catalogVersion,
            catalogReady = catalogState is SeriesCatalogState.Ready,
            onBack = { activeGrid.open = false; restoreAllShowsFocus = true; gridOpen = false; restoreBrowseFocus = false },
            onSeries = onGridSeries, onNavigationChanged = navigationChanged)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AtlasBackground)
            .padding(horizontal = 48.dp, vertical = 24.dp)
            .semantics { testTagsAsResourceId = true }
            .testTag("series_screen")
            .semantics { contentDescription = "series_screen" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowseButton(
                "Back",
                if (detailMode) ({
                    closeDetails()
                }) else onBack,
                "series_back",
                Modifier.focusRequester(backRequester),
            )
            if (!detailMode) {
                BrowseSearchButton(
                    "Search",
                    { searchOpen = true },
                    "series_search_open",
                    modifier = Modifier.focusRequester(searchButtonRequester).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            if (catalogState is SeriesCatalogState.Ready && stateHolder.refreshError != null) retryRequester.requestFocus()
                            else if (catalogState is SeriesCatalogState.Ready && (catalogState.searchResult?.rows ?: catalogState.browseRows).isNotEmpty()) browseEntryRequest++
                            else if (catalogState is SeriesCatalogState.Error) retryRequester.requestFocus()
                            true
                        } else false
                    },
                )
                if (gridHolder != null) BrowseSearchButton("All Shows", {
                    categoryBrowser?.grid?.open = false
                    watchlistBrowser?.grid?.open = false
                    gridHolder.open = true
                    gridOpen = true
                }, "series_all_open", Modifier.focusRequester(allShowsRequester))
            }
            if (!detailMode && watchlistBrowser != null) BrowseSearchButton("Watchlist", {
                categoryBrowser?.grid?.open = false
                gridHolder?.open = false
                watchlistBrowser.originRow = false; watchlistBrowser.grid.open = true; gridOpen = true
            }, "series_watchlist_open", Modifier.focusRequester(watchlistRequester))
            if (!detailMode && categoryBrowser != null) BrowseSearchButton("Categories", {
                gridHolder?.open = false
                watchlistBrowser?.grid?.open = false
                categoryBrowser.categoriesOpen = true
            }, "series_categories_open", Modifier.focusRequester(categoriesRequester))
            if (!detailMode) Text("Shows", color = AtlasText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        when (val state = catalogState) {
            SeriesCatalogState.Loading -> BrowseStatus("Loading Shows", "series_loading")
            is SeriesCatalogState.Error -> {
                BrowseStatus(state.message, "series_error")
                BrowseSearchButton("Retry", {
                    searchButtonRequester.requestFocus()
                    stateHolder.invalidateCachedCatalog()
                }, "series_retry", Modifier.focusRequester(retryRequester))
            }
            is SeriesCatalogState.Ready -> {
                if (stateHolder.isRefreshing) BrowseStatus("Updating Shows…", "series_refreshing")
                stateHolder.refreshError?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrowseSearchButton("Retry", {
                            if (detailMode) detailActionRequester.requestFocus() else searchButtonRequester.requestFocus()
                            stateHolder.invalidateCachedCatalog()
                        }, "series_refresh_retry", Modifier.focusRequester(retryRequester).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionUp -> { if (detailMode) detailActionRequester.requestFocus() else searchButtonRequester.requestFocus(); true }
                                Key.DirectionDown -> { if (detailMode) detailActionRequester.requestFocus() else browseEntryRequest++; true }
                                else -> false
                            }
                        })
                        Text(it, color = AtlasMutedText, modifier = Modifier.testTag("series_refresh_error"))
                    }
                }
                stateHolder.saveError?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (stateHolder.canRetryWatchlistSave) BrowseSearchButton("Retry save", stateHolder::retryWatchlistSave, "series_watchlist_save_retry")
                        BrowseStatus(it, "series_save_error")
                    }
                }
                if (detailMode) {
                    SeriesDetailPage(
                        ready = state,
                        stateHolder = stateHolder,
                        backRequester = backRequester,
                        playRequester = detailActionRequester,
                        preferredSeries = activeGrid?.detailSeries ?: selectedSeriesForDetails.takeIf {
                            openSelectedSeriesDetailsRequest > 0 &&
                                openSelectedSeriesDetailsRequest == handledSeriesDetailsRequest &&
                                openSelectedSeriesDetailsRequest == activePreferredSeriesRequest
                        },
                        onSeasonSelected = stateHolder::selectSeason,
                        onEpisodeFocused = stateHolder::rememberEpisode,
                        onEpisodeSelected = onEpisodeSelected,
                        onToggleWatchlist = stateHolder::toggleWatchlistForSeries,
                        onStartOver = onStartOver,
                        episodeWatchActions = episodeWatchActions,
                        onRetryEpisodes = stateHolder::retryEpisodes,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val sortedSeries = state.series.sortedForSeries(state.sortMode)
                    val searchResult = if (searchQuery.isBlank()) null else seriesSearchBrowseRows(sortedSeries, searchQuery)
                    if (searchOpen || searchQuery.isNotBlank()) {
                        BrowseSearchOverlay(
                            query = searchQuery,
                            resultCount = state.searchResult?.totalCount ?: searchResult?.totalCount ?: state.series.size,
                            focusRequester = searchRequester,
                            onQueryChange = { searchQuery = it },
                            onQueryCommit = stateHolder::onSearchQueryChanged,
                            onClear = {
                                searchQuery = ""
                                stateHolder.clearSearch()
                                searchOpen = false
                                searchButtonRequester.requestFocus()
                            },
                            onBack = {
                                searchQuery = ""
                                stateHolder.clearSearch()
                                searchOpen = false
                                searchButtonRequester.requestFocus()
                            },
                            overlayTag = "series_search_overlay",
                            fieldTag = "series_search_field",
                            clearTag = "series_clear_search",
                            countTag = "series_search_result_count",
                        )
                    }
                    val rows = state.searchResult?.takeIf { searchQuery.isNotBlank() }?.rows
                        ?: searchResult?.rows
                        ?: state.browseRows.takeIf { it.isNotEmpty() }
                        ?: seriesBrowseRows(sortedSeries, state.watchProgress, state.watchlistSeriesIds)
                    if (rows.isEmpty()) {
                        BrowseStatus(if (searchQuery.isBlank()) "No cached shows found." else "No shows match your search.", "series_empty")
                    } else {
                        SeriesRowBrowser(
                            rows = rows,
                            progress = { com.atlas.atlastv.ui.browse.progressForShow(it, state.watchProgress) },
                            firstFocusRequester = firstRowRequester,
                            entryRequest = browseEntryRequest,
                            shelfNavigation = if (searchQuery.isBlank()) shelfNavigation else searchShelves,
                            onNavigationChanged = { if (searchQuery.isBlank()) navigationChanged() },
                            restoreFocus = restoreBrowseFocus,
                            onRestoreFocusConsumed = { restoreBrowseFocus = false },
                            categoryBrowser = categoryBrowser,
                            restoreCategoryRow = restoreCategoryRow,
                            onCategoryRestoreConsumed = { restoreCategoryRow = null },
                            onSeeAll = if (categoryBrowser != null || watchlistBrowser != null) ({ row ->
                                if (row.id == "series_watchlist" && watchlistBrowser != null) {
                                    categoryBrowser?.grid?.open = false
                                    gridHolder?.open = false
                                    watchlistBrowser.originRow = true; watchlistBrowser.grid.open = true; gridOpen = true
                                } else row.categoryKey?.let {
                                    openCategory(it, row.id)
                                }
                            }) else null,
                            previousFocusRequester = if (stateHolder.refreshError != null) retryRequester else if (searchOpen || searchQuery.isNotBlank()) searchRequester else searchButtonRequester,
                            onSeriesFocused = stateHolder::rememberSeries,
                            onSeriesSelected = { series ->
                                activePreferredSeriesRequest = 0
                                stateHolder.rememberSeries(series)
                                stateHolder.restoreWatchedEpisodeForDetails()
                                detailMode = true
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesRowBrowser(
    rows: List<BrowseRow<SeriesItem>>,
    progress: (SeriesItem) -> com.atlas.atlastv.ui.catalog.CardWatchProgress?,
    firstFocusRequester: FocusRequester,
    entryRequest: Int,
    shelfNavigation: com.atlas.atlastv.ui.browse.BrowseShelfNavigation,
    onNavigationChanged: () -> Unit,
    restoreFocus: Boolean,
    onRestoreFocusConsumed: () -> Unit,
    previousFocusRequester: FocusRequester?,
    onSeriesFocused: (SeriesItem) -> Unit,
    onSeriesSelected: (SeriesItem) -> Unit,
    modifier: Modifier,
    categoryBrowser: SeriesCategoryBrowser?,
    restoreCategoryRow: String?,
    onCategoryRestoreConsumed: () -> Unit,
    onSeeAll: ((BrowseRow<SeriesItem>) -> Unit)?,
) {
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val headerRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(categoryBrowser?.browseScrollIndex ?: 0, categoryBrowser?.browseScrollOffset ?: 0)
    rows.forEachIndexed { index, row ->
        rowRequesters[row.id] = if (index == 0) firstFocusRequester
        else rowRequesters[row.id]?.takeUnless { it === firstFocusRequester } ?: FocusRequester()
        headerRequesters.getOrPut(row.id) { FocusRequester() }
    }
    var contentFocused by remember { mutableStateOf(false) }
    var focusedItem by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(entryRequest, restoreFocus, restoreCategoryRow) {
        if (restoreCategoryRow != null) {
            val index = rows.indexOfFirst { it.id == restoreCategoryRow && (it.categoryKey != null || it.id == "series_watchlist") }
            if (index >= 0) {
                list.scrollToItem(index)
                androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == index } }
                    .first { it }
                androidx.compose.runtime.withFrameNanos { }
                headerRequesters[restoreCategoryRow]?.requestFocus()
            } else {
                list.scrollToItem(0)
                androidx.compose.runtime.withFrameNanos { }
                firstFocusRequester.requestFocus()
            }
            onCategoryRestoreConsumed()
        } else if (restoreFocus || entryRequest > 0) {
            val target = shelfNavigation.targetRow(rows.map { it.id })
            val index = rows.indexOfFirst { it.id == target }.coerceAtLeast(0)
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
            androidx.compose.runtime.withFrameNanos { }
            rowRequesters[target]?.requestFocus()
        }
        onRestoreFocusConsumed()
    }
    LaunchedEffect(list, categoryBrowser) {
        if (categoryBrowser != null) androidx.compose.runtime.snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> categoryBrowser.browseScrollIndex = index; categoryBrowser.browseScrollOffset = offset; onNavigationChanged() }
    }
    LaunchedEffect(rows.map { row -> row.id to row.items.map { it.seriesId } }) {
        if (contentFocused && focusedItem != null && rows.none { row -> row.id == focusedItem?.first && row.items.any { it.seriesId == focusedItem?.second } }) {
            firstFocusRequester.requestFocus()
        }
    }
    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { contentFocused = it.hasFocus }
            .testTag("series_row_page")
            .semantics { contentDescription = "series_row_page" },
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.id }) { rowIndex, row ->
            BrowseSeriesRow(
                row = row,
                navigation = shelfNavigation,
                onNavigationChanged = onNavigationChanged,
                progress = progress,
                firstFocusRequester = rowRequesters[row.id],
                previousFocusRequester = rows.getOrNull(rowIndex - 1)?.let { rowRequesters[it.id] }
                    ?: if (rowIndex == 0) previousFocusRequester else null,
                nextFocusRequester = rows.getOrNull(rowIndex + 1)?.let { rowRequesters[it.id] },
                onSeriesFocused = { series ->
                    focusedItem = row.id to series.seriesId
                    shelfNavigation.select(row.id, series.seriesId, row.items.indexOfFirst { it.seriesId == series.seriesId })
                    onNavigationChanged(); onSeriesFocused(series)
                },
                onSeriesSelected = onSeriesSelected,
                seeAllRequester = headerRequesters[row.id],
                onSeeAll = onSeeAll?.takeIf { row.categoryKey != null || row.id == "series_watchlist" }?.let { action -> { action(row) } },
            )
        }
    }
}

@Composable
private fun BrowseSeriesRow(
    row: BrowseRow<SeriesItem>,
    navigation: com.atlas.atlastv.ui.browse.BrowseShelfNavigation,
    onNavigationChanged: () -> Unit,
    progress: (SeriesItem) -> com.atlas.atlastv.ui.catalog.CardWatchProgress?,
    firstFocusRequester: FocusRequester?,
    previousFocusRequester: FocusRequester?,
    nextFocusRequester: FocusRequester?,
    onSeriesFocused: (SeriesItem) -> Unit,
    onSeriesSelected: (SeriesItem) -> Unit,
    seeAllRequester: FocusRequester?,
    onSeeAll: (() -> Unit)?,
) {
    val position = navigation.position(row.id, row.items.map { it.seriesId })
    val artworkScroll = androidx.compose.foundation.lazy.rememberLazyListState(position.scrollIndex, position.scrollOffset)
    LaunchedEffect(artworkScroll, navigation, row.id) {
        androidx.compose.runtime.snapshotFlow { artworkScroll.firstVisibleItemIndex to artworkScroll.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> navigation.scroll(row.id, index, offset); onNavigationChanged() }
    }
    com.atlas.atlastv.ui.media.NearbyArtworkPrefetch(remember(row.items) { row.items.map { it.posterImage } }) {
        artworkScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("horizontal_row")
            .semantics { contentDescription = "horizontal_row" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            row.title,
            color = AtlasText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).testTag("series_row_title").semantics { contentDescription = "series_row_title" },
        )
        if (onSeeAll != null) BrowseSearchButton("See All", onSeeAll, "show_category_see_all_${row.id}",
            Modifier.focusRequester(seeAllRequester ?: FocusRequester()).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionDown, Key.DirectionRight -> { firstFocusRequester?.requestFocus(); true }
                    Key.DirectionUp, Key.DirectionLeft -> { previousFocusRequester?.requestFocus(); true }
                    else -> false
                }
            })
        }
        LazyRow(state = artworkScroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(row.items, key = { _, series -> "${row.id}-${series.seriesId}" }) { index, series ->
                SeriesPoster(
                    series = series,
                    cardProgress = progress(series),
                    modifier = if (index == position.selectedIndex) Modifier.focusRequester(firstFocusRequester ?: FocusRequester()) else Modifier,
                    onFocused = { onSeriesFocused(series) },
                    onSelected = { onSeriesSelected(series) },
                    onMoveUp = { if (onSeeAll != null) seeAllRequester?.requestFocus() else previousFocusRequester?.requestFocus() },
                    onMoveDown = { nextFocusRequester?.requestFocus() },
                    onMoveLeft = if (index == 0 && onSeeAll != null) ({ seeAllRequester?.requestFocus() }) else null,
                )
            }
        }
    }
}

@Composable
private fun SeriesPoster(
    series: SeriesItem,
    cardProgress: com.atlas.atlastv.ui.catalog.CardWatchProgress?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onSelected: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(184.dp)
            .height(276.dp)
            .scale(if (focused) 1.06f else 1f)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { onMoveUp(); true }
                    Key.DirectionDown -> { onMoveDown(); true }
                    Key.DirectionLeft -> { onMoveLeft?.invoke(); onMoveLeft != null }
                    Key.DirectionCenter, Key.Enter -> { onSelected(); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onSelected)
            .testTag(if (focused) "focused_poster_card" else "series_poster_card")
            .semantics { contentDescription = if (focused) "focused_poster_card" else "series_poster_card" },
    ) {
        AtlasPosterCard(
            title = series.name,
            subtitle = series.categoryName,
            badge = "Series",
            imageRef = series.posterImage,
            focused = focused,
            progressLabel = null,
            cardProgress = cardProgress,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SeriesDetailPage(
    ready: SeriesCatalogState.Ready,
    stateHolder: SeriesCatalogStateHolder,
    backRequester: FocusRequester,
    playRequester: FocusRequester,
    preferredSeries: SeriesItem?,
    onSeasonSelected: (Int?) -> Unit,
    onEpisodeFocused: (SeriesEpisodeItem) -> Unit,
    onEpisodeSelected: (SeriesItem, SeriesEpisodeItem) -> Unit,
    onToggleWatchlist: (SeriesItem) -> Unit,
    onStartOver: ((SeriesItem, SeriesEpisodeItem) -> Unit)?,
    episodeWatchActions: EpisodeWatchActionsStateHolder?,
    onRetryEpisodes: () -> Unit,
    modifier: Modifier,
) {
    val originalSeries = preferredSeries ?: ready.series.firstOrNull { it.seriesId == ready.selectedSeriesId }
    val selectedSeries = originalSeries?.let { it.copy(available = stateHolder.availabilityFor(it)) }
    val available = selectedSeries?.available == true
    val details = ready.normalBrowseSeriesDetailsFor(selectedSeries?.seriesId)
    val selectedEpisode = ready.selectedEpisode()
    val selectedEpisodeDetails = ready.selectedEpisodeDetails()
    val watchlistRequester = remember(selectedSeries?.seriesId) { FocusRequester() }
    val episodeRequester = remember(selectedSeries?.seriesId) { FocusRequester() }
    val startOverRequester = remember(selectedSeries?.seriesId, selectedEpisode?.episodeId) { FocusRequester() }
    val refreshEpisodesRequester = remember(selectedSeries?.seriesId) { FocusRequester() }
    val showStartOver = onStartOver != null && available && selectedEpisode != null && com.atlas.atlastv.playback.canStartOver(
        ready.watchProgress[selectedEpisode.episodeId], com.atlas.atlastv.playback.WatchProgressContentType.SeriesEpisode,
        selectedEpisode.episodeId, selectedSeries?.seriesId)
    var restartRequested by remember(selectedSeries?.seriesId, selectedEpisode?.episodeId) { mutableStateOf(false) }
    var restoreRestartFocus by remember(selectedSeries?.seriesId, selectedEpisode?.episodeId) { mutableStateOf(false) }
    LaunchedEffect(restartRequested) {
        if (!restartRequested && restoreRestartFocus) {
            androidx.compose.runtime.withFrameNanos { }
            if (showStartOver) startOverRequester.requestFocus() else playRequester.requestFocus()
            restoreRestartFocus = false
        }
    }
    if (showStartOver && restartRequested && selectedSeries != null && selectedEpisode != null)
        com.atlas.atlastv.ui.components.StartOverConfirmation("${displayTitle(selectedSeries.name)} · ${selectedEpisode.shortEpisodeCode().ifBlank { "Episode" }}",
            onConfirm = { restartRequested = false; onStartOver?.invoke(selectedSeries, selectedEpisode) },
            onDismiss = { restoreRestartFocus = true; restartRequested = false })
    LaunchedEffect(selectedSeries?.seriesId, available) {
        if (available) playRequester.requestFocus()
        else if (selectedSeries?.seriesId in ready.watchlistSeriesIds) watchlistRequester.requestFocus()
        else backRequester.requestFocus()
    }
    LaunchedEffect(available, ready.watchlistSeriesIds) {
        if (!available && selectedSeries?.seriesId !in ready.watchlistSeriesIds) backRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("series_detail_page")
            .semantics { contentDescription = "series_detail_page" },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val episodeDetails = ready.episodeState as? SeriesEpisodeState.Ready
        SeriesDetailHero(details?.name ?: selectedSeries?.name ?: "Show",
            selectedSeries?.posterImage ?: com.atlas.atlastv.ui.media.AtlasImageRef.Missing,
            if (available) episodeDetails?.metadata ?: SeriesShowMetadata() else SeriesShowMetadata(synopsis = "No longer in your library. You can remove it from your watchlist."),
            episodeDetails?.seasons()?.size.takeIf { available },
            cacheStatus = when {
                episodeDetails?.refreshFailed == true -> "Refresh failed"
                else -> null
            },
            fromSaved = available && episodeDetails?.saved == true,
            refreshAction = { if (available && episodeDetails != null) com.atlas.atlastv.ui.components.DetailRefreshButton(refreshEpisodesRequester,
                refreshing = episodeDetails.refreshing, onRefresh = onRetryEpisodes,
                onDown = { playRequester.requestFocus() }, onUp = { backRequester.requestFocus() },
                testTag = "series_detail_refresh_episodes", description = "Refresh episodes") }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (available) SeriesDetailButton(
                    text = when (ready.episodeState) {
                        SeriesEpisodeState.Loading -> "Loading…"
                        is SeriesEpisodeState.Error -> "Retry episodes"
                        SeriesEpisodeState.Idle -> "Load episodes"
                        is SeriesEpisodeState.Ready -> "${if (selectedEpisodeDetails?.resumeEligible == true) "Resume" else "Play"} ${selectedEpisode?.shortEpisodeCode().orEmpty()}".trim()
                    },
                    onClick = {
                        if (selectedEpisode != null) onEpisodeSelected(selectedSeries, selectedEpisode)
                        else if (ready.episodeState != SeriesEpisodeState.Loading) onRetryEpisodes()
                    },
                    testTag = if (ready.episodeState is SeriesEpisodeState.Error) "series_episode_retry" else "series_detail_play_episode",
                    modifier = Modifier.focusRequester(playRequester),
                    onMoveRight = { watchlistRequester.requestFocus() },
                    onMoveDown = { if (selectedEpisode != null) episodeRequester.requestFocus() },
                    onMoveUp = if (episodeDetails != null) ({ refreshEpisodesRequester.requestFocus() }) else null,
                )
                SeriesDetailButton(text = when {
                    stateHolder.watchlistSaving -> "Saving…"
                    stateHolder.canRetryWatchlistSave -> "Retry save"
                    selectedSeries?.seriesId in ready.watchlistSeriesIds -> "− Watchlist"
                    !available -> "Removed"
                    else -> "+ Watchlist"
                },
                    onClick = { if (stateHolder.canRetryWatchlistSave) stateHolder.retryWatchlistSave() else selectedSeries?.let(onToggleWatchlist) }, testTag = "series_detail_watchlist",
                    enabled = !stateHolder.watchlistSaving && (stateHolder.canRetryWatchlistSave || available || selectedSeries?.seriesId in ready.watchlistSeriesIds),
                    modifier = Modifier.focusRequester(watchlistRequester),
                    onMoveLeft = { if (available) playRequester.requestFocus() else backRequester.requestFocus() },
                    onMoveRight = if (showStartOver) ({ startOverRequester.requestFocus() }) else null,
                    onMoveDown = { if (available && selectedEpisode != null) episodeRequester.requestFocus() })
                if (showStartOver && selectedSeries != null && selectedEpisode != null) SeriesDetailButton(
                    text = "Start over", onClick = { restartRequested = true }, testTag = "series_detail_start_over",
                    modifier = Modifier.focusRequester(startOverRequester),
                    onMoveLeft = { watchlistRequester.requestFocus() }, onMoveDown = { episodeRequester.requestFocus() })
            }
        }
        if (available) when (val episodeState = ready.episodeState) {
            SeriesEpisodeState.Idle -> BrowseStatus("Episodes not loaded yet.", "series_episode_status")
            SeriesEpisodeState.Loading -> BrowseStatus("Loading episodes", "series_episode_status")
            is SeriesEpisodeState.Error -> BrowseStatus(episodeState.message, "series_episode_status")
            is SeriesEpisodeState.Ready -> EpisodeRows(
                ready = ready,
                selectedSeries = selectedSeries,
                episodeState = episodeState,
                onSeasonSelected = onSeasonSelected,
                onEpisodeFocused = onEpisodeFocused,
                onEpisodeSelected = onEpisodeSelected,
                playRequester = playRequester,
                episodeRequester = episodeRequester,
                episodeWatchActions = episodeWatchActions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SeriesDetailButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(160.dp)
            .height(50.dp)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> {
                        onMoveLeft?.invoke()
                        onMoveLeft != null
                    }
                    Key.DirectionRight -> {
                        onMoveRight?.invoke()
                        onMoveRight != null
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (enabled) onClick()
                        true
                    }
                    Key.DirectionDown -> {
                        onMoveDown?.invoke()
                        onMoveDown != null
                    }
                    Key.DirectionUp -> { onMoveUp?.invoke(); onMoveUp != null }
                    else -> false
                }
            }
            .focusable()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EpisodeRows(
    ready: SeriesCatalogState.Ready,
    selectedSeries: SeriesItem?,
    episodeState: SeriesEpisodeState.Ready,
    onSeasonSelected: (Int?) -> Unit,
    onEpisodeFocused: (SeriesEpisodeItem) -> Unit,
    onEpisodeSelected: (SeriesItem, SeriesEpisodeItem) -> Unit,
    playRequester: FocusRequester,
    episodeRequester: FocusRequester,
    episodeWatchActions: EpisodeWatchActionsStateHolder?,
    modifier: Modifier,
) {
    val seasonRequester = remember { FocusRequester() }
    val seasons = remember(episodeState.episodes) { episodeState.seasons() }
    val episodes = remember(episodeState, ready.selectedSeasonNumber) { episodeState.episodesForSeason(ready.selectedSeasonNumber) }
    val selectedIndex = episodes.indexOfFirst { it.episodeId == ready.selectedEpisodeId }.coerceAtLeast(0)
    val episodeScroll = remember(ready.selectedSeasonNumber) { LazyListState(selectedIndex) }
    val seasonScroll = remember { LazyListState(seasons.indexOfFirst { it.seasonNumber == ready.selectedSeasonNumber }.coerceAtLeast(0)) }
    val episodeIds = episodes.map { it.episodeId }.toSet()
    val seasonIds = seasons.map { it.seasonNumber ?: -1 }.toSet()
    val currentEpisodeIds by rememberUpdatedState(episodeIds)
    val currentSeasonIds by rememberUpdatedState(seasonIds)
    var focusedEpisodeId by remember { mutableStateOf<Int?>(null) }
    var focusedSeasonId by remember { mutableStateOf<Int?>(null) }
    // An ordinary refresh must not steal focus. Restore only a focused item actually removed by it.
    LaunchedEffect(episodeIds, seasonIds) {
        val lostEpisode = focusedEpisodeId?.let { it !in episodeIds } == true
        val lostSeason = focusedSeasonId?.let { it !in seasonIds } == true
        if (lostEpisode && episodes.isNotEmpty()) {
            focusedEpisodeId = null
            episodeScroll.scrollToItem(selectedIndex)
            androidx.compose.runtime.withFrameNanos { }
            episodeRequester.requestFocus()
        } else if (lostSeason && seasons.isNotEmpty()) {
            focusedSeasonId = null
            seasonScroll.scrollToItem(seasons.indexOfFirst { it.seasonNumber == ready.selectedSeasonNumber }.coerceAtLeast(0))
            androidx.compose.runtime.withFrameNanos { }
            seasonRequester.requestFocus()
        }
    }
    var enterEpisodes by remember { mutableStateOf(false) }
    LaunchedEffect(ready.selectedSeasonNumber, enterEpisodes) {
        kotlinx.coroutines.yield()
        if (enterEpisodes && episodes.isNotEmpty()) {
            episodeScroll.scrollToItem(selectedIndex)
            androidx.compose.runtime.withFrameNanos { }
            episodeRequester.requestFocus()
        }
        enterEpisodes = false
    }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        LazyColumn(
            state = seasonScroll,
            modifier = Modifier
                .width(190.dp)
                .fillMaxHeight()
                .testTag("series_season_list")
                .semantics { contentDescription = "series_season_list" },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(seasons, key = { _, season -> season.seasonNumber ?: -1 }) { index, season ->
                SeasonRow(
                    season = season,
                    selected = season.seasonNumber == ready.selectedSeasonNumber,
                    modifier = (if (index == seasons.indexOfFirst { it.seasonNumber == ready.selectedSeasonNumber }) Modifier.focusRequester(seasonRequester) else Modifier)
                        .onFocusChanged { focus ->
                            val id = season.seasonNumber ?: -1
                            if (focus.hasFocus) focusedSeasonId = id
                            else if (focusedSeasonId == id && id in currentSeasonIds) focusedSeasonId = null
                        },
                    onClick = {
                        onSeasonSelected(season.seasonNumber)
                        enterEpisodes = true
                    },
                    onMoveRight = {
                        onSeasonSelected(season.seasonNumber)
                        enterEpisodes = true
                    },
                    onMoveUp = if (index == 0) ({ playRequester.requestFocus() }) else null,
                )
            }
        }
        LazyColumn(
            state = episodeScroll,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("series_episode_list")
                .semantics { contentDescription = "series_episode_list" },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(episodes, key = { _, episode -> episode.episodeId }) { index, episode ->
                val presentation = episode.rowPresentation(selectedSeries?.seriesId, ready.watchProgress[episode.episodeId])
                EpisodeRow(
                    episode = episode,
                    presentation = presentation,
                    show = selectedSeries,
                    watchActions = episodeWatchActions,
                    seriesName = selectedSeries?.name,
                    onMoveUp = if (index == 0) ({ playRequester.requestFocus() }) else null,
                    selected = episode.episodeId == ready.selectedEpisodeId,
                    modifier = (if (episode.episodeId == (ready.selectedEpisodeId ?: episodes.firstOrNull()?.episodeId)) Modifier.focusRequester(episodeRequester) else Modifier)
                        .onFocusChanged { focus ->
                            if (focus.hasFocus) focusedEpisodeId = episode.episodeId
                            else if (focusedEpisodeId == episode.episodeId && episode.episodeId in currentEpisodeIds) focusedEpisodeId = null
                        },
                    onFocused = { onEpisodeFocused(episode) },
                    onMoveLeft = { seasonRequester.requestFocus() },
                    onSelected = {
                        onEpisodeFocused(episode)
                        selectedSeries?.let { onEpisodeSelected(it, episode) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SeasonRow(
    season: SeriesSeason,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveUp: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (focused || selected) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else if (selected) AtlasSurfaceFocused else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionRight -> { onMoveRight(); true }
                    Key.DirectionUp -> {
                        onMoveUp?.invoke()
                        onMoveUp != null
                    }
                    Key.DirectionCenter, Key.Enter -> { onClick(); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag("series_season_${season.seasonNumber ?: "unknown"}")
            .semantics { contentDescription = "series_season_${season.seasonNumber ?: "unknown"}" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(season.label, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EpisodeRow(
    episode: SeriesEpisodeItem,
    presentation: EpisodeRowPresentation,
    show: SeriesItem?,
    watchActions: EpisodeWatchActionsStateHolder?,
    seriesName: String?,
    onMoveUp: (() -> Unit)?,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onMoveLeft: () -> Unit,
    onSelected: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val rowRequester = remember(episode.episodeId) { FocusRequester() }
    val optionsRequester = remember(episode.episodeId) { FocusRequester() }
    var optionsOpen by remember(episode.episodeId) { mutableStateOf(false) }
    var restoreOptions by remember(episode.episodeId) { mutableStateOf(false) }
    val hasOptions = selected && show?.available == true && watchActions != null
    LaunchedEffect(optionsOpen) {
        if (!optionsOpen && restoreOptions) {
            androidx.compose.runtime.withFrameNanos { }
            if (hasOptions) optionsRequester.requestFocus() else rowRequester.requestFocus()
            restoreOptions = false
        }
    }
    if (hasOptions && optionsOpen && show != null && watchActions != null) EpisodeActionsDialog(show, episode,
        presentation.watched, watchActions) { restoreOptions = true; optionsOpen = false }
    val shape = RoundedCornerShape(8.dp)
    val tag = if (selected) "series_selected_episode" else "series_episode_${episode.episodeId}"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp)
            .background(if (focused || selected) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else if (selected) AtlasSurfaceFocused else AtlasSurface), shape)
            .focusRequester(rowRequester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> { onMoveLeft(); true }
                    Key.DirectionRight -> { if (hasOptions) optionsRequester.requestFocus(); hasOptions }
                    Key.DirectionUp -> { onMoveUp?.invoke(); onMoveUp != null }
                    Key.DirectionCenter, Key.Enter -> { onSelected(); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onSelected)
            .padding(8.dp)
            .testTag(tag)
            .semantics { contentDescription = tag },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(100.dp).height(56.dp).clip(RoundedCornerShape(5.dp)).testTag("series_episode_thumbnail")) {
            AtlasBackdrop(episode.thumbnail, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(AtlasBackground), contentAlignment = Alignment.Center) {
                    Text("▶", color = AtlasMutedText, style = MaterialTheme.typography.titleLarge)
                }
            }
            presentation.progress?.fraction?.let { AtlasCardProgressBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = episode.displayEpisodeName(seriesName)
                episode.episodeNumber?.takeIf { name != "Episode $it" }?.let {
                    Text("$it", color = AtlasMutedText, style = MaterialTheme.typography.titleMedium)
                }
                Text(name, color = AtlasText,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("series_episode_name"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                presentation.durationLabel?.let { Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, modifier = Modifier.testTag("series_episode_duration")) }
                val actionStatus = if (show != null && watchActions?.matches(show.seriesId, episode.episodeId) == true) when {
                    watchActions.failed -> "Not saved"
                    watchActions.saving -> "Saving…"
                    else -> null
                } else null
                (actionStatus ?: presentation.progress?.timeLabel)?.let { Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, modifier = Modifier.testTag("series_episode_progress_label")) }
            }
        }
        if (hasOptions) EpisodeMoreButton(optionsRequester, onClick = { optionsOpen = true },
            onLeft = { rowRequester.requestFocus() })
    }
}

@Composable
private fun EpisodeMoreButton(requester: FocusRequester, onClick: () -> Unit, onLeft: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.width(42.dp).height(46.dp).background(if (focused) AtlasSurface else AtlasSurfaceFocused, shape)
        .border(BorderStroke(if (focused) 2.dp else 0.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { onLeft(); true }
                Key.DirectionCenter, Key.Enter -> { onClick(); true }
                else -> false
            }
        }.focusable().clickable(onClick = onClick).testTag("series_episode_more")
        .semantics { contentDescription = "Episode actions" }, contentAlignment = Alignment.Center) {
        Text("⋯", color = AtlasText, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun BrowseStatus(text: String, testTag: String) {
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
private fun BrowseButton(text: String, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(118.dp)
            .height(40.dp)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick()
                    true
                } else false
            }
            .focusable()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}
