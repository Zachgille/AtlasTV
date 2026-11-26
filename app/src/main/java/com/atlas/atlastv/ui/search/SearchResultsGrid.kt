package com.atlas.atlastv.ui.search

import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.browse.CatalogGridSource
import com.atlas.atlastv.ui.browse.CatalogGridStateHolder
import com.atlas.atlastv.ui.browse.CatalogGridWindow

/** One query/type window. Never loads browse models, credentials or episode metadata. */
class RoomSearchGridSource(private val dao: SafeCatalogDao, private val type: String, query: String) : CatalogGridSource<UnifiedSearchItem> {
    private val normalized = query.take(MaxUnifiedSearchQueryLength).normalizedCatalogText()
    private val exactQuery = query.take(MaxUnifiedSearchQueryLength).trim()
    init { require(type == SafeCatalogContentTypes.Movie || type == SafeCatalogContentTypes.Series) }
    override suspend fun load(index: Int, selectedId: Int?): CatalogGridWindow<UnifiedSearchItem> {
        val page = dao.searchWindow(type, normalized, index, selectedId, exactQuery)
        return CatalogGridWindow(page.total, page.offset, page.anchor, page.items.map { entity ->
            if (type == SafeCatalogContentTypes.Movie) UnifiedSearchItem.Movie(entity.toMovieItem("room://safe_catalog/vod/${entity.itemId}.${entity.containerExtension ?: "mp4"}"))
            else UnifiedSearchItem.Show(entity.toSeriesItem())
        })
    }
}

fun searchResultId(item: UnifiedSearchItem): Int = when (item) {
    is UnifiedSearchItem.Movie -> item.movie.streamId
    is UnifiedSearchItem.Show -> item.series.seriesId
}

data class SearchResultsGrid(val query: String, val rowId: String, val title: String,
    val holder: CatalogGridStateHolder<UnifiedSearchItem>)

fun roomSearchGrid(dao: SafeCatalogDao, query: String, rowId: String): SearchResultsGrid {
    val movies = rowId == "unified_search_movies"
    require(movies || rowId == "unified_search_shows")
    return SearchResultsGrid(query, rowId, if (movies) "Movie Results" else "Show Results",
        CatalogGridStateHolder(RoomSearchGridSource(dao, if (movies) SafeCatalogContentTypes.Movie else SafeCatalogContentTypes.Series, query),
            ::searchResultId, "Couldn't load these results. Try again."))
}
