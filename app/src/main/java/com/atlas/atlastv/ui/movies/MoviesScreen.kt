package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.catalog.displayTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import com.atlas.atlastv.ui.browse.movieBrowseRows
import com.atlas.atlastv.ui.browse.movieSearchBrowseRows
import com.atlas.atlastv.ui.browse.progressForMovie
import com.atlas.atlastv.ui.components.AtlasPosterCard
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MoviesScreen(
    stateHolder: MoviesCatalogStateHolder,
    onBack: () -> Unit,
    onMovieSelected: (MovieItem) -> Unit,
    modifier: Modifier = Modifier,
    openSelectedMovieDetailsRequest: Int = 0,
    selectedMovieForDetails: MovieItem? = null,
    gridHolder: MovieGridStateHolder? = null,
    onDetailsClosed: (() -> Unit)? = null,
    categoryBrowser: MovieCategoryBrowser? = null,
    watchlistBrowser: MovieWatchlistBrowser? = null,
    onStartOver: ((MovieItem) -> Unit)? = null,
    movieWatchActions: MovieWatchActionsStateHolder? = null,
    browseSession: MovieBrowseSessionStateHolder? = null,
) {
    if (browseSession != null) {
        LaunchedEffect(browseSession) { browseSession.restore(reopen = selectedMovieForDetails == null) }
        androidx.compose.runtime.DisposableEffect(browseSession) { onDispose { browseSession.checkpoint() } }
        if (!browseSession.ready) {
            val back = remember { FocusRequester() }
            BackHandler { onBack() }
            LaunchedEffect(Unit) { back.requestFocus() }
            Column(Modifier.fillMaxSize().background(AtlasBackground).padding(48.dp)) {
                BrowseSearchButton("Back", onBack, "movies_restore_back", Modifier.focusRequester(back))
            }
            return
        }
    }
    val activeGrid = watchlistBrowser?.grid?.takeIf { it.open } ?: categoryBrowser?.grid?.takeIf { it.open } ?: gridHolder
    var detailMode by remember { mutableStateOf(activeGrid?.detailMovie != null) }
    var gridOpen by remember { mutableStateOf(activeGrid?.open == true) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var handledMovieDetailsRequest by remember { mutableStateOf(0) }
    var activePreferredMovieRequest by remember { mutableStateOf(0) }
    val searchButtonRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val firstRowRequester = remember { FocusRequester() }
    val shelfNavigation = categoryBrowser?.shelfNavigation ?: remember { com.atlas.atlastv.ui.browse.BrowseShelfNavigation() }
    val searchShelves = remember(searchQuery) { com.atlas.atlastv.ui.browse.BrowseShelfNavigation() }
    val retryRequester = remember { FocusRequester() }
    val detailActionRequester = remember { FocusRequester() }
    var restoreBrowseFocus by remember { mutableStateOf(false) }
    var browseEntryRequest by remember { mutableStateOf(0) }
    val allMoviesRequester = remember { FocusRequester() }
    var restoreAllMoviesFocus by remember { mutableStateOf(false) }
    val categoriesRequester = remember { FocusRequester() }
    var restoreCategoriesFocus by remember { mutableStateOf(false) }
    var restoreCategoryRow by remember { mutableStateOf<String?>(null) }
    val watchlistRequester = remember { FocusRequester() }
    var restoreWatchlistFocus by remember { mutableStateOf(false) }
    fun openCategory(name: String, rowId: String? = null) {
        gridHolder?.open = false
        watchlistBrowser?.grid?.open = false
        categoryBrowser?.openCategory(name, rowId)
        gridOpen = categoryBrowser != null
    }
    LaunchedEffect(gridOpen, categoryBrowser?.categoriesOpen, activeGrid) { browseSession?.checkpoint() }
    val navigationChanged: () -> Unit = { browseSession?.checkpoint() }
    val categoryNavigation = stateHolder.categoryNavigation
    fun closeDetails() {
        val parent = categoryNavigation.returnParent((stateHolder.state as? MoviesCatalogState.Ready)?.selectedMovieStreamId)
        if (parent != null) {
            activePreferredMovieRequest = 0
            stateHolder.rememberMovie(parent)
            if (gridOpen) activeGrid?.detailMovie = parent
            return
        }
        categoryNavigation.clear()
        activePreferredMovieRequest = 0
        detailMode = false
        activeGrid?.detailMovie = null
        if (!gridOpen) restoreBrowseFocus = true
        onDetailsClosed?.invoke()
    }
    val catalogState = stateHolder.state
    LaunchedEffect(stateHolder, stateHolder.catalogVersion) { stateHolder.ensureCachedCatalog() }
    LaunchedEffect(gridOpen, detailMode, categoryBrowser?.categoriesOpen) {
        if (!gridOpen && !detailMode && categoryBrowser?.categoriesOpen != true) {
            if (restoreAllMoviesFocus) { allMoviesRequester.requestFocus(); restoreAllMoviesFocus = false }
            else if (restoreWatchlistFocus) { watchlistRequester.requestFocus(); restoreWatchlistFocus = false }
            else if (restoreCategoriesFocus) { categoriesRequester.requestFocus(); restoreCategoriesFocus = false }
            else searchButtonRequester.requestFocus()
        }
    }
    LaunchedEffect(searchOpen, gridOpen, detailMode, categoryBrowser?.categoriesOpen, catalogState is MoviesCatalogState.Ready) {
        if (searchOpen && !gridOpen && !detailMode && categoryBrowser?.categoriesOpen != true && catalogState is MoviesCatalogState.Ready) searchRequester.requestFocus()
    }
    LaunchedEffect(openSelectedMovieDetailsRequest, selectedMovieForDetails?.streamId, catalogState) {
        if (
            openSelectedMovieDetailsRequest > 0 &&
            openSelectedMovieDetailsRequest != handledMovieDetailsRequest &&
            catalogState is MoviesCatalogState.Ready
        ) {
            handledMovieDetailsRequest = openSelectedMovieDetailsRequest
            selectedMovieForDetails?.let {
                stateHolder.rememberMovie(it)
                activePreferredMovieRequest = openSelectedMovieDetailsRequest
                detailMode = true
            }
        }
    }
    BackHandler(enabled = searchOpen || searchQuery.isNotBlank()) {
        searchOpen = false
        searchQuery = ""
        stateHolder.clearSearch()
        searchButtonRequester.requestFocus()
    }
    BackHandler(enabled = detailMode) {
        closeDetails()
    }

    if (categoryBrowser?.categoriesOpen == true && !gridOpen && !detailMode) {
        MovieCategoriesScreen(categoryBrowser.categories, stateHolder.catalogVersion,
            onBack = { categoryBrowser.categoriesOpen = false; restoreCategoriesFocus = true },
            onCategory = { category -> openCategory(category.id) }, onNavigationChanged = navigationChanged)
        return
    }

    if (gridOpen && !detailMode && activeGrid != null) {
        val onGridMovie: (MovieItem) -> Unit = { movie -> categoryNavigation.clear(); stateHolder.rememberMovie(movie); activeGrid.detailMovie = movie; activePreferredMovieRequest = 0; detailMode = true }
        if (watchlistBrowser?.grid === activeGrid) {
            MovieWatchlistGridScreen(activeGrid, stateHolder.catalogVersion,
                catalogReady = catalogState is MoviesCatalogState.Ready,
                statusMessage = stateHolder.saveError ?: "Saving watchlist…".takeIf { stateHolder.watchlistSaving },
                onRetrySave = if (stateHolder.canRetryWatchlistSave) stateHolder::retryWatchlistSave else null,
                onBack = {
                    activeGrid.open = false; gridOpen = false
                    if (watchlistBrowser.originRow) restoreCategoryRow = "movies_watchlist" else restoreWatchlistFocus = true
                }, onMovie = onGridMovie, onNavigationChanged = navigationChanged)
        } else if (categoryBrowser?.grid === activeGrid && categoryBrowser.categoryName != null) {
            MovieCategoryGridScreen(activeGrid, stateHolder.catalogVersion, categoryBrowser.categoryName!!,
                catalogReady = catalogState is MoviesCatalogState.Ready,
                onBack = {
                    activeGrid.open = false; gridOpen = false
                    restoreCategoryRow = categoryBrowser.originRowId
                    categoryBrowser.categoriesOpen = categoryBrowser.originRowId == null
                }, onMovie = onGridMovie, onNavigationChanged = navigationChanged)
        } else AllMoviesScreen(activeGrid, stateHolder.catalogVersion,
            catalogReady = catalogState is MoviesCatalogState.Ready,
            onBack = { activeGrid.open = false; restoreAllMoviesFocus = true; gridOpen = false; restoreBrowseFocus = false },
            onMovie = onGridMovie, onNavigationChanged = navigationChanged)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AtlasBackground)
            .padding(horizontal = 48.dp, vertical = 24.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && detailMode && event.key == Key.Back) {
                    closeDetails()
                    true
                } else {
                    false
                }
            }
            .semantics { testTagsAsResourceId = true }
            .testTag("movies_screen")
            .semantics { contentDescription = "movies_screen" },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!detailMode) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowseButton(
                "Back",
                if (detailMode) ({
                    closeDetails()
                }) else onBack,
                "movies_back",
            )
            if (!detailMode) {
                BrowseSearchButton(
                    "Search",
                    { searchOpen = true },
                    "movies_search_open",
                    modifier = Modifier.focusRequester(searchButtonRequester).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            if (catalogState is MoviesCatalogState.Ready && stateHolder.refreshError != null) retryRequester.requestFocus()
                            else if (catalogState is MoviesCatalogState.Ready && (catalogState.searchResult?.rows ?: catalogState.browseRows).isNotEmpty()) browseEntryRequest++
                            else if (catalogState is MoviesCatalogState.Error) retryRequester.requestFocus()
                            true
                        } else false
                    },
                )
                if (gridHolder != null) BrowseSearchButton("All Movies", {
                    categoryBrowser?.grid?.open = false
                    watchlistBrowser?.grid?.open = false
                    gridHolder.open = true
                    gridOpen = true
                }, "movies_all_open", Modifier.focusRequester(allMoviesRequester))
                if (watchlistBrowser != null) BrowseSearchButton("Watchlist", {
                    categoryBrowser?.grid?.open = false
                    gridHolder?.open = false
                    watchlistBrowser.originRow = false; watchlistBrowser.grid.open = true; gridOpen = true
                }, "movies_watchlist_open", Modifier.focusRequester(watchlistRequester))
                if (categoryBrowser != null) BrowseSearchButton("Categories", {
                    gridHolder?.open = false
                    watchlistBrowser?.grid?.open = false
                    categoryBrowser.categoriesOpen = true
                }, "movies_categories_open", Modifier.focusRequester(categoriesRequester))
            }
            if (!detailMode) Text("Movies", color = AtlasText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        when (val state = catalogState) {
            MoviesCatalogState.Loading -> BrowseStatus("Loading Movies", "movies_loading")
            is MoviesCatalogState.Error -> {
                BrowseStatus(state.message, "movies_error")
                BrowseSearchButton("Retry", {
                    searchButtonRequester.requestFocus()
                    stateHolder.invalidateCachedCatalog()
                }, "movies_retry", Modifier.focusRequester(retryRequester))
            }
            is MoviesCatalogState.Ready -> {
                if (stateHolder.isRefreshing) BrowseStatus("Updating Movies…", "movies_refreshing")
                stateHolder.refreshError?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrowseSearchButton("Retry", {
                            if (detailMode) detailActionRequester.requestFocus() else searchButtonRequester.requestFocus()
                            stateHolder.invalidateCachedCatalog()
                        }, "movies_refresh_retry", Modifier.focusRequester(retryRequester).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionUp -> { if (detailMode) detailActionRequester.requestFocus() else searchButtonRequester.requestFocus(); true }
                                Key.DirectionDown -> { if (detailMode) detailActionRequester.requestFocus() else browseEntryRequest++; true }
                                else -> false
                            }
                        })
                        Text(it, color = AtlasMutedText, modifier = Modifier.testTag("movies_refresh_error"))
                    }
                }
                stateHolder.saveError?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (stateHolder.canRetryWatchlistSave) BrowseSearchButton("Retry save", stateHolder::retryWatchlistSave, "movies_watchlist_save_retry")
                        BrowseStatus(it, "movies_save_error")
                    }
                }
                if (detailMode) {
                    MovieDetailPage(
                        ready = state,
                        stateHolder = stateHolder,
                        playRequester = detailActionRequester,
                        preferredMovie = activeGrid?.detailMovie ?: selectedMovieForDetails.takeIf {
                            openSelectedMovieDetailsRequest > 0 &&
                                openSelectedMovieDetailsRequest == handledMovieDetailsRequest &&
                                openSelectedMovieDetailsRequest == activePreferredMovieRequest
                        },
                        onBack = ::closeDetails,
                        onPlay = { movie -> onMovieSelected(movie) },
                        onToggleWatchlist = stateHolder::toggleWatchlistForMovie,
                        onStartOver = onStartOver,
                        watchActions = movieWatchActions,
                        restoreCategoryMovieId = categoryNavigation.restoreMovieId,
                        onCategoryRestoreConsumed = { categoryNavigation.restoreMovieId = null },
                        onRelatedMovieSelected = categorySelection@ { parent, movie ->
                            if ((stateHolder.state as? MoviesCatalogState.Ready)?.selectedMovieStreamId != parent.streamId) return@categorySelection
                            categoryNavigation.openChild(parent)
                            activePreferredMovieRequest = 0
                            stateHolder.rememberMovie(movie)
                            if (gridOpen) activeGrid?.detailMovie = movie
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val searchResult = if (searchQuery.isBlank()) null else movieSearchBrowseRows(state.movies, searchQuery)
                    if (searchOpen || searchQuery.isNotBlank()) {
                        BrowseSearchOverlay(
                            query = searchQuery,
                            resultCount = state.searchResult?.totalCount ?: searchResult?.totalCount ?: state.movies.size,
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
                            overlayTag = "movies_search_overlay",
                            fieldTag = "movies_search_field",
                            clearTag = "movies_clear_search",
                            countTag = "movies_search_result_count",
                        )
                    }
                    val rows = state.searchResult?.takeIf { searchQuery.isNotBlank() }?.rows
                        ?: searchResult?.rows
                        ?: state.browseRows.takeIf { it.isNotEmpty() }
                        ?: movieBrowseRows(
                        movies = state.movies,
                        watchlistIds = state.watchlistStreamIds,
                        progressRecords = state.watchProgress,
                    )
                    if (rows.isEmpty()) {
                        BrowseStatus(if (searchQuery.isBlank()) "No cached movies found." else "No movies match your search.", "movies_empty")
                    } else {
                        MoviesRowBrowser(
                            rows = rows,
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
                                if (row.id == "movies_watchlist" && watchlistBrowser != null) {
                                    categoryBrowser?.grid?.open = false
                                    gridHolder?.open = false
                                    watchlistBrowser.originRow = true; watchlistBrowser.grid.open = true; gridOpen = true
                                } else row.categoryKey?.let { key ->
                                    watchlistBrowser?.grid?.open = false
                                    openCategory(key, row.id)
                                }
                            }) else null,
                        ready = state,
                        previousFocusRequester = if (stateHolder.refreshError != null) retryRequester else if (searchOpen || searchQuery.isNotBlank()) searchRequester else searchButtonRequester,
                        onMovieFocused = stateHolder::rememberMovie,
                        onMovieSelected = { movie ->
                            categoryNavigation.clear()
                            activePreferredMovieRequest = 0
                            stateHolder.rememberMovie(movie)
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
private fun MovieDetailPage(
    ready: MoviesCatalogState.Ready,
    stateHolder: MoviesCatalogStateHolder,
    playRequester: FocusRequester,
    preferredMovie: MovieItem?,
    onBack: () -> Unit,
    onPlay: (MovieItem) -> Unit,
    onToggleWatchlist: (MovieItem) -> Unit,
    onStartOver: ((MovieItem) -> Unit)?,
    watchActions: MovieWatchActionsStateHolder?,
    restoreCategoryMovieId: Int?,
    onCategoryRestoreConsumed: () -> Unit,
    onRelatedMovieSelected: (MovieItem, MovieItem) -> Unit,
    modifier: Modifier,
) {
    val original = preferredMovie ?: ready.movies.firstOrNull { it.streamId == ready.selectedMovieStreamId }
    val movie = original?.copy(available = stateHolder.availabilityFor(original))
    if (movie == null) {
        LaunchedEffect(ready.selectedMovieStreamId) { onBack() }
        BrowseStatus("Returning to Movies.", "movie_detail_empty")
        return
    }
    val infoContext = stateHolder.movieInfoContextKey()
    LaunchedEffect(movie.streamId, movie.available, stateHolder, stateHolder.movieInfoVersion, infoContext) { if (movie.available) stateHolder.ensureMovieInfo(movie.streamId) }
    val infoState = stateHolder.visibleMovieInfoState(movie.streamId)
    val savedInfo = infoState as? MovieInfoState.Ready
    val metadata = if (!movie.available) MovieMetadata(synopsis = "No longer in your library. You can remove it from your watchlist.")
        else (savedInfo?.metadata ?: MovieMetadata()).withPlaybackRuntime(movie.streamId, ready.watchProgress[movie.streamId])
    val details = ready.normalBrowseMovieDetailsFor(movie)
    var categoryMovies by remember(movie.streamId, movie.categoryName, movie.available, stateHolder.catalogVersion, infoContext) {
        mutableStateOf<List<MovieItem>?>(null)
    }
    LaunchedEffect(movie.streamId, movie.categoryName, movie.available, stateHolder.catalogVersion, infoContext) {
        categoryMovies = stateHolder.categoryPreviewFor(movie)
    }
    val categoryPreview = categoryMovies.orEmpty()
    var categoryRowHadFocus by remember(movie.streamId) { mutableStateOf(false) }
    val watchlistRequester = remember(details.streamId) { FocusRequester() }
    val backRequester = remember(details.streamId) { FocusRequester() }
    val refreshRequester = remember(details.streamId) { FocusRequester() }
    val startOverRequester = remember(details.streamId) { FocusRequester() }
    val moreRequester = remember(details.streamId) { FocusRequester() }
    val showMore = movie.available && watchActions != null
    var actionsOpen by remember(details.streamId) { mutableStateOf(false) }
    var restoreActionsFocus by remember(details.streamId) { mutableStateOf(false) }
    val watched = ready.watchProgress[movie.streamId]?.completed == true
    val editingProgress = watchActions?.matches(movie.streamId) == true
    val progressLabel = when {
        editingProgress && watchActions?.failed == true -> "Not saved"
        editingProgress && watchActions?.saving == true -> "Saving…"
        watched -> "Watched"
        else -> details.resumeLabel
    }
    LaunchedEffect(actionsOpen) {
        if (!actionsOpen && restoreActionsFocus) {
            androidx.compose.runtime.withFrameNanos { }
            if (showMore) moreRequester.requestFocus() else backRequester.requestFocus()
            restoreActionsFocus = false
        }
    }
    if (showMore && actionsOpen && watchActions != null) {
        val undoAvailable = remember(watchActions.revision, watchActions.saving, watchActions.failed, watchActions.undoEdit) {
            watchActions.canUndo(movie.streamId)
        }
        com.atlas.atlastv.ui.catalog.WatchActionsDialog(displayTitle(movie.title), null, watched, watchActions,
            watchActions.matches(movie.streamId), undoAvailable, "movie",
            onChange = { watchActions.change(movie, it) },
            onDismiss = { restoreActionsFocus = true; actionsOpen = false })
    }
    val showStartOver = onStartOver != null && movie.available && com.atlas.atlastv.playback.canStartOver(
        ready.watchProgress[movie.streamId], com.atlas.atlastv.playback.WatchProgressContentType.Movie, movie.streamId)
    var restartRequested by remember(details.streamId) { mutableStateOf(false) }
    var restoreRestartFocus by remember(details.streamId) { mutableStateOf(false) }
    LaunchedEffect(restartRequested) {
        if (!restartRequested && restoreRestartFocus) {
            androidx.compose.runtime.withFrameNanos { }
            if (showStartOver) startOverRequester.requestFocus() else playRequester.requestFocus()
            restoreRestartFocus = false
        }
    }
    if (showStartOver && restartRequested) com.atlas.atlastv.ui.components.StartOverConfirmation(movie.title,
        onConfirm = { restartRequested = false; onStartOver?.invoke(movie) },
        onDismiss = { restoreRestartFocus = true; restartRequested = false })
    val relatedRequester = remember(details.streamId, categoryPreview.map { it.streamId }) { FocusRequester() }
    LaunchedEffect(details.streamId, movie.available) { if (movie.available) playRequester.requestFocus() else if (details.isWatchlisted) watchlistRequester.requestFocus() else backRequester.requestFocus() }
    LaunchedEffect(movie.available, details.isWatchlisted, stateHolder.watchlistSaving, stateHolder.canRetryWatchlistSave) {
        if (!movie.available && !details.isWatchlisted && !stateHolder.watchlistSaving && !stateHolder.canRetryWatchlistSave) backRequester.requestFocus()
    }
    BoxWithConstraints(modifier = modifier) {
        // Keep core actions visible on TV; optional related titles must not squeeze them below the viewport.
        val showRelated = categoryPreview.isNotEmpty() && maxHeight >= 492.dp
        LaunchedEffect(showRelated, categoryMovies, restoreCategoryMovieId, categoryRowHadFocus) {
            if (!showRelated && (categoryRowHadFocus || (categoryMovies != null && restoreCategoryMovieId != null))) {
                if (movie.available) playRequester.requestFocus()
                else if (details.isWatchlisted) watchlistRequester.requestFocus()
                else backRequester.requestFocus()
                categoryRowHadFocus = false
                onCategoryRestoreConsumed()
            }
        }
        Column(
            modifier = Modifier.fillMaxSize()
                .fillMaxWidth()
                .testTag("movie_detail_screen")
                .semantics { contentDescription = "movie_detail_screen" },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MovieDetailHero(movie, metadata, progressLabel,
                cacheStatus = if (!movie.available) null else when {
                    savedInfo?.refreshFailed == true -> "Refresh failed"
                    infoState is MovieInfoState.Unavailable -> "Details unavailable"
                    else -> null
                },
                fromSaved = movie.available && savedInfo?.saved == true,
                refreshAction = { if (movie.available) com.atlas.atlastv.ui.components.DetailRefreshButton(refreshRequester,
                    infoState is MovieInfoState.Loading || savedInfo?.refreshing == true, stateHolder::retryMovieInfo,
                    onDown = { playRequester.requestFocus() }, onUp = { backRequester.requestFocus() },
                    testTag = "movie_detail_refresh", description = "Refresh details") }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (movie.available) MovieDetailButton(
                            text = if (details.resumeLabel != null) "Resume" else "Play",
                            onClick = { if (movie.available) onPlay(movie) },
                            testTag = "movie_detail_play",
                            width = 112,
                            modifier = Modifier.focusRequester(playRequester),
                            onMoveRight = { watchlistRequester.requestFocus() },
                            onMoveDown = if (showRelated) ({ relatedRequester.requestFocus() }) else null,
                            onMoveUp = { refreshRequester.requestFocus() },
                        )
                        MovieDetailButton(
                            text = when {
                                stateHolder.watchlistSaving -> "Saving…"
                                stateHolder.canRetryWatchlistSave -> "Retry save"
                                !movie.available && !details.isWatchlisted -> "Removed"
                                details.isWatchlisted -> "− Watchlist"
                                else -> "+ Watchlist"
                            },
                            enabled = !stateHolder.watchlistSaving && (movie.available || details.isWatchlisted || stateHolder.canRetryWatchlistSave),
                            onClick = { if (stateHolder.canRetryWatchlistSave) stateHolder.retryWatchlistSave() else onToggleWatchlist(movie) },
                            testTag = "movie_detail_watchlist",
                            modifier = Modifier.focusRequester(watchlistRequester),
                            onMoveLeft = { if (movie.available) playRequester.requestFocus() else backRequester.requestFocus() },
                            onMoveRight = { if (showStartOver) startOverRequester.requestFocus() else if (showMore) moreRequester.requestFocus() else backRequester.requestFocus() },
                            onMoveDown = if (showRelated) ({ relatedRequester.requestFocus() }) else null,
                        )
                        if (showStartOver) MovieDetailButton(
                            text = "Start over", onClick = { restartRequested = true }, testTag = "movie_detail_start_over",
                            width = 96, modifier = Modifier.focusRequester(startOverRequester),
                            onMoveLeft = { watchlistRequester.requestFocus() }, onMoveRight = { if (showMore) moreRequester.requestFocus() else backRequester.requestFocus() },
                            onMoveDown = if (showRelated) ({ relatedRequester.requestFocus() }) else null,
                        )
                        if (showMore) MovieDetailButton(
                            text = "⋯", width = 48, testTag = "movie_detail_more", description = "Movie actions",
                            onClick = { actionsOpen = true }, modifier = Modifier.focusRequester(moreRequester),
                            onMoveLeft = { if (showStartOver) startOverRequester.requestFocus() else watchlistRequester.requestFocus() },
                            onMoveRight = { backRequester.requestFocus() },
                            onMoveDown = if (showRelated) ({ relatedRequester.requestFocus() }) else null,
                        )
                        MovieDetailButton(
                            text = "Back",
                            width = 80,
                            onClick = onBack,
                            testTag = "movie_detail_back",
                            modifier = Modifier.focusRequester(backRequester),
                            onMoveLeft = { if (showMore) moreRequester.requestFocus() else if (showStartOver) startOverRequester.requestFocus() else watchlistRequester.requestFocus() },
                            onMoveDown = if (showRelated) ({ relatedRequester.requestFocus() }) else null,
                        )
                    }
            }
            if (showRelated) {
                MovieCategoryPreviewRow(
                    movies = categoryPreview,
                    firstFocusRequester = relatedRequester,
                    playRequester = playRequester,
                    restoreMovieId = restoreCategoryMovieId,
                    onRestoreConsumed = onCategoryRestoreConsumed,
                    onRowFocused = { categoryRowHadFocus = true },
                    onRowLeft = { categoryRowHadFocus = false },
                    onMovieSelected = { onRelatedMovieSelected(movie, it) },
                )
            }
        }
    }
}

@Composable
private fun MoviesRowBrowser(
    rows: List<BrowseRow<MovieItem>>,
    firstFocusRequester: FocusRequester,
    entryRequest: Int,
    shelfNavigation: com.atlas.atlastv.ui.browse.BrowseShelfNavigation,
    onNavigationChanged: () -> Unit,
    restoreFocus: Boolean,
    onRestoreFocusConsumed: () -> Unit,
    ready: MoviesCatalogState.Ready,
    previousFocusRequester: FocusRequester?,
    onMovieFocused: (MovieItem) -> Unit,
    onMovieSelected: (MovieItem) -> Unit,
    modifier: Modifier,
    categoryBrowser: MovieCategoryBrowser?,
    restoreCategoryRow: String?,
    onCategoryRestoreConsumed: () -> Unit,
    onSeeAll: ((BrowseRow<MovieItem>) -> Unit)?,
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
            val index = rows.indexOfFirst { it.id == restoreCategoryRow && (it.categoryKey != null || it.id == "movies_watchlist") }
            if (index >= 0) {
                list.scrollToItem(index)
                androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == index } }
                    .first { it }
                androidx.compose.runtime.withFrameNanos { }
                headerRequesters[restoreCategoryRow]?.requestFocus()
            } else {
                list.scrollToItem(0)
                androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == 0 } }.first { it }
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
    LaunchedEffect(rows.map { row -> row.id to row.items.map { it.streamId } }) {
        if (contentFocused && focusedItem != null && rows.none { row -> row.id == focusedItem?.first && row.items.any { it.streamId == focusedItem?.second } }) {
            firstFocusRequester.requestFocus()
        }
    }
    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { contentFocused = it.hasFocus }
            .testTag("movies_row_page")
            .semantics { contentDescription = "movies_row_page" },
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.id }) { rowIndex, row ->
            BrowseMovieRow(
                row = row,
                navigation = shelfNavigation,
                onNavigationChanged = onNavigationChanged,
                progress = { movie -> progressForMovie(movie, ready.watchProgress) },
                firstFocusRequester = rowRequesters[row.id],
                previousFocusRequester = rows.getOrNull(rowIndex - 1)?.let { rowRequesters[it.id] }
                    ?: if (rowIndex == 0) previousFocusRequester else null,
                nextFocusRequester = rows.getOrNull(rowIndex + 1)?.let { rowRequesters[it.id] },
                onMovieFocused = { movie ->
                    focusedItem = row.id to movie.streamId
                    shelfNavigation.select(row.id, movie.streamId, row.items.indexOfFirst { it.streamId == movie.streamId })
                    onNavigationChanged(); onMovieFocused(movie)
                },
                onMovieSelected = onMovieSelected,
                seeAllRequester = headerRequesters[row.id],
                onSeeAll = onSeeAll?.takeIf { row.categoryKey != null || row.id == "movies_watchlist" }?.let { action -> { action(row) } },
            )
        }
    }
}

@Composable
private fun MovieCategoryPreviewRow(
    movies: List<MovieItem>,
    firstFocusRequester: FocusRequester,
    playRequester: FocusRequester,
    restoreMovieId: Int?,
    onRestoreConsumed: () -> Unit,
    onRowFocused: () -> Unit,
    onRowLeft: () -> Unit,
    onMovieSelected: (MovieItem) -> Unit,
) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val requesters = remember(movies.map { it.streamId }) { movies.associate { it.streamId to FocusRequester() } }
    LaunchedEffect(restoreMovieId, movies.map { it.streamId }) {
        if (restoreMovieId != null) {
            val index = movies.indexOfFirst { it.streamId == restoreMovieId }
            if (index >= 0) {
                list.scrollToItem(index)
                androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
                androidx.compose.runtime.withFrameNanos { }
                requesters[restoreMovieId]?.requestFocus()
            } else playRequester.requestFocus()
            onRestoreConsumed()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .testTag("movie_more_like_this_row")
            .semantics { contentDescription = "More in this category" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "More in this category",
            color = AtlasText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("movie_more_like_this_title").semantics { contentDescription = "More in this category" },
        )
        LazyRow(state = list, modifier = Modifier.testTag("movie_category_preview_viewport"), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
            itemsIndexed(movies, key = { _, movie -> "more-like-this-${movie.streamId}" }) { index, movie ->
                MoviePoster(
                    movie = movie,
                    progressLabel = null,
                    modifier = Modifier.focusRequester(requesters.getValue(movie.streamId)).then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier),
                    onFocused = onRowFocused,
                    onSelected = { onRowLeft(); onMovieSelected(movie) },
                    onMoveUp = { onRowLeft(); playRequester.requestFocus() },
                    onMoveDown = {},
                    focusedTag = "focused_movie_more_like_this_card",
                    defaultTag = "movie_more_like_this_card",
                    width = 112.dp,
                    height = 160.dp,
                )
            }
        }
    }
}

@Composable
private fun MovieDetailButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    width: Int = 132,
    testTag: String,
    description: String = testTag,
    modifier: Modifier = Modifier,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(width.dp)
            .height(56.dp)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (enabled) onClick()
                        true
                    }
                    Key.DirectionDown -> {
                        onMoveDown?.invoke()
                        onMoveDown != null
                    }
                    Key.DirectionUp -> { onMoveUp?.invoke(); onMoveUp != null }
                    Key.DirectionLeft -> {
                        onMoveLeft?.invoke()
                        onMoveLeft != null
                    }
                    Key.DirectionRight -> {
                        onMoveRight?.invoke()
                        onMoveRight != null
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BrowseMovieRow(
    row: BrowseRow<MovieItem>,
    navigation: com.atlas.atlastv.ui.browse.BrowseShelfNavigation,
    onNavigationChanged: () -> Unit,
    progress: (MovieItem) -> com.atlas.atlastv.ui.browse.BrowseProgress,
    firstFocusRequester: FocusRequester?,
    previousFocusRequester: FocusRequester?,
    nextFocusRequester: FocusRequester?,
    onMovieFocused: (MovieItem) -> Unit,
    onMovieSelected: (MovieItem) -> Unit,
    seeAllRequester: FocusRequester?,
    onSeeAll: (() -> Unit)?,
) {
    val position = navigation.position(row.id, row.items.map { it.streamId })
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
            modifier = Modifier.weight(1f).testTag("movie_row_title").semantics { contentDescription = "movie_row_title" },
        )
        if (onSeeAll != null) BrowseSearchButton("See All", onSeeAll, "movie_category_see_all_${row.id}",
            Modifier.focusRequester(seeAllRequester ?: FocusRequester()).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionDown, Key.DirectionRight -> { firstFocusRequester?.requestFocus(); true }
                    Key.DirectionUp, Key.DirectionLeft -> { previousFocusRequester?.requestFocus(); true }
                    else -> false
                }
            })
        }
        LazyRow(state = artworkScroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(row.items, key = { _, movie -> "${row.id}-${movie.streamId}" }) { index, movie ->
                val progressState = progress(movie)
                MoviePoster(
                    movie = movie,
                    progressLabel = progressState.label,
                    cardProgress = progressState.cardProgress,
                    modifier = if (index == position.selectedIndex) Modifier.focusRequester(firstFocusRequester ?: FocusRequester()) else Modifier,
                    onFocused = { onMovieFocused(movie) },
                    onSelected = { onMovieSelected(movie) },
                    onMoveUp = { if (onSeeAll != null) seeAllRequester?.requestFocus() else previousFocusRequester?.requestFocus() },
                    onMoveDown = { nextFocusRequester?.requestFocus() },
                    onMoveLeft = if (index == 0 && onSeeAll != null) ({ seeAllRequester?.requestFocus() }) else null,
                )
            }
        }
    }
}

@Composable
private fun MoviePoster(
    movie: MovieItem,
    progressLabel: String?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onSelected: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    focusedTag: String = "focused_poster_card",
    defaultTag: String = "movie_poster_card",
    width: androidx.compose.ui.unit.Dp = 184.dp,
    height: androidx.compose.ui.unit.Dp = 276.dp,
    onMoveLeft: (() -> Unit)? = null,
    cardProgress: com.atlas.atlastv.ui.catalog.CardWatchProgress? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(width)
            .height(height)
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
            .testTag(if (focused) focusedTag else defaultTag)
            .semantics { contentDescription = if (focused) focusedTag else defaultTag },
    ) {
        AtlasPosterCard(
            title = movie.title,
            subtitle = movie.categoryName,
            badge = "Movie",
            imageRef = movie.posterImage,
            focused = focused,
            progressLabel = progressLabel,
            cardProgress = cardProgress,
            modifier = Modifier.fillMaxSize(),
        )
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
private fun BrowseButton(text: String, onClick: () -> Unit, testTag: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(118.dp)
            .height(54.dp)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}
