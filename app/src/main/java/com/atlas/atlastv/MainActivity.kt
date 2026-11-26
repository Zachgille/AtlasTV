package com.atlas.atlastv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.data.db.toSeriesItem
import com.atlas.atlastv.playlist.debug.DebugPlayableLiveStream
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressPolicy
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.PlaybackStartState
import com.atlas.atlastv.playback.PlaybackStartStateHolder
import com.atlas.atlastv.playback.WatchProgressCoordinator
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.ui.debug.PreparingPlaybackScreen
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.debug.DebugPlaylistLoaderScreen
import com.atlas.atlastv.ui.debug.DebugPlaylistLoaderStateHolder
import com.atlas.atlastv.ui.home.AtlasHomeScreen
import com.atlas.atlastv.ui.home.HomeCard
import com.atlas.atlastv.ui.home.HomeContentType
import com.atlas.atlastv.ui.home.HomeShellStateHolder
import com.atlas.atlastv.ui.home.homeContinueCardId
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.livetv.LiveTvScreen
import com.atlas.atlastv.ui.livetv.LiveTvStreamUrlProvider
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MovieStreamUrlProvider
import com.atlas.atlastv.ui.movies.MoviesCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesScreen
import com.atlas.atlastv.ui.movies.MovieGridStateHolder
import com.atlas.atlastv.ui.movies.RoomMovieGridSource
import com.atlas.atlastv.ui.search.UnifiedSearchScreen
import com.atlas.atlastv.ui.search.UnifiedSearchStateHolder
import com.atlas.atlastv.ui.series.SeriesCatalogStateHolder
import com.atlas.atlastv.ui.series.SeriesCatalogMapper
import com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult
import com.atlas.atlastv.ui.series.SeriesEpisodeLoader
import com.atlas.atlastv.ui.series.SeriesEpisodeItem
import com.atlas.atlastv.ui.series.SeriesEpisodeStreamUrlProvider
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.SeriesScreen
import com.atlas.atlastv.ui.series.SeriesGridStateHolder
import com.atlas.atlastv.ui.series.RoomSeriesGridSource
import com.atlas.atlastv.ui.settings.ProviderSettingsDebugBootstrap
import com.atlas.atlastv.ui.settings.ProviderSettingsStateHolder
import com.atlas.atlastv.ui.settings.SettingsScreen
import com.atlas.atlastv.ui.settings.catalogFreshness
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasText
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var startupProfileEnabled = false
    private inline fun <T> profileStartup(label: String, block: () -> T): T {
        if (!startupProfileEnabled) return block()
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            android.util.Log.i("AtlasStartup", "$label ms=${(android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000}")
        }
    }

    private val pendingDebugIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupProfileEnabled = BuildConfig.DEBUG && intent.getBooleanExtra("atlastv.profileStartup", false)
        profileStartup("debug_bootstrap") { ProviderSettingsDebugBootstrap.bootstrapIfEmpty(applicationContext) }
        pendingDebugIntent.value = intent

        setContent {
            AtlasTvTheme {
                var currentScreen by remember { mutableStateOf(AtlasScreen.Home) }
                var selectedLiveStream by remember { mutableStateOf<LiveTvChannel?>(null) }
                var selectedPlaybackUrl by remember { mutableStateOf<String?>(null) }
                var selectedProgressRecord by remember { mutableStateOf<WatchProgressRecord?>(null) }
                var selectedInitialPositionMs by remember { mutableStateOf<Long?>(null) }
                var selectedStartedFromBeginning by remember { mutableStateOf(false) }
                var playbackReturnScreen by remember { mutableStateOf(AtlasScreen.LiveTv) }
                var movieDetailRequest by remember { mutableStateOf(0) }
                var seriesDetailRequest by remember { mutableStateOf(0) }
                var selectedMovieForDetails by remember { mutableStateOf<MovieItem?>(null) }
                var selectedSeriesForDetails by remember { mutableStateOf<SeriesItem?>(null) }
                var selectedEpisodeForDetails by remember { mutableStateOf<SeriesEpisodeItem?>(null) }
                var detailReturnScreen by remember { mutableStateOf<AtlasScreen?>(null) }
                val scope = rememberCoroutineScope()
                val debugStateHolder = remember { DebugPlaylistLoaderStateHolder(scope) }
                val appContext = this.applicationContext
                val playbackPreferences = remember { profileStartup("playbackPreferences") { com.atlas.atlastv.playback.PlaybackPreferencesStateHolder(
                    com.atlas.atlastv.playback.DataStorePlaybackPreferencesStore.create(appContext), scope)  } }
                val trackPreferences = remember { profileStartup("trackPreferences") { com.atlas.atlastv.playback.TrackPreferencesStateHolder(
                    com.atlas.atlastv.playback.DataStorePlaybackPreferencesStore.create(appContext), scope)  } }
                val providerSettingsStateHolder = remember { profileStartup("providerSettingsStateHolder") { ProviderSettingsStateHolder.create(appContext, scope)  } }
                val settingsNavigation = remember { com.atlas.atlastv.ui.settings.SettingsNavigationState() }
                fun providerCredentials() = providerSettingsStateHolder.savedCredentialsOrNull() ?: DebugConfig.debugXtreamCredentials
                val discoveryBrowser = remember { profileStartup("discoveryBrowser") { com.atlas.atlastv.ui.home.DiscoveryCollectionBrowser(AtlasDatabaseProvider.database(appContext).safeCatalogDao())  } }
                val homeStateHolder = remember { profileStartup("homeStateHolder") { HomeShellStateHolder(appContext, credentialsProvider = ::providerCredentials)  } }
                val safeCatalogRepository = remember { profileStartup("safeCatalogRepository") { SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao())  } }
                var catalogPolicyReady by remember { mutableStateOf(false) }
                var catalogPolicyFailed by remember { mutableStateOf(false) }
                var catalogPolicyAttempt by remember { mutableStateOf(0) }
                LaunchedEffect(catalogPolicyAttempt) {
                    catalogPolicyFailed = false
                    try {
                        profileStartup("language_policy") { withContext(Dispatchers.IO) { AtlasDatabaseProvider.database(appContext).safeCatalogDao().applyEnglishCategoryPolicy() } }
                        catalogPolicyReady = true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { catalogPolicyFailed = true }
                }
                // Record usable library UI, rather than the earlier loading-shell frame.
                ReportDrawnWhen { catalogPolicyFailed ||
                    (catalogPolicyReady && homeStateHolder.state !is com.atlas.atlastv.ui.home.HomeShellState.Loading) }
                val searchStateHolder = remember { profileStartup("searchStateHolder") { UnifiedSearchStateHolder(appContext, scope, safeCatalogRepository,
                    sessionOwner = { providerCredentials()?.let { com.atlas.atlastv.ui.series.providerDetailsKey(it) } })  } }
                val liveTvStateHolder = remember { profileStartup("liveTvStateHolder") { LiveTvCatalogStateHolder(appContext, scope = scope)  } }
                val liveTvStreamUrlProvider = remember { LiveTvStreamUrlProvider() }
                val moviesStateHolder = remember { profileStartup("moviesStateHolder") { MoviesCatalogStateHolder(appContext, scope = scope, credentialsProvider = ::providerCredentials)  } }
                val catalogBrowseOwner = providerCredentials()?.let { com.atlas.atlastv.ui.series.providerDetailsKey(it) }
                val movieGrid = remember(catalogBrowseOwner) { profileStartup("movieGrid") { MovieGridStateHolder(RoomMovieGridSource(AtlasDatabaseProvider.database(appContext).safeCatalogDao()))  } }
                val movieCategories = remember(catalogBrowseOwner) { profileStartup("movieCategories") {
                    val dao = AtlasDatabaseProvider.database(appContext).safeCatalogDao()
                    com.atlas.atlastv.ui.movies.MovieCategoryBrowser(
                        com.atlas.atlastv.ui.movies.MovieCategoriesStateHolder(com.atlas.atlastv.ui.movies.RoomMovieCategoriesSource(dao)),
                        { name -> MovieGridStateHolder(com.atlas.atlastv.ui.movies.RoomMovieCategoryGridSource(dao, name), allowEmptyRefresh = true) })
                 } }
                val movieWatchlist = remember(catalogBrowseOwner) { profileStartup("movieWatchlist") {
                    com.atlas.atlastv.ui.movies.MovieWatchlistBrowser(MovieGridStateHolder(
                        com.atlas.atlastv.ui.movies.RoomMovieWatchlistGridSource(AtlasDatabaseProvider.database(appContext).movieWatchlistDao()),
                        allowEmptyRefresh = true))
                 } }
                val movieBrowseSession = remember(catalogBrowseOwner, movieGrid, movieCategories, movieWatchlist) {
                    com.atlas.atlastv.ui.movies.MovieBrowseSessionStateHolder(
                        com.atlas.atlastv.ui.movies.FileMovieBrowseSessionStore(java.io.File(appContext.filesDir,
                            com.atlas.atlastv.ui.movies.FileMovieBrowseSessionStore.FileName)),
                        catalogBrowseOwner, movieGrid, movieCategories, movieWatchlist)
                }
                val movieStreamUrlProvider = remember { MovieStreamUrlProvider() }
                val showDetailsRepository = remember { profileStartup("showDetailsRepository") { com.atlas.atlastv.ui.series.ShowDetailsRepository.create(appContext)  } }
                val seriesStateHolder = remember { profileStartup("seriesStateHolder") { SeriesCatalogStateHolder(appContext, scope = scope, credentialsProvider = ::providerCredentials,
                    detailsRepository = showDetailsRepository)  } }
                val seriesGrid = remember(catalogBrowseOwner) { profileStartup("seriesGrid") { SeriesGridStateHolder(RoomSeriesGridSource(AtlasDatabaseProvider.database(appContext).safeCatalogDao()))  } }
                val seriesCategories = remember(catalogBrowseOwner) { profileStartup("seriesCategories") {
                    val dao = AtlasDatabaseProvider.database(appContext).safeCatalogDao()
                    com.atlas.atlastv.ui.series.SeriesCategoryBrowser(
                        com.atlas.atlastv.ui.series.SeriesCategoriesStateHolder(com.atlas.atlastv.ui.series.RoomSeriesCategoriesSource(dao)),
                        { name -> SeriesGridStateHolder(com.atlas.atlastv.ui.series.RoomSeriesCategoryGridSource(dao, name), allowEmptyRefresh = true) })
                 } }
                val seriesWatchlist = remember(catalogBrowseOwner) { profileStartup("seriesWatchlist") { com.atlas.atlastv.ui.series.SeriesWatchlistBrowser(
                    SeriesGridStateHolder(com.atlas.atlastv.ui.series.RoomSeriesWatchlistGridSource(
                        AtlasDatabaseProvider.database(appContext).seriesWatchlistDao()), allowEmptyRefresh = true))  } }
                val seriesBrowseSession = remember(catalogBrowseOwner, seriesGrid, seriesCategories, seriesWatchlist) {
                    com.atlas.atlastv.ui.series.SeriesBrowseSessionStateHolder(
                        com.atlas.atlastv.ui.series.FileSeriesBrowseSessionStore(java.io.File(appContext.filesDir,
                            com.atlas.atlastv.ui.series.FileSeriesBrowseSessionStore.FileName)),
                        catalogBrowseOwner, seriesGrid, seriesCategories, seriesWatchlist)
                }
                val seriesEpisodeStreamUrlProvider = remember { SeriesEpisodeStreamUrlProvider() }
                val myList = remember { profileStartup("myList") {
                    val source = com.atlas.atlastv.ui.mylist.RoomMyListSource.create(appContext)
                    com.atlas.atlastv.ui.mylist.MyListBrowser({ filter -> com.atlas.atlastv.ui.mylist.MyListGridSource { index, id -> source.load(filter, index, id) } })
                 } }
                val nextEpisodes = remember { com.atlas.atlastv.playback.NextEpisodeStateHolder(loadEpisodes = { id ->
                    providerCredentials()?.let { showDetailsRepository.forPlayback(it, id) }
                        ?: com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult.Failure("MissingProvider")
                }, contextKey = { providerCredentials()?.let { credentials -> com.atlas.atlastv.ui.series.providerDetailsKey(credentials) } },
                    refreshEpisodes = { id -> providerCredentials()?.let { showDetailsRepository.forPlayback(it, id, forceRefresh = true) }
                        ?: com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult.Failure("MissingProvider") }) }
                val watchProgressStore = remember { profileStartup("watchProgressStore") {
                    WatchProgressStore(
                        File(appContext.filesDir, WatchProgressStore.FileName),
                        WatchProgressRoomStore(AtlasDatabaseProvider.database(appContext).watchProgressDao()),
                    )
                 } }
                val progressCoordinator = remember { profileStartup("progressCoordinator") { WatchProgressCoordinator.forStore(watchProgressStore)  } }
                val continueWatchingActions = remember { com.atlas.atlastv.ui.home.ContinueWatchingActionsStateHolder(progressCoordinator, scope) }
                val episodeWatchActions = remember { com.atlas.atlastv.ui.series.EpisodeWatchActionsStateHolder(progressCoordinator, scope) }
                val movieWatchActions = remember { com.atlas.atlastv.ui.movies.MovieWatchActionsStateHolder(progressCoordinator, scope) }
                val playbackStart = remember { PlaybackStartStateHolder(scope, progressCoordinator::loadSnapshot) }
                val playbackStartState by playbackStart.state.collectAsState()
                val progressRevision by progressCoordinator.revision.collectAsState()
                fun refreshProgressState() {
                    homeStateHolder.refresh()
                    moviesStateHolder.refreshWatchProgress()
                    seriesStateHolder.refreshWatchProgress()
                }
                var discoveryRevision by remember { mutableStateOf(0) }
                fun invalidateRefreshedCatalog(refreshDetails: Boolean) {
                    discoveryRevision++
                    homeStateHolder.refresh(clearHeroMetadata = refreshDetails)
                    moviesStateHolder.invalidateCachedCatalog()
                    seriesStateHolder.invalidateCachedCatalog()
                    searchStateHolder.invalidateCatalog()
                    if (refreshDetails) {
                        moviesStateHolder.retryMovieInfo()
                        seriesStateHolder.retryEpisodes()
                        nextEpisodes.deactivate(clearCache = true)
                    }
                }
                fun refreshMoviesShowsCatalog() {
                    providerSettingsStateHolder.syncMoviesAndShowsAsync { invalidateRefreshedCatalog(refreshDetails = true) }
                }
                LaunchedEffect(Unit) {
                    var completed = com.atlas.atlastv.data.catalog.CatalogRefreshSignals.changes.value.completedRevision
                    com.atlas.atlastv.data.catalog.CatalogRefreshSignals.changes.drop(1).collect { signal ->
                        providerSettingsStateHolder.reloadSyncStatus()
                        if (signal.completedRevision != completed) {
                            completed = signal.completedRevision
                            invalidateRefreshedCatalog(refreshDetails = false)
                        }
                    }
                }
                LaunchedEffect(catalogPolicyReady, providerSettingsStateHolder.state.hasSavedCredentials) {
                    if (!catalogPolicyReady || !providerSettingsStateHolder.state.hasSavedCredentials) return@LaunchedEffect
                    androidx.compose.runtime.snapshotFlow { homeStateHolder.state !is com.atlas.atlastv.ui.home.HomeShellState.Loading }.first { it }
                    kotlinx.coroutines.delay(5_000)
                    // Deferred Android initialization and DB scheduling never block the usable Home frame.
                    for (attempt in 0..2) {
                        try {
                            withContext(Dispatchers.IO) { com.atlas.atlastv.data.catalog.CatalogRefreshScheduler.ensure(appContext).result.get() }
                            break
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { if (attempt < 2) kotlinx.coroutines.delay(30_000) }
                    }
                }
                fun prepareMoviePlayback(movie: MovieItem, fromBeginning: Boolean) {
                    if (currentScreen != AtlasScreen.Movies) moviesStateHolder.categoryNavigation.clear()
                    if (currentScreen != AtlasScreen.Movies) detailReturnScreen = currentScreen.takeIf { it == AtlasScreen.Home }
                    selectedMovieForDetails = movie
                    selectedSeriesForDetails = null
                    moviesStateHolder.rememberMovie(movie)
                    movieDetailRequest += 1
                    selectedLiveStream = movie.toLiveTvChannel()
                    playbackReturnScreen = AtlasScreen.Movies
                    val prepare: suspend () -> String? = {
                        withContext(Dispatchers.IO) { movieStreamUrlProvider.buildPlaybackUrl(providerCredentials(), movie) }
                    }
                    if (fromBeginning) playbackStart.startOver(movie.toProgressRecord(), prepare)
                    else playbackStart.start(movie.toProgressRecord(), prepare)
                    currentScreen = AtlasScreen.PreparingPlayback
                }
                fun startMoviePlayback(movie: MovieItem) = prepareMoviePlayback(movie, false)
                fun startMovieOver(movie: MovieItem) = prepareMoviePlayback(movie, true)
                fun prepareSeriesEpisodePlayback(series: SeriesItem, episode: SeriesEpisodeItem, fromBeginning: Boolean) {
                    if (currentScreen != AtlasScreen.Series && currentScreen != AtlasScreen.DebugLivePlayback) {
                        detailReturnScreen = currentScreen.takeIf { it == AtlasScreen.Home }
                    }
                    selectedSeriesForDetails = series
                    selectedEpisodeForDetails = episode
                    selectedMovieForDetails = null
                    seriesStateHolder.rememberSeries(series)
                    nextEpisodes.loadedFor(series.seriesId)?.let { seriesStateHolder.rememberPlaybackEpisodes(series.seriesId, it) }
                    seriesStateHolder.rememberEpisode(episode)
                    seriesDetailRequest += 1
                    selectedLiveStream = episode.toLiveTvChannel(series)
                    playbackReturnScreen = AtlasScreen.Series
                    val prepare: suspend () -> String? = {
                        withContext(Dispatchers.IO) { seriesEpisodeStreamUrlProvider.buildPlaybackUrl(providerCredentials(), episode) }
                    }
                    if (fromBeginning) playbackStart.startOver(episode.toProgressRecord(series), prepare)
                    else playbackStart.start(episode.toProgressRecord(series), prepare)
                    currentScreen = AtlasScreen.PreparingPlayback
                }
                fun startSeriesEpisodePlayback(series: SeriesItem, episode: SeriesEpisodeItem) = prepareSeriesEpisodePlayback(series, episode, false)
                fun startEpisodeOver(series: SeriesItem, episode: SeriesEpisodeItem) = prepareSeriesEpisodePlayback(series, episode, true)
                fun startContinueWatchingPlayback(record: WatchProgressRecord) {
                    when (record.contentType) {
                        WatchProgressContentType.Movie -> startMoviePlayback(record.toMovieItem())
                        WatchProgressContentType.SeriesEpisode -> startSeriesEpisodePlayback(record.toSeriesItem(), record.toSeriesEpisodeItem())
                    }
                }
                fun openMovieDetail(movie: MovieItem) {
                    moviesStateHolder.categoryNavigation.clear()
                    detailReturnScreen = currentScreen.takeIf { it == AtlasScreen.Search || it == AtlasScreen.Home || it == AtlasScreen.MyList || it == AtlasScreen.Collection }
                    movieWatchlist.grid.open = false
                    movieWatchlist.grid.detailMovie = null
                    movieCategories.grid?.open = false
                    movieCategories.grid?.detailMovie = null
                    movieCategories.categoriesOpen = false
                    movieGrid.open = false
                    movieGrid.detailMovie = null
                    selectedMovieForDetails = movie
                    selectedSeriesForDetails = null
                    moviesStateHolder.rememberMovie(movie)
                    movieDetailRequest += 1
                    currentScreen = AtlasScreen.Movies
                }
                fun openSeriesDetail(series: SeriesItem) {
                    selectedEpisodeForDetails = null
                    detailReturnScreen = currentScreen.takeIf { it == AtlasScreen.Search || it == AtlasScreen.Home || it == AtlasScreen.MyList || it == AtlasScreen.Collection }
                    seriesWatchlist.grid.open = false
                    seriesWatchlist.grid.detailSeries = null
                    seriesCategories.grid?.open = false
                    seriesCategories.grid?.detailSeries = null
                    seriesCategories.categoriesOpen = false
                    seriesGrid.open = false
                    seriesGrid.detailSeries = null
                    selectedSeriesForDetails = series
                    selectedMovieForDetails = null
                    seriesStateHolder.rememberSeries(series)
                    seriesDetailRequest += 1
                    currentScreen = AtlasScreen.Series
                }
                suspend fun openDebugSeriesPlaybackIntent(debugIntent: Intent) {
                    if (!BuildConfig.DEBUG || debugIntent.action != DEBUG_ACTION_OPEN_SERIES_PLAYBACK) return
                    val credentials = providerCredentials() ?: return
                    val requestedSeriesId = debugIntent.getIntExtra(DEBUG_EXTRA_SERIES_ID, 0).takeIf { it > 0 }
                    val requestedEpisodeId = debugIntent.getIntExtra(DEBUG_EXTRA_EPISODE_ID, 0).takeIf { it > 0 }
                    val cachedSeries = runCatching {
                        LiveTvCachedCatalogStore(appContext.filesDir)
                            .read()
                            ?.let { SeriesCatalogMapper().mapCachedCatalogJson(it, maxItems = Int.MAX_VALUE).series }
                            .orEmpty()
                    }.getOrDefault(emptyList())
                    val fallbackSeries = safeCatalogRepository.seriesEntities(limit = DEBUG_SERIES_FALLBACK_LIMIT)
                    val seriesItem = requestedSeriesId
                        ?.let { id -> safeCatalogRepository.seriesEntitiesByIds(setOf(id), limit = 1).firstOrNull() }
                        ?.toSeriesItem()
                        ?: requestedSeriesId?.let { id -> cachedSeries.firstOrNull { it.seriesId == id } }
                        ?: fallbackSeries.firstOrNull()?.toSeriesItem()
                        ?: cachedSeries.firstOrNull()
                        ?: return
                    val episodeResult = SeriesEpisodeLoader().loadEpisodes(credentials, seriesItem.seriesId, maxEpisodes = DEBUG_EPISODE_FALLBACK_LIMIT)
                    val episodes = (episodeResult as? SeriesEpisodeLoadResult.Success)?.episodes.orEmpty()
                    val episode = requestedEpisodeId?.let { id -> episodes.firstOrNull { it.episodeId == id } }
                        ?: episodes.firstOrNull { it.containerExtension.equals("mp4", ignoreCase = true) }
                        ?: episodes.firstOrNull()
                        ?: return
                    startSeriesEpisodePlayback(seriesItem, episode)
                }
                fun handleHomeCardSelected(card: HomeCard) {
                    val continueRecord = homeStateHolder.continueWatching.firstOrNull { record ->
                        card.id == record.homeContinueCardId()
                    }
                    if (continueRecord != null) {
                        startContinueWatchingPlayback(continueRecord)
                        return
                    }
                    when (card.contentType) {
                        HomeContentType.Movie -> startMoviePlayback(card.toMovieItem())
                        HomeContentType.Show -> openSeriesDetail(card.toSeriesItem())
                        HomeContentType.SeriesEpisode -> currentScreen = AtlasScreen.Series
                    }
                }

                LaunchedEffect(catalogPolicyReady, discoveryRevision) {
                    if (!catalogPolicyReady) return@LaunchedEffect
                    val account = providerCredentials() ?: return@LaunchedEffect
                    try {
                        val changed = withContext(Dispatchers.IO) {
                            val dao = AtlasDatabaseProvider.database(appContext).safeCatalogDao()
                            val providerKey = com.atlas.atlastv.ui.series.providerDetailsKey(account)
                            fun currentOwner() = providerCredentials()?.let { com.atlas.atlastv.ui.series.providerDetailsKey(it) } == providerKey
                            val indexer = com.atlas.atlastv.data.discovery.ImdbDiscoveryIndexer(dao, { com.atlas.atlastv.data.discovery.ImdbDiscoveryIndex.from(appContext) })
                            suspend fun indexIndependentData(): Int = try { profileStartup("discovery_index") { indexer.update(::currentOwner) } }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { 0 } // Independent data unavailable: never substitute provider scores.
                            val seeded = profileStartup("discovery_seed") { com.atlas.atlastv.data.discovery.DiscoveryLegacySeeder(dao).run(::currentOwner) }
                            val initialIndexed = indexIndependentData()
                            if (seeded + initialIndexed > 0 && currentOwner()) withContext(Dispatchers.Main) {
                                homeStateHolder.refresh()
                                moviesStateHolder.invalidateCachedCatalog()
                                seriesStateHolder.invalidateCachedCatalog()
                            }
                            // Optional provider dates/genres cannot delay the independent local rating index.
                            val filled = profileStartup("discovery_backfill") { com.atlas.atlastv.data.discovery.DiscoveryMetadataBackfill.create(dao).run(account, ::providerCredentials) }
                            val indexed = indexIndependentData()
                            filled + indexed
                        }
                        if (changed > 0) {
                            homeStateHolder.refresh()
                            moviesStateHolder.invalidateCachedCatalog()
                            seriesStateHolder.invalidateCachedCatalog()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Optional discovery never blocks cached browsing or playback. */ }
                }
                val titleRepairOwner = providerCredentials()?.let { com.atlas.atlastv.ui.series.providerDetailsKey(it) }
                LaunchedEffect(catalogPolicyReady, titleRepairOwner) {
                    if (!catalogPolicyReady || titleRepairOwner == null) return@LaunchedEffect
                    try {
                        withContext(Dispatchers.IO) {
                            com.atlas.atlastv.data.catalog.CatalogTitleRepair.create(
                                AtlasDatabaseProvider.database(appContext).catalogTitleRepairDao()).run(::providerCredentials) {
                                withContext(Dispatchers.Main.immediate) {
                                    homeStateHolder.refresh()
                                    moviesStateHolder.invalidateCachedCatalog()
                                    seriesStateHolder.invalidateCachedCatalog()
                                    searchStateHolder.invalidateCatalog()
                                }
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Optional metadata repair never blocks the last-good library. */ }
                }
                LaunchedEffect(progressRevision) { refreshProgressState() }
                LaunchedEffect(playbackStartState) {
                    val ready = playbackStartState as? PlaybackStartState.Ready
                    if (currentScreen == AtlasScreen.PreparingPlayback && ready != null) {
                        selectedProgressRecord = ready.record
                        selectedPlaybackUrl = ready.playbackUrl
                        selectedInitialPositionMs = ready.resumeMs
                        selectedStartedFromBeginning = ready.startedFromBeginning
                        playbackStart.cancel()
                        currentScreen = AtlasScreen.DebugLivePlayback
                    }
                }
                val debugIntentToHandle = pendingDebugIntent.value
                LaunchedEffect(debugIntentToHandle) {
                    val debugIntent = debugIntentToHandle ?: return@LaunchedEffect
                    openDebugSeriesPlaybackIntent(debugIntent)
                    pendingDebugIntent.value = null
                }

                BackHandler(enabled = currentScreen == AtlasScreen.Home) { }
                BackHandler(enabled = currentScreen == AtlasScreen.Search) { currentScreen = AtlasScreen.Home }
                BackHandler(enabled = currentScreen == AtlasScreen.Movies) { currentScreen = AtlasScreen.Home }
                BackHandler(enabled = currentScreen == AtlasScreen.Series) { currentScreen = AtlasScreen.Home }
                BackHandler(enabled = currentScreen == AtlasScreen.LiveTv) { currentScreen = AtlasScreen.Home }
                BackHandler(enabled = currentScreen == AtlasScreen.Settings) { currentScreen = AtlasScreen.Home }
                BackHandler(enabled = currentScreen == AtlasScreen.DebugPlaylistLoader) { currentScreen = AtlasScreen.Settings }

                if (!catalogPolicyReady) {
                    Box(Modifier.fillMaxSize().background(AtlasBackground), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (catalogPolicyFailed) "Your library could not be opened. Try again." else "Opening your library...", color = AtlasText)
                            if (catalogPolicyFailed) com.atlas.atlastv.ui.browse.BrowseSearchButton("Retry", { catalogPolicyAttempt++ }, "library_policy_retry")
                        }
                    }
                } else when (currentScreen) {
                    AtlasScreen.Home -> AtlasHomeScreen(
                        stateHolder = homeStateHolder,
                        onLiveTvSelected = { currentScreen = AtlasScreen.LiveTv },
                        onMoviesSelected = {
                            detailReturnScreen = null
                            selectedMovieForDetails = null
                            currentScreen = AtlasScreen.Movies
                        },
                        onSeriesSelected = {
                            detailReturnScreen = null
                            selectedSeriesForDetails = null
                            currentScreen = AtlasScreen.Series
                        },
                        onSearchSelected = {
                            detailReturnScreen = null
                            selectedMovieForDetails = null
                            selectedSeriesForDetails = null
                            currentScreen = AtlasScreen.Search
                        },
                        onHomeSelected = { currentScreen = AtlasScreen.Home },
                        onSettingsSelected = { currentScreen = AtlasScreen.Settings },
                        onMyListSelected = {
                            homeStateHolder.rememberNavDeparture("top_nav_item_my_list")
                            detailReturnScreen = null
                            currentScreen = AtlasScreen.MyList
                        },
                        catalogFreshness = providerSettingsStateHolder.state.catalogFreshness(),
                        onRefreshCatalogSelected = ::refreshMoviesShowsCatalog,
                        onHomeCardSelected = ::handleHomeCardSelected,
                        onHomeCardDetailsSelected = { card ->
                            if (card.contentType == HomeContentType.Movie) openMovieDetail(card.toMovieItem())
                            else openSeriesDetail(card.toSeriesItem())
                        },
                        onCollectionSelected = { collection -> discoveryBrowser.open(collection); currentScreen = AtlasScreen.Collection },
                        onRemoveContinueWatching = continueWatchingActions::remove,
                        continueWatchingActions = continueWatchingActions,
                    )
                    AtlasScreen.Collection -> com.atlas.atlastv.ui.home.DiscoveryCollectionScreen(discoveryBrowser,
                        revision = moviesStateHolder.catalogVersion + seriesStateHolder.catalogVersion,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onItem = { card -> if(card.contentType==HomeContentType.Movie) openMovieDetail(card.toMovieItem()) else openSeriesDetail(card.toSeriesItem()) })
                    AtlasScreen.MyList -> com.atlas.atlastv.ui.mylist.MyListScreen(
                        browser = myList,
                        revision = moviesStateHolder.catalogVersion + seriesStateHolder.catalogVersion,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onItem = { item -> when (item) {
                            is com.atlas.atlastv.ui.mylist.MyListItem.Movie -> openMovieDetail(item.item)
                            is com.atlas.atlastv.ui.mylist.MyListItem.Show -> openSeriesDetail(item.item)
                        } },
                        statusMessage = when {
                            moviesStateHolder.canRetryWatchlistSave || seriesStateHolder.canRetryWatchlistSave -> "Couldn't save your change. Please try again."
                            moviesStateHolder.watchlistSaving || seriesStateHolder.watchlistSaving -> "Saving My List…"
                            else -> null
                        },
                        onRetrySave = if (moviesStateHolder.canRetryWatchlistSave || seriesStateHolder.canRetryWatchlistSave)
                            ({ moviesStateHolder.retryWatchlistSave(); seriesStateHolder.retryWatchlistSave() }) else null,
                    )
                    AtlasScreen.LiveTv -> LiveTvScreen(
                        stateHolder = liveTvStateHolder,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onChannelSelected = { channel ->
                            selectedLiveStream = channel
                            selectedPlaybackUrl = liveTvStreamUrlProvider.buildPlaybackUrl(DebugConfig.debugXtreamCredentials, channel)
                            selectedProgressRecord = null
                            selectedInitialPositionMs = null
                            selectedStartedFromBeginning = false
                            playbackReturnScreen = AtlasScreen.LiveTv
                            currentScreen = AtlasScreen.DebugLivePlayback
                        },
                    )
                    AtlasScreen.Movies -> MoviesScreen(
                        stateHolder = moviesStateHolder,
                        gridHolder = movieGrid,
                        browseSession = movieBrowseSession,
                        categoryBrowser = movieCategories,
                        watchlistBrowser = movieWatchlist,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onMovieSelected = ::startMoviePlayback,
                        onStartOver = ::startMovieOver,
                        movieWatchActions = movieWatchActions,
                        openSelectedMovieDetailsRequest = movieDetailRequest,
                        selectedMovieForDetails = selectedMovieForDetails,
                        onDetailsClosed = detailReturnScreen?.let { destination -> { detailReturnScreen = null; currentScreen = destination } },
                    )
                    AtlasScreen.Series -> SeriesScreen(
                        stateHolder = seriesStateHolder,
                        gridHolder = seriesGrid,
                        browseSession = seriesBrowseSession,
                        categoryBrowser = seriesCategories,
                        watchlistBrowser = seriesWatchlist,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onEpisodeSelected = ::startSeriesEpisodePlayback,
                        onStartOver = ::startEpisodeOver,
                        episodeWatchActions = episodeWatchActions,
                        selectedEpisodeForDetails = selectedEpisodeForDetails,
                        onPlaybackEpisodeRestored = { selectedEpisodeForDetails = null },
                        openSelectedSeriesDetailsRequest = seriesDetailRequest,
                        selectedSeriesForDetails = selectedSeriesForDetails,
                        onDetailsClosed = detailReturnScreen?.let { destination -> { detailReturnScreen = null; currentScreen = destination } },
                    )
                    AtlasScreen.Search -> UnifiedSearchScreen(
                        searchStateHolder = searchStateHolder,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onMovieSelected = ::openMovieDetail,
                        onSeriesSelected = ::openSeriesDetail,
                    )
                    AtlasScreen.DebugPlaylistLoader -> DebugPlaylistLoaderScreen(
                        stateHolder = debugStateHolder,
                        onBack = { currentScreen = AtlasScreen.Settings },
                        onPlayableLiveStreamSelected = { stream ->
                            selectedLiveStream = stream.toLiveTvChannel()
                            selectedPlaybackUrl = stream.streamUrl
                            selectedProgressRecord = null
                            selectedInitialPositionMs = null
                            selectedStartedFromBeginning = false
                            playbackReturnScreen = AtlasScreen.DebugPlaylistLoader
                            currentScreen = AtlasScreen.DebugLivePlayback
                        },
                    )
                    AtlasScreen.Settings -> SettingsScreen(
                        stateHolder = providerSettingsStateHolder,
                        onBack = { currentScreen = AtlasScreen.Home },
                        onAdvancedDiagnostics = { currentScreen = AtlasScreen.DebugPlaylistLoader },
                        navigationState = settingsNavigation,
                        onRefreshCatalog = ::refreshMoviesShowsCatalog,
                        playbackPreferences = playbackPreferences,
                    )
                    AtlasScreen.PreparingPlayback -> PreparingPlaybackScreen(
                        failed = playbackStartState == PlaybackStartState.Failed,
                        onRetry = playbackStart::retry,
                        onBack = {
                            playbackStart.cancel()
                            currentScreen = playbackReturnScreen
                        },
                    )
                    AtlasScreen.DebugLivePlayback -> {
                        val stream = selectedLiveStream
                        val episodeRecord = selectedProgressRecord?.takeIf { it.contentType == WatchProgressContentType.SeriesEpisode && it.seriesId != null }
                        val series = episodeRecord?.let { record -> selectedSeriesForDetails?.takeIf { it.seriesId == record.seriesId } ?: record.toSeriesItem() }
                        LaunchedEffect(episodeRecord?.key, nextEpisodes.retryVersion, seriesStateHolder.episodeContextKey()) {
                            if (episodeRecord != null && series != null) {
                                val ready = (seriesStateHolder.state as? com.atlas.atlastv.ui.series.SeriesCatalogState.Ready)
                                    ?.let(seriesStateHolder::visibleEpisodeState)
                                val seed = (ready?.episodeState as? com.atlas.atlastv.ui.series.SeriesEpisodeState.Ready)
                                    ?.takeIf { ready.selectedSeriesId == series.seriesId && it.episodes.any { item -> item.episodeId == episodeRecord.streamId } }
                                nextEpisodes.ensureNext(series.seriesId, episodeRecord.streamId, seed)
                            }
                        }
                        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { nextEpisodes.deactivate() } }
                        if (stream == null) {
                            currentScreen = AtlasScreen.DebugPlaylistLoader
                        } else {
                            DebugLivePlaybackScreen(
                                stream = stream,
                                streamUrl = selectedPlaybackUrl ?: stream.redactedStreamUrl,
                                onBack = {
                                    val target = playbackReturnScreen
                                    currentScreen = target
                                    refreshProgressState()
                                },
                                initialPositionMs = selectedInitialPositionMs,
                                startedFromBeginning = selectedStartedFromBeginning,
                                progressRecord = selectedProgressRecord,
                                progressStore = watchProgressStore,
                                progressCoordinator = progressCoordinator,
                                providerCredentials = ::providerCredentials,
                                showDiagnostics = playbackReturnScreen == AtlasScreen.DebugPlaylistLoader,
                                nextEpisodeState = if (series != null) nextEpisodes.state else com.atlas.atlastv.playback.NextEpisodeState.Idle,
                                onNextEpisode = series?.let { selected -> { episode -> startSeriesEpisodePlayback(selected, episode) } },
                                onRetryNextEpisode = if (series != null) nextEpisodes::retry else null,
                                autoplayEnabled = playbackPreferences.state.loaded && playbackPreferences.state.enabled,
                                trackPreferences = trackPreferences,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDebugIntent.value = intent
    }

    private enum class AtlasScreen {
        Home,
        Search,
        MyList,
        Collection,
        LiveTv,
        Movies,
        Series,
        Settings,
        DebugPlaylistLoader,
        PreparingPlayback,
        DebugLivePlayback,
    }

    private companion object {
        const val DEBUG_ACTION_OPEN_SERIES_PLAYBACK = "com.atlas.atlastv.DEBUG_OPEN_SERIES_PLAYBACK"
        const val DEBUG_EXTRA_SERIES_ID = "series_id"
        const val DEBUG_EXTRA_EPISODE_ID = "episode_id"
        const val DEBUG_SERIES_FALLBACK_LIMIT = 24
        const val DEBUG_EPISODE_FALLBACK_LIMIT = 24
    }
}

private fun DebugPlayableLiveStream.toLiveTvChannel(): LiveTvChannel =
    LiveTvChannel(
        name = name,
        categoryName = categoryName,
        streamId = streamId,
        containerExtension = "ts",
        redactedStreamUrl = redactedStreamUrl,
    )

private fun MovieItem.toLiveTvChannel(): LiveTvChannel =
    LiveTvChannel(
        name = title,
        categoryName = categoryName,
        streamId = streamId,
        containerExtension = containerExtension,
        redactedStreamUrl = redactedStreamUrl,
    )

private fun SeriesEpisodeItem.toLiveTvChannel(series: SeriesItem): LiveTvChannel =
    LiveTvChannel(
        name = playbackEpisodeTitle(series, this),
        categoryName = series.categoryName,
        streamId = episodeId,
        containerExtension = containerExtension,
        redactedStreamUrl = redactedStreamUrl,
    )

private fun MovieItem.toProgressRecord(): WatchProgressRecord =
    WatchProgressRecord(
        contentType = WatchProgressContentType.Movie,
        streamId = streamId,
        title = title,
        categoryName = categoryName,
        extension = containerExtension,
        positionMs = 0L,
        updatedAtMs = System.currentTimeMillis(),
    )

private fun SeriesEpisodeItem.toProgressRecord(series: SeriesItem): WatchProgressRecord =
    WatchProgressRecord(
        contentType = WatchProgressContentType.SeriesEpisode,
        streamId = episodeId,
        seriesId = series.seriesId,
        title = playbackEpisodeTitle(series, this),
        seriesTitle = cleanProviderTitle(series.name).cleanTitle,
        episodeTitle = cleanProviderTitle(title).cleanTitle,
        categoryName = series.categoryName,
        extension = containerExtension,
        positionMs = 0L,
        updatedAtMs = System.currentTimeMillis(),
    )

private fun playbackEpisodeTitle(series: SeriesItem, episode: SeriesEpisodeItem): String {
    val seriesTitle = cleanProviderTitle(series.name).cleanTitle
    val episodeTitle = cleanProviderTitle(episode.title).cleanTitle
    return if (episodeTitle.startsWith(seriesTitle, ignoreCase = true)) {
        episodeTitle
    } else {
        "$seriesTitle - $episodeTitle"
    }
}

private fun WatchProgressRecord.toMovieItem(): MovieItem =
    MovieItem(
        title = title,
        categoryName = categoryName ?: "Continue Watching",
        streamId = streamId,
        containerExtension = extension ?: "mp4",
        redactedStreamUrl = "watch-progress:movie:$streamId",
    )

private fun HomeCard.toMovieItem(): MovieItem =
    MovieItem(
        title = title,
        categoryName = categoryLabel,
        streamId = streamId,
        containerExtension = extension ?: "mp4",
        redactedStreamUrl = "home:movie:$streamId",
        posterImage = posterImage,
    )

private fun HomeCard.toSeriesItem(): SeriesItem =
    SeriesItem(
        name = title,
        categoryName = categoryLabel,
        seriesId = seriesId ?: streamId,
        posterImage = posterImage,
    )

private fun WatchProgressRecord.toSeriesItem(): SeriesItem =
    SeriesItem(
        name = seriesTitle ?: title,
        categoryName = categoryName ?: "Continue Watching",
        seriesId = seriesId ?: streamId,
    )

private fun WatchProgressRecord.toSeriesEpisodeItem(): SeriesEpisodeItem =
    SeriesEpisodeItem(
        title = episodeTitle ?: title,
        seasonNumber = null,
        episodeNumber = null,
        episodeId = streamId,
        containerExtension = extension ?: "mp4",
        redactedStreamUrl = "watch-progress:series_episode:$streamId",
    )

