package com.atlas.atlastv.ui.mylist

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.MyListCheckActivity
import com.atlas.atlastv.ui.series.SeriesCatalogState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MyListConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MyListCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun ready() { focused("my_list_tile_0") }
    private fun filter(name: String) { compose.onNodeWithTag("my_list_filter_$name").performClick(); focused("my_list_tile_0") }
    private fun detail(index: Int, title: String, show: Boolean = false) {
        compose.onNodeWithTag("my_list_grid").performScrollToIndex(index)
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("my_list_tile_$index") and hasContentDescription(title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("my_list_tile_$index").performClick()
        val tag = if (show) "series_detail_title" else "movie_detail_title"
        waitFor(tag); compose.onNodeWithTag(tag).assertTextEquals(title)
    }
    private fun settled() { compose.waitUntil(8_000) { !compose.activity.movies.watchlistSaving && !compose.activity.shows.watchlistSaving } }
    @Test fun mixedTypesAndRemotePagingReachFinalTileWithoutAdministrativeControls() {
        ready(); compose.onNodeWithTag("my_list_count").assertDoesNotExist(); assertEquals(1026, compose.activity.browser.grid.state.value.total)
        compose.onNodeWithTag("my_list_first").assertDoesNotExist(); compose.onNodeWithTag("my_list_last").assertDoesNotExist(); compose.onNodeWithTag("my_list_position").assertDoesNotExist()
        compose.onNodeWithTag("my_list_filter_all").assertIsSelected()
        native(KeyEvent.KEYCODE_PAGE_DOWN); focused("my_list_tile_40")
        detail(1025, "Saved Show 513", true); compose.onNodeWithTag("series_back").performClick(); focused("my_list_tile_1025")
        assertTrue(compose.activity.browser.grid.state.value.items.size <= 200)
    }
    @Test fun filtersReachAllTitlesKeepSelectedFilterAndDoNotMixContentTypes() {
        ready(); filter("movies"); compose.onNodeWithTag("my_list_count").assertDoesNotExist(); assertEquals(513, compose.activity.browser.grid.state.value.total)
        compose.onNodeWithTag("my_list_filter_movies").assertIsSelected()
        detail(512, "Saved Movie 513"); compose.onNodeWithTag("movie_detail_back").performClick(); focused("my_list_tile_512")
        compose.onNodeWithTag("my_list_filter_movies").performClick(); focused("my_list_tile_512")
        filter("shows"); compose.onNodeWithTag("my_list_filter_shows").assertIsSelected()
        detail(512, "Saved Show 513", true); compose.onNodeWithTag("series_back").performClick(); focused("my_list_tile_512")
        filter("all"); compose.onNodeWithTag("my_list_count").assertDoesNotExist(); assertEquals(1026, compose.activity.browser.grid.state.value.total)
    }
    @Test fun sameIdMovieRemovalLeavesShowAndShowRemovalLeavesOtherMovies() {
        ready(); detail(0, "Saved Movie 1"); compose.onNodeWithTag("movie_detail_watchlist").performClick(); settled()
        assertFalse(1 in compose.activity.movieIds); assertTrue(1 in compose.activity.showIds)
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("my_list_tile_0")
        detail(0, "Saved Show 1", true); compose.onNodeWithTag("series_detail_watchlist").performClick(); settled()
        assertFalse(1 in compose.activity.showIds); assertTrue(2 in compose.activity.movieIds)
        compose.onNodeWithTag("series_back").performClick(); focused("my_list_tile_0")
        compose.onNodeWithTag("my_list_count").assertDoesNotExist(); assertEquals(1024, compose.activity.browser.grid.state.value.total)
        compose.onNodeWithTag("my_list_tile_0").assert(hasContentDescription("Saved Movie 2"))
    }
    @Test fun deepMoviePlaybackReturnsToDetailsThenExactMixedTile() {
        ready(); detail(800, "Saved Movie 401"); compose.onNodeWithTag("movie_detail_play").performClick(); waitFor("my_list_playback_back")
        compose.onNodeWithTag("my_list_playback_back").performClick(); waitFor("movie_detail_back")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Saved Movie 401")
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("my_list_tile_800")
    }
    @Test fun deepShowKeepsFinalSeasonEpisodePlaybackAndTypedTile() {
        ready(); detail(801, "Saved Show 401", true); waitFor("series_episode_list")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
        repeat(2) { native(KeyEvent.KEYCODE_DPAD_DOWN) }; focused("series_season_3")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); repeat(3) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
        val before = compose.activity.shows.state as SeriesCatalogState.Ready
        assertEquals(3, before.selectedSeasonNumber); assertEquals(40134, before.selectedEpisodeId)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("my_list_playback_back")
        compose.onNodeWithTag("my_list_playback_back").performClick(); waitFor("series_episode_list")
        val after = compose.activity.shows.state as SeriesCatalogState.Ready
        assertEquals(3, after.selectedSeasonNumber); assertEquals(40134, after.selectedEpisodeId)
        compose.onNodeWithTag("series_back").performClick(); focused("my_list_tile_801")
    }
    @Test fun failedSaveCanBeRetriedFromMixedGridAndRepeatedActivationIsBlocked() {
        ready(); detail(0, "Saved Movie 1")
        compose.runOnIdle { compose.activity.writesReleased = false; compose.activity.writesFail = true }
        compose.onNodeWithTag("movie_detail_watchlist").performClick()
        compose.waitUntil(8_000) { compose.activity.movies.watchlistSaving }
        repeat(3) { compose.onNodeWithTag("movie_detail_watchlist").performClick() }; assertEquals(1, compose.activity.writes.get())
        compose.runOnIdle { compose.activity.writesReleased = true }; waitFor("movies_watchlist_save_retry")
        native(KeyEvent.KEYCODE_BACK); focused("my_list_tile_0"); waitFor("my_list_save_retry")
        compose.runOnIdle { compose.activity.writesFail = false }
        native(KeyEvent.KEYCODE_DPAD_UP); focused("my_list_back"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("my_list_save_retry")
        native(KeyEvent.KEYCODE_DPAD_CENTER); settled()
        compose.waitUntil(8_000) { compose.activity.browser.grid.state.value.total == 1025 }
        assertFalse(1 in compose.activity.movieIds); assertTrue(1 in compose.activity.showIds)
        focused("my_list_back"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("my_list_tile_0")
        compose.onNodeWithTag("my_list_tile_0").assert(hasContentDescription("Saved Show 1"))
    }
    @Test fun pendingWriteSurvivesLeavingMyListAndShowsAcknowledgedMembershipOnReturn() {
        ready(); detail(1, "Saved Show 1", true); compose.runOnIdle { compose.activity.writesReleased = false }
        compose.onNodeWithTag("series_detail_watchlist").performClick(); compose.waitUntil(8_000) { compose.activity.writes.get() == 1 }
        native(KeyEvent.KEYCODE_BACK); waitFor("my_list_screen"); native(KeyEvent.KEYCODE_BACK); waitFor("home_screen")
        compose.runOnIdle { compose.activity.writesReleased = true }; settled()
        compose.onNodeWithTag("top_nav_item_my_list").performClick(); focused("my_list_tile_1")
        compose.onNodeWithTag("my_list_count").assertDoesNotExist(); assertEquals(1025, compose.activity.browser.grid.state.value.total)
        assertFalse(1 in compose.activity.showIds); assertTrue(1 in compose.activity.movieIds)
    }
    @Test fun failedShowRemovalRemainsRetryableAfterSwitchingToMoviesFilter() {
        ready(); compose.runOnIdle { compose.activity.movieIds = setOf(1); compose.activity.showIds = setOf(1); compose.activity.refresh() }
        filter("shows"); detail(0, "Saved Show 1", true)
        compose.runOnIdle { compose.activity.writesFail = true }; compose.onNodeWithTag("series_detail_watchlist").performClick()
        waitFor("series_watchlist_save_retry"); native(KeyEvent.KEYCODE_BACK); focused("my_list_tile_0")
        filter("movies"); waitFor("my_list_save_retry")
        compose.runOnIdle { compose.activity.writesFail = false }
        native(KeyEvent.KEYCODE_DPAD_UP); focused("my_list_back"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("my_list_save_retry")
        native(KeyEvent.KEYCODE_DPAD_CENTER); settled()
        assertTrue(1 in compose.activity.movieIds); assertTrue(compose.activity.showIds.isEmpty())
        compose.onNodeWithTag("my_list_count").assertDoesNotExist(); assertEquals(1, compose.activity.browser.grid.state.value.total)
        compose.onNodeWithTag("my_list_filter_shows").performClick(); waitFor("my_list_empty"); focused("my_list_back")
    }
    @Test fun unavailableTitlesOfEitherTypeRemainRemovableWithoutPlayOrMetadata() {
        ready(); compose.runOnIdle { compose.activity.orphans = setOf(MyListKey(MyListType.Movie, 1), MyListKey(MyListType.Show, 1)); compose.activity.refresh() }
        compose.waitUntil(8_000) { !compose.activity.browser.grid.state.value.loading }
        detail(0, "Saved Movie 1"); compose.onNodeWithTag("movie_detail_play").assertDoesNotExist()
        compose.onNodeWithTag("movie_detail_watchlist").performClick(); settled(); compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("Removed")
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("my_list_tile_0")
        detail(0, "Saved Show 1", true); compose.onNodeWithTag("series_detail_play_episode").assertDoesNotExist(); compose.onNodeWithTag("series_episode_list").assertDoesNotExist()
        assertEquals(0, compose.activity.metadata.get())
        compose.onNodeWithTag("series_detail_watchlist").performClick(); settled(); compose.onNodeWithTag("series_detail_watchlist").assertTextEquals("Removed")
        compose.onNodeWithTag("series_back").performClick(); focused("my_list_tile_0")
    }
    @Test fun reorderRefreshFollowsShowIdentityRatherThanItsMovieIdCollision() {
        ready(); detail(601, "Saved Show 301", true); compose.onNodeWithTag("series_back").performClick(); focused("my_list_tile_601")
        compose.runOnIdle { compose.activity.reversed = true; compose.activity.refresh() }; focused("my_list_tile_424")
        compose.onNodeWithTag("my_list_tile_424").assert(hasContentDescription("Saved Show 301"))
        compose.runOnIdle { compose.activity.showIds -= 301; compose.activity.refresh() }
        compose.waitUntil(8_000) { compose.activity.browser.grid.state.value.total == 1025 }; focused("my_list_tile_424")
        compose.onNodeWithTag("my_list_tile_424").assert(hasContentDescription("Saved Movie 301"))
    }
    @Test fun pendingLoadBackCancelsAndReadFailureHasRemoteRetry() {
        ready(); native(KeyEvent.KEYCODE_BACK); waitFor("home_screen")
        compose.runOnIdle { compose.activity.gridsReleased = false; compose.activity.refresh() }
        compose.onNodeWithTag("top_nav_item_my_list").performClick(); waitFor("my_list_screen")
        native(KeyEvent.KEYCODE_BACK); waitFor("home_screen"); compose.waitUntil(8_000) { compose.activity.canceled.get() > 0 }
        compose.runOnIdle { compose.activity.gridsReleased = true; compose.activity.gridsFail = true }
        compose.onNodeWithTag("top_nav_item_my_list").performClick(); waitFor("my_list_error")
        compose.onNodeWithTag("my_list_filter_movies").performClick(); waitFor("my_list_error"); focused("my_list_back")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("my_list_retry")
        compose.runOnIdle { compose.activity.gridsFail = false }
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("my_list_tile_0")
    }
    @Test fun emptyFiltersAndFinalRemovalKeepUsableBackAndHomeNavReturn() {
        ready(); compose.runOnIdle { compose.activity.movieIds = emptySet(); compose.activity.showIds = setOf(1); compose.activity.refresh() }
        compose.onNodeWithTag("my_list_filter_movies").performClick(); waitFor("my_list_empty"); focused("my_list_back")
        compose.onNodeWithTag("my_list_empty").assertTextEquals("No saved movies yet. Add movies from their details.")
        filter("shows"); detail(0, "Saved Show 1", true); compose.onNodeWithTag("series_detail_watchlist").performClick(); settled()
        compose.onNodeWithTag("series_back").performClick(); waitFor("my_list_empty"); focused("my_list_back")
        filterEmptyAll(); native(KeyEvent.KEYCODE_BACK); focused("top_nav_item_my_list")
        compose.onNodeWithTag("top_nav_item_settings").assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("top_nav_item_live_tv")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("top_nav_item_settings")
    }
    private fun filterEmptyAll() { compose.onNodeWithTag("my_list_filter_all").performClick(); waitFor("my_list_empty"); focused("my_list_back") }
    @Test fun deliberateBackFocusIsKeptDuringMembershipRefresh() {
        ready(); native(KeyEvent.KEYCODE_DPAD_UP); focused("my_list_back")
        compose.runOnIdle { compose.activity.movieIds -= 1; compose.activity.refresh() }
        compose.waitUntil(8_000) { compose.activity.browser.grid.state.value.total == 1025 && !compose.activity.browser.grid.state.value.loading }
        focused("my_list_back")
    }
}
