package com.atlas.atlastv.ui.movies

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieCategoryBrowserTest {
    private fun category(name: String, count: Int = 1) = MovieCategory(name, name, count)
    @Test fun categoryMetadataLoadsAsynchronouslyAndLeavingRejectsNoncancellableLateResponse() = runTest {
        val gate = CompletableDeferred<Unit>()
        val holder = MovieCategoriesStateHolder(MovieCategoriesSource { withContext(NonCancellable) { gate.await(); listOf(category("Drama")) } }, StandardTestDispatcher(testScheduler))
        val screen = backgroundScope.launch { holder.activate(1) }; runCurrent()
        assertTrue(holder.state.value.loading); assertTrue(holder.state.value.items.isEmpty())
        screen.cancel(); gate.complete(Unit); runCurrent()
        assertFalse(holder.state.value.initialized); assertFalse(holder.state.value.loading)
    }
    @Test fun sameRevisionReentryReusesNamesAndRefreshPreservesIdentityAcrossReordering() = runTest {
        var reads = 0; var rows = listOf(category("Drama"), category("Kids & Family"))
        val holder = MovieCategoriesStateHolder(MovieCategoriesSource { reads++; rows }, StandardTestDispatcher(testScheduler))
        var screen = backgroundScope.launch { holder.activate(1) }; runCurrent(); holder.selectedName = "Kids & Family"; screen.cancelAndJoin()
        screen = backgroundScope.launch { holder.activate(1) }; runCurrent(); assertEquals(1, reads); screen.cancelAndJoin()
        rows = listOf(category("Comedy")) + rows
        screen = backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals(2, reads); assertEquals("Kids & Family", holder.selectedName); screen.cancelAndJoin()
        rows = listOf(category("Comedy")); backgroundScope.launch { holder.activate(3) }; runCurrent()
        assertEquals("Comedy", holder.selectedName)
    }
    @Test fun canceledOldActivationCannotClearTheNewScreenScopeOrPublishItsCategoryList() = runTest {
        val gate = CompletableDeferred<Unit>(); var reads = 0
        val holder = MovieCategoriesStateHolder(MovieCategoriesSource {
            reads++
            if (reads == 1) withContext(NonCancellable) { gate.await(); listOf(category("Old")) } else listOf(category("New"))
        }, StandardTestDispatcher(testScheduler))
        val old = backgroundScope.launch { holder.activate(1) }; runCurrent(); old.cancel()
        backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals("New", holder.state.value.items.single().id)
        gate.complete(Unit); runCurrent()
        holder.retry(); runCurrent()
        assertEquals(3, reads); assertEquals("New", holder.state.value.items.single().id)
    }
    @Test fun failedRefreshKeepsGoodCategoryNamesAndRetryAcceptsTheNewList() = runTest {
        var failed = false
        val holder = MovieCategoriesStateHolder(MovieCategoriesSource { if (failed) error("private endpoint"); listOf(category("Drama")) }, StandardTestDispatcher(testScheduler))
        val first = backgroundScope.launch { holder.activate(1) }; runCurrent(); first.cancelAndJoin()
        failed = true; backgroundScope.launch { holder.activate(2) }; runCurrent()
        assertEquals("Drama", holder.state.value.items.single().id)
        assertEquals("Couldn't load categories. Try again.", holder.state.value.error)
        failed = false; holder.retry(); runCurrent(); assertNull(holder.state.value.error)
    }
    @Test fun initialErrorCanRetryToAnEmptyLibraryWithoutInventingACategory() = runTest {
        var failed = true
        val holder = MovieCategoriesStateHolder(MovieCategoriesSource { if (failed) error("private"); emptyList() }, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        assertFalse(holder.state.value.initialized); assertFalse(holder.state.value.loading); assertNotNull(holder.state.value.error)
        failed = false; holder.retry(); runCurrent()
        assertTrue(holder.state.value.initialized); assertNull(holder.selectedName); assertTrue(holder.state.value.items.isEmpty())
    }
    @Test fun exactCategoryKeysDoNotCollideAndOnlyOneCategoryWindowIsRetained() {
        val names = mutableListOf<String>()
        val browser = MovieCategoryBrowser(MovieCategoriesStateHolder(MovieCategoriesSource { emptyList() })) { name ->
            names += name; MovieGridStateHolder(MovieGridSource { _, _ -> MovieGridWindow(0, 0, 0, emptyList()) }, allowEmptyRefresh = true)
        }
        browser.openCategory("Kids & Family", "first-row"); val first = browser.grid
        browser.openCategory("Kids & Family", "first-row"); assertSame(first, browser.grid)
        browser.openCategory("Kids Family"); assertNotSame(first, browser.grid)
        assertEquals(listOf("Kids & Family", "Kids Family"), names)
        assertNull(browser.originRowId); assertEquals("Kids Family", browser.categories.selectedName)
        assertFalse(browser.categoriesOpen)
    }
    @Test fun successfulRefreshCanRemoveTheLastMovieFromACategory() = runTest {
        var empty = false
        val item = MovieItem("Drama", "Drama", 1, "mp4", "room://safe")
        val grid = MovieGridStateHolder(MovieGridSource { _, _ ->
            if (empty) MovieGridWindow(0, 0, 0, emptyList()) else MovieGridWindow(1, 0, 0, listOf(item))
        }, StandardTestDispatcher(testScheduler), allowEmptyRefresh = true)
        val first = backgroundScope.launch { grid.activate(1) }; runCurrent(); first.cancelAndJoin()
        empty = true; backgroundScope.launch { grid.activate(2) }; runCurrent()
        assertEquals(0, grid.state.value.total); assertTrue(grid.state.value.items.isEmpty()); assertNull(grid.state.value.error)
    }
}
