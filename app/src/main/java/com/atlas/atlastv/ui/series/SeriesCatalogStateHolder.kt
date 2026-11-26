package com.atlas.atlastv.ui.series

import android.content.Context
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.xtream.model.XtreamCredentials
import java.io.File

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface SeriesCatalogSource {
    suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState
    suspend fun savePreferences(preferences: SeriesPreferences) {}
    suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) {}
    suspend fun setWatchlisted(item: SeriesItem, saved: Boolean, series: List<SeriesItem>, ids: Set<Int>) {
        saveWatchlist(series, ids)
    }
    suspend fun clearPreferences() {}
    suspend fun clearWatchlist() {}
}

class SeriesCatalogStateHolder(
    private val source: SeriesCatalogSource,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadEpisodes: suspend (Int) -> SeriesEpisodeLoadResult = { SeriesEpisodeLoadResult.Failure("MissingProvider") },
    private val loadSavedEpisodes: suspend (Int) -> SavedShowDetails? = { null },
    private val savedNeedsRefresh: suspend (SavedShowDetails) -> Boolean = { true },
    private val episodeContext: () -> String? = { null },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        mapper: SeriesCatalogMapper = SeriesCatalogMapper(),
        cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
        preferencesStore: SeriesPreferencesStore = SeriesPreferencesStore(File(context.filesDir, SeriesPreferencesStore.FileName)),
        episodeLoader: SeriesEpisodeLoader = SeriesEpisodeLoader(registerArtwork = { entries ->
            com.atlas.atlastv.ui.media.AtlasProviderImageIndex.create(context).registerAllAsync(entries)
        }),
        progressStore: WatchProgressStore = WatchProgressStore(
            File(context.filesDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(AtlasDatabaseProvider.database(context).watchProgressDao()),
        ),
        watchlistStore: SeriesWatchlistStore = SeriesWatchlistStore(
            File(context.filesDir, SeriesWatchlistStore.FileName),
            SeriesWatchlistRoomStore(AtlasDatabaseProvider.database(context).seriesWatchlistDao()),
        ),
        credentialsProvider: () -> XtreamCredentials? = { DebugConfig.debugXtreamCredentials },
        catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        detailsRepository: ShowDetailsRepository = ShowDetailsRepository.create(context, episodeLoader),
    ) : this(RoomSeriesCatalogSource(context, mapper, cacheStore, preferencesStore, progressStore, watchlistStore, catalogRepository), scope,
        loadEpisodes = { id -> credentialsProvider()?.let { detailsRepository.refresh(it, id) } ?: SeriesEpisodeLoadResult.Failure("MissingProvider") },
        loadSavedEpisodes = { id -> credentialsProvider()?.let { detailsRepository.saved(it, id) } },
        savedNeedsRefresh = detailsRepository::stale,
        episodeContext = { credentialsProvider()?.let(::providerDetailsKey) ?: "no-provider" })

    var state: SeriesCatalogState by mutableStateOf(SeriesCatalogState.Loading)
        private set
    var catalogVersion by mutableStateOf(0L)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var refreshError by mutableStateOf<String?>(null)
        private set
    private var preferencesSaveError by mutableStateOf<String?>(null)
    private var watchlistSaveError by mutableStateOf<String?>(null)
    val saveError: String? get() = watchlistSaveError ?: preferencesSaveError
    var watchlistSaving by mutableStateOf(false)
        private set
    val canRetryWatchlistSave: Boolean get() = watchlistSaveError != null && pendingWatchlistWrite != null && !watchlistSaving
    private var pendingWatchlistIds: Set<Int>? = null
    private var pendingWatchlistWrite: (suspend () -> Unit)? = null
    private var watchlistWriteVersion = 0L
    private var watchlistWrites = 0

    private var rememberedAvailability by mutableStateOf<Pair<Int, Boolean>?>(null)
    fun availabilityFor(item: SeriesItem): Boolean =
        (state as? SeriesCatalogState.Ready)?.series?.firstOrNull { it.seriesId == item.seriesId }?.available
            ?: rememberedAvailability?.takeIf { it.first == item.seriesId }?.second ?: item.available

    private var loadedCatalogVersion = -1L
    private var requestVersion = 0L
    private var selectionVersion = 0L
    private var preferencesVersion = 0L
    private var episodeRequestVersion = 0L
    private var episodeSelectionVersion = 0L
    var episodeVersion by mutableStateOf(0L)
        private set

    // Writes outlive a section change, remain ordered, and never run on the UI thread.
    // Superseded focus preferences are skipped so rapid D-pad movement cannot queue disk writes.
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        scope.launch {
            for (write in writes) {
                try { write() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { watchlistSaveError = SaveFailure }
            }
        }
    }

    fun invalidateCachedCatalog() { catalogVersion++ }
    fun refreshWatchProgress() { invalidateCachedCatalog() }

    private var episodeOwnerKey by mutableStateOf<String?>(null)
    private var explicitEpisodeRefresh = false
    private var episodeLoadedAtMs: Long? = null
    fun episodeContextKey(): String? = episodeContext()
    fun visibleEpisodeState(ready: SeriesCatalogState.Ready): SeriesCatalogState.Ready =
        if (episodeOwnerKey == episodeContext()) ready else ready.copy(episodeState = SeriesEpisodeState.Idle,
            selectedSeasonNumber = null, selectedEpisodeId = null)
    fun retryEpisodes() { explicitEpisodeRefresh = true; episodeVersion++ }

    /** Cached content is usable before optional refresh; the detail effect owns cancellation. */
    suspend fun ensureEpisodesForSelectedSeries(expectedSeriesId: Int? = null) {
        var ready = state as? SeriesCatalogState.Ready ?: return
        val id = ready.selectedSeriesId ?: return
        if (expectedSeriesId != null && expectedSeriesId != id) return
        if (ready.series.firstOrNull { it.seriesId == id }?.available != true) return
        val context = episodeContext()
        if (episodeOwnerKey != null && episodeOwnerKey != context) {
            ready = ready.copy(episodeState = SeriesEpisodeState.Idle, selectedSeasonNumber = null, selectedEpisodeId = null)
            state = ready
        }
        if (ready.episodeState is SeriesEpisodeState.Ready && !explicitEpisodeRefresh &&
            (episodeLoadedAtMs == null || (clockMs() - episodeLoadedAtMs!!) in 0 until ShowDetailsRepository.FreshMs)) return
        val force = explicitEpisodeRefresh; explicitEpisodeRefresh = false
        val request = ++episodeRequestVersion
        val selectionAtLoad = episodeSelectionVersion
        episodeOwnerKey = context
        fun current(): SeriesCatalogState.Ready? = (state as? SeriesCatalogState.Ready)?.takeIf {
            request == episodeRequestVersion && it.selectedSeriesId == id && context == episodeContext() &&
                it.series.firstOrNull { item -> item.seriesId == id }?.available == true
        }
        try {
            var existing = ready.episodeState as? SeriesEpisodeState.Ready
            if (existing == null) {
                state = ready.copy(episodeState = SeriesEpisodeState.Loading)
                val saved = withContext(dispatcher) { loadSavedEpisodes(id) }
                coroutineContext.ensureActive()
                val latest = current() ?: return
                if (saved != null && saved.seriesId == id && (context == null || saved.providerKey == context) && saved.result.episodes.isNotEmpty()) {
                    existing = SeriesEpisodeState.Ready(saved.result.episodes, saved.result.metadata, saved = true)
                    episodeLoadedAtMs = saved.savedAtMs
                    state = latest.copy(episodeState = existing).withValidEpisodeSelection(
                        preferWatchProgress = selectionAtLoad == episodeSelectionVersion)
                    if (!force && !withContext(dispatcher) { savedNeedsRefresh(saved) }) return
                }
            }
            val latest = current() ?: return
            state = latest.copy(episodeState = existing?.copy(refreshing = true, refreshFailed = false) ?: SeriesEpisodeState.Loading)
            val result = withContext(dispatcher) { loadEpisodes(id) }
            coroutineContext.ensureActive()
            val after = current() ?: return
            val fallback = after.episodeState as? SeriesEpisodeState.Ready
            state = when (result) {
                is SeriesEpisodeLoadResult.Success -> if (result.episodes.isNotEmpty()) {
                    episodeLoadedAtMs = clockMs()
                    after.copy(episodeState = SeriesEpisodeState.Ready(result.episodes,
                        result.metadata.withLastGood(fallback?.metadata), saved = result.fromSaved)).withValidEpisodeSelection(
                            preferWatchProgress = fallback == null && selectionAtLoad == episodeSelectionVersion)
                } else if (fallback != null) after.copy(episodeState = fallback.copy(refreshing = false, refreshFailed = true))
                else after.copy(episodeState = SeriesEpisodeState.Error("No episodes are available yet. Try again, or refresh your library in Settings."))
                is SeriesEpisodeLoadResult.Failure -> if (fallback != null) after.copy(episodeState = fallback.copy(refreshing = false, refreshFailed = true))
                else after.copy(episodeState = SeriesEpisodeState.Error(if (result.category == "MissingProvider")
                    "Add your provider in Settings to load episodes." else "Couldn't load episodes. Please try again."))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutineContext.ensureActive()
            val latest = current() ?: return
            val fallback = latest.episodeState as? SeriesEpisodeState.Ready
            state = latest.copy(episodeState = fallback?.copy(refreshing = false, refreshFailed = true)
                ?: SeriesEpisodeState.Error("Couldn't load episodes. Please try again."))
        } finally {
            val latest = current()
            if (latest != null) state = when (val loaded = latest.episodeState) {
                SeriesEpisodeState.Loading -> latest.copy(episodeState = SeriesEpisodeState.Idle)
                is SeriesEpisodeState.Ready -> latest.copy(episodeState = loaded.copy(refreshing = false))
                else -> latest
            }
        }
    }

    fun selectSeason(seasonNumber: Int?) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        val episodes = (ready.episodeState as? SeriesEpisodeState.Ready)?.episodesForSeason(seasonNumber) ?: return
        if (ready.selectedSeasonNumber == seasonNumber && episodes.any { it.episodeId == ready.selectedEpisodeId }) return
        episodeSelectionVersion++
        state = ready.copy(selectedSeasonNumber = seasonNumber, selectedEpisodeId = episodes.firstOrNull()?.episodeId).withValidEpisodeSelection()
        savePreferences()
    }

    fun rememberEpisode(episode: SeriesEpisodeItem) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        if (ready.selectedEpisodeId == episode.episodeId && ready.selectedSeasonNumber == episode.seasonNumber) return
        episodeSelectionVersion++
        state = ready.copy(selectedSeasonNumber = episode.seasonNumber, selectedEpisodeId = episode.episodeId).withValidEpisodeSelection()
        savePreferences()
    }

    /** A playback lookup can supply the same full list when returning from Home's direct Resume. */
    fun rememberPlaybackEpisodes(seriesId: Int, episodes: SeriesEpisodeState.Ready) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        if (ready.selectedSeriesId != seriesId) return
        episodeRequestVersion++
        episodeOwnerKey = episodeContext()
        episodeLoadedAtMs = clockMs()
        state = ready.copy(episodeState = episodes)
    }

    suspend fun ensureCachedCatalog() {
        if (state !is SeriesCatalogState.Ready || loadedCatalogVersion != catalogVersion) loadCachedCatalog()
    }

    /** The screen owns the load: leaving Series cancels it, without cancelling saved changes. */
    suspend fun loadCachedCatalog() {
        val request = ++requestVersion
        val version = catalogVersion
        val selectedVersion = selectionVersion
        val previous = state as? SeriesCatalogState.Ready
        if (previous == null) state = SeriesCatalogState.Loading
        isRefreshing = true
        refreshError = null
        try {
            val barrier = CompletableDeferred<Unit>()
            writes.send { barrier.complete(Unit) }
            barrier.await()
            val result = withContext(dispatcher) { source.load(previous) }
            coroutineContext.ensureActive()
            if (request != requestVersion || version != catalogVersion) return
            if (result is SeriesCatalogState.Ready) {
                rememberedAvailability?.first?.let { id ->
                    result.series.firstOrNull { it.seriesId == id }?.let { rememberedAvailability = id to it.available }
                }
                val loaded = pendingWatchlistIds?.let { result.copy(watchlistSeriesIds = it).withWatchlistCategory() } ?: result
                val current = state as? SeriesCatalogState.Ready
                val selected = current?.series?.firstOrNull { it.seriesId == current.selectedSeriesId }
                var next = if (selected != null && (selectedVersion != selectionVersion || result.series.any { it.seriesId == selected.seriesId })) {
                    loaded.withRememberedSeries(result.series.firstOrNull { it.seriesId == selected.seriesId } ?: selected)
                } else loaded
                if (current != null && next.selectedSeriesId == current.selectedSeriesId) {
                    next = next.copy(episodeState = current.episodeState, selectedSeasonNumber = current.selectedSeasonNumber,
                        selectedEpisodeId = current.selectedEpisodeId).withValidEpisodeSelection()
                }
                if (next.series.firstOrNull { it.seriesId == next.selectedSeriesId }?.available == false) {
                    episodeRequestVersion++
                    next = next.copy(episodeState = SeriesEpisodeState.Idle, selectedSeasonNumber = null, selectedEpisodeId = null)
                }
                state = next
                loadedCatalogVersion = version
            } else if (previous == null) state = result
            else refreshError = RefreshFailure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            if (request == requestVersion && version == catalogVersion) {
                if (previous == null) state = SeriesCatalogState.Error("Couldn't load Series. Try again, or check your library in Settings.")
                else refreshError = RefreshFailure
            }
        } finally {
            if (request == requestVersion) isRefreshing = false
        }
    }

    fun selectCategory(categoryId: String) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        val selected = ready.seriesForCategory(categoryId).firstOrNull()
        if (selected?.seriesId != ready.selectedSeriesId) episodeRequestVersion++
        state = (selected?.takeIf { it.seriesId != ready.selectedSeriesId }?.let { ready.withRememberedSeries(it) } ?: ready)
            .copy(selectedCategoryId = categoryId, selectedSeriesId = selected?.seriesId, searchQuery = "", searchResult = null).withWatchlistCategory()
        savePreferences()
    }

    fun rememberSeries(item: SeriesItem) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        rememberedAvailability = item.seriesId to (ready.series.firstOrNull { it.seriesId == item.seriesId }?.available ?: item.available)
        if (ready.selectedSeriesId == item.seriesId && ready.series.any { it.seriesId == item.seriesId }) return
        selectionVersion++
        episodeRequestVersion++
        state = ready.withRememberedSeries(item)
        savePreferences()
    }

    fun cycleSortMode() {
        val ready = state as? SeriesCatalogState.Ready ?: return
        state = ready.copy(sortMode = ready.sortMode.next())
        savePreferences()
        invalidateCachedCatalog()
    }

    /** Opening a Show resumes viewing history; merely browsing episodes cannot replace it. */
    fun restoreWatchedEpisodeForDetails() {
        val ready = state as? SeriesCatalogState.Ready ?: return
        state = ready.withValidEpisodeSelection(preferWatchProgress = true)
    }

    fun toggleWatchlistForSelectedSeries() {
        val ready = state as? SeriesCatalogState.Ready ?: return
        ready.series.firstOrNull { it.seriesId == ready.selectedSeriesId }?.let(::toggleWatchlistForSeries)
    }

    /** Detail actions use the displayed Show identity even if a background reload moved browse selection. */
    fun toggleWatchlistForSeries(item: SeriesItem) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        val id = item.seriesId
        if (!item.available && id !in ready.watchlistSeriesIds) return
        val next = if (id in ready.watchlistSeriesIds) ready.watchlistSeriesIds - id else ready.watchlistSeriesIds + id
        rememberedAvailability = id to item.available
        val selected = if (ready.selectedSeriesId == id) ready else {
            episodeRequestVersion++
            ready.withRememberedSeries(item)
        }
        if (ready.selectedSeriesId != id) selectionVersion++
        state = selected.copy(watchlistSeriesIds = next).withWatchlistCategory()
        scheduleWatchlistWrite(next) { source.setWatchlisted(item, id in next, selected.series, next) }
        savePreferences()
    }

    fun clearWatchlist() {
        val ready = state as? SeriesCatalogState.Ready ?: return
        state = ready.copy(selectedCategoryId = SeriesCategoryIds.Watchlist, watchlistSeriesIds = emptySet(),
            selectedSeriesId = null, searchQuery = "", searchResult = null).withWatchlistCategory()
        episodeRequestVersion++
        state = (state as SeriesCatalogState.Ready).copy(episodeState = SeriesEpisodeState.Idle,
            selectedSeasonNumber = null, selectedEpisodeId = null)
        scheduleWatchlistWrite(emptySet()) { source.clearWatchlist() }
        savePreferences()
    }

    fun retryWatchlistSave() {
        if (!canRetryWatchlistSave) return
        val ids = pendingWatchlistIds ?: return
        val action = pendingWatchlistWrite ?: return
        scheduleWatchlistWrite(ids, action)
    }

    private fun scheduleWatchlistWrite(ids: Set<Int>, action: suspend () -> Unit) {
        pendingWatchlistIds = ids.toSet(); pendingWatchlistWrite = action
        val version = ++watchlistWriteVersion
        watchlistSaveError = null
        watchlistWrites++; watchlistSaving = true
        writes.trySend {
            try {
                withContext(dispatcher) { action() }
                if (version == watchlistWriteVersion) {
                    pendingWatchlistIds = null; pendingWatchlistWrite = null; watchlistSaveError = null
                    invalidateCachedCatalog()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == watchlistWriteVersion) watchlistSaveError = SaveFailure
            } finally {
                watchlistWrites--; watchlistSaving = watchlistWrites > 0
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        val ready = state as? SeriesCatalogState.Ready ?: return
        val bounded = query.take(DefaultMaxSeriesSearchQueryLength)
        if (bounded == ready.searchQuery) return
        state = ready.copy(searchQuery = bounded, searchResult = null)
        savePreferences()
        invalidateCachedCatalog()
    }

    fun clearSearch() { onSearchQueryChanged("") }

    fun resetSeriesPreferences() {
        preferencesVersion++
        val ready = state as? SeriesCatalogState.Ready
        if (ready != null) state = ready.copy(selectedCategoryId = SeriesCategoryIds.All,
            selectedSeriesId = ready.series.firstOrNull()?.seriesId,
            sortMode = SeriesSortMode.Provider, searchQuery = "", searchResult = null, episodeState = SeriesEpisodeState.Idle,
            selectedSeasonNumber = null, selectedEpisodeId = null).withWatchlistCategory()
        episodeRequestVersion++
        enqueueWrite(preferences = true) { source.clearPreferences() }
        invalidateCachedCatalog()
    }

    private fun savePreferences() {
        val ready = state as? SeriesCatalogState.Ready ?: return
        val item = ready.series.firstOrNull { it.seriesId == ready.selectedSeriesId }
        val category = ready.categories.firstOrNull { it.id == ready.selectedCategoryId }
        val snapshot = SeriesPreferences(ready.selectedCategoryId, category?.name, ready.selectedSeriesId,
            item?.name, item?.categoryName, ready.sortMode, ready.searchQuery,
            ready.selectedSeasonNumber, ready.selectedEpisodeId)
        val version = ++preferencesVersion
        writes.trySend {
            if (version == preferencesVersion) {
                try {
                    withContext(dispatcher) { source.savePreferences(snapshot) }
                    preferencesSaveError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    preferencesSaveError = SaveFailure
                }
            }
        }
    }

    private fun enqueueWrite(preferences: Boolean = false, action: suspend () -> Unit) {
        writes.trySend {
            try {
                withContext(dispatcher) { action() }
                if (preferences) preferencesSaveError = null else watchlistSaveError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (preferences) preferencesSaveError = SaveFailure else watchlistSaveError = SaveFailure
            }
        }
    }

    private companion object {
        const val RefreshFailure = "Couldn't update Series. Your saved library is still available."
        const val SaveFailure = "Couldn't save your change. Please try again."
    }
}
