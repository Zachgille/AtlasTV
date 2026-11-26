package com.atlas.atlastv.ui.browse

import com.atlas.atlastv.data.db.SafeCatalogWindow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared bounded paging; each catalog keeps its own holder, identity and return context. */
data class CatalogGridWindow<T : Any>(val total: Int, val offset: Int, val anchor: Int, val items: List<T>)
fun interface CatalogGridSource<T : Any> { suspend fun load(index: Int, selectedId: Int?): CatalogGridWindow<T> }
fun interface KeyedCatalogGridSource<T : Any, K : Any> { suspend fun load(index: Int, selectedId: K?): CatalogGridWindow<T> }

data class CatalogGridState<T : Any>(
    val total: Int = 0, val offset: Int = 0, val items: List<T> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val initialized: Boolean = false,
    val selectedIndex: Int = 0,
) {
    fun item(index: Int): T? = items.getOrNull(index - offset)
}

open class KeyedCatalogGridStateHolder<T : Any, K : Any>(
    private val source: KeyedCatalogGridSource<T, K>,
    private val idOf: (T) -> K,
    private val loadFailure: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowEmptyRefresh: Boolean = false,
) {
    private val mutable = MutableStateFlow(CatalogGridState<T>())
    val state = mutable.asStateFlow()
    var open = false
    var detailItem: T? = null
    var scrollIndex = 0
    var scrollOffset = 0
    private var selectedId: K? = null
    val selectedKey: K? get() = selectedId

    /** Restore identity before the first page; the source reanchors it after catalog changes. */
    fun restoreNavigation(id: K?, index: Int, firstVisibleIndex: Int, offset: Int) {
        if (mutable.value.initialized || screenScope != null) return
        selectedId = id
        mutable.value = mutable.value.copy(selectedIndex = index.coerceAtLeast(0))
        scrollIndex = firstVisibleIndex.coerceAtLeast(0)
        scrollOffset = offset.coerceAtLeast(0)
    }
    private var requestVersion = 0L
    private var job: Job? = null
    private var pendingIndex: Int? = null
    private var loadedRevision: Pair<Long, Boolean>? = null
    private var activeRevision = 0L to false
    private var pendingRefresh = false

    suspend fun activate(revision: Long, catalogReady: Boolean = true): Unit = coroutineScope {
        val owner = this
        screenScope = owner
        activeRevision = revision to catalogReady
        val readinessOnly = mutable.value.initialized && mutable.value.total > 0 && loadedRevision?.first == revision
        if (readinessOnly) loadedRevision = activeRevision
        else if (!mutable.value.initialized || loadedRevision != activeRevision) {
            request(mutable.value.selectedIndex, refresh = true)
        }
        try { awaitCancellation() } finally {
            if (screenScope === owner) {
                job?.cancel(); job = null; pendingIndex = null; screenScope = null; requestVersion++
                mutable.value = mutable.value.copy(loading = false)
            }
        }
    }

    // Only called with the screen-owned scope. Navigation/UI remain on the caller's dispatcher.
    private var screenScope: CoroutineScope? = null
    fun ensureWindow(index: Int) {
        val state = mutable.value
        if (state.initialized && state.total == 0) return
        val target = index.coerceIn(0, (state.total - 1).coerceAtLeast(0))
        val local = target - state.offset
        if (state.item(target) != null && (state.offset == 0 || local >= PageSize) &&
            (state.offset + state.items.size >= state.total || local < state.items.size - PageSize)) return
        if (pendingIndex?.div(PageSize) == target / PageSize) return
        request(target)
    }
    fun retry() { request(pendingIndex ?: mutable.value.selectedIndex, refresh = pendingRefresh || !mutable.value.initialized) }
    fun select(index: Int) {
        selectedId = mutable.value.item(index)?.let(idOf)
        mutable.value = mutable.value.copy(selectedIndex = index.coerceIn(0, (mutable.value.total - 1).coerceAtLeast(0)))
        ensureWindow(index)
    }

    private fun request(index: Int, refresh: Boolean = false) {
        val scope = screenScope ?: return
        val version = ++requestVersion
        job?.cancel(); pendingIndex = index; pendingRefresh = refresh
        mutable.value = mutable.value.copy(loading = true, error = null)
        val retainedId = selectedId.takeIf { refresh }
        job = scope.launch {
            try {
                val page = withContext(dispatcher) { source.load(index, retainedId) }
                ensureActive()
                if (version != requestVersion) return@launch
                require(page.items.size <= MaxItems && page.offset >= 0 && page.total >= 0)
                if (page.total == 0 && mutable.value.initialized && mutable.value.total > 0 && !allowEmptyRefresh) error("Empty refresh")
                val previous = mutable.value
                val selection = if (refresh) page.anchor else previous.selectedIndex.coerceIn(0, (page.total - 1).coerceAtLeast(0))
                mutable.value = previous.copy(total = page.total, offset = page.offset, items = page.items,
                    selectedIndex = selection, initialized = true, loading = false, error = null)
                loadedRevision = activeRevision
                pendingIndex = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == requestVersion) mutable.value = mutable.value.copy(loading = false, error = loadFailure)
            }
        }
    }
    companion object {
        const val PageSize = SafeCatalogWindow.PageSize
        const val MaxItems = SafeCatalogWindow.MaxItems
    }
}

/** Keep existing per-type Int APIs while combined lists use a typed identity. */
open class CatalogGridStateHolder<T : Any>(source: CatalogGridSource<T>, idOf: (T) -> Int,
    loadFailure: String, dispatcher: CoroutineDispatcher = Dispatchers.IO, allowEmptyRefresh: Boolean = false) :
    KeyedCatalogGridStateHolder<T, Int>(KeyedCatalogGridSource { index, id -> source.load(index, id) },
        idOf, loadFailure, dispatcher, allowEmptyRefresh) {
    companion object {
        const val PageSize = KeyedCatalogGridStateHolder.PageSize
        const val MaxItems = KeyedCatalogGridStateHolder.MaxItems
    }
}

enum class CatalogGridDirection { Left, Right, Up, Down, PageUp, PageDown }
fun catalogGridTarget(index: Int, total: Int, columns: Int, direction: CatalogGridDirection): Int? {
    if (total <= 0 || columns <= 0) return null
    val current = index.coerceIn(0, total - 1)
    return when (direction) {
        CatalogGridDirection.Left -> if (current % columns == 0) current else current - 1
        CatalogGridDirection.Right -> if (current % columns == columns - 1) current else minOf(current + 1, total - 1)
        CatalogGridDirection.Up -> (current - columns).takeIf { it >= 0 }
        CatalogGridDirection.Down -> if (current / columns == (total - 1) / columns) current else minOf(current + columns, total - 1)
        CatalogGridDirection.PageUp -> maxOf(0, current - CatalogGridStateHolder.PageSize)
        CatalogGridDirection.PageDown -> minOf(total - 1, current + CatalogGridStateHolder.PageSize)
    }
}
