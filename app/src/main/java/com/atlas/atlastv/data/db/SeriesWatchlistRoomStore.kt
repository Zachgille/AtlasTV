package com.atlas.atlastv.data.db

import com.atlas.atlastv.ui.series.SeriesItem

class SeriesWatchlistRoomStore(
    private val dao: SeriesWatchlistDao,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadItems(): List<SeriesWatchlistEntity> =
        dao.items()

    suspend fun loadIds(): Set<Int> =
        dao.items().mapTo(linkedSetOf()) { it.seriesId }

    suspend fun saveWatchlist(series: List<SeriesItem>, watchlistSeriesIds: Set<Int>) {
        val now = clockMs()
        val items = series
            .filter { it.seriesId in watchlistSeriesIds }
            .map { item -> item.toSeriesWatchlistEntity(now) }
        dao.replaceAll(items)
    }

    suspend fun saveSnapshots(items: List<SeriesWatchlistEntity>) {
        dao.replaceAll(items)
    }

    suspend fun clear() {
        dao.clear()
    }

    suspend fun count(): Int =
        dao.count()
}
