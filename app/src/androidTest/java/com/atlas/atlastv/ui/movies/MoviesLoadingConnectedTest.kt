package com.atlas.atlastv.ui.movies

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.MoviesLoadingCheckActivity
import org.junit.Rule
import org.junit.Test

class MoviesLoadingConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MoviesLoadingCheckActivity>()

    @Test fun watchlistCanBeAddedAndRemovedFromMovieDetails() {
        waitFor("movies_loading")
        compose.runOnIdle { compose.activity.complete() }
        waitFor("movies_row_page")
        press("movies_search_open", Key.DirectionDown)
        press("focused_poster_card", Key.Enter)
        waitFor("movie_detail_play")
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("+ Watchlist").performClick()
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("− Watchlist").performClick()
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("+ Watchlist")
        compose.runOnIdle { org.junit.Assert.assertTrue((compose.activity.holder.state as MoviesCatalogState.Ready).watchlistStreamIds.isEmpty()) }
    }

    @Test fun loadingAndRefreshKeepNavigationAndCardFocus() {
        waitFor("movies_loading")
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        waitFor("movies_row_page")
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
        press("movies_search_open", Key.DirectionDown)
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        press("focused_poster_card", Key.DirectionRight)
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("movies_refreshing")
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("movies_refreshing")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("movies_refresh_retry")
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        press("focused_poster_card", Key.DirectionUp)
        compose.onNodeWithTag("movies_refresh_retry").assertIsFocused()
        press("movies_refresh_retry", Key.Enter)
        waitFor("movies_refreshing")
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
    }

    @Test fun backWorksDuringLoadAndCancelsIt() {
        waitFor("movies_loading")
        compose.onNodeWithTag("movies_back").performClick()
        waitFor("movies_check_destination")
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() == 1 }
    }

    @Test fun firstFailureHasRemoteRetryWithoutLosingFocus() {
        waitFor("movies_loading")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("movies_retry")
        press("movies_search_open", Key.DirectionDown)
        compose.onNodeWithTag("movies_retry").assertIsFocused()
        press("movies_retry", Key.Enter)
        waitFor("movies_loading")
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        waitFor("movies_row_page")
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
    }

    @Test fun openingSearchDuringLoadingWaitsForAReadyField() {
        waitFor("movies_loading")
        press("movies_search_open", Key.Enter)
        compose.runOnIdle { compose.activity.complete() }
        waitFor("movies_search_field")
        compose.onNodeWithTag("movies_search_field").assertIsFocused()
        compose.onNodeWithTag("movies_search_field").performTextInput("saved")
        waitFor("movies_refreshing")
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("movies_search_field").assertIsFocused()
        compose.onNodeWithTag("movies_clear_search").performClick()
        compose.onNodeWithTag("movies_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
    }

    private fun waitFor(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun refreshingDetailsCanRetryWithoutRequestingADetachedBrowseControl() {
        waitFor("movies_loading")
        compose.runOnIdle { compose.activity.complete() }
        waitFor("movies_row_page")
        press("movies_search_open", Key.DirectionDown)
        press("focused_poster_card", Key.Enter)
        waitFor("movie_detail_play")
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("movies_refreshing")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("movies_refresh_retry")
        compose.onNodeWithTag("movies_refresh_retry").performClick()
        waitFor("movies_refreshing")
        compose.onNodeWithTag("movie_detail_play").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("movie_detail_play").assertIsFocused()
    }

    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
}
