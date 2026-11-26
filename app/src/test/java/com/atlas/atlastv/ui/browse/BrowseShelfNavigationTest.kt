package com.atlas.atlastv.ui.browse

import org.junit.Assert.*
import org.junit.Test

class BrowseShelfNavigationTest {
    @Test fun eachShelfKeepsItsOwnTypedSelectionAndScroll() {
        val state = BrowseShelfNavigation()
        state.select("new", 8, 7); state.scroll("new", 6, 42)
        state.select("rated", 3, 2); state.scroll("rated", 1, 9)
        assertEquals(BrowseShelfPosition(8, 7, 6, 42), state.position("new", (1..18).toList()))
        assertEquals(BrowseShelfPosition(3, 2, 1, 9), state.position("rated", (1..18).toList()))
        assertEquals("rated", state.targetRow(listOf("new", "rated")))
    }
    @Test fun reorderReanchorsByIdentityAndVanishedItemsFallBackSafely() {
        val state = BrowseShelfNavigation()
        state.select("new", 8, 7); state.scroll("new", 6, 42)
        assertEquals(BrowseShelfPosition(8, 0, 0, 0), state.position("new", listOf(8, 9, 10)))
        assertEquals(2, state.position("new", listOf(2, 3, 4)).selectedIndex)
        assertEquals(BrowseShelfPosition(), state.position("new", emptyList()))
        assertEquals("other", state.targetRow(listOf("other")))
    }
    @Test fun snapshotsAreBoundedAndRestoreWithoutCatalogModels() {
        val state = BrowseShelfNavigation()
        repeat(40) { state.select("row-$it", it + 1, 0) }
        val saved = state.snapshot()
        assertEquals(32, saved.rows.size); assertFalse(saved.rows.containsKey("row-0"))
        val restored = BrowseShelfNavigation().apply { restore(saved) }
        assertEquals(saved, restored.snapshot())
    }
    @Test fun invalidOptionalShelfStateResetsAndPositionsAreClamped() {
        val state = BrowseShelfNavigation()
        state.restore(BrowseShelfSnapshot("http://private")); assertNull(state.focusedRow)
        state.restore(BrowseShelfSnapshot(rows = mapOf("new" to BrowseShelfPosition(-1))))
        assertTrue(state.snapshot().rows.isEmpty())
        state.restore(BrowseShelfSnapshot(rows = mapOf("new" to BrowseShelfPosition(1, -1, Int.MAX_VALUE, -2))))
        assertEquals(BrowseShelfPosition(1, 0, 10_000, 0), state.snapshot().rows["new"])
    }
}
