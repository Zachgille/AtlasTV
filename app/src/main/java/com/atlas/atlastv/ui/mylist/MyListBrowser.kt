package com.atlas.atlastv.ui.mylist

import android.content.Context
import androidx.compose.runtime.*
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

typealias MyListGridSource = KeyedCatalogGridSource<MyListItem, MyListKey>
class RoomMyListSource(private val dao: MyListDao, private val initialize: () -> Unit = {}) {
    private var initialized = false
    private val initialization = Mutex()
    suspend fun load(filter: MyListFilter, index: Int, selected: MyListKey?): CatalogGridWindow<MyListItem> {
        initialization.withLock { if (!initialized) { initialize(); initialized = true } }
        val page = dao.window(filter.storageKey, index, selected)
        return CatalogGridWindow(page.total, page.offset, page.anchor, page.items.map { it.toItem() })
    }
    companion object {
        fun create(context: Context): RoomMyListSource {
            val db = AtlasDatabaseProvider.database(context)
            return RoomMyListSource(db.myListDao()) {
                MoviesWatchlistStore(File(context.filesDir, MoviesWatchlistStore.FileName), MoviesWatchlistRoomStore(db.movieWatchlistDao())).loadWatchlistIds()
                SeriesWatchlistStore(File(context.filesDir, SeriesWatchlistStore.FileName), SeriesWatchlistRoomStore(db.seriesWatchlistDao())).loadWatchlistIds()
            }
        }
    }
}
class MyListBrowser(private val source: (MyListFilter) -> MyListGridSource, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    var filter by mutableStateOf(MyListFilter.All)
        private set
    var grid by mutableStateOf(newGrid(filter))
        private set
    private fun newGrid(filter: MyListFilter) = KeyedCatalogGridStateHolder(source(filter), { it.key }, "Couldn't load My List. Try again.", dispatcher, allowEmptyRefresh = true)
    fun selectFilter(next: MyListFilter) {
        if (filter == next) return
        filter = next
        grid = newGrid(next)
    }
}
