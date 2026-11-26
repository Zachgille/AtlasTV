package com.atlas.atlastv.ui.series

import com.atlas.atlastv.data.db.SafeCatalogDao
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.db.toSeriesItem
import com.atlas.atlastv.ui.browse.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

typealias SeriesGridWindow = CatalogGridWindow<SeriesItem>
typealias SeriesGridState = CatalogGridState<SeriesItem>
typealias SeriesGridSource = CatalogGridSource<SeriesItem>

class RoomSeriesGridSource(private val dao: SafeCatalogDao) : SeriesGridSource {
    override suspend fun load(index: Int, selectedId: Int?): SeriesGridWindow {
        val page = dao.catalogWindow(SafeCatalogContentTypes.Series, index, selectedId)
        return SeriesGridWindow(page.total, page.offset, page.anchor, page.items.map { it.toSeriesItem() })
    }
}
class RoomSeriesCategoryGridSource(private val dao: SafeCatalogDao, private val category: String) : SeriesGridSource {
    override suspend fun load(index: Int, selectedId: Int?): SeriesGridWindow {
        val page = dao.seriesCategoryWindow(category, index, selectedId)
        return SeriesGridWindow(page.total, page.offset, page.anchor, page.items.map { it.toSeriesItem() })
    }
}
class RoomSeriesWatchlistGridSource(private val dao: com.atlas.atlastv.data.db.SeriesWatchlistDao) : SeriesGridSource {
    override suspend fun load(index: Int, selectedId: Int?): SeriesGridWindow {
        val page = dao.window(index, selectedId)
        return SeriesGridWindow(page.total, page.offset, page.anchor, page.items.map { it.toSeriesItem() })
    }
}
class SeriesGridStateHolder(source: SeriesGridSource, dispatcher: CoroutineDispatcher = Dispatchers.IO, allowEmptyRefresh: Boolean = false) :
    CatalogGridStateHolder<SeriesItem>(source, { it.seriesId }, LoadFailure, dispatcher, allowEmptyRefresh) {
    var detailSeries: SeriesItem?
        get() = detailItem
        set(value) { detailItem = value }
    companion object {
        const val PageSize = CatalogGridStateHolder.PageSize
        const val MaxItems = CatalogGridStateHolder.MaxItems
        const val LoadFailure = "Couldn't load these shows. Try again."
    }
}
