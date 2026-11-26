package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.browse.openGridOptions

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SeriesWatchlistCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SeriesWatchlistGridConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SeriesWatchlistCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun open() { waitFor("series_watchlist_open"); compose.onNodeWithTag("series_watchlist_open").performClick(); waitFor("show_watchlist_screen") }
    private fun title(id: Int) { compose.onNodeWithTag("series_detail_title").assertTextEquals("Saved Show $id") }
    private fun detail(index: Int = 0) { focused("show_watchlist_tile_$index"); native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_detail_watchlist") }
    private fun toggle() { compose.onNodeWithTag("series_detail_watchlist").performClick(); compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving } }

    @Test fun remoteDeepLastSavedTitleAndPlaybackReturnPreserveTheTileAndEntry() {
        waitFor("series_watchlist_open"); focused("series_search_open")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_watchlist_open"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_watchlist_tile_0")
        native(KeyEvent.KEYCODE_PAGE_DOWN); focused("show_watchlist_tile_40")
        compose.openGridOptions("show_watchlist"); compose.onNodeWithTag("show_watchlist_last").performClick(); focused("show_watchlist_tile_512")
        detail(512); title(513); compose.onNodeWithTag("series_detail_play_episode").performClick(); waitFor("watchlist_check_playback_back")
        compose.onNodeWithTag("watchlist_check_playback_back").performClick(); waitFor("series_back"); title(513)
        compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_512")
        assertTrue(compose.activity.browser.grid.state.value.items.size <= 200)
        native(KeyEvent.KEYCODE_BACK); focused("series_watchlist_open")
    }
    @Test fun watchlistRowSeeAllIsRemoteReachableAndReturnsToItsHeader() {
        waitFor("series_row_page"); focused("series_search_open"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("show_category_see_all_series_watchlist")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("show_watchlist_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("show_category_see_all_series_watchlist")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
    }
    @Test fun deepOriginatingWatchlistRowIsVisibleAndFocusedAfterReturn() {
        waitFor("series_row_page"); compose.runOnIdle { compose.activity.watchlistAtEnd = true; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("series_row_page").performScrollToIndex(8)
        compose.onNodeWithTag("show_category_see_all_series_watchlist").performClick(); focused("show_watchlist_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("show_category_see_all_series_watchlist")
        compose.onNodeWithTag("show_category_see_all_series_watchlist").assertIsDisplayed()
    }
    @Test fun removeAndReaddSavedShowUpdateMembershipAndTheFullGrid() {
        open(); detail(); title(1); toggle()
        compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("+ Watchlist")
        assertFalse(1 in compose.activity.ids)
        toggle(); compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("− Watchlist")
        assertTrue(1 in compose.activity.ids)
        toggle(); compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_0")
        compose.onNodeWithTag("show_watchlist_count").assertDoesNotExist(); assertEquals(512, compose.activity.browser.grid.state.value.total)
        compose.onNodeWithTag("show_watchlist_tile_0").assert(hasContentDescription("Saved Show 2"))
    }
    @Test fun removingFinalTileRestoresThePreviousTileAndRemovingAllLeavesUsableBack() {
        open(); focused("show_watchlist_tile_0"); compose.openGridOptions("show_watchlist"); compose.onNodeWithTag("show_watchlist_last").performClick(); focused("show_watchlist_tile_512")
        detail(512); toggle(); compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_511")
        compose.onNodeWithTag("show_watchlist_tile_511").assert(hasContentDescription("Saved Show 512"))
        compose.runOnIdle { compose.activity.ids = emptySet(); compose.activity.holder.invalidateCachedCatalog() }
        waitFor("show_watchlist_empty"); focused("show_watchlist_back")
        compose.onNodeWithTag("show_watchlist_empty").assertTextEquals("Your watchlist is empty. Add shows from their details.")
        native(KeyEvent.KEYCODE_BACK); focused("series_watchlist_open")
    }
    @Test fun failedRemovalHasSafeRetryAndRepeatedActivationDuringSavingDoesNotToggleTwice() {
        open(); detail(); compose.runOnIdle { compose.activity.writesReleased = false; compose.activity.writesFail = true }
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_detail_watchlist"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(8_000) { compose.activity.holder.watchlistSaving }
        repeat(3) { native(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals(1, compose.activity.writes.get()); assertTrue(1 in compose.activity.ids)
        compose.runOnIdle { compose.activity.writesReleased = true }; waitFor("series_watchlist_save_retry")
        assertTrue(compose.activity.holder.canRetryWatchlistSave)
        compose.onNodeWithText("Couldn't save your change. Please try again.").assertIsDisplayed()
        compose.runOnIdle { compose.activity.writesFail = false }
        compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("Retry save").performClick()
        compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving && !compose.activity.holder.canRetryWatchlistSave }
        assertFalse(1 in compose.activity.ids); assertEquals(2, compose.activity.writes.get())
        compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_0")
        compose.onNodeWithTag("show_watchlist_tile_0").assert(hasContentDescription("Saved Show 2"))
    }
    @Test fun removalWriteOutlivesLeavingTheSectionAndIsVisibleOnReturn() {
        open(); detail(); compose.runOnIdle { compose.activity.writesReleased = false }
        compose.onNodeWithTag("series_detail_watchlist").performClick()
        compose.waitUntil(8_000) { compose.activity.writes.get() == 1 }
        native(KeyEvent.KEYCODE_BACK); waitFor("show_watchlist_screen"); native(KeyEvent.KEYCODE_BACK)
        focused("series_watchlist_open"); compose.onNodeWithTag("series_back").performClick(); waitFor("watchlist_check_home_back")
        compose.runOnIdle { compose.activity.writesReleased = true }
        compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving }
        compose.onNodeWithTag("watchlist_check_home_back").performClick(); open(); focused("show_watchlist_tile_0")
        compose.onNodeWithTag("show_watchlist_count").assertDoesNotExist(); assertEquals(512, compose.activity.browser.grid.state.value.total)
        assertFalse(1 in compose.activity.ids)
    }
    @Test fun pendingGridBackCancelsAndFailedWindowHasRemoteRetry() {
        compose.runOnIdle { compose.activity.gridsReleased = false }; open(); waitFor("show_watchlist_loading")
        focused("show_watchlist_back"); native(KeyEvent.KEYCODE_BACK); focused("series_watchlist_open")
        compose.waitUntil(8_000) { compose.activity.canceled.get() == 1 }
        compose.runOnIdle { compose.activity.gridsReleased = true; compose.activity.gridsFail = true }
        open(); waitFor("show_watchlist_error"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("show_watchlist_retry")
        compose.runOnIdle { compose.activity.gridsFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_watchlist_tile_0")
    }
    @Test fun disappearedCatalogTitleRemainsRemovableWithoutPlayOrMetadataRequests() {
        compose.runOnIdle { compose.activity.orphanId = 513 }; open(); focused("show_watchlist_tile_0")
        compose.openGridOptions("show_watchlist"); compose.onNodeWithTag("show_watchlist_last").performClick(); focused("show_watchlist_tile_512"); detail(512)
        title(513); compose.onNodeWithTag("series_detail_play_episode").assertDoesNotExist()
        focused("series_detail_watchlist"); assertEquals(0, compose.activity.metadata.get())
        compose.onNodeWithTag("series_detail_synopsis").assertTextContains("No longer in your library", substring = true)
        toggle(); compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("Removed")
        focused("series_back")
        native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_watchlist_tile_511")
        assertFalse(513 in compose.activity.ids)
    }
    @Test fun refreshedOrderFollowsSelectedSavedIdentityAndDeletionRestoresAVisibleNeighbor() {
        open(); focused("show_watchlist_tile_0"); compose.onNodeWithTag("show_watchlist_grid").performScrollToIndex(300)
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("show_watchlist_tile_300") and hasContentDescription("Saved Show 301")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("show_watchlist_tile_300").performClick(); waitFor("series_detail_title"); title(301)
        compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_300")
        compose.runOnIdle { compose.activity.reverseOrder = true; compose.activity.holder.invalidateCachedCatalog() }
        focused("show_watchlist_tile_212")
        compose.onNodeWithTag("show_watchlist_tile_212").assert(hasContentDescription("Saved Show 301"))
        compose.runOnIdle { compose.activity.ids = compose.activity.ids - 301; compose.activity.holder.invalidateCachedCatalog() }
        focused("show_watchlist_tile_212")
        compose.onNodeWithTag("show_watchlist_tile_212").assert(hasContentDescription("Saved Show 300"))
    }
    @Test fun initiallyEmptyWatchlistRemainsAccessibleFromTheTopAction() {
        waitFor("series_watchlist_open")
        compose.runOnIdle { compose.activity.ids = emptySet(); compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        open(); waitFor("show_watchlist_empty"); focused("show_watchlist_back")
        native(KeyEvent.KEYCODE_BACK); focused("series_watchlist_open")
    }
    @Test fun removingAllFromADeepWatchlistOriginReturnsToAVisibleRemainingRow() {
        waitFor("series_row_page"); compose.runOnIdle { compose.activity.watchlistAtEnd = true; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("series_row_page").performScrollToIndex(8)
        compose.onNodeWithTag("show_category_see_all_series_watchlist").performClick(); focused("show_watchlist_tile_0")
        compose.runOnIdle { compose.activity.ids = emptySet(); compose.activity.holder.invalidateCachedCatalog() }
        waitFor("show_watchlist_empty"); native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
        compose.onNodeWithTag("focused_poster_card").assertIsDisplayed()
    }
    @Test fun deliberateBackFocusIsPreservedDuringWatchlistRefresh() {
        open(); focused("show_watchlist_tile_0"); native(KeyEvent.KEYCODE_DPAD_UP); focused("show_watchlist_back")
        compose.runOnIdle { compose.activity.ids = compose.activity.ids - 1; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { compose.activity.browser.grid.state.value.total == 512 && !compose.activity.browser.grid.state.value.loading }
        focused("show_watchlist_back")
    }
    @Test fun failedRemovalCanBeRetriedFromTheFullGridUsingOnlyTheRemote() {
        open(); detail(); compose.runOnIdle { compose.activity.writesFail = true }; toggle()
        waitFor("series_watchlist_save_retry"); native(KeyEvent.KEYCODE_BACK)
        focused("show_watchlist_tile_0"); waitFor("show_watchlist_save_retry")
        native(KeyEvent.KEYCODE_DPAD_UP); focused("show_watchlist_back")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("show_watchlist_save_retry")
        compose.runOnIdle { compose.activity.writesFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(8_000) { !compose.activity.holder.watchlistSaving && !compose.activity.holder.canRetryWatchlistSave }
        focused("show_watchlist_back"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("show_watchlist_tile_0")
        compose.onNodeWithTag("show_watchlist_tile_0").assert(hasContentDescription("Saved Show 2"))
    }
    @Test fun selectedShowDisappearingOnRefreshKeepsRemovalReachableAndHidesPlay() {
        open(); detail(); compose.waitUntil(8_000) { compose.activity.metadata.get() == 1 }
        compose.runOnIdle { compose.activity.orphanId = 1; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("series_detail_play_episode").fetchSemanticsNodes().isEmpty() }
        focused("series_detail_watchlist"); title(1); toggle()
        compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("Removed")
        assertFalse(1 in compose.activity.ids)
        focused("series_back")
        native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_watchlist_tile_0")
        compose.onNodeWithTag("show_watchlist_tile_0").assert(hasContentDescription("Saved Show 2"))
    }
    @Test fun deepSavedShowKeepsEverySeasonAndFinalEpisodeThroughPlaybackAndMembershipRefresh() {
        open(); focused("show_watchlist_tile_0"); assertEquals(0, compose.activity.metadata.get())
        compose.openGridOptions("show_watchlist"); compose.onNodeWithTag("show_watchlist_last").performClick(); detail(512); waitFor("series_episode_list")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_2")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_3")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_selected_episode")
        repeat(3) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
        val before = compose.activity.holder.state as SeriesCatalogState.Ready
        assertEquals(3, before.selectedSeasonNumber); assertEquals(51334, before.selectedEpisodeId)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("watchlist_check_playback_back")
        compose.onNodeWithTag("watchlist_check_playback_back").performClick(); waitFor("series_episode_list")
        title(513); toggle()
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        val after = compose.activity.holder.state as SeriesCatalogState.Ready
        assertEquals(3, after.selectedSeasonNumber); assertEquals(51334, after.selectedEpisodeId)
        assertEquals(1, compose.activity.metadata.get())
        compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_511")
        native(KeyEvent.KEYCODE_BACK); focused("series_watchlist_open")
    }
    @Test fun pendingSavedShowEpisodesCancelOnBackAndFailureRetriesWithoutLosingTheTile() {
        compose.runOnIdle { compose.activity.episodesReleased = false }
        open(); detail(); compose.waitUntil(8_000) { compose.activity.metadata.get() == 1 }
        compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_0")
        compose.waitUntil(8_000) { compose.activity.canceledEpisodes.get() == 1 }
        compose.runOnIdle { compose.activity.episodesReleased = true; compose.activity.episodesFail = true }
        detail(); waitFor("series_episode_retry")
        compose.runOnIdle { compose.activity.episodesFail = false }
        compose.onNodeWithTag("series_episode_retry").performClick(); waitFor("series_episode_list")
        assertEquals(3, compose.activity.metadata.get())
        compose.onNodeWithTag("series_back").performClick(); focused("show_watchlist_tile_0")
    }
    @Test fun failedFinalOrphanRemovalKeepsRemoteRetryAndEndsAtUsableEmptyBack() {
        waitFor("series_watchlist_open")
        compose.runOnIdle { compose.activity.ids = setOf(513); compose.activity.orphanId = 513; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { !compose.activity.holder.isRefreshing }
        open(); detail(); focused("series_detail_watchlist")
        compose.runOnIdle { compose.activity.writesFail = true }
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_watchlist_save_retry")
        assertTrue(513 in compose.activity.ids)
        compose.onNodeWithTag("series_detail_play_episode").assertDoesNotExist()
        native(KeyEvent.KEYCODE_BACK); focused("show_watchlist_tile_0")
        native(KeyEvent.KEYCODE_DPAD_UP); focused("show_watchlist_back")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("show_watchlist_save_retry")
        compose.runOnIdle { compose.activity.writesFail = false }
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("show_watchlist_empty"); focused("show_watchlist_back")
        assertEquals(emptySet<Int>(), compose.activity.ids)
        native(KeyEvent.KEYCODE_BACK); focused("series_watchlist_open")
    }
    @Test fun deepSavedShowDisappearingOnRefreshStopsEpisodesAndRemainsRemovable() {
        open(); focused("show_watchlist_tile_0")
        compose.openGridOptions("show_watchlist"); compose.onNodeWithTag("show_watchlist_last").performClick(); detail(512); waitFor("series_episode_list")
        assertEquals(1, compose.activity.metadata.get())
        compose.runOnIdle { compose.activity.orphanId = 513; compose.activity.holder.invalidateCachedCatalog() }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("series_detail_play_episode").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("series_episode_list").assertDoesNotExist()
        title(513); focused("series_detail_watchlist"); toggle()
        compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("Removed")
        focused("series_back"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("show_watchlist_tile_511")
        assertFalse(513 in compose.activity.ids); assertEquals(1, compose.activity.metadata.get())
    }
}
