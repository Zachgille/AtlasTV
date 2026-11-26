package com.atlas.atlastv.ui.series

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.ui.browse.NavigationSnapshotFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File

data class SeriesGridPosition(val selectedId: Int? = null, val selectedIndex: Int = 0,
    val scrollIndex: Int = 0, val scrollOffset: Int = 0)
data class SeriesBrowseSession(val owner: String, val surface: String = "browse",
    val all: SeriesGridPosition = SeriesGridPosition(), val categoryName: String? = null,
    val category: SeriesGridPosition? = null, val originRowId: String? = null,
    val selectedCategory: String? = null, val categoriesIndex: Int = 0, val categoriesOffset: Int = 0,
    val browseIndex: Int = 0, val browseOffset: Int = 0,
    val watchlist: SeriesGridPosition = SeriesGridPosition(), val watchlistOriginRow: Boolean = false,
    val shelves: com.atlas.atlastv.ui.browse.BrowseShelfSnapshot? = null)

interface SeriesBrowseSessionStore {
    suspend fun load(owner: String): SeriesBrowseSession?
    fun schedule(value: SeriesBrowseSession)
    suspend fun awaitSaved()
}
class FileSeriesBrowseSessionStore(file: File) : SeriesBrowseSessionStore {
    private val snapshots = NavigationSnapshotFile(file, SeriesBrowseSession::class.java)
    override suspend fun load(owner: String) = snapshots.load()?.validated()?.takeIf { it.owner == owner }
    override fun schedule(value: SeriesBrowseSession) { value.validated()?.let(snapshots::schedule) }
    override suspend fun awaitSaved() = snapshots.awaitSaved()
    companion object { const val FileName = "show_browse_session.json" }
}

/** Restored lazily when Shows opens, before grids attach; no catalog or Series models are saved. */
class SeriesBrowseSessionStateHolder(private val store: SeriesBrowseSessionStore, private val owner: String?,
    private val all: SeriesGridStateHolder, private val categories: SeriesCategoryBrowser,
    private val watchlist: SeriesWatchlistBrowser) {
    var ready by mutableStateOf(false)
        private set
    suspend fun restore(reopen: Boolean = true) {
        if (ready) return
        val saved = try { owner?.let { store.load(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        coroutineContext.ensureActive()
        if (saved != null && saved.owner == owner) saved.validated()?.let { value ->
            all.restore(value.all)
            watchlist.grid.restore(value.watchlist); watchlist.originRow = value.watchlistOriginRow
            categories.browseScrollIndex = value.browseIndex; categories.browseScrollOffset = value.browseOffset
            categories.shelfNavigation.restore(value.shelves)
            if (value.categoryName != null) {
                categories.openCategory(value.categoryName, value.originRowId)
                value.category?.let { categories.grid!!.restore(it) }
                categories.grid!!.open = false
            }
            categories.categories.selectedName = value.selectedCategory
            categories.categories.scrollIndex = value.categoriesIndex; categories.categories.scrollOffset = value.categoriesOffset
            if (reopen) when (value.surface) {
                "all" -> all.open = true
                "category" -> categories.grid?.open = true
                "categories" -> categories.categoriesOpen = true
                "watchlist" -> watchlist.grid.open = true
            }
        }
        ready = true
    }
    fun checkpoint() {
        if (!ready || owner == null) return
        val surface = when {
            watchlist.grid.open -> "watchlist"
            categories.grid?.open == true -> "category"
            all.open -> "all"
            categories.categoriesOpen -> "categories"
            else -> "browse"
        }
        store.schedule(SeriesBrowseSession(owner, surface, all.position(), categories.categoryName,
            categories.grid?.position(), categories.originRowId, categories.categories.selectedName,
            categories.categories.scrollIndex, categories.categories.scrollOffset,
            categories.browseScrollIndex, categories.browseScrollOffset, watchlist.grid.position(), watchlist.originRow,
            categories.shelfNavigation.snapshot()))
    }
}
private fun SeriesGridStateHolder.position() = SeriesGridPosition(selectedKey, state.value.selectedIndex, scrollIndex, scrollOffset)
private fun SeriesGridStateHolder.restore(position: SeriesGridPosition) = restoreNavigation(
    position.selectedId, position.selectedIndex, position.scrollIndex, position.scrollOffset)
internal fun SeriesBrowseSession.validated(): SeriesBrowseSession? = runCatching {
    require(owner.length in 1..128 && owner.matches(Regex("[A-Za-z0-9_-]+")))
    require(surface in setOf("browse", "all", "category", "categories", "watchlist"))
    fun String.safeName() = length in 1..256 && none { it.isISOControl() } && !contains("://")
    require(categoryName == null || categoryName.safeName())
    require(selectedCategory == null || selectedCategory.safeName())
    require(surface != "category" || (categoryName != null && category != null))
    require(originRowId == null || (originRowId.length <= 300 && originRowId.none { it.isISOControl() } && !originRowId.contains("://")))
    fun Int.index() = coerceIn(0, 10_000_000)
    fun Int.offset() = coerceIn(0, 100_000)
    fun SeriesGridPosition.safe(): SeriesGridPosition {
        require(selectedId == null || selectedId > 0)
        return copy(selectedIndex = selectedIndex.index(), scrollIndex = scrollIndex.index(), scrollOffset = scrollOffset.offset())
    }
    copy(all = all.safe(), category = category?.safe(), watchlist = watchlist.safe(),
        categoriesIndex = categoriesIndex.index(), categoriesOffset = categoriesOffset.offset(),
        browseIndex = browseIndex.index(), browseOffset = browseOffset.offset(),
        shelves = shelves?.let { com.atlas.atlastv.ui.browse.validateBrowseShelves(it) })
}.getOrNull()
