package com.atlas.atlastv.ui.movies

import android.content.Context
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
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

interface MoviesCatalogSource {
    suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState
    suspend fun categoryPreview(movie: MovieItem, maxItems: Int): List<MovieItem>? = null
    suspend fun savePreferences(preferences: MoviesPreferences) {}
    suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) {}
    suspend fun setWatchlisted(movie: MovieItem, saved: Boolean, movies: List<MovieItem>, ids: Set<Int>) {
        saveWatchlist(movies, ids)
    }
    suspend fun clearPreferences() {}
    suspend fun clearWatchlist() {}
}

class MoviesCatalogStateHolder(
    private val source: MoviesCatalogSource,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadMovieInfo: suspend (Int) -> MovieMetadata? = { null },
    private val loadSavedMovieInfo: suspend (Int) -> SavedMovieDetails? = { null },
    private val savedNeedsRefresh: suspend (SavedMovieDetails) -> Boolean = { true },
    private val movieInfoContext: () -> String? = { null },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        mapper: MoviesCatalogMapper = MoviesCatalogMapper(),
        cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
        preferencesStore: MoviesPreferencesStore = MoviesPreferencesStore(File(context.filesDir, MoviesPreferencesStore.FileName)),
        watchlistStore: MoviesWatchlistStore = MoviesWatchlistStore(
            File(context.filesDir, MoviesWatchlistStore.FileName),
            MoviesWatchlistRoomStore(AtlasDatabaseProvider.database(context).movieWatchlistDao()),
        ),
        progressStore: WatchProgressStore = WatchProgressStore(
            File(context.filesDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(AtlasDatabaseProvider.database(context).watchProgressDao()),
        ),
        catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
        endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
        movieInfoLoader: MovieInfoLoader = MovieInfoLoader(registerArtwork = { entries ->
            com.atlas.atlastv.ui.media.AtlasProviderImageIndex.create(context).registerAllAsync(entries)
        }),
        credentialsProvider: () -> com.atlas.atlastv.xtream.model.XtreamCredentials? = { com.atlas.atlastv.core.config.DebugConfig.debugXtreamCredentials },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        detailsRepository: MovieDetailsRepository = MovieDetailsRepository.create(context, movieInfoLoader),
    ) : this(RoomMoviesCatalogSource(context, mapper, cacheStore, preferencesStore, watchlistStore, progressStore, catalogRepository, endpointBuilder), scope,
        loadMovieInfo = { id -> credentialsProvider()?.let { detailsRepository.refresh(it, id) } },
        loadSavedMovieInfo = { id -> credentialsProvider()?.let { detailsRepository.saved(it, id) } },
        savedNeedsRefresh = detailsRepository::stale,
        movieInfoContext = { credentialsProvider()?.let(::providerMovieDetailsKey) ?: "no-provider" })

    val categoryNavigation = MovieCategoryNavigationState()

    var movieInfoState: MovieInfoState by mutableStateOf(MovieInfoState.Empty)
        private set
    private var movieInfoRequest = 0L
    private var movieInfoOwnerKey by mutableStateOf<String?>(null)
    private var movieInfoLoadedAtMs: Long? = null
    private var forceMovieInfo = false
    var movieInfoVersion by mutableStateOf(0L)
        private set
    fun movieInfoContextKey(): String? = movieInfoContext()
    fun retryMovieInfo() { forceMovieInfo = true; movieInfoVersion++ }
    fun visibleMovieInfoState(streamId: Int): MovieInfoState {
        if (movieInfoOwnerKey != movieInfoContext()) return MovieInfoState.Empty
        return when (val info = movieInfoState) {
            is MovieInfoState.Ready -> info.takeIf { it.streamId == streamId } ?: MovieInfoState.Empty
            is MovieInfoState.Loading -> info.takeIf { it.streamId == streamId } ?: MovieInfoState.Empty
            is MovieInfoState.Unavailable -> info.takeIf { it.streamId == streamId } ?: MovieInfoState.Empty
            MovieInfoState.Empty -> info
        }
    }

    /** Owned by the open detail screen. Optional metadata never gates playback or moves focus. */
    suspend fun ensureMovieInfo(streamId: Int) {
        val ready = state as? MoviesCatalogState.Ready ?: return
        if (ready.selectedMovieStreamId != streamId || ready.movies.firstOrNull { it.streamId == streamId }?.available != true) return
        val context = movieInfoContext()
        var existing = (visibleMovieInfoState(streamId) as? MovieInfoState.Ready)
        if (existing != null && !forceMovieInfo && (movieInfoLoadedAtMs == null ||
            (clockMs() - movieInfoLoadedAtMs!!) in 0 until MovieDetailsRepository.FreshMs)) return
        val force = forceMovieInfo; forceMovieInfo = false
        val request = ++movieInfoRequest
        movieInfoOwnerKey = context
        fun current(): Boolean = request == movieInfoRequest && context == movieInfoContext() &&
            (state as? MoviesCatalogState.Ready)?.let { it.selectedMovieStreamId == streamId && it.movies.firstOrNull { item -> item.streamId == streamId }?.available == true } == true
        try {
            if (existing == null) {
                movieInfoState = MovieInfoState.Loading(streamId)
                val saved = withContext(dispatcher) {
                    try { loadSavedMovieInfo(streamId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                }
                coroutineContext.ensureActive()
                if (!current()) return
                if (saved != null && saved.streamId == streamId && (context == null || saved.providerKey == context) && saved.metadata.hasDetails()) {
                    existing = MovieInfoState.Ready(streamId, saved.metadata, saved = true)
                    movieInfoLoadedAtMs = saved.savedAtMs
                    movieInfoState = existing!!
                    if (!force && !withContext(dispatcher) { savedNeedsRefresh(saved) }) return
                }
            }
            if (!current()) return
            movieInfoState = existing?.copy(refreshing = true, refreshFailed = false) ?: MovieInfoState.Loading(streamId)
            val metadata = withContext(dispatcher) { loadMovieInfo(streamId) }
            coroutineContext.ensureActive()
            if (!current()) return
            val fallback = movieInfoState as? MovieInfoState.Ready
            movieInfoState = if (metadata != null && metadata.hasDetails()) {
                movieInfoLoadedAtMs = clockMs()
                MovieInfoState.Ready(streamId, metadata.withLastGood(fallback?.metadata))
            } else fallback?.copy(refreshing = false, refreshFailed = true) ?: MovieInfoState.Unavailable(streamId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutineContext.ensureActive()
            if (current()) movieInfoState = (movieInfoState as? MovieInfoState.Ready)?.copy(refreshing = false, refreshFailed = true)
                ?: MovieInfoState.Unavailable(streamId)
        } finally {
            if (current()) movieInfoState = when (val info = movieInfoState) {
                is MovieInfoState.Loading -> MovieInfoState.Empty
                is MovieInfoState.Ready -> info.copy(refreshing = false)
                else -> info
            }
        }
    }

    /** Bounded local collection lookup; never starts provider metadata or mutates personal state. */
    suspend fun categoryPreviewFor(movie: MovieItem): List<MovieItem> {
        if (!movie.available || movie.categoryName.isBlank()) return emptyList()
        val ready = state as? MoviesCatalogState.Ready ?: return emptyList()
        return try {
            val result = withContext(dispatcher) { source.categoryPreview(movie, 12) }
            coroutineContext.ensureActive()
            movieCategoryCandidates(movie, result ?: ready.movies, 12)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }

    var state: MoviesCatalogState by mutableStateOf(MoviesCatalogState.Loading)
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
    fun availabilityFor(movie: MovieItem): Boolean =
        (state as? MoviesCatalogState.Ready)?.movies?.firstOrNull { it.streamId == movie.streamId }?.available
            ?: rememberedAvailability?.takeIf { it.first == movie.streamId }?.second ?: movie.available

    private var loadedCatalogVersion = -1L
    private var requestVersion = 0L
    private var selectionVersion = 0L
    private var preferencesVersion = 0L

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

    suspend fun ensureCachedCatalog() {
        if (state !is MoviesCatalogState.Ready || loadedCatalogVersion != catalogVersion) loadCachedCatalog()
    }

    /** The screen owns the load: leaving Movies cancels it, without cancelling saved changes. */
    suspend fun loadCachedCatalog() {
        val request = ++requestVersion
        val version = catalogVersion
        val selectedVersion = selectionVersion
        val previous = state as? MoviesCatalogState.Ready
        if (previous == null) state = MoviesCatalogState.Loading
        isRefreshing = true
        refreshError = null
        try {
            val barrier = CompletableDeferred<Unit>()
            writes.send { barrier.complete(Unit) }
            barrier.await()
            val result = withContext(dispatcher) { source.load(previous) }
            coroutineContext.ensureActive()
            if (request != requestVersion || version != catalogVersion) return
            if (result is MoviesCatalogState.Ready) {
                rememberedAvailability?.first?.let { id ->
                    result.movies.firstOrNull { it.streamId == id }?.let { rememberedAvailability = id to it.available }
                }
                val loaded = pendingWatchlistIds?.let { result.copy(watchlistStreamIds = it).withWatchlistCategory() } ?: result
                val current = state as? MoviesCatalogState.Ready
                val selected = current?.movies?.firstOrNull { it.streamId == current.selectedMovieStreamId }
                state = if (selected != null && (selectedVersion != selectionVersion || result.movies.any { it.streamId == selected.streamId })) {
                    loaded.withRememberedMovie(result.movies.firstOrNull { it.streamId == selected.streamId } ?: selected)
                } else loaded
                loadedCatalogVersion = version
            } else if (previous == null) state = result
            else refreshError = RefreshFailure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            if (request == requestVersion && version == catalogVersion) {
                if (previous == null) state = MoviesCatalogState.Error("Couldn't load Movies. Try again, or check your library in Settings.")
                else refreshError = RefreshFailure
            }
        } finally {
            if (request == requestVersion) isRefreshing = false
        }
    }

    fun selectCategory(categoryId: String) {
        val ready = state as? MoviesCatalogState.Ready ?: return
        state = ready.copy(selectedCategoryId = categoryId,
            selectedMovieStreamId = ready.moviesForCategory(categoryId).firstOrNull()?.streamId,
            searchQuery = "", searchResult = null).withWatchlistCategory()
        savePreferences()
    }

    fun rememberMovie(movie: MovieItem) {
        val ready = state as? MoviesCatalogState.Ready ?: return
        rememberedAvailability = movie.streamId to (ready.movies.firstOrNull { it.streamId == movie.streamId }?.available ?: movie.available)
        if (ready.selectedMovieStreamId == movie.streamId && ready.movies.any { it.streamId == movie.streamId }) return
        selectionVersion++
        movieInfoRequest++
        movieInfoState = MovieInfoState.Empty
        state = ready.withRememberedMovie(movie)
        savePreferences()
    }

    fun cycleSortMode() {
        val ready = state as? MoviesCatalogState.Ready ?: return
        state = ready.copy(sortMode = ready.sortMode.next())
        savePreferences()
        invalidateCachedCatalog()
    }

    fun toggleWatchlistForSelectedMovie() {
        val ready = state as? MoviesCatalogState.Ready ?: return
        ready.movies.firstOrNull { it.streamId == ready.selectedMovieStreamId }?.let(::toggleWatchlistForMovie)
    }

    /** Detail actions use the displayed Movie identity even if a background reload moved browse selection. */
    fun toggleWatchlistForMovie(movie: MovieItem) {
        val ready = state as? MoviesCatalogState.Ready ?: return
        val id = movie.streamId
        if (!movie.available && id !in ready.watchlistStreamIds) return
        val next = if (id in ready.watchlistStreamIds) ready.watchlistStreamIds - id else ready.watchlistStreamIds + id
        rememberedAvailability = id to movie.available
        val selected = ready.withRememberedMovie(movie)
        if (ready.selectedMovieStreamId != id) selectionVersion++
        state = selected.copy(watchlistStreamIds = next).withWatchlistCategory()
        scheduleWatchlistWrite(next) { source.setWatchlisted(movie, id in next, selected.movies, next) }
        savePreferences()
    }

    fun clearWatchlist() {
        val ready = state as? MoviesCatalogState.Ready ?: return
        state = ready.copy(selectedCategoryId = MovieCategoryIds.Watchlist, watchlistStreamIds = emptySet(),
            selectedMovieStreamId = null, searchQuery = "", searchResult = null).withWatchlistCategory()
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
        val ready = state as? MoviesCatalogState.Ready ?: return
        val bounded = query.take(DefaultMaxMoviesSearchQueryLength)
        if (bounded == ready.searchQuery) return
        state = ready.copy(searchQuery = bounded, searchResult = null)
        savePreferences()
        invalidateCachedCatalog()
    }

    fun clearSearch() { onSearchQueryChanged("") }

    fun resetMoviesPreferences() {
        preferencesVersion++
        val ready = state as? MoviesCatalogState.Ready
        if (ready != null) state = ready.copy(selectedCategoryId = MovieCategoryIds.All,
            selectedMovieStreamId = ready.movies.firstOrNull()?.streamId,
            sortMode = MoviesSortMode.Provider, searchQuery = "", searchResult = null).withWatchlistCategory()
        enqueueWrite(preferences = true) { source.clearPreferences() }
        invalidateCachedCatalog()
    }

    private fun savePreferences() {
        val ready = state as? MoviesCatalogState.Ready ?: return
        val movie = ready.movies.firstOrNull { it.streamId == ready.selectedMovieStreamId }
        val category = ready.categories.firstOrNull { it.id == ready.selectedCategoryId }
        val snapshot = MoviesPreferences(ready.selectedCategoryId, category?.name, ready.selectedMovieStreamId,
            movie?.title, movie?.categoryName, ready.sortMode, ready.searchQuery)
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
        const val RefreshFailure = "Couldn't update Movies. Your saved library is still available."
        const val SaveFailure = "Couldn't save your change. Please try again."
    }
}
