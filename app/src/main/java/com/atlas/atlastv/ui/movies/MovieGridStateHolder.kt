package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.data.db.SafeCatalogDao
import com.atlas.atlastv.data.db.toMovieItem
import com.atlas.atlastv.ui.browse.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

typealias MovieGridWindow = CatalogGridWindow<MovieItem>
typealias MovieGridState = CatalogGridState<MovieItem>
typealias MovieGridSource = CatalogGridSource<MovieItem>
typealias MovieGridDirection = CatalogGridDirection

class RoomMovieGridSource(private val dao: SafeCatalogDao) : MovieGridSource {
    override suspend fun load(index: Int, selectedId: Int?): MovieGridWindow {
        val page = dao.movieWindow(index, selectedId)
        return MovieGridWindow(page.total, page.offset, page.anchor, page.items.map { it.toMovieItem("room://safe_catalog/vod/${it.itemId}.${it.containerExtension ?: "mp4"}") })
    }
}
class RoomMovieCategoryGridSource(private val dao: SafeCatalogDao, private val category: String) : MovieGridSource {
    override suspend fun load(index: Int, selectedId: Int?): MovieGridWindow {
        val page = dao.movieCategoryWindow(category, index, selectedId)
        return MovieGridWindow(page.total, page.offset, page.anchor, page.items.map {
            it.toMovieItem("room://safe_catalog/vod/${it.itemId}.${it.containerExtension ?: "mp4"}")
        })
    }
}
class RoomMovieWatchlistGridSource(private val dao: com.atlas.atlastv.data.db.MovieWatchlistDao) : MovieGridSource {
    override suspend fun load(index: Int, selectedId: Int?): MovieGridWindow {
        val page = dao.window(index, selectedId)
        return MovieGridWindow(page.total, page.offset, page.anchor, page.items.map { it.toMovieItem() })
    }
}
class MovieGridStateHolder(source: MovieGridSource, dispatcher: CoroutineDispatcher = Dispatchers.IO, allowEmptyRefresh: Boolean = false) :
    CatalogGridStateHolder<MovieItem>(source, { it.streamId }, LoadFailure, dispatcher, allowEmptyRefresh) {
    var detailMovie: MovieItem?
        get() = detailItem
        set(value) { detailItem = value }
    companion object {
        const val PageSize = CatalogGridStateHolder.PageSize
        const val MaxItems = CatalogGridStateHolder.MaxItems
        const val LoadFailure = "Couldn't load these movies. Try again."
    }
}
fun movieGridTarget(index: Int, total: Int, columns: Int, direction: MovieGridDirection): Int? =
    catalogGridTarget(index, total, columns, direction)
