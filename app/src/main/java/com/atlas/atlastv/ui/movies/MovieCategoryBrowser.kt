package com.atlas.atlastv.ui.movies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

typealias MovieCategoriesSource = com.atlas.atlastv.ui.browse.CatalogCategoriesSource
typealias MovieCategoriesState = com.atlas.atlastv.ui.browse.CatalogCategoriesState
typealias MovieCategoriesStateHolder = com.atlas.atlastv.ui.browse.CatalogCategoriesStateHolder
class RoomMovieCategoriesSource(private val dao: com.atlas.atlastv.data.db.SafeCatalogDao) : MovieCategoriesSource {
    override suspend fun load() = dao.allMovieCategories().map { MovieCategory(it.name, it.name, it.itemCount) }
}

/** One category grid at a time. Holder survives detail/playback composition without accumulating windows. */
class MovieCategoryBrowser(val categories: MovieCategoriesStateHolder, private val createGrid: (String) -> MovieGridStateHolder) {
    var categoryName by mutableStateOf<String?>(null)
        private set
    var grid by mutableStateOf<MovieGridStateHolder?>(null)
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
