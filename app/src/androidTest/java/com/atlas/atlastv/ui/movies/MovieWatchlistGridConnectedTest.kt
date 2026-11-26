package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.openGridOptions

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.MovieWatchlistCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MovieWatchlistGridConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MovieWatchlistCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun open() { waitFor("movies_watchlist_open"); compose.onNodeWithTag("movies_watchlist_open").performClick(); waitFor("movie_watchlist_screen") }
    private fun title(id: Int) { compose.onNodeWithTag("movie_detail_title").assertTextEquals("Saved Movie $id") }
    private fun detail(index: Int = 0) { focused("movie_watchlist_tile_$index"); native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_detail_watchlist") }
    private fun toggle() { compose.onNodeWithTag("movie_detail_watchlist").performClick(); compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving } }

    @Test fun remoteDeepLastSavedTitleAndPlaybackReturnPreserveTheTileAndEntry() {
        waitFor("movies_watchlist_open"); focused("movies_search_open")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movies_watchlist_open"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("movie_watchlist_tile_0")
        native(KeyEvent.KEYCODE_PAGE_DOWN); focused("movie_watchlist_tile_40")
        compose.openGridOptions("movie_watchlist"); compose.onNodeWithTag("movie_watchlist_last").performClick(); focused("movie_watchlist_tile_512")
        detail(512); title(513); compose.onNodeWithTag("movie_detail_play").performClick(); waitFor("watchlist_check_playback_back")
        compose.onNodeWithTag("watchlist_check_playback_back").performClick(); waitFor("movie_detail_back"); title(513)
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_watchlist_tile_512")
        assertTrue(compose.activity.browser.grid.state.value.items.size <= 200)
        native(KeyEvent.KEYCODE_BACK); focused("movies_watchlist_open")
    }
    @Test fun watchlistRowSeeAllIsRemoteReachableAndReturnsToItsHeader() {
        waitFor("movies_row_page"); focused("movies_search_open"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("movie_category_see_all_movies_watchlist")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_watchlist_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_see_all_movies_watchlist")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
    }
    @Test fun deepOriginatingWatchlistRowIsVisibleAndFocusedAfterReturn() {
        waitFor("movies_row_page"); compose.runOnIdle { compose.activity.watchlistAtEnd = true; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("movies_row_page").performScrollToIndex(8)
        compose.onNodeWithTag("movie_category_see_all_movies_watchlist").performClick(); focused("movie_watchlist_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_see_all_movies_watchlist")
        compose.onNodeWithTag("movie_category_see_all_movies_watchlist").assertIsDisplayed()
    }
    @Test fun removeAndReaddSavedMovieUpdateMembershipAndTheFullGrid() {
        open(); detail(); title(1); toggle()
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("+ Watchlist")
        assertFalse(1 in compose.activity.ids)
        toggle(); compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("− Watchlist")
        assertTrue(1 in compose.activity.ids)
        toggle(); compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_watchlist_tile_0")
        compose.onNodeWithTag("movie_watchlist_count").assertDoesNotExist(); assertEquals(512, compose.activity.browser.grid.state.value.total)
        compose.onNodeWithTag("movie_watchlist_tile_0").assert(hasContentDescription("Saved Movie 2"))
    }
    @Test fun removingFinalTileRestoresThePreviousTileAndRemovingAllLeavesUsableBack() {
        open(); focused("movie_watchlist_tile_0"); compose.openGridOptions("movie_watchlist"); compose.onNodeWithTag("movie_watchlist_last").performClick(); focused("movie_watchlist_tile_512")
        detail(512); toggle(); compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_watchlist_tile_511")
        compose.onNodeWithTag("movie_watchlist_tile_511").assert(hasContentDescription("Saved Movie 512"))
        compose.runOnIdle { compose.activity.ids = emptySet(); compose.activity.holder.invalidateCachedCatalog() }
        waitFor("movie_watchlist_empty"); focused("movie_watchlist_back")
        compose.onNodeWithTag("movie_watchlist_empty").assertTextEquals("Your watchlist is empty. Add movies from their details.")
        native(KeyEvent.KEYCODE_BACK); focused("movies_watchlist_open")
    }
    @Test fun failedRemovalHasSafeRetryAndRepeatedActivationDuringSavingDoesNotToggleTwice() {
        open(); detail(); compose.runOnIdle { compose.activity.writesReleased = false; compose.activity.writesFail = true }
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_watchlist"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(8_000) { compose.activity.holder.watchlistSaving }
        repeat(3) { native(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals(1, compose.activity.writes.get()); assertTrue(1 in compose.activity.ids)
        compose.runOnIdle { compose.activity.writesReleased = true }; waitFor("movies_watchlist_save_retry")
        assertTrue(compose.activity.holder.canRetryWatchlistSave)
        compose.onNodeWithText("Couldn't save your change. Please try again.").assertIsDisplayed()
        compose.runOnIdle { compose.activity.writesFail = false }
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("Retry save").performClick()
        compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving && !compose.activity.holder.canRetryWatchlistSave }
        assertFalse(1 in compose.activity.ids); assertEquals(2, compose.activity.writes.get())
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_watchlist_tile_0")
        compose.onNodeWithTag("movie_watchlist_tile_0").assert(hasContentDescription("Saved Movie 2"))
    }
    @Test fun removalWriteOutlivesLeavingTheSectionAndIsVisibleOnReturn() {
        open(); detail(); compose.runOnIdle { compose.activity.writesReleased = false }
        compose.onNodeWithTag("movie_detail_watchlist").performClick()
        compose.waitUntil(8_000) { compose.activity.writes.get() == 1 }
        native(KeyEvent.KEYCODE_BACK); waitFor("movie_watchlist_screen"); native(KeyEvent.KEYCODE_BACK)
        focused("movies_watchlist_open"); compose.onNodeWithTag("movies_back").performClick(); waitFor("watchlist_check_home_back")
        compose.runOnIdle { compose.activity.writesReleased = true }
        compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving }
        compose.onNodeWithTag("watchlist_check_home_back").performClick(); open(); focused("movie_watchlist_tile_0")
        compose.onNodeWithTag("movie_watchlist_count").assertDoesNotExist(); assertEquals(512, compose.activity.browser.grid.state.value.total)
        assertFalse(1 in compose.activity.ids)
    }
    @Test fun pendingGridBackCancelsAndFailedWindowHasRemoteRetry() {
        compose.runOnIdle { compose.activity.gridsReleased = false }; open(); waitFor("movie_watchlist_loading")
        focused("movie_watchlist_back"); native(KeyEvent.KEYCODE_BACK); focused("movies_watchlist_open")
        compose.waitUntil(8_000) { compose.activity.canceled.get() == 1 }
        compose.runOnIdle { compose.activity.gridsReleased = true; compose.activity.gridsFail = true }
        open(); waitFor("movie_watchlist_error"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_watchlist_retry")
        compose.runOnIdle { compose.activity.gridsFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("movie_watchlist_tile_0")
    }
    @Test fun disappearedCatalogTitleRemainsRemovableWithoutPlayOrMetadataRequests() {
        compose.runOnIdle { compose.activity.orphanId = 513 }; open(); focused("movie_watchlist_tile_0")
        compose.openGridOptions("movie_watchlist"); compose.onNodeWithTag("movie_watchlist_last").performClick(); focused("movie_watchlist_tile_512"); detail(512)
        title(513); compose.onNodeWithTag("movie_detail_play").assertDoesNotExist()
        focused("movie_detail_watchlist"); assertEquals(0, compose.activity.metadata.get())
        compose.onNodeWithTag("movie_detail_synopsis").assertTextContains("No longer in your library", substring = true)
        toggle(); compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("Removed")
        focused("movie_detail_back"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_watchlist_tile_511")
        assertFalse(513 in compose.activity.ids)
    }
    @Test fun refreshedOrderFollowsSelectedSavedIdentityAndDeletionRestoresAVisibleNeighbor() {
        open(); focused("movie_watchlist_tile_0"); compose.onNodeWithTag("movie_watchlist_grid").performScrollToIndex(300)
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("movie_watchlist_tile_300") and hasContentDescription("Saved Movie 301")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("movie_watchlist_tile_300").performClick(); waitFor("movie_detail_title"); title(301)
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_watchlist_tile_300")
        compose.runOnIdle { compose.activity.reverseOrder = true; compose.activity.holder.invalidateCachedCatalog() }
        focused("movie_watchlist_tile_212")
        compose.onNodeWithTag("movie_watchlist_tile_212").assert(hasContentDescription("Saved Movie 301"))
        compose.runOnIdle { compose.activity.ids = compose.activity.ids - 301; compose.activity.holder.invalidateCachedCatalog() }
        focused("movie_watchlist_tile_212")
        compose.onNodeWithTag("movie_watchlist_tile_212").assert(hasContentDescription("Saved Movie 300"))
    }
    @Test fun initiallyEmptyWatchlistRemainsAccessibleFromTheTopAction() {
        waitFor("movies_watchlist_open")
        compose.runOnIdle { compose.activity.ids = emptySet(); compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        open(); waitFor("movie_watchlist_empty"); focused("movie_watchlist_back")
        native(KeyEvent.KEYCODE_BACK); focused("movies_watchlist_open")
    }
    @Test fun removingAllFromADeepWatchlistOriginReturnsToAVisibleRemainingRow() {
        waitFor("movies_row_page"); compose.runOnIdle { compose.activity.watchlistAtEnd = true; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("movies_row_page").performScrollToIndex(8)
        compose.onNodeWithTag("movie_category_see_all_movies_watchlist").performClick(); focused("movie_watchlist_tile_0")
        compose.runOnIdle { compose.activity.ids = emptySet(); compose.activity.holder.invalidateCachedCatalog() }
        waitFor("movie_watchlist_empty"); native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
        compose.onNodeWithTag("focused_poster_card").assertIsDisplayed()
    }
    @Test fun deliberateBackFocusIsPreservedDuringWatchlistRefresh() {
        open(); focused("movie_watchlist_tile_0"); native(KeyEvent.KEYCODE_DPAD_UP); focused("movie_watchlist_back")
        compose.runOnIdle { compose.activity.ids = compose.activity.ids - 1; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { compose.activity.browser.grid.state.value.total == 512 && !compose.activity.browser.grid.state.value.loading }
        focused("movie_watchlist_back")
    }
    @Test fun failedRemovalCanBeRetriedFromTheFullGridUsingOnlyTheRemote() {
        open(); detail(); compose.runOnIdle { compose.activity.writesFail = true }; toggle()
        waitFor("movies_watchlist_save_retry"); native(KeyEvent.KEYCODE_BACK)
        focused("movie_watchlist_tile_0"); waitFor("movie_watchlist_save_retry")
        native(KeyEvent.KEYCODE_DPAD_UP); focused("movie_watchlist_back")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_watchlist_save_retry")
        compose.runOnIdle { compose.activity.writesFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving && !compose.activity.holder.canRetryWatchlistSave }
        focused("movie_watchlist_back"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_watchlist_tile_0")
        compose.onNodeWithTag("movie_watchlist_tile_0").assert(hasContentDescription("Saved Movie 2"))
    }
    @Test fun selectedMovieDisappearingOnRefreshKeepsRemovalReachableAndHidesPlay() {
        open(); detail(); compose.waitUntil(8_000) { compose.activity.metadata.get() == 1 }
        compose.runOnIdle { compose.activity.orphanId = 1; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("movie_detail_play").fetchSemanticsNodes().isEmpty() }
        focused("movie_detail_watchlist"); title(1); toggle()
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("Removed")
        assertFalse(1 in compose.activity.ids)
        focused("movie_detail_back"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_watchlist_tile_0")
        compose.onNodeWithTag("movie_watchlist_tile_0").assert(hasContentDescription("Saved Movie 2"))
    }
    @Test fun deepSavedMovieDisappearingOnRefreshUsesFreshUnavailableSnapshotAndRemainsRemovable() {
        open(); focused("movie_watchlist_tile_0")
        compose.openGridOptions("movie_watchlist"); compose.onNodeWithTag("movie_watchlist_last").performClick(); detail(512)
        compose.waitUntil(8_000) { compose.activity.metadata.get() == 1 }
        compose.runOnIdle { compose.activity.orphanId = 513; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("movie_detail_play").fetchSemanticsNodes().isEmpty() }
        title(513); focused("movie_detail_watchlist"); toggle()
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("Removed")
        focused("movie_detail_back"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_watchlist_tile_511")
        assertFalse(513 in compose.activity.ids); assertEquals(1, compose.activity.metadata.get())
    }
}
