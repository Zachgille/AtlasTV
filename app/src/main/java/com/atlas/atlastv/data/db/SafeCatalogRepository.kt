package com.atlas.atlastv.data.db

import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MovieCategory
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.SeriesCategory

class SafeCatalogRepository(
    private val dao: SafeCatalogDao,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun genreCollections(type: String, limit: Int): List<com.atlas.atlastv.data.discovery.DiscoveryGenreItem> =
        dao.genreCollections(com.atlas.atlastv.data.discovery.discoveryGenreQuery(type, limit))

    suspend fun discovery(type: String, shelf: com.atlas.atlastv.data.discovery.DiscoveryShelf, limit: Int, genre: String? = null): List<SafeCatalogItemEntity> {
        val count = limit.coerceIn(1, MaxBoundedItems)
        val now = clockMs()
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return when (shelf) {
            com.atlas.atlastv.data.discovery.DiscoveryShelf.New -> dao.newDiscovery(type, (today.year - 1) * 10000 + 101, today.year * 10000 + today.monthValue * 100 + today.dayOfMonth, count)
            com.atlas.atlastv.data.discovery.DiscoveryShelf.HighlyRated -> dao.ratedDiscovery(type, count)
            com.atlas.atlastv.data.discovery.DiscoveryShelf.RecentlyAdded -> dao.addedDiscovery(type, now, count)
            com.atlas.atlastv.data.discovery.DiscoveryShelf.RecentlyUpdated -> dao.updatedDiscovery(type, now, count)
            com.atlas.atlastv.data.discovery.DiscoveryShelf.Genre -> if (genre == null) emptyList() else dao.genreDiscovery(type, genre, count)
        }
    }

    suspend fun replaceLibraryFromRefresh(
        movies: List<SafeCatalogItemEntity>,
        shows: List<SafeCatalogItemEntity>,
        discovery: List<CatalogDiscoveryEntity> = emptyList(),
    ): CatalogRefreshWriteResult = dao.replaceLibraryFromRefresh(movies, shows, discovery)

    suspend fun replaceLibrary(movies: List<SafeCatalogItemEntity>, shows: List<SafeCatalogItemEntity>) {
        dao.replaceLibrary(movies, shows)
    }
    suspend fun replaceMovies(movies: List<MovieItem>) {
        val now = clockMs()
        dao.replaceType(
            SafeCatalogContentTypes.Movie,
            movies.mapIndexed { index, movie -> movie.toSafeCatalogEntity(index, now) },
        )
    }

    suspend fun seedLegacyMoviesIfEmpty(movies: List<MovieItem>) {
        val now = clockMs()
        dao.seedLegacyMoviesIfEmpty(movies.filter { !com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter().excludes(it.categoryName) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.title) }.mapIndexed { index, movie -> movie.toSafeCatalogEntity(index, now) })
    }

    suspend fun replaceSeries(series: List<SeriesItem>) {
        val now = clockMs()
        dao.replaceType(
            SafeCatalogContentTypes.Series,
            series.mapIndexed { index, item -> item.toSafeCatalogEntity(index, now) },
        )
    }

    suspend fun seedLegacySeriesIfEmpty(series: List<SeriesItem>) {
        val now = clockMs()
        dao.seedLegacySeriesIfEmpty(series.filter { !com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter().excludes(it.categoryName) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.name) }.mapIndexed { index, item -> item.toSafeCatalogEntity(index, now) })
    }

    suspend fun seedLegacyCatalogIfEmpty(movies: List<MovieItem>, series: List<SeriesItem>) {
        val now = clockMs()
        dao.seedLegacyCatalogIfEmpty(
            movies.filter { !com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter().excludes(it.categoryName) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.title) }.mapIndexed { index, movie -> movie.toSafeCatalogEntity(index, now) } +
                series.filter { !com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter().excludes(it.categoryName) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.name) }.mapIndexed { index, item -> item.toSafeCatalogEntity(index, now) },
        )
    }

    suspend fun replaceCatalogEntities(
        contentType: String,
        entities: List<SafeCatalogItemEntity>,
        batchSize: Int = DefaultBatchSize,
    ): Int =
        replaceCatalogEntitySequence(contentType, entities.asSequence(), batchSize)

    suspend fun replaceCatalogEntitySequence(
        contentType: String,
        entities: Sequence<SafeCatalogItemEntity>,
        batchSize: Int = DefaultBatchSize,
    ): Int {
        dao.deleteType(contentType)
        var inserted = 0
        entities
            .filter { it.contentType == contentType }
            .chunked(batchSize.coerceAtLeast(1))
            .forEach { batch ->
                dao.insertAll(batch)
                inserted += batch.size
            }
        return inserted
    }

    suspend fun clearCatalog() {
        dao.deleteAll()
    }

    suspend fun movieEntities(): List<SafeCatalogItemEntity> =
        dao.itemsForType(SafeCatalogContentTypes.Movie)

    suspend fun seriesEntities(): List<SafeCatalogItemEntity> =
        dao.itemsForType(SafeCatalogContentTypes.Series)

    suspend fun movieEntities(limit: Int): List<SafeCatalogItemEntity> =
        dao.itemsForType(SafeCatalogContentTypes.Movie, limit.coerceIn(1, MaxBoundedItems))

    suspend fun seriesEntities(limit: Int): List<SafeCatalogItemEntity> =
        dao.itemsForType(SafeCatalogContentTypes.Series, limit.coerceIn(1, MaxBoundedItems))

    suspend fun movieEntityAtOffset(offset: Int): SafeCatalogItemEntity? =
        dao.itemForTypeAtOffset(SafeCatalogContentTypes.Movie, offset.coerceAtLeast(0))

    suspend fun seriesEntityAtOffset(offset: Int): SafeCatalogItemEntity? =
        dao.itemForTypeAtOffset(SafeCatalogContentTypes.Series, offset.coerceAtLeast(0))

    suspend fun movieEntitiesForSort(sortKey: String, limit: Int): List<SafeCatalogItemEntity> =
        entitiesForSort(SafeCatalogContentTypes.Movie, sortKey, limit)

    suspend fun seriesEntitiesForSort(sortKey: String, limit: Int): List<SafeCatalogItemEntity> =
        entitiesForSort(SafeCatalogContentTypes.Series, sortKey, limit)

    suspend fun recentMovieEntities(limit: Int): List<SafeCatalogItemEntity> =
        dao.recentlyAddedItemsForType(SafeCatalogContentTypes.Movie, limit.coerceIn(1, MaxBoundedItems))

    suspend fun recentSeriesEntities(limit: Int): List<SafeCatalogItemEntity> =
        dao.recentlyAddedItemsForType(SafeCatalogContentTypes.Series, limit.coerceIn(1, MaxBoundedItems))

    suspend fun movieEntitiesForCategory(categoryName: String, sortKey: String, limit: Int): List<SafeCatalogItemEntity> =
        entitiesForCategory(SafeCatalogContentTypes.Movie, categoryName, sortKey, limit)

    suspend fun seriesEntitiesForCategory(categoryName: String, sortKey: String, limit: Int): List<SafeCatalogItemEntity> =
        entitiesForCategory(SafeCatalogContentTypes.Series, categoryName, sortKey, limit)

    suspend fun movieEntitiesByIds(ids: Set<Int>, limit: Int): List<SafeCatalogItemEntity> =
        entitiesByIds(SafeCatalogContentTypes.Movie, ids, limit)

    suspend fun seriesEntitiesByIds(ids: Set<Int>, limit: Int): List<SafeCatalogItemEntity> =
        entitiesByIds(SafeCatalogContentTypes.Series, ids, limit)

    suspend fun movieCategories(limit: Int, minItems: Int = 2): List<MovieCategory> {
        val allCount = dao.countForType(SafeCatalogContentTypes.Movie)
        val categoryRows = dao.categorySummariesForType(
            contentType = SafeCatalogContentTypes.Movie,
            minItems = minItems.coerceAtLeast(1),
            limit = limit.coerceIn(1, MaxCategoryRows),
        ).map { summary -> MovieCategory(id = summary.name, name = summary.name, itemCount = summary.itemCount) }
        return listOf(MovieCategory(id = "all", name = "All", itemCount = allCount)) + categoryRows
    }

    suspend fun seriesCategories(limit: Int, watchlistCount: Int = 0, minItems: Int = 2): List<SeriesCategory> {
        val allCount = dao.countForType(SafeCatalogContentTypes.Series)
        val categoryRows = dao.categorySummariesForType(
            contentType = SafeCatalogContentTypes.Series,
            minItems = minItems.coerceAtLeast(1),
            limit = limit.coerceIn(1, MaxCategoryRows),
        ).map { summary -> SeriesCategory(id = summary.name, name = summary.name, itemCount = summary.itemCount) }
        return listOf(SeriesCategory(id = "watchlist", name = "Watchlist", itemCount = watchlistCount)) +
            listOf(SeriesCategory(id = "all", name = "All", itemCount = allCount)) +
            categoryRows
    }

    suspend fun movieCategorySummaries(limit: Int = MaxAuditCategoryRows): List<SafeCatalogCategorySummary> =
        dao.allCategorySummariesForType(SafeCatalogContentTypes.Movie, limit.coerceIn(1, MaxAuditCategoryRows))

    suspend fun seriesCategorySummaries(limit: Int = MaxAuditCategoryRows): List<SafeCatalogCategorySummary> =
        dao.allCategorySummariesForType(SafeCatalogContentTypes.Series, limit.coerceIn(1, MaxAuditCategoryRows))

    suspend fun searchMovieEntities(query: String, limit: Int): List<SafeCatalogItemEntity> =
        dao.searchItemsForType(SafeCatalogContentTypes.Movie, query.normalizedCatalogText(), limit.coerceIn(1, MaxSearchItems), query.trim())

    suspend fun searchSeriesEntities(query: String, limit: Int): List<SafeCatalogItemEntity> =
        dao.searchItemsForType(SafeCatalogContentTypes.Series, query.normalizedCatalogText(), limit.coerceIn(1, MaxSearchItems), query.trim())

    suspend fun countSearchMovies(query: String): Int =
        dao.countSearchItemsForType(SafeCatalogContentTypes.Movie, query.normalizedCatalogText())

    suspend fun countSearchSeries(query: String): Int =
        dao.countSearchItemsForType(SafeCatalogContentTypes.Series, query.normalizedCatalogText())

    suspend fun movies(redactedUrlFor: (SafeCatalogItemEntity) -> String): List<MovieItem> =
        movies(DefaultUiLimit, redactedUrlFor)

    suspend fun movies(limit: Int, redactedUrlFor: (SafeCatalogItemEntity) -> String): List<MovieItem> =
        movieEntities(limit).map { entity -> entity.toMovieItem(redactedUrlFor(entity)) }

    suspend fun series(): List<SeriesItem> =
        series(DefaultUiLimit)

    suspend fun series(limit: Int): List<SeriesItem> =
        seriesEntities(limit).map { entity -> entity.toSeriesItem() }

    suspend fun homeCatalogStamp(): Pair<Long?, Long?> =
        dao.newestUpdatedAtMs(SafeCatalogContentTypes.Movie) to dao.newestUpdatedAtMs(SafeCatalogContentTypes.Series)

    suspend fun moviesCacheAgeMs(): Long? =
        cacheAgeMs(SafeCatalogContentTypes.Movie)

    suspend fun seriesCacheAgeMs(): Long? =
        cacheAgeMs(SafeCatalogContentTypes.Series)

    private suspend fun cacheAgeMs(contentType: String): Long? {
        val newest = dao.newestUpdatedAtMs(contentType) ?: return null
        return (clockMs() - newest).coerceAtLeast(0L)
    }

    private suspend fun entitiesForSort(
        contentType: String,
        sortKey: String,
        limit: Int,
    ): List<SafeCatalogItemEntity> =
        when (sortKey) {
            SortTitle -> dao.itemsForTypeTitle(contentType, limit.coerceIn(1, MaxBoundedItems))
            SortCategoryThenTitle -> dao.itemsForTypeCategoryThenTitle(contentType, limit.coerceIn(1, MaxBoundedItems))
            else -> dao.itemsForType(contentType, limit.coerceIn(1, MaxBoundedItems))
        }

    private suspend fun entitiesForCategory(
        contentType: String,
        categoryName: String,
        sortKey: String,
        limit: Int,
    ): List<SafeCatalogItemEntity> =
        when (sortKey) {
            SortTitle -> dao.itemsForCategoryTitle(contentType, categoryName, limit.coerceIn(1, MaxBoundedItems))
            SortCategoryThenTitle -> dao.itemsForCategoryThenTitle(contentType, categoryName, limit.coerceIn(1, MaxBoundedItems))
            else -> dao.itemsForCategory(contentType, categoryName, limit.coerceIn(1, MaxBoundedItems))
        }

    private suspend fun entitiesByIds(
        contentType: String,
        ids: Set<Int>,
        limit: Int,
    ): List<SafeCatalogItemEntity> {
        if (ids.isEmpty()) return emptyList()
        return dao.itemsByIds(contentType, ids.toList(), limit.coerceIn(1, MaxBoundedItems))
    }

    private companion object {
        private const val DefaultBatchSize = 500
        private const val DefaultUiLimit = 120
        private const val MaxBoundedItems = 500
        private const val MaxSearchItems = 120
        private const val MaxCategoryRows = 48
        private const val MaxAuditCategoryRows = 500
        private const val SortTitle = "title"
        private const val SortCategoryThenTitle = "category_then_title"
    }
}
