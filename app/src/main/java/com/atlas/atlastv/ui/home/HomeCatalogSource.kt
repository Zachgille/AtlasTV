package com.atlas.atlastv.ui.home

import android.content.Context
import com.atlas.atlastv.data.discovery.DiscoveryCollectionPhase
import com.atlas.atlastv.data.discovery.DiscoveryCollection
import com.atlas.atlastv.data.discovery.discoveryCollections
import com.atlas.atlastv.data.discovery.forHome
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogItemEntity
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.data.db.toMovieItem
import com.atlas.atlastv.data.db.toSeriesItem
import com.atlas.atlastv.playback.WatchProgressCoordinator
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.movies.MoviesCatalogMapper
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesCatalogMapper
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import com.atlas.atlastv.playback.WatchProgressRecord
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class HomeCatalogSource(
    context: Context,
    private val cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
    private val moviesMapper: MoviesCatalogMapper = MoviesCatalogMapper(),
    private val seriesMapper: SeriesCatalogMapper = SeriesCatalogMapper(),
    private val watchlistStore: MoviesWatchlistStore = MoviesWatchlistStore(
        File(context.filesDir, MoviesWatchlistStore.FileName),
        MoviesWatchlistRoomStore(AtlasDatabaseProvider.database(context).movieWatchlistDao()),
    ),
    private val seriesWatchlistStore: SeriesWatchlistStore = SeriesWatchlistStore(
        File(context.filesDir, SeriesWatchlistStore.FileName),
        SeriesWatchlistRoomStore(AtlasDatabaseProvider.database(context).seriesWatchlistDao()),
    ),
    private val progressStore: WatchProgressStore = WatchProgressStore(
        File(context.filesDir, WatchProgressStore.FileName),
        WatchProgressRoomStore(AtlasDatabaseProvider.database(context).watchProgressDao()),
    ),
    private val homeMapper: HomeShellMapper = HomeShellMapper(),
    private val catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    /** Compatibility reads await the complete catalog; normal Home consumes each stage. */
    suspend fun load(): HomeLoadResult = updates().last()

    fun updates(): Flow<HomeLoadResult> = flow {
        suspend fun publish(result: HomeLoadResult) { emit(result) }

        val watchlistIds = watchlistStore.loadWatchlistIds()
        val seriesWatchlistIds = seriesWatchlistStore.loadWatchlistIds()
        val progress = WatchProgressCoordinator.forStore(progressStore).loadSnapshot().continueWatching()
            .filter { !com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter().excludes(it.categoryName.orEmpty()) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.seriesTitle ?: it.title) }
        if (loadRoomHome(watchlistIds, seriesWatchlistIds, progress, ::publish)) return@flow
        val cacheJson = cacheStore.read()
        if (cacheJson == null) {
            emit(HomeLoadResult(HomeShellState.Error("Open Settings to add a provider and load Movies and Shows.", needsSetup = true)))
            return@flow
        }
        val movies = moviesMapper.mapCachedCatalogJson(cacheJson, DebugConfig.debugXtreamCredentials).movies
        val series = seriesMapper.mapCachedCatalogJson(cacheJson).series
        catalogRepository.seedLegacyCatalogIfEmpty(movies, series)
        if (!loadRoomHome(watchlistIds, seriesWatchlistIds, progress, ::publish)) {
            emit(HomeLoadResult(HomeShellState.Error("Open Settings to add a provider and load Movies and Shows.", needsSetup = true), progress))
        }
    }

    private suspend fun loadRoomHome(
        watchlistIds: Set<Int>, seriesWatchlistIds: Set<Int>, progress: List<WatchProgressRecord>,
        publish: suspend (HomeLoadResult) -> Unit,
    ): Boolean {
        val stamp = catalogRepository.homeCatalogStamp()
        val movies = homeMovies(watchlistIds, progress)
        val series = homeSeries(seriesWatchlistIds, progress)
        if (movies.isEmpty() && series.isEmpty()) return false
        val age = listOfNotNull(catalogRepository.moviesCacheAgeMs(), catalogRepository.seriesCacheAgeMs()).minOrNull()
        suspend fun discovery(phase: DiscoveryCollectionPhase) = coroutineScope {
            val movieRows = async { catalogRepository.discoveryCollections(com.atlas.atlastv.data.db.SafeCatalogContentTypes.Movie, HomeRowItems, phase) }
            val showRows = async { catalogRepository.discoveryCollections(com.atlas.atlastv.data.db.SafeCatalogContentTypes.Series, HomeRowItems, phase) }
            movieRows.await() + showRows.await()
        }
        suspend fun publishRows(collections: List<DiscoveryCollection>, complete: Boolean) {
            check(stamp == catalogRepository.homeCatalogStamp()) { "Library updated while opening Home" }
            publish(HomeLoadResult(homeMapper.map(
                source = "room", cacheAgeMs = age, movies = movies, series = series,
                watchlistStreamIds = watchlistIds, watchlistSeriesIds = seriesWatchlistIds,
                continueWatching = progress, discovery = collections.forHome(),
            ), progress, isComplete = complete))
        }
        val leading = discovery(DiscoveryCollectionPhase.Leading)
        publishRows(leading, complete = false)
        publishRows(leading + discovery(DiscoveryCollectionPhase.Genres), complete = true)
        return true
    }

    private suspend fun homeMovies(watchlistIds: Set<Int>, progress: List<WatchProgressRecord>): List<com.atlas.atlastv.ui.movies.MovieItem> {
        val progressIds = progress
            .filter { it.contentType == com.atlas.atlastv.playback.WatchProgressContentType.Movie }
            .map { it.streamId }
            .toSet()
        return (
            catalogRepository.movieEntitiesByIds(watchlistIds, HomeRowItems).map(::toMovieItem) +
                catalogRepository.movieEntitiesByIds(progressIds, HomeRowItems).map(::toMovieItem) +
                catalogRepository.movieEntitiesForSort("provider", HomeRowItems).map(::toMovieItem) +
                catalogRepository.recentMovieEntities(HomeRowItems).map(::toMovieItem)
            ).distinctBy { it.streamId }.take(HomeMaterializedLimit)
    }

    private suspend fun homeSeries(watchlistIds: Set<Int>, progress: List<WatchProgressRecord>): List<com.atlas.atlastv.ui.series.SeriesItem> {
        val continueSeriesIds = progress
            .filter { it.contentType == com.atlas.atlastv.playback.WatchProgressContentType.SeriesEpisode }
            .mapNotNull { it.seriesId }
            .toSet()
        return (
            catalogRepository.seriesEntitiesByIds(watchlistIds, HomeRowItems).map { it.toSeriesItem() } +
                catalogRepository.seriesEntitiesByIds(continueSeriesIds, HomeRowItems).map { it.toSeriesItem() } +
                catalogRepository.seriesEntitiesForSort("provider", HomeRowItems).map { it.toSeriesItem() } +
                catalogRepository.recentSeriesEntities(HomeRowItems).map { it.toSeriesItem() }
            ).distinctBy { it.seriesId }.take(HomeMaterializedLimit)
    }

    private fun redactedMovieUrl(entity: SafeCatalogItemEntity): String {
        val extension = entity.containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() } ?: DefaultRoomMovieExtension
        return DebugConfig.debugXtreamCredentials?.let { credentials ->
            endpointBuilder.buildRedactedVodStream(credentials, entity.itemId, extension)
        } ?: "room://safe_catalog/vod/${entity.itemId}.$extension"
    }

    private fun toMovieItem(entity: SafeCatalogItemEntity): com.atlas.atlastv.ui.movies.MovieItem =
        entity.toMovieItem(redactedMovieUrl(entity))

    private companion object {
        private const val DefaultRoomMovieExtension = "mp4"
        private const val HomeRowItems = 12
        private const val HomeMaterializedLimit = 48
    }
}



