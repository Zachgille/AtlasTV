package com.atlas.atlastv.ui.browse

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CatalogCategory(val id: String, val name: String, val itemCount: Int)

fun interface CatalogCategoriesSource { suspend fun load(): List<CatalogCategory> }
data class CatalogCategoriesState(val items: List<CatalogCategory> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val initialized: Boolean = false)

/** Only category names/counts are retained; content windows stay in the separate bounded grid. */
class CatalogCategoriesStateHolder(private val source: CatalogCategoriesSource, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow(CatalogCategoriesState())
    val state = mutable.asStateFlow()
    var selectedName: String? = null
    var scrollIndex = 0
    var scrollOffset = 0
    private var revision: Long? = null
    private var activeRevision = 0L
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var version = 0L
    suspend fun activate(catalogRevision: Long): Unit = coroutineScope {
        val owner = this
        scope = owner; activeRevision = catalogRevision
        if (!mutable.value.initialized || revision != catalogRevision) retry()
        try { awaitCancellation() } finally {
            if (scope === owner) {
                job?.cancel(); job = null; scope = null; version++
                mutable.value = mutable.value.copy(loading = false)
            }
        }
    }
    fun retry() {
        val screenScope = scope ?: return
        val request = ++version
        job?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        job = screenScope.launch {
            try {
                val items = withContext(dispatcher) { source.load() }
                ensureActive()
                if (request != version) return@launch
                mutable.value = CatalogCategoriesState(items, initialized = true)
                revision = activeRevision
                if (items.none { it.id == selectedName }) selectedName = items.firstOrNull()?.id
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == version) mutable.value = mutable.value.copy(loading = false, error = "Couldn't load categories. Try again.")
            }
        }
    }
}

