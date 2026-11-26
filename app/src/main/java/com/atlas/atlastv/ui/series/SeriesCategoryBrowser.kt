package com.atlas.atlastv.ui.series

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

typealias SeriesCategoriesSource = com.atlas.atlastv.ui.browse.CatalogCategoriesSource
typealias SeriesCategoriesState = com.atlas.atlastv.ui.browse.CatalogCategoriesState
typealias SeriesCategoriesStateHolder = com.atlas.atlastv.ui.browse.CatalogCategoriesStateHolder
class RoomSeriesCategoriesSource(private val dao: com.atlas.atlastv.data.db.SafeCatalogDao) : SeriesCategoriesSource {
    override suspend fun load() = dao.allSeriesCategories().map { SeriesCategory(it.name, it.name, it.itemCount) }
}

/** One category grid at a time. Holder survives detail/playback composition without accumulating windows. */
class SeriesCategoryBrowser(val categories: SeriesCategoriesStateHolder, private val createGrid: (String) -> SeriesGridStateHolder) {
    var categoryName by mutableStateOf<String?>(null)
        private set
    var grid by mutableStateOf<SeriesGridStateHolder?>(null)
        private set
    var categoriesOpen by mutableStateOf(false)
    var originRowId: String? = null
    var browseScrollIndex = 0
    var browseScrollOffset = 0
    val shelfNavigation = com.atlas.atlastv.ui.browse.BrowseShelfNavigation()
    fun openCategory(name: String, rowId: String? = null) {
        if (categoryName != name || grid == null) {
            categoryName = name
            grid = createGrid(name)
        }
        grid!!.open = true
        categories.selectedName = name
        originRowId = rowId
        categoriesOpen = false
    }
}
