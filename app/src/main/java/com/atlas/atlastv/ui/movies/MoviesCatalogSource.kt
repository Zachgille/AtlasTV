package com.atlas.atlastv.ui.movies

import android.content.Context
import com.atlas.atlastv.data.discovery.discoveryCollections
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogItemEntity
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.data.db.toMovieItem
import com.atlas.atlastv.data.db.toMovieWatchlistEntity
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressCoordinator
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchResult
import com.atlas.atlastv.ui.browse.cleanBrowseRowTitle
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import java.io.File

/** Blocking legacy stores are confined to the holder's IO boundary; Room queries suspend directly. */
class RoomMoviesCatalogSource(
    private val context: Context,
    private val mapper: MoviesCatalogMapper = MoviesCatalogMapper(),
    private val cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
    private val preferencesStore: MoviesPreferencesStore = MoviesPreferencesStore(File(context.filesDir, MoviesPreferencesStore.FileName)),
    private val watchlistStore: MoviesWatchlistStore = MoviesWatchlistStore(
        File(context.filesDir, MoviesWatchlistStore.FileName),
        MoviesWatchlistRoomStore(AtlasDatabaseProvider.database(context).movieWatchlistDao()),
    ),
    private val progressStore: WatchProgressStore = WatchProgressStore(
        File(context.filesDir, WatchProgressStore.FileName),
        WatchProgressRoomStore(AtlasDatabaseProvider.database(context).watchProgressDao()),
    ),
    private val catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
    private val watchlistDao: com.atlas.atlastv.data.db.MovieWatchlistDao = AtlasDatabaseProvider.database(context).movieWatchlistDao(),
) : MoviesCatalogSource {
    override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
        val room = loadRoomCatalogOrNull(previous)
        if (room != null && (room.movies.any { it.available } || !cacheStore.exists())) return room
        val cacheJson = cacheStore.read() ?: return room
            ?: MoviesCatalogState.Error("No movies yet. Open Settings to add or refresh your library.")
        try {
            val legacy = mapper.mapCachedCatalogJson(cacheJson, DebugConfig.debugXtreamCredentials)
            val categories = com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter()
            val allowed = legacy.movies.filter { !categories.excludes(it.categoryName) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.title) }
            // Saved placeholders do not suppress legacy catalog bootstrap; seed only an empty content type.
            catalogRepository.seedLegacyMoviesIfEmpty(allowed)
            return loadRoomCatalogOrNull(previous) ?: room
                ?: MoviesCatalogState.Error("No movies yet. Open Settings to add or refresh your library.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { return room ?: throw failure }
    }

    private suspend fun loadRoomCatalogOrNull(previous: MoviesCatalogState.Ready?): MoviesCatalogState.Ready? {
        val preferences = preferencesStore.load()
        val watchlist = watchlistStore.loadWatchlistIds()
        val progress = WatchProgressCoordinator.forStore(progressStore).loadSnapshot().records.filter { it.contentType == WatchProgressContentType.Movie && com.atlas.atlastv.playback.WatchProgressPolicy.isResumeEligible(it) }
        val sort = previous?.sortMode ?: preferences.sortMode
        val rows = roomMovieRows(sort, watchlist, progress)
        val rowMovies = rows.flatMap { it.items }.distinctBy { it.streamId }
        val movies = rowMovies.ifEmpty { catalogRepository.movies(RowItemsPerRow, ::redactedMovieUrl) }
        val query = previous?.searchQuery ?: preferences.searchQuery
        val result = query.takeIf { it.isNotBlank() }?.let { roomMovieSearch(it) }
        val selectedId = previous?.selectedMovieStreamId ?: preferences.selectedMovieStreamId
        val selected = selectedId?.let { id ->
            catalogRepository.movieEntitiesByIds(setOf(id), 1).firstOrNull()?.let(::toMovieItem)
                ?: watchlistDao.let { dao ->
                    dao.offset(id)?.let { dao.page(it, 1).firstOrNull()?.toMovieItem() }
                }
        }
        val ready = MoviesCatalogState.Ready("room", catalogRepository.moviesCacheAgeMs(),
            (result?.rows?.flatMap { it.items } ?: movies).let { if (selected != null) (it + selected).distinctBy { movie -> movie.streamId } else it },
            catalogRepository.movieCategories(CategoryRowLimit), browseRows = rows)
            .withRestoredContext(previous?.selectedCategoryId ?: preferences.selectedCategoryId, selectedId, query, sort, watchlist)
        // Watchlist IDs cover the full catalog, not only the bounded visible snapshots.
        return ready.copy(watchlistStreamIds = watchlist, watchProgress = progress.associateBy { it.streamId },
            searchResult = result, selectedMovieStreamId = selected?.streamId ?: ready.selectedMovieStreamId)
            .withWatchlistCategory()
    }

    override suspend fun categoryPreview(movie: MovieItem, maxItems: Int): List<MovieItem> =
        catalogRepository.movieEntitiesForCategory(movie.categoryName, MoviesSortMode.Provider.storageKey, maxItems + 1).map(::toMovieItem)

    override suspend fun savePreferences(preferences: MoviesPreferences) { preferencesStore.save(preferences) }
    override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) { watchlistStore.saveWatchlist(movies, ids) }
    override suspend fun setWatchlisted(movie: MovieItem, saved: Boolean, movies: List<MovieItem>, ids: Set<Int>) {
        watchlistStore.loadWatchlistIds()
        watchlistDao.setSaved(movie.toMovieWatchlistEntity(System.currentTimeMillis() + 1), saved)
    }
    override suspend fun clearPreferences() { preferencesStore.clear() }
    override suspend fun clearWatchlist() { watchlistStore.clear() }

    private suspend fun roomMovieRows(
        sortMode: MoviesSortMode,
        watchlistIds: Set<Int>,
        progressRecords: List<WatchProgressRecord>,
    ): List<BrowseRow<MovieItem>> {
        val rows = mutableListOf<BrowseRow<MovieItem>>()
        watchlistDao.page(0, RowItemsPerRow)
            .map { it.toMovieItem() }
            .takeIf { it.isNotEmpty() }
            ?.let { rows += BrowseRow("movies_watchlist", "Watchlist", it) }
        catalogRepository.movieEntitiesByIds(progressRecords.map { it.streamId }.toSet(), RowItemsPerRow)
            .map(::toMovieItem)
            .takeIf { it.isNotEmpty() }
            ?.let { rows += BrowseRow("movies_continue", "Continue Watching Movies", it) }
        catalogRepository.discoveryCollections(com.atlas.atlastv.data.db.SafeCatalogContentTypes.Movie, RowItemsPerRow)
            .forEach { row -> rows += BrowseRow(row.id, row.title, row.items.map(::toMovieItem)) }
        // No rating/date data? Keep an honest route into the full library.
        if (rows.none { it.id.endsWith("_new") || it.id.endsWith("_rated") })
            catalogRepository.movieEntitiesForSort(sortMode.storageKey, RowItemsPerRow).map(::toMovieItem)
                .takeIf { it.isNotEmpty() }?.let { rows += BrowseRow("movies_popular", "Browse Movies", it) }
        val categoryRows = catalogRepository.movieCategories(CategoryRowLimit)
            .filter { it.id != MovieCategoryIds.All && it.id != MovieCategoryIds.Watchlist }
            .mapNotNull { category ->
                val items = catalogRepository.movieEntitiesForCategory(category.id, sortMode.storageKey, RowItemsPerRow)
                    .map(::toMovieItem)
                items.takeIf { it.isNotEmpty() }?.let {
                    BrowseRow("movies_category-${category.id}", cleanBrowseRowTitle(category.name), it, categoryKey = category.id)
                }
            }
        rows += categoryRows
        return rows.distinctBy { it.id }.filter { it.items.isNotEmpty() }.take(MaxRows)
    }

    private suspend fun roomMovieSearch(query: String): BrowseSearchResult<MovieItem> {
        val matches = catalogRepository.searchMovieEntities(query, SearchItems).map(::toMovieItem)
        return BrowseSearchResult(query.trim(), catalogRepository.countSearchMovies(query),
            matches.chunked(RowItemsPerRow).take(SearchRows).mapIndexed { index, chunk ->
                BrowseRow("movies_search-$index", if (index == 0) "Search Results" else "Search Results ${index + 1}", chunk)
            })
    }

    private fun redactedMovieUrl(entity: SafeCatalogItemEntity): String {
        val extension = entity.containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() } ?: DefaultRoomMovieExtension
        return DebugConfig.debugXtreamCredentials?.let { credentials ->
            endpointBuilder.buildRedactedVodStream(credentials, entity.itemId, extension)
        } ?: "room://safe_catalog/vod/${entity.itemId}.$extension"
    }

    private fun toMovieItem(entity: SafeCatalogItemEntity): MovieItem =
        entity.toMovieItem(redactedMovieUrl(entity))

    private companion object {
        private const val MaxSearchQueryLength = DefaultMaxMoviesSearchQueryLength
        private const val DefaultRoomMovieExtension = "mp4"
        private const val RowItemsPerRow = 18
        private const val MaxRows = 24
        private const val CategoryRowLimit = 8
        private const val SearchItems = 126
        private const val SearchRows = 7
    }
}
