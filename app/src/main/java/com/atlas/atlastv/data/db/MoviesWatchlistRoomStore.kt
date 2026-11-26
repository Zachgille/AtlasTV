package com.atlas.atlastv.data.db

import com.atlas.atlastv.ui.movies.MovieItem

class MoviesWatchlistRoomStore(
    private val dao: MovieWatchlistDao,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadItems(): List<MovieWatchlistEntity> =
        dao.items()

    suspend fun loadIds(): Set<Int> =
        dao.items().mapTo(linkedSetOf()) { it.streamId }

    suspend fun saveWatchlist(movies: List<MovieItem>, watchlistStreamIds: Set<Int>) {
        val now = clockMs()
        val items = movies
            .filter { it.streamId in watchlistStreamIds }
            .map { movie -> movie.toMovieWatchlistEntity(now) }
        dao.replaceAll(items)
    }

    suspend fun saveSnapshots(items: List<MovieWatchlistEntity>) {
        dao.replaceAll(items)
    }

    suspend fun clear() {
        dao.clear()
    }

    suspend fun count(): Int =
        dao.count()
}
