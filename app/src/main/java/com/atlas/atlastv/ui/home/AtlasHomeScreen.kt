package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.catalog.displayTitle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import com.atlas.atlastv.AppMetadata
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.components.AtlasPosterCard
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.settings.CatalogFreshnessState
import com.atlas.atlastv.ui.settings.CatalogFreshnessUiState
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AtlasHomeScreen(
    stateHolder: HomeShellStateHolder,
    onSearchSelected: () -> Unit = {},
    onHomeSelected: () -> Unit = {},
    onLiveTvSelected: () -> Unit,
    onMoviesSelected: () -> Unit,
    onSeriesSelected: () -> Unit,
    onSettingsSelected: () -> Unit = {},
    onMyListSelected: (() -> Unit)? = null,
    catalogFreshness: CatalogFreshnessUiState? = null,
    onRefreshCatalogSelected: () -> Unit = {},
    onHomeCardSelected: (HomeCard) -> Unit = {},
    onHomeCardDetailsSelected: ((HomeCard) -> Unit)? = null,
    onCollectionSelected: ((com.atlas.atlastv.data.discovery.DiscoveryCollectionSpec) -> Unit)? = null,
    onRemoveContinueWatching: ((HomeCard) -> Unit)? = null,
    modifier: Modifier = Modifier,
    continueWatchingActions: ContinueWatchingActionsStateHolder? = null,
) {
    val navFocusRequester = remember { FocusRequester() }
    val firstContentFocusRequester = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    LaunchedEffect(stateHolder) { if (stateHolder.returnFocus == null && stateHolder.returnNavTag == null) navFocusRequester.requestFocus() }
    LaunchedEffect(stateHolder, stateHolder.refreshVersion) { stateHolder.loadHome() }
    val retryHome = {
        navFocusRequester.requestFocus()
        stateHolder.refresh()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .background(AtlasBackground)
            .padding(horizontal = 48.dp, vertical = 20.dp)
            .testTag("home_screen")
            .semantics { contentDescription = "home_screen" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeTopNav(
            firstContentFocusRequester = firstContentFocusRequester.takeIf { stateHolder.state !is HomeShellState.Loading },
            firstNavFocusRequester = navFocusRequester,
            onSearchSelected = onSearchSelected,
            onHomeSelected = onHomeSelected,
            onSeriesSelected = onSeriesSelected,
            onMoviesSelected = onMoviesSelected,
            onLiveTvSelected = onLiveTvSelected,
            onSettingsSelected = onSettingsSelected,
            onMyListSelected = onMyListSelected,
            returnNavTag = stateHolder.returnNavTag,
            onReturnNavConsumed = stateHolder::consumeReturnNavFocus,
            onNavFocused = { contentFocused = false },
            isRefreshing = stateHolder.state is HomeShellState.Ready && stateHolder.isRefreshing,
        )
        stateHolder.refreshError?.let { message ->
            HomeStatus(message, "home_refresh_error")
            HomeNavButton(HomeNavItem("Retry", "home_refresh_retry", retryHome), firstContentFocusRequester, {})
        }
        when (val state = stateHolder.state) {
            HomeShellState.Loading -> HomeStatus("Loading your library…", "home_loading")
            is HomeShellState.Error -> {
                HomeStatus(state.message, "home_error")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!state.needsSetup) {
                        HomeNavButton(HomeNavItem("Retry", "home_retry", retryHome), firstContentFocusRequester, {})
                    }
                    HomeNavButton(HomeNavItem("Open Settings", "home_open_settings", onSettingsSelected),
                        if (state.needsSetup) firstContentFocusRequester else null, {})
                }
            }
            is HomeShellState.Ready -> HomeReadyContent(
                ready = state,
                stateHolder = stateHolder,
                navFocusRequester = navFocusRequester,
                firstContentFocusRequester = firstContentFocusRequester,
                recoveryVisible = stateHolder.refreshError != null,
                restoreMissingCardFocus = contentFocused,
                onContentFocused = { contentFocused = true },
                catalogFreshness = catalogFreshness,
                onSearchSelected = onSearchSelected,
                onHomeSelected = onHomeSelected,
                onSeriesSelected = onSeriesSelected,
                onMoviesSelected = onMoviesSelected,
                onLiveTvSelected = onLiveTvSelected,
                onSettingsSelected = onSettingsSelected,
                onRefreshCatalogSelected = onRefreshCatalogSelected,
                onHomeCardSelected = onHomeCardSelected,
                onHomeCardDetailsSelected = onHomeCardDetailsSelected,
                onCollectionSelected = onCollectionSelected,
                onRemoveContinueWatching = onRemoveContinueWatching,
                continueWatchingActions = continueWatchingActions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeReadyContent(
    ready: HomeShellState.Ready,
    stateHolder: HomeShellStateHolder,
    navFocusRequester: FocusRequester,
    firstContentFocusRequester: FocusRequester,
    recoveryVisible: Boolean,
    restoreMissingCardFocus: Boolean,
    onContentFocused: () -> Unit,
    catalogFreshness: CatalogFreshnessUiState?,
    onSearchSelected: () -> Unit,
    onHomeSelected: () -> Unit,
    onSeriesSelected: () -> Unit,
    onMoviesSelected: () -> Unit,
    onLiveTvSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onRefreshCatalogSelected: () -> Unit,
    onHomeCardSelected: (HomeCard) -> Unit,
    onHomeCardDetailsSelected: ((HomeCard) -> Unit)?,
    onCollectionSelected: ((com.atlas.atlastv.data.discovery.DiscoveryCollectionSpec) -> Unit)?,
    onRemoveContinueWatching: ((HomeCard) -> Unit)?,
    continueWatchingActions: ContinueWatchingActionsStateHolder?,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(ready, stateHolder, stateHolder.refreshVersion, stateHolder.heroContextKey()) {
        stateHolder.loadCardPosters(ready)
    }
    var editingContinueWatching by remember { mutableStateOf(false) }
    if (editingContinueWatching && onRemoveContinueWatching != null) {
        ContinueWatchingEditor(ready.rows.firstOrNull { it.id == HomeRowIds.ContinueWatching }?.cards.orEmpty(),
            onRemove = onRemoveContinueWatching, onBack = { editingContinueWatching = false; navFocusRequester.requestFocus() }, modifier = modifier, actions = continueWatchingActions)
        return
    }
    val initialCard = ready.cardById(stateHolder.selectedCardId) ?: ready.cardById(ready.initialFocusCardId) ?: ready.rows.firstNotNullOfOrNull { it.cards.firstOrNull() }
    var focusedCardId by remember { mutableStateOf(initialCard?.id) }
    val focusedCard = ready.cardById(focusedCardId) ?: initialCard
    LaunchedEffect(focusedCard?.heroMetadataKey(), stateHolder, stateHolder.heroRefreshVersion, stateHolder.heroContextKey()) { focusedCard?.let { stateHolder.ensureHeroMetadata(it) } }
    val hero = stateHolder.heroState?.takeIf { it.key == focusedCard?.heroMetadataKey() }
    val heroMetadata = hero?.metadata ?: HomeHeroMetadata()
    val ownHeroRequester = remember { FocusRequester() }
    val heroRequester = if (!recoveryVisible && focusedCard != null) firstContentFocusRequester else ownHeroRequester
    val detailsRequester = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    val ownPromptRequester = remember { FocusRequester() }
    val hasCleanup = onRemoveContinueWatching != null && ready.rows.any { it.id == HomeRowIds.ContinueWatching && it.cards.isNotEmpty() }
    val editRequester = remember { FocusRequester() }
    val recoveryRequester = remember { FocusRequester() }
    val emptyRowFocusRequesters = remember(ready.rows.map { it.id }) {
        ready.rows.associate { it.id to FocusRequester() }
    }
    val rememberedCardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val rowCardFocusRequesters = remember(ready.rows, focusedCard == null, recoveryVisible) { ready.rows.associate { row ->
        row.id to row.cards.associate { card ->
            card.id to if (focusedCard == null && card.id == initialCard?.id && !recoveryVisible) {
                firstContentFocusRequester
            } else rememberedCardRequesters.getOrPut("${row.id}:${card.id}") { FocusRequester() }
        }
    } }
    val scope = rememberCoroutineScope()
    // Prepare one neighbouring shelf and retain one on return instead of rebuilding it on keypress.
    val shelfCache = remember { androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 260.dp, behind = 260.dp) }
    val rows = rememberLazyListState(shelfCache, stateHolder.rowScrollIndex, stateHolder.rowScrollOffset)
    val horizontalStates = remember { mutableStateMapOf<String, LazyListState>() }
    val attachedCards = remember { mutableStateMapOf<String, Boolean>() }
    val collectionRequesters = remember { mutableMapOf<String, FocusRequester>() }
    ready.rows.forEach { collectionRequesters.getOrPut(it.id) { FocusRequester() } }
    val liveKeys = remember(ready.rows) { ready.rows.flatMap { row -> row.cards.map { "${row.id}:${it.id}" } }.toSet() }
    rememberedCardRequesters.keys.retainAll(liveKeys)
    collectionRequesters.keys.retainAll(ready.rows.map { it.id }.toSet())
    stateHolder.horizontalPositions.keys.retainAll(ready.rows.map { it.id }.toSet())
    var pendingFocus by remember { mutableStateOf<HomeRowFocus?>(null) }
    fun focusRow(index: Int, column: Int = 0, header: Boolean = false, preserveScroll: Boolean = false) {
        val target = ready.rows.getOrNull(index) ?: return
        val bounded = column.coerceIn(0,(target.cards.size-1).coerceAtLeast(0))
        val vertical = rows.layoutInfo.visibleItemsInfo.firstOrNull { it.key == target.id }
        val horizontal = horizontalStates[target.id]
        val tile = horizontal?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.index == bounded }
        val card = target.cards.getOrNull(bounded)
        val visibleRow = vertical != null && vertical.offset >= rows.layoutInfo.viewportStartOffset &&
            vertical.offset + vertical.size <= rows.layoutInfo.viewportEndOffset
        if (!header && visibleRow && tile != null && horizontal != null && card != null &&
            tile.offset >= horizontal.layoutInfo.viewportStartOffset && tile.offset + tile.size <= horizontal.layoutInfo.viewportEndOffset &&
            attachedCards["${target.id}:${card.id}"] == true) {
            pendingFocus = null
            rowCardFocusRequesters[target.id]?.get(card.id)?.requestFocus()
        } else pendingFocus = HomeRowFocus(target.id,bounded,header,preserveScroll)
    }
    fun moveRow(index: Int, direction: Int, column: Int) {
        val origin=pendingFocus?.let {p ->ready.rows.indexOfFirst {it.id==p.rowId}}?.takeIf {it>=0} ?: index
        if(origin+direction<0) {pendingFocus=null;heroRequester.requestFocus()}
        else focusRow(origin+direction,column)
    }
    fun focusFirst() {
        val index=ready.rows.indexOfFirst { row -> row.cards.any {it.id==ready.initialFocusCardId} }.coerceAtLeast(0)
        focusRow(index)
    }
    LaunchedEffect(rows) {
        snapshotFlow {rows.firstVisibleItemIndex to rows.firstVisibleItemScrollOffset}.collect {(index,offset) ->
            stateHolder.rowScrollIndex=index;stateHolder.rowScrollOffset=offset
        }
    }
    DisposableEffect(rows) { onDispose {stateHolder.rowScrollIndex=rows.firstVisibleItemIndex;stateHolder.rowScrollOffset=rows.firstVisibleItemScrollOffset} }
    LaunchedEffect(pendingFocus,ready.rows.map {row ->row.id to row.cards.map {it.id}}) {
        val target=pendingFocus ?: return@LaunchedEffect
        val index=ready.rows.indexOfFirst {it.id==target.rowId}
        if(index<0) {pendingFocus=null;heroRequester.requestFocus();return@LaunchedEffect}
        if(!target.preserveScroll || rows.layoutInfo.visibleItemsInfo.none {it.key==target.rowId}) rows.scrollToItem(index)
        snapshotFlow {horizontalStates[target.rowId]}.filter {it!=null}.first()
        if(target.header) {
            androidx.compose.runtime.withFrameNanos { }
            if(pendingFocus!=target) return@LaunchedEffect
            collectionRequesters[target.rowId]?.requestFocus()
        } else {
            val row=ready.rows[index]
            val card=row.cards.getOrNull(target.column)
            if(card==null) {emptyRowFocusRequesters[row.id]?.requestFocus()}
            else {
                val horizontal=horizontalStates.getValue(row.id)
                if(horizontal.layoutInfo.visibleItemsInfo.none {it.index==target.column}) horizontal.scrollToItem(target.column)
                snapshotFlow {attachedCards["${row.id}:${card.id}"]==true}.filter {it}.first()
                androidx.compose.runtime.withFrameNanos { }
                if(pendingFocus!=target) return@LaunchedEffect
                rowCardFocusRequesters[row.id]?.get(card.id)?.requestFocus()
            }
        }
        if(pendingFocus==target) pendingFocus=null
    }
    BackHandler(enabled=restoreMissingCardFocus) {
        pendingFocus=null
        focusedCardId=ready.initialFocusCardId
        focusedCardId?.let(stateHolder::rememberCardFocus)
        navFocusRequester.requestFocus()
        scope.launch {rows.scrollToItem(0)}
    }
    val canShowDetails = onHomeCardDetailsSelected != null && focusedCard != null &&
        (focusedCard.contentType == HomeContentType.Movie || focusedCard.progressLabel != null)

    LaunchedEffect(Unit) {
        stateHolder.returnFocus?.let {focus ->
            androidx.compose.runtime.withFrameNanos { }
            when(focus.target) {
                HomeReturnTarget.Primary -> heroRequester.requestFocus()
                HomeReturnTarget.Details -> (if(canShowDetails) detailsRequester else heroRequester).requestFocus()
                HomeReturnTarget.Card, HomeReturnTarget.Collection -> {
                    val index=ready.rows.indexOfFirst {it.id==focus.rowId || it.cards.any {card ->card.id==focus.cardId}}
                    if(index<0) heroRequester.requestFocus()
                    else focusRow(index,ready.rows[index].cards.indexOfFirst {it.id==focus.cardId}.coerceAtLeast(0),
                        header=focus.target==HomeReturnTarget.Collection && onCollectionSelected!=null && ready.rows[index].collection!=null,preserveScroll=true)
                }
            }
            stateHolder.consumeReturnFocus()
        }
    }
    LaunchedEffect(ready.rows.map {row ->row.cards.map {it.id}},focusedCardId,restoreMissingCardFocus) {
        if(restoreMissingCardFocus && focusedCardId!=null && ready.cardById(focusedCardId)==null) {
            if(heroFocused) heroRequester.requestFocus() else focusFirst()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val hasDetails = canShowDetails
        HomeHero(focusedCard, heroMetadata, fromSaved = hero?.fromSaved == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                focusedCard?.let { card ->
                    HomeNavButton(HomeNavItem(card.heroActionLabel(), "home_hero_primary", {
                        val selected = ready.cardById(focusedCardId) ?: card
                        stateHolder.rememberDeparture(selected.id, HomeReturnTarget.Primary); onHomeCardSelected(selected)
                    }),
                        heroRequester, onMoveDown = { if (continueWatchingActions?.message != null) recoveryRequester.requestFocus() else focusFirst() },
                        onFocused = { heroFocused = true; onContentFocused() }, height = 42,
                        onMoveUp = { navFocusRequester.requestFocus() },
                        onMoveRight = {
                            if (hasDetails) detailsRequester.requestFocus()
                            else if (catalogFreshness != null) ownPromptRequester.requestFocus()
                        })
                    if (hasDetails) HomeNavButton(HomeNavItem(if (card.contentType == HomeContentType.Movie) "Details" else "Episodes",
                        "home_hero_details", {
                            val selected = ready.cardById(focusedCardId) ?: card
                            stateHolder.rememberDeparture(selected.id, HomeReturnTarget.Details); onHomeCardDetailsSelected?.invoke(selected)
                        }), detailsRequester,
                        onMoveDown = { if (continueWatchingActions?.message != null) recoveryRequester.requestFocus() else focusFirst() }, height = 42,
                        onFocused = { heroFocused = true; onContentFocused() },
                        onMoveUp = { navFocusRequester.requestFocus() }, onMoveLeft = { heroRequester.requestFocus() },
                        onMoveRight = { if (catalogFreshness != null) ownPromptRequester.requestFocus() })
                }
                catalogFreshness?.let { freshness ->
                    HomeCatalogRefreshPrompt(freshness, ownPromptRequester,
                        onMoveDown = { if (continueWatchingActions?.message != null) recoveryRequester.requestFocus() else focusFirst() },
                        onMoveUp = { navFocusRequester.requestFocus() },
                        onMoveLeft = { if (hasDetails) detailsRequester.requestFocus() else heroRequester.requestFocus() },
                        onRefresh = if (freshness.state == CatalogFreshnessState.NeverSynced && freshness.actionLabel == "Open Settings") onSettingsSelected else onRefreshCatalogSelected)
                }
            }
        }
        continueWatchingActions?.let { ContinueWatchingNotice(it, recoveryRequester,
            onUp = { if (focusedCard != null) heroRequester.requestFocus() else navFocusRequester.requestFocus() },
            onDown = { focusFirst() }) }
        LazyColumn(
            state = rows,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("home_rows_viewport")
                ,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        )
        {
            itemsIndexed(ready.rows, key = { _, row -> row.id }) { index, row ->
                val cardRequesters = rowCardFocusRequesters[row.id].orEmpty()
                val emptyRequester = emptyRowFocusRequesters[row.id]
                HomeContentRowView(
                    row = row,
                    posterFallbacks = { if (row.id == HomeRowIds.ContinueWatching) stateHolder.posterFallbacks(it) else emptyList() },
                    artworkOwner = stateHolder.heroContextKey(),
                    cardFocusRequesters = cardRequesters,
                    emptyRowFocusRequester = emptyRequester,
                    onMoveUp = { cardIndex ->
                        if (hasCleanup && row.id == HomeRowIds.ContinueWatching) {
                            editRequester.requestFocus()
                        } else if (index == 0) {
                            heroRequester.requestFocus()
                        } else {
                            moveRow(index,-1,cardIndex)
                        }
                    },
                    onMoveDown = { cardIndex ->
                        moveRow(index,1,cardIndex)
                    },
                    onCardFocused = { focusedCardId = it.id; stateHolder.rememberCardFocus(it.id); heroFocused = false; onContentFocused() },
                    onCardSelected = { card ->
                        stateHolder.rememberDeparture(card.id, HomeReturnTarget.Card)
                        if (row.id == HomeRowIds.ContinueWatching) onHomeCardSelected(card)
                        else (onHomeCardDetailsSelected ?: onHomeCardSelected)(card)
                    },
                    initialCardId = stateHolder.selectedCardId,
                    initialPosition = stateHolder.horizontalPositions[row.id],
                    onPosition = {stateHolder.horizontalPositions[row.id]=it},
                    onRowState = {if(it==null) horizontalStates.remove(row.id) else horizontalStates[row.id]=it},
                    onCardAttached = {id,attached -> if(attached) attachedCards["${row.id}:$id"]=true else attachedCards.remove("${row.id}:$id")},
                    boundaryRequester = collectionRequesters[row.id].takeIf {onCollectionSelected!=null && row.collection!=null},
                    headerAction = if (row.id == HomeRowIds.ContinueWatching && row.cards.isNotEmpty() && onRemoveContinueWatching != null) ({
                        HomeNavButton(HomeNavItem("Edit", "continue_watching_edit", { editingContinueWatching = true }),
                            editRequester, onMoveDown = { if (continueWatchingActions?.message != null) recoveryRequester.requestFocus() else focusFirst() },
                            onMoveUp = { heroRequester.requestFocus() }, height = 34)
                    }) else if(row.collection!=null && onCollectionSelected!=null) ({
                        HomeNavButton(HomeNavItem("See All","home_collection_${row.id}", {
                            stateHolder.rememberCollectionDeparture(row);onCollectionSelected(row.collection)
                        }),collectionRequesters[row.id],onMoveDown={focusRow(index,row.cards.indexOfFirst {it.id==focusedCardId}.coerceAtLeast(0))},
                            onMoveUp={moveRow(index,-1,0)},onMoveLeft={focusRow(index)},
                            onFocused={heroFocused=false;onContentFocused()},height=34)
                    }) else null,
                )
            }
        }
    }
}

@Composable
private fun HomeCatalogRefreshPrompt(
    freshness: CatalogFreshnessUiState,
    focusRequester: FocusRequester,
    onMoveDown: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveLeft: () -> Unit,
    onRefresh: () -> Unit,
) {
    val label = when (freshness.state) {
        CatalogFreshnessState.Syncing -> "Refreshing…"
        CatalogFreshnessState.Failed -> "Retry Refresh"
        CatalogFreshnessState.NeverSynced -> if (freshness.actionLabel == "Open Settings") "Open Settings" else "Refresh Library"
        else -> "Refresh Library"
    }
    HomeNavButton(HomeNavItem(label, "catalog_refresh_prompt", {
        if (freshness.state != CatalogFreshnessState.Syncing) onRefresh()
    }), focusRequester, onMoveDown, height = 42, onMoveUp = onMoveUp, onMoveLeft = onMoveLeft)
}

@Composable
private fun HomeTopNav(
    firstContentFocusRequester: FocusRequester?,
    firstNavFocusRequester: FocusRequester?,
    onSearchSelected: () -> Unit,
    onHomeSelected: () -> Unit,
    onSeriesSelected: () -> Unit,
    onMoviesSelected: () -> Unit,
    onLiveTvSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onMyListSelected: (() -> Unit)?,
    returnNavTag: String?,
    onReturnNavConsumed: () -> Unit,
    onNavFocused: () -> Unit = {},
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val items = listOfNotNull(
        HomeNavItem("Search", "top_nav_item_search", onSearchSelected),
        HomeNavItem("Home", "top_nav_item_home", onHomeSelected),
        HomeNavItem("Shows", "top_nav_item_shows", onSeriesSelected),
        HomeNavItem("Movies", "top_nav_item_movies", onMoviesSelected),
        onMyListSelected?.let { HomeNavItem("My List", "top_nav_item_my_list", it) },
        HomeNavItem("Live TV", "top_nav_item_live_tv", onLiveTvSelected),
        HomeNavItem("Settings", "top_nav_item_settings", onSettingsSelected),
    )
    val navRequesters = remember { mutableMapOf<String, FocusRequester>() }
    items.forEachIndexed { index, item -> navRequesters[item.testTag] = if (index == 0) firstNavFocusRequester ?: FocusRequester() else navRequesters[item.testTag] ?: FocusRequester() }
    LaunchedEffect(returnNavTag) {
        if (returnNavTag != null) {
            androidx.compose.runtime.withFrameNanos { }
            navRequesters[returnNavTag]?.requestFocus()
            onReturnNavConsumed()
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("top_nav")
            .semantics { contentDescription = "top_nav" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(120.dp)) {
        Text(
            text = AppMetadata.DisplayName,
            color = AtlasText,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (isRefreshing) Text("Updating…", color = AtlasMutedText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("home_refreshing"))
        }
        items.forEachIndexed { index, item ->
            HomeNavButton(
                item = item,
                focusRequester = navRequesters[item.testTag],
                onMoveDown = { firstContentFocusRequester?.requestFocus() },
                onFocused = onNavFocused,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeContentRowView(
    row: HomeContentRow,
    posterFallbacks: (HomeCard) -> List<AtlasImageRef>,
    artworkOwner: String?,
    cardFocusRequesters: Map<String, FocusRequester>,
    emptyRowFocusRequester: FocusRequester?,
    onMoveUp: (cardIndex: Int) -> Unit,
    onMoveDown: (cardIndex: Int) -> Unit,
    onCardFocused: (HomeCard) -> Unit,
    onCardSelected: (HomeCard) -> Unit,
    initialCardId: String?,
    headerAction: (@Composable () -> Unit)? = null,
    initialPosition: Pair<Int,Int>? = null,
    onPosition: (Pair<Int,Int>) -> Unit = {},
    onRowState: (LazyListState?) -> Unit = {},
    onCardAttached: (String,Boolean) -> Unit = {_,_->},
    boundaryRequester: FocusRequester? = null,
) {
    val horizontal=rememberLazyListState((initialPosition?.first ?: row.cards.indexOfFirst {it.id==initialCardId}.coerceAtLeast(0)).coerceIn(0,(row.cards.size-1).coerceAtLeast(0)),initialPosition?.second ?: 0,
        prefetchStrategy = remember { androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 7) })
    LaunchedEffect(horizontal) {snapshotFlow {horizontal.firstVisibleItemIndex to horizontal.firstVisibleItemScrollOffset}.collect(onPosition)}
    com.atlas.atlastv.ui.media.NearbyArtworkPrefetch(remember(row.cards) { row.cards.map { it.posterImage } }) {
        horizontal.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    }
    DisposableEffect(horizontal) {onRowState(horizontal);onDispose {onPosition(horizontal.firstVisibleItemIndex to horizontal.firstVisibleItemScrollOffset);onRowState(null)}}
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(row.testTag())
            .semantics { contentDescription = row.testTag() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.title, color = AtlasText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (row.cards.isEmpty()) {
                Text("Empty", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
            }
            headerAction?.invoke()
        }
        if (row.cards.isEmpty()) {
            HomeEmptyRowCard(
                row = row,
                focusRequester = emptyRowFocusRequester,
                onMoveUp = { onMoveUp(0) },
                onMoveDown = { onMoveDown(0) },
            )
        } else {
            LazyRow(
                state = horizontal,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                // The card frame already reserves its scale; keep the first row within its existing height.
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                itemsIndexed(row.cards, key = { _, card -> card.id }) { index, card ->
                    DisposableEffect(card.id) {onCardAttached(card.id,true);onDispose {onCardAttached(card.id,false)}}
                    HomePosterCard(
                        card = card,
                        posterFallbacks = posterFallbacks(card),
                        artworkIdentity = "${artworkOwner}:${card.heroCacheIdentity() ?: card.id}",
                        modifier = cardFocusRequesters[card.id]?.let { Modifier.focusRequester(it) } ?: Modifier,
                        onFocused = { onCardFocused(card) },
                        onClick = { onCardSelected(card) },
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) },
                        onMoveLeft = if(index==0 && boundaryRequester!=null) ({boundaryRequester.requestFocus()}) else null,
                        onMoveRight = if(index==row.cards.lastIndex && boundaryRequester!=null) ({boundaryRequester.requestFocus()}) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePosterCard(
    card: HomeCard,
    posterFallbacks: List<AtlasImageRef>,
    artworkIdentity: String,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(112.dp)
            // Focus scrolls the reserved frame, including the enlarged poster.
            .height(200.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }
                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }
                    Key.DirectionLeft -> if(onMoveLeft!=null) {onMoveLeft();true} else false
                    Key.DirectionRight -> if(onMoveRight!=null) {onMoveRight();true} else false
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .testTag(if (focused) "focused_home_card" else "home_card_${card.id}")
            .semantics { contentDescription = if (focused) "focused_home_card" else "home_card_${card.id}" }
            .padding(vertical = 6.dp),
    ) {
        AtlasPosterCard(
            title = card.title,
            subtitle = card.categoryLabel,
            badge = card.contentType.displayName,
            imageRef = card.posterImage,
            posterFallbacks = posterFallbacks,
            artworkIdentity = artworkIdentity,
            focused = focused,
            progressLabel = card.progressLabel,
            cardProgress = card.cardProgress,
            modifier = Modifier.fillMaxSize().scale(if (focused) 1.06f else 1f),
        )
    }
}

@Composable
private fun HomeEmptyRowCard(
    row: HomeContentRow,
    focusRequester: FocusRequester?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(260.dp)
            .height(92.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }
                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .testTag("${row.testTag()}_empty")
            .semantics { contentDescription = "${row.testTag()}_empty" }
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Nothing saved yet", color = AtlasMutedText, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HomeNavButton(
    item: HomeNavItem,
    focusRequester: FocusRequester?,
    onMoveDown: () -> Unit,
    onFocused: () -> Unit = {},
    height: Int = 46,
    onMoveUp: (() -> Unit)? = null,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .height(height.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(if (focused) AtlasSurfaceFocused else AtlasBackground, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasBackground), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { onMoveUp?.invoke(); onMoveUp != null }
                    Key.DirectionLeft -> { onMoveLeft?.invoke(); onMoveLeft != null }
                    Key.DirectionRight -> { onMoveRight?.invoke(); onMoveRight != null }
                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        item.onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = item.onClick)
            .testTag(item.testTag)
            .semantics { contentDescription = item.testTag }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(item.title, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomeStatus(text: String, testTag: String) {
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

private data class HomeNavItem(
    val title: String,
    val testTag: String,
    val onClick: () -> Unit,
)

private fun HomeShellState.Ready.cardById(id: String?): HomeCard? =
    rows.asSequence().flatMap { it.cards.asSequence() }.firstOrNull { it.id == id }

private fun HomeCard.metadataLine(): String =
    listOfNotNull(if (contentType == HomeContentType.SeriesEpisode) "Show" else contentType.displayName, year).joinToString(" · ")

private fun HomeContentRow.testTag(): String =
    when (id) {
        HomeRowIds.Watchlist -> "watchlist_row"
        HomeRowIds.ContinueWatching -> "continue_watching_row"
        HomeRowIds.Popular -> "popular_row"
        HomeRowIds.RecentlyAddedMovies -> "recently_added_movies_row"
        HomeRowIds.RecentlyAddedShows -> "recently_added_shows_row"
        else -> "home_row_$id"
    }

private data class HomeRowFocus(val rowId: String,val column: Int,val header: Boolean,val preserveScroll: Boolean)
