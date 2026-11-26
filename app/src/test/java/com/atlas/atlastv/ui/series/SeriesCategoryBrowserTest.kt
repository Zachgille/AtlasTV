package com.atlas.atlastv.ui.series

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesCategoryBrowserTest {
    @Test fun exactCategoryKeysDoNotCollideAndOnlyOneCategoryWindowIsRetained() {
        val names = mutableListOf<String>()
        val browser = SeriesCategoryBrowser(SeriesCategoriesStateHolder(SeriesCategoriesSource { emptyList() })) { name ->
            names += name; SeriesGridStateHolder(SeriesGridSource { _, _ -> SeriesGridWindow(0, 0, 0, emptyList()) }, allowEmptyRefresh = true)
        }
        browser.openCategory("Kids & Family", "first-row"); val first = browser.grid
        browser.openCategory("Kids & Family", "first-row"); assertSame(first, browser.grid)
        browser.openCategory("Kids Family"); assertNotSame(first, browser.grid)
        assertEquals(listOf("Kids & Family", "Kids Family"), names)
        assertNull(browser.originRowId); assertEquals("Kids Family", browser.categories.selectedName)
        assertFalse(browser.categoriesOpen)
    }
    @Test fun successfulRefreshCanRemoveTheLastSeriesFromACategory() = runTest {
        var empty = false
        val item = SeriesItem("Drama", "Drama", 1)
        val grid = SeriesGridStateHolder(SeriesGridSource { _, _ ->
            if (empty) SeriesGridWindow(0, 0, 0, emptyList()) else SeriesGridWindow(1, 0, 0, listOf(item))
        }, StandardTestDispatcher(testScheduler), allowEmptyRefresh = true)
        val first = backgroundScope.launch { grid.activate(1) }; runCurrent(); first.cancelAndJoin()
        empty = true; backgroundScope.launch { grid.activate(2) }; runCurrent()
        assertEquals(0, grid.state.value.total); assertTrue(grid.state.value.items.isEmpty()); assertNull(grid.state.value.error)
    }
}
