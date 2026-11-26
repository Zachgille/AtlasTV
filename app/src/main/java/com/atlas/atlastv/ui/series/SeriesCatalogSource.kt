package com.atlas.atlastv.ui.series

import android.content.Context
import com.atlas.atlastv.data.discovery.discoveryCollections
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.toSeriesWatchlistEntity
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.data.db.toSeriesItem
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressCoordinator
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchResult
import com.atlas.atlastv.ui.browse.cleanBrowseRowTitle
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import java.io.File

/** Catalog-only source: browsing never requests episode info or provider credentials. */
class RoomSeriesCatalogSource(
    context: Context,
    private val mapper: SeriesCatalogMapper = SeriesCatalogMapper(),
    private val cacheStore: LiveTvCachedCatalogStore = LiveTvCachedCatalogStore(context.filesDir),
    private val preferencesStore: SeriesPreferencesStore = SeriesPreferencesStore(File(context.filesDir, SeriesPreferencesStore.FileName)),
    private val progressStore: WatchProgressStore = WatchProgressStore(
        File(context.filesDir, WatchProgressStore.FileName),
        WatchProgressRoomStore(AtlasDatabaseProvider.database(context).watchProgressDao()),
    ),
    private val watchlistStore: SeriesWatchlistStore = SeriesWatchlistStore(
        File(context.filesDir, SeriesWatchlistStore.FileName),
        SeriesWatchlistRoomStore(AtlasDatabaseProvider.database(context).seriesWatchlistDao()),
    ),
    private val catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
    private val watchlistDao: com.atlas.atlastv.data.db.SeriesWatchlistDao = AtlasDatabaseProvider.database(context).seriesWatchlistDao(),
) : SeriesCatalogSource {
    override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
        val room = loadRoomCatalogOrNull(previous)
        if (room != null && (room.series.any { it.available } || !cacheStore.exists())) return room
        val cacheJson = cacheStore.read() ?: return room
            ?: SeriesCatalogState.Error("No shows yet. Open Settings to add or refresh your library.")
        try {
            val legacy = mapper.mapCachedCatalogJson(cacheJson)
            val categories = com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter()
            val allowed = legacy.series.filter { !categories.excludes(it.categoryName) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.name) }
            catalogRepository.seedLegacySeriesIfEmpty(allowed)
            return loadRoomCatalogOrNull(previous) ?: room
                ?: SeriesCatalogState.Error("No shows yet. Open Settings to add or refresh your library.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { return room ?: throw failure }
    }

    private suspend fun loadRoomCatalogOrNull(previous: SeriesCatalogState.Ready?): SeriesCatalogState.Ready? {
        val preferences = preferencesStore.load()
        val watchlist = watchlistStore.loadWatchlistIds()
        val progress = WatchProgressCoordinator.forStore(progressStore).loadSnapshot().records.filter { it.contentType == WatchProgressContentType.SeriesEpisode }
        val sort = previous?.sortMode ?: preferences.sortMode
        val rows = roomSeriesRows(sort, watchlist, progress)
        val rowSeries = rows.flatMap { it.items }.distinctBy { it.seriesId }
        val series = rowSeries.ifEmpty { catalogRepository.series(RowItemsPerRow) }
        val query = previous?.searchQuery ?: preferences.searchQuery
        val result = query.takeIf { it.isNotBlank() }?.let { roomSeriesSearch(it) }
        val selectedId = previous?.selectedSeriesId ?: preferences.selectedSeriesId
        val selected = selectedId?.let { id ->
            catalogRepository.seriesEntitiesByIds(setOf(id), 1).firstOrNull()?.toSeriesItem()
                ?: watchlistDao.offset(id)?.let { watchlistDao.page(it, 1).firstOrNull()?.toSeriesItem() }
        }
        val ready = SeriesCatalogState.Ready("room", catalogRepository.seriesCacheAgeMs(),
            (result?.rows?.flatMap { it.items } ?: series).let { if (selected != null) (it + selected).distinctBy { item -> item.seriesId } else it },
            catalogRepository.seriesCategories(CategoryRowLimit, watchlist.size), browseRows = rows)
            .withRestoredContext(previous?.selectedCategoryId ?: preferences.selectedCategoryId, selectedId, query, sort, watchlist)
        return ready.copy(watchlistSeriesIds = watchlist, watchProgress = progress.associateBy { it.streamId },
            searchResult = result, selectedSeriesId = selected?.seriesId ?: ready.selectedSeriesId,
            selectedSeasonNumber = preferences.selectedSeasonNumber.takeIf { (selected?.seriesId ?: ready.selectedSeriesId) == preferences.selectedSeriesId },
            selectedEpisodeId = preferences.selectedEpisodeId.takeIf { (selected?.seriesId ?: ready.selectedSeriesId) == preferences.selectedSeriesId }).withWatchlistCategory()
    }

    override suspend fun savePreferences(preferences: SeriesPreferences) { preferencesStore.save(preferences) }
    override suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) { watchlistStore.saveWatchlist(series, ids) }
    override suspend fun setWatchlisted(item: SeriesItem, saved: Boolean, series: List<SeriesItem>, ids: Set<Int>) {
        watchlistStore.loadWatchlistIds()
        watchlistDao.setSaved(item.toSeriesWatchlistEntity(System.currentTimeMillis() + 1), saved)
    }
    override suspend fun clearPreferences() { preferencesStore.clear() }
    override suspend fun clearWatchlist() { watchlistStore.clear() }

    private suspend fun roomSeriesRows(
        sortMode: SeriesSortMode,
        watchlistIds: Set<Int>,
        progressRecords: List<WatchProgressRecord>,
    ): List<BrowseRow<SeriesItem>> {
        val rows = mutableListOf<BrowseRow<SeriesItem>>()
        watchlistDao.page(0, RowItemsPerRow)
            .map { it.toSeriesItem() }
            .takeIf { it.isNotEmpty() }
            ?.let { rows += BrowseRow("series_watchlist", "Watchlist", it) }
        val continueSeriesIds = progressRecords.filter(com.atlas.atlastv.playback.WatchProgressPolicy::isResumeEligible).mapNotNull { it.seriesId }.toSet()
        catalogRepository.seriesEntitiesByIds(continueSeriesIds, RowItemsPerRow)
            .map { it.toSeriesItem() }
            .takeIf { it.isNotEmpty() }
            ?.let { rows += BrowseRow("series_continue", "Continue Watching Shows", it) }
        catalogRepository.discoveryCollections(com.atlas.atlastv.data.db.SafeCatalogContentTypes.Series, RowItemsPerRow)
            .forEach { row -> rows += BrowseRow(row.id, row.title, row.items.map { it.toSeriesItem() }) }
        // No rating/date data? Keep an honest route into the full library.
        if (rows.none { it.id.endsWith("_new") || it.id.endsWith("_rated") })
            catalogRepository.seriesEntitiesForSort(sortMode.storageKey, RowItemsPerRow).map { it.toSeriesItem() }
                .takeIf { it.isNotEmpty() }?.let { rows += BrowseRow("series_popular", "Browse Shows", it) }
        val categoryRows = catalogRepository.seriesCategories(CategoryRowLimit, watchlistIds.size)
            .filter { it.id != SeriesCategoryIds.All && it.id != SeriesCategoryIds.Watchlist }
            .mapNotNull { category ->
                val items = catalogRepository.seriesEntitiesForCategory(category.id, sortMode.storageKey, RowItemsPerRow)
                    .map { it.toSeriesItem() }
                items.takeIf { it.isNotEmpty() }?.let {
                    BrowseRow("series_category-${category.id}", cleanBrowseRowTitle(category.name), it, categoryKey = category.id)
                }
            }
        rows += categoryRows
        return rows.distinctBy { it.id }.filter { it.items.isNotEmpty() }.take(MaxRows)
    }

    private suspend fun roomSeriesSearch(query: String): BrowseSearchResult<SeriesItem> {
        val matches = catalogRepository.searchSeriesEntities(query, SearchItems).map { it.toSeriesItem() }
        return BrowseSearchResult(query.trim(), catalogRepository.countSearchSeries(query),
            matches.chunked(RowItemsPerRow).take(SearchRows).mapIndexed { index, chunk ->
                BrowseRow("series_search-$index", if (index == 0) "Search Results" else "Search Results ${index + 1}", chunk)
            })
    }

    private companion object {
        private const val MaxSearchQueryLength = DefaultMaxSeriesSearchQueryLength
        private const val RowItemsPerRow = 18
        private const val MaxRows = 24
        private const val CategoryRowLimit = 8
        private const val SearchItems = 126
        private const val SearchRows = 7
    }
}
