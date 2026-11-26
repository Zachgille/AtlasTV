package com.atlas.atlastv.ui.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.movies.MoviesCatalogMapper
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesCatalogMapper
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import java.io.File
import kotlin.coroutines.coroutineContext

class HomeShellStateHolder(
    private val loadCatalog: suspend () -> HomeLoadResult,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadHeroMetadata: suspend (HomeCard) -> HomeHeroMetadata? = { null },
    private val heroDebounceMs: Long = 250,
    private val loadSavedHeroMetadata: suspend (HomeCard) -> SavedHomeHero? = { null },
    private val savedHeroNeedsRefresh: suspend (SavedHomeHero, HomeCard) -> Boolean = { _, _ -> true },
    private val heroContext: () -> String? = { null },
    private val loadSavedPosters: suspend (HomeCard) -> List<com.atlas.atlastv.ui.media.AtlasImageRef> = { emptyList() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val catalogUpdates: (() -> Flow<HomeLoadResult>)? = null,
) {
    constructor(
        context: Context,
        cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
        moviesMapper: MoviesCatalogMapper = MoviesCatalogMapper(),
        seriesMapper: SeriesCatalogMapper = SeriesCatalogMapper(),
        watchlistStore: MoviesWatchlistStore = MoviesWatchlistStore(File(context.filesDir, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(AtlasDatabaseProvider.database(context).movieWatchlistDao())),
        seriesWatchlistStore: SeriesWatchlistStore = SeriesWatchlistStore(File(context.filesDir, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(AtlasDatabaseProvider.database(context).seriesWatchlistDao())),
        progressStore: WatchProgressStore = WatchProgressStore(File(context.filesDir, WatchProgressStore.FileName), WatchProgressRoomStore(AtlasDatabaseProvider.database(context).watchProgressDao())),
        homeMapper: HomeShellMapper = HomeShellMapper(),
        catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
        endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
        credentialsProvider: () -> com.atlas.atlastv.xtream.model.XtreamCredentials? = { com.atlas.atlastv.core.config.DebugConfig.debugXtreamCredentials },
        heroLoader: HomeHeroMetadataLoader = HomeHeroMetadataLoader(
            com.atlas.atlastv.ui.movies.MovieInfoLoader(registerArtwork = { entries -> com.atlas.atlastv.ui.media.AtlasProviderImageIndex.create(context).registerAllAsync(entries) }),
            com.atlas.atlastv.ui.series.SeriesEpisodeLoader(registerArtwork = { entries -> com.atlas.atlastv.ui.media.AtlasProviderImageIndex.create(context).registerAllAsync(entries) }),
        ),
        heroRepository: HomeHeroRepository = HomeHeroRepository.create(context, heroLoader),
        catalogSource: HomeCatalogSource = HomeCatalogSource(context, cacheStore, moviesMapper, seriesMapper, watchlistStore, seriesWatchlistStore, progressStore, homeMapper, catalogRepository, endpointBuilder),
    ) : this(catalogSource::load,
        catalogUpdates = catalogSource::updates,
        loadHeroMetadata = { card -> credentialsProvider()?.let { heroRepository.refresh(it, card) } },
        loadSavedHeroMetadata = { card -> credentialsProvider()?.let { heroRepository.saved(it, card) } },
        savedHeroNeedsRefresh = heroRepository::stale,
        loadSavedPosters = { card -> credentialsProvider()?.let { heroRepository.savedPosters(it, card) }.orEmpty() },
        heroContext = { credentialsProvider()?.let(::providerHomeHeroKey) ?: "no-provider" })

    private var posterOwnerKey by mutableStateOf<String?>(null)
    private var savedCardPosters by mutableStateOf<Map<String, List<com.atlas.atlastv.ui.media.AtlasImageRef>>>(emptyMap())
    private var posterRequestVersion = 0L
    fun posterFallbacks(card: HomeCard): List<com.atlas.atlastv.ui.media.AtlasImageRef> =
        if (posterOwnerKey == heroContext()) savedCardPosters[card.heroCacheIdentity()].orEmpty() else emptyList()

    /** Separate optional work cannot delay basic Home actions or own D-pad focus. */
    suspend fun loadCardPosters(ready: HomeShellState.Ready) {
        val version = ++posterRequestVersion
        val owner = heroContext()
        if (posterOwnerKey != owner) { savedCardPosters = emptyMap(); posterOwnerKey = owner }
        val cards = ready.rows.firstOrNull { it.id == HomeRowIds.ContinueWatching }?.cards.orEmpty()
            .filter { it.heroCacheIdentity() != null }.distinctBy { it.heroCacheIdentity() }.take(12)
        savedCardPosters = savedCardPosters.filterKeys { key -> cards.any { it.heroCacheIdentity() == key } }
        for (card in cards) {
            val refs = withContext(loadDispatcher) {
                try { loadSavedPosters(card).take(2) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            }
            coroutineContext.ensureActive()
            if (version != posterRequestVersion || owner != heroContext()) return
            if (refs.isNotEmpty()) savedCardPosters = savedCardPosters + (card.heroCacheIdentity()!! to refs)
        }
    }

    private var storedHeroState: HomeHeroState? by mutableStateOf(null)
    private var heroOwnerKey by mutableStateOf<String?>(null)
    val heroState: HomeHeroState? get() = storedHeroState.takeIf { heroOwnerKey == heroContext() }
    fun heroContextKey(): String? = heroContext()
    private data class WarmHero(val state: HomeHeroState, val loadedAtMs: Long)
    private val heroCache = LinkedHashMap<String, WarmHero>()
    private var forceHeroRefresh = false
    private var heroRequestVersion = 0L
    var heroRefreshVersion by mutableStateOf(0L)
        private set
    var selectedCardId: String? = null
        private set
    var returnFocus: HomeReturnFocus? = null
        private set
    fun rememberCardFocus(cardId: String) { selectedCardId = cardId }
    fun rememberDeparture(cardId: String, target: HomeReturnTarget) {
        selectedCardId = cardId
        returnFocus = HomeReturnFocus(cardId, target)
    }
    var rowScrollIndex = 0
    var rowScrollOffset = 0
    val horizontalPositions = mutableMapOf<String, Pair<Int, Int>>()
    fun rememberCollectionDeparture(row: HomeContentRow) {
        val card=row.cards.firstOrNull {it.id==selectedCardId} ?: row.cards.firstOrNull() ?: return
        selectedCardId=card.id
        returnFocus=HomeReturnFocus(card.id,HomeReturnTarget.Collection,row.id)
    }
    fun consumeReturnFocus() { returnFocus = null }
    var returnNavTag by mutableStateOf<String?>(null)
        private set
    fun rememberNavDeparture(tag: String) { returnFocus = null; returnNavTag = tag }
    fun consumeReturnNavFocus() { returnNavTag = null }

    /** Saved information precedes debounced optional network work; neither path owns focus. */
    suspend fun ensureHeroMetadata(card: HomeCard) {
        val key = card.heroMetadataKey()
        val context = heroContext()
        if (heroOwnerKey != context) { heroCache.clear(); storedHeroState = null; heroOwnerKey = context }
        val request = ++heroRequestVersion
        val force = forceHeroRefresh; forceHeroRefresh = false
        val cached = heroCache.remove(key)?.also { heroCache[key] = it }
        val retained = cached?.state ?: storedHeroState?.takeIf { it.key == key }
        storedHeroState = retained ?: HomeHeroState(key)
        fun current() = request == heroRequestVersion && context == heroContext()
        fun remember(value: HomeHeroState, savedAtMs: Long) {
            storedHeroState = value; heroCache[key] = WarmHero(value, savedAtMs)
            while (heroCache.size > 16) heroCache.remove(heroCache.keys.first())
        }
        if (card.heroCacheIdentity() == null) return
        if (cached != null && !force && (clockMs() - cached.loadedAtMs) in 0 until HomeHeroRepository.FreshMs) return
        try {
            val saved = withContext(loadDispatcher) {
                try { loadSavedHeroMetadata(card)?.let { it.copy(metadata = HomeHeroCacheCodec.safe(it.metadata)) } }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            coroutineContext.ensureActive()
            if (!current()) return
            if (saved != null && saved.metadataKey == key && (context == null || saved.providerKey == context)) {
                val metadata = saved.metadata
                if (metadata.hasDetails()) {
                    remember(HomeHeroState(key, metadata, fromSaved = true), saved.savedAtMs)
                    if (!force && !withContext(loadDispatcher) { savedHeroNeedsRefresh(saved, card) }) return
                }
            }
            delay(heroDebounceMs)
            if (!current()) return
            val metadata = withContext(loadDispatcher) { loadHeroMetadata(card)?.let(HomeHeroCacheCodec::safe) }
            coroutineContext.ensureActive()
            if (!current() || metadata == null || !metadata.hasDetails()) return
            remember(HomeHeroState(key, metadata.withLastGood(storedHeroState?.metadata)), clockMs())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional preview failure retains the last-good information and all actions. */ }
    }

    var state: HomeShellState by mutableStateOf(HomeShellState.Loading)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var refreshError by mutableStateOf<String?>(null)
        private set
    var refreshVersion by mutableStateOf(0L)
        private set
    var continueWatching: List<WatchProgressRecord> = emptyList()
        private set
    private var requestVersion = 0L

    fun refresh(clearHeroMetadata: Boolean = false) {
        refreshVersion++
        if (clearHeroMetadata) { heroRequestVersion++; heroCache.clear(); forceHeroRefresh = true; heroRefreshVersion++ }
    }

    /** Its calling screen owns cancellation; no detached job survives leaving Home. */
    suspend fun loadHome() {
        val version = ++requestVersion
        val revision = refreshVersion
        val owner = heroContext()
        val lastGood = state as? HomeShellState.Ready
        if (lastGood == null) state = HomeShellState.Loading
        isRefreshing = true
        refreshError = null
        try {
            val updates = catalogUpdates?.invoke() ?: flow { emit(loadCatalog()) }
            updates.buffer(0).flowOn(loadDispatcher).collect { result ->
                coroutineContext.ensureActive()
                if (requestVersion != version || revision != refreshVersion || owner != heroContext())
                    throw CancellationException("Home request superseded")
                // Refresh/reentry keeps every last-good row until the full replacement is ready.
                if (result.isComplete || lastGood == null) {
                    if (result.state is HomeShellState.Ready || state !is HomeShellState.Ready) {
                        state = result.state
                        continueWatching = result.continueWatching
                    } else {
                        refreshError = "Couldn't update your library. Your saved content is still available."
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            if (requestVersion == version && revision == refreshVersion && owner == heroContext()) {
                if (state is HomeShellState.Ready) refreshError = "Couldn't update your library. Your saved content is still available."
                else state = HomeShellState.Error("Couldn't load your library. Try again, or open Settings.")
            }
        } finally {
            if (requestVersion == version) isRefreshing = false
        }
    }
}

data class HomeLoadResult(
    val state: HomeShellState,
    val continueWatching: List<WatchProgressRecord> = emptyList(),
    val isComplete: Boolean = true,
)
