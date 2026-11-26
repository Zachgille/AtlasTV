package com.atlas.atlastv.ui.browse

/** Bounded shelf context only: no titles, artwork, credentials or catalog models. */
data class BrowseShelfPosition(val selectedId: Int? = null, val selectedIndex: Int = 0,
    val scrollIndex: Int = 0, val scrollOffset: Int = 0)
data class BrowseShelfSnapshot(val focusedRow: String? = null,
    val rows: Map<String, BrowseShelfPosition> = emptyMap())

class BrowseShelfNavigation {
    private var saved = BrowseShelfSnapshot()
    val focusedRow: String? get() = saved.focusedRow
    fun snapshot(): BrowseShelfSnapshot = saved
    fun restore(value: BrowseShelfSnapshot?) { saved = value?.validated() ?: BrowseShelfSnapshot() }
    fun position(row: String, ids: List<Int>): BrowseShelfPosition {
        if (ids.isEmpty()) return BrowseShelfPosition()
        val previous = saved.rows[row] ?: BrowseShelfPosition()
        val selected = ids.indexOf(previous.selectedId).takeIf { it >= 0 }
            ?: previous.selectedIndex.coerceIn(ids.indices)
        val reordered = selected != previous.selectedIndex
        return BrowseShelfPosition(ids[selected], selected,
            if (reordered) selected else previous.scrollIndex.coerceIn(ids.indices),
            if (reordered) 0 else previous.scrollOffset)
    }
    fun select(row: String, id: Int, index: Int) {
        val previous = saved.rows[row] ?: BrowseShelfPosition()
        update(row, previous.copy(selectedId = id, selectedIndex = index.coerceAtLeast(0)))
        saved = saved.copy(focusedRow = row)
    }
    fun scroll(row: String, index: Int, offset: Int) {
        val previous = saved.rows[row] ?: BrowseShelfPosition()
        update(row, previous.copy(scrollIndex = index.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0)))
    }
    fun targetRow(rowIds: List<String>): String? = focusedRow?.takeIf { it in rowIds } ?: rowIds.firstOrNull()
    private fun update(row: String, value: BrowseShelfPosition) {
        val rows = LinkedHashMap(saved.rows)
        rows.remove(row)
        rows[row] = value
        while (rows.size > MaxRows) rows.remove(rows.keys.first())
        saved = saved.copy(rows = rows)
    }
    companion object { const val MaxRows = 32 }
}

internal fun BrowseShelfSnapshot.validated(): BrowseShelfSnapshot? = runCatching {
    fun String.validRow() = length in 1..300 && none { it.isISOControl() } && !contains("://")
    require(focusedRow == null || focusedRow.validRow())
    require(rows.size <= BrowseShelfNavigation.MaxRows)
    val safe = rows.mapValues { (row, position) ->
        require(row.validRow())
        require(position.selectedId == null || position.selectedId > 0)
        position.copy(selectedIndex = position.selectedIndex.coerceIn(0, 10_000),
            scrollIndex = position.scrollIndex.coerceIn(0, 10_000),
            scrollOffset = position.scrollOffset.coerceIn(0, 100_000))
    }
    copy(rows = safe)
}.getOrNull()

internal fun validateBrowseShelves(value: BrowseShelfSnapshot): BrowseShelfSnapshot? = value.validated()
