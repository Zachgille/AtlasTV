package com.atlas.atlastv.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UnifiedSearchStateHolder(
    private val scope: CoroutineScope,
    private val searchCatalog: suspend (String) -> UnifiedSearchState.Ready,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = DefaultUnifiedSearchDebounceMs,
    private val gridFactory: ((String, String) -> SearchResultsGrid)? = null,
    private val sessionStore: SearchSessionStore? = null,
    private val sessionOwner: () -> String? = { null },
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        catalogRepository: SafeCatalogRepository = SafeCatalogRepository(AtlasDatabaseProvider.database(context).safeCatalogDao()),
        sessionOwner: () -> String? = { null },
    ) : this(scope, RoomUnifiedSearchSource(catalogRepository)::search,
        gridFactory = { query, row -> roomSearchGrid(AtlasDatabaseProvider.database(context).safeCatalogDao(), query, row) },
        sessionStore = FileSearchSessionStore(File(context.filesDir, FileSearchSessionStore.FileName)), sessionOwner = sessionOwner)

    var state: UnifiedSearchState by mutableStateOf(emptySearch(""))
        private set

    private var searchJob: Job? = null
    private var restoreJob: Job? = null
    private var restoredOwner: String? = null
    private var searchingVersion = -1L
    private var entryFocusRow: String? = null
    var restorationReady by mutableStateOf(sessionStore == null)
        private set
    fun contextKey(): String? = sessionOwner()
    private var requestVersion = 0L
    var query by mutableStateOf("")
        private set
    var catalogVersion by mutableStateOf(0L)
        private set
    private var loadedVersion = -1L
    var activeGrid by mutableStateOf<SearchResultsGrid?>(null)
        private set
    val supportsContinuation: Boolean get() = gridFactory != null
    var selectedResultId: String? = null
    var restoreResult = false
    var restoreEntryRowId: String? = null
    var listIndex = 0
    var listOffset = 0
    val rowScroll = mutableMapOf<String, Pair<Int, Int>>()

    fun activate() {
        val owner = sessionOwner()
        if (sessionStore != null && (!restorationReady || owner != restoredOwner)) {
            if (restoreJob?.isActive == true && owner == restoredOwner) return
            restoreJob?.cancel(); cancel(); resetContext()
            restoredOwner = owner; restorationReady = false
            val version = requestVersion
            restoreJob = scope.launch {
                val saved = try { owner?.let { withContext(searchDispatcher) { sessionStore.load(it) } } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (version != requestVersion || owner != sessionOwner()) return@launch
                if (saved != null && saved.owner == owner && saved.validated() != null) {
                    query = saved.query; selectedResultId = saved.selectedResultId
                    restoreResult = saved.selectedResultId != null
                    restoreEntryRowId = saved.entryRowId; entryFocusRow = saved.entryRowId
                    listIndex = saved.position.index; listOffset = saved.position.offset
                    rowScroll.putAll(saved.rows.mapValues { it.value.index to it.value.offset })
                    saved.grid?.let { grid -> activeGrid = gridFactory?.invoke(query, grid.rowId)?.also {
                        it.holder.restoreNavigation(grid.selectedId, grid.selectedIndex, grid.position.index, grid.position.offset)
                        it.holder.open = true
                    } }
                }
                restorationReady = true; restoreJob = null
                search(query)
            }
            return
        }
        if (searchJob?.isActive == true && searchingVersion == catalogVersion) return
        if (state !is UnifiedSearchState.Ready || loadedVersion != catalogVersion) search(query)
    }
    fun invalidateCatalog() { catalogVersion++ }
    fun openAll(rowId: String) {
        val ready = state as? UnifiedSearchState.Ready ?: return
        if (ready.query.isBlank() || ready.rows.none { it.id == rowId }) return
        activeGrid = gridFactory?.invoke(ready.query, rowId)?.also { it.holder.open = true }
        restoreResult = false
        restoreEntryRowId = null
        entryFocusRow = rowId
        checkpoint()
    }
    fun closeAll() {
        val grid = activeGrid ?: return
        val row = (state as? UnifiedSearchState.Ready)?.rows?.firstOrNull { it.id == grid.rowId }
        if (row != null && supportsContinuation && row.totalCount > row.items.size) restoreEntryRowId = row.id
        else {
            restoreEntryRowId = null
            selectedResultId = row?.items?.firstOrNull()?.id
            restoreResult = selectedResultId != null
        }
        grid.holder.open = false
        activeGrid = null
        entryFocusRow = restoreEntryRowId
        checkpoint()
    }
    fun selectResult(item: UnifiedSearchItem) {
        selectedResultId = item.id
        restoreResult = activeGrid == null
        entryFocusRow = null
        checkpoint()
    }

    fun rememberResultFocus(item: UnifiedSearchItem) { selectedResultId = item.id; entryFocusRow = null; checkpoint() }
    fun rememberEntryFocus(rowId: String) { entryFocusRow = rowId; checkpoint() }

    fun checkpoint() {
        val owner = sessionOwner() ?: return
        if (!restorationReady || owner != restoredOwner) return
        val grid = activeGrid?.let {
            SearchGridSession(it.rowId, it.holder.selectedKey, it.holder.state.value.selectedIndex,
                SearchScrollPosition(it.holder.scrollIndex, it.holder.scrollOffset))
        }
        sessionStore?.schedule(SearchSession(owner, query, selectedResultId, entryFocusRow,
            SearchScrollPosition(listIndex, listOffset), rowScroll.mapValues { SearchScrollPosition(it.value.first, it.value.second) }, grid))
    }

    private fun resetContext() {
        query = ""; state = emptySearch(""); activeGrid = null; selectedResultId = null
        restoreResult = false; restoreEntryRowId = null; entryFocusRow = null
        listIndex = 0; listOffset = 0; rowScroll.clear()
    }

    /** Called on the UI thread; database work and mapping run on the search dispatcher. */
    fun search(query: String) {
        cancel()
        if (sessionStore != null && (!restorationReady || restoredOwner != sessionOwner())) {
            restoreJob?.cancel(); restoreJob = null
            if (restoredOwner != sessionOwner()) resetContext()
            restoredOwner = sessionOwner(); restorationReady = true
        }
        val version = requestVersion
        val catalog = catalogVersion
        searchingVersion = catalog
        val safeQuery = query.take(MaxUnifiedSearchQueryLength)
        if (this.query != safeQuery) {
            activeGrid = null; selectedResultId = null; restoreResult = false; restoreEntryRowId = null
            listIndex = 0; listOffset = 0; rowScroll.clear()
            entryFocusRow = null
        }
        this.query = safeQuery
        checkpoint()
        if (safeQuery.isBlank()) {
            state = emptySearch(safeQuery)
            loadedVersion = catalogVersion
            return
        }
        state = UnifiedSearchState.Loading
        searchJob = scope.launch {
            delay(debounceMs)
            val result = try {
                withContext(searchDispatcher) { searchCatalog(safeQuery) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UnifiedSearchState.Error("Search is unavailable right now. Please try again.")
            }
            coroutineContext.ensureActive()
            if (requestVersion == version) {
                if (result is UnifiedSearchState.Ready) {
                    if (restoreEntryRowId != null && result.rows.none { it.id == restoreEntryRowId }) {
                        restoreEntryRowId = null; restoreResult = result.rows.isNotEmpty()
                    }
                    if (result.rows.none { it.id == entryFocusRow }) entryFocusRow = null
                    if (result.rows.isEmpty() && activeGrid == null) { selectedResultId = null; restoreResult = false }
                }
                state = result; loadedVersion = catalog
            }
        }
    }

    fun cancel() {
        requestVersion++
        searchJob?.cancel()
        searchJob = null
    }

    private fun emptySearch(query: String) =
        UnifiedSearchState.Ready(query, movieCount = 0, seriesCount = 0, rows = emptyList())
}

const val DefaultUnifiedSearchDebounceMs = 250L
