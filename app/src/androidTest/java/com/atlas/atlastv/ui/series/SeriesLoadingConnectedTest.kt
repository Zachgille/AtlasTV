package com.atlas.atlastv.ui.series

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.SeriesLoadingCheckActivity
import org.junit.Rule
import org.junit.Test

class SeriesLoadingConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SeriesLoadingCheckActivity>()

    @Test fun loadingAndRefreshKeepNavigationAndCardFocus() {
        waitFor("series_loading")
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        waitFor("series_row_page")
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        press("series_search_open", Key.DirectionDown)
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        press("focused_poster_card", Key.DirectionRight)
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("series_refreshing")
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("series_refreshing")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("series_refresh_retry")
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        press("focused_poster_card", Key.DirectionUp)
        compose.onNodeWithTag("series_refresh_retry").assertIsFocused()
        press("series_refresh_retry", Key.Enter)
        waitFor("series_refreshing")
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("series_search_open").assertIsFocused()
    }

    @Test fun backWorksDuringLoadAndCancelsIt() {
        waitFor("series_loading")
        compose.onNodeWithTag("series_back").performClick()
        waitFor("series_check_destination")
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() == 1 }
    }

    @Test fun firstFailureHasRemoteRetryWithoutLosingFocus() {
        waitFor("series_loading")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("series_retry")
        press("series_search_open", Key.DirectionDown)
        compose.onNodeWithTag("series_retry").assertIsFocused()
        press("series_retry", Key.Enter)
        waitFor("series_loading")
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        waitFor("series_row_page")
        compose.onNodeWithTag("series_search_open").assertIsFocused()
    }

    @Test fun openingSearchDuringLoadingWaitsForAReadyField() {
        waitFor("series_loading")
        press("series_search_open", Key.Enter)
        compose.runOnIdle { compose.activity.complete() }
        waitFor("series_search_field")
        compose.onNodeWithTag("series_search_field").assertIsFocused()
        compose.onNodeWithTag("series_search_field").performTextInput("saved")
        waitFor("series_refreshing")
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("series_search_field").assertIsFocused()
        compose.onNodeWithTag("series_clear_search").performClick()
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
    }

    @Test fun episodesStartOnlyInDetailsAndOfferRemoteRetryWithoutStealingFocus() {
        openDetails()
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 1 }
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        press("series_detail_play_episode", Key.DirectionDown)
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        press("series_detail_play_episode", Key.DirectionRight)
        compose.onNodeWithTag("series_detail_watchlist").assertIsFocused()
        compose.runOnIdle { compose.activity.failEpisodes() }
        waitFor("series_episode_retry")
        compose.onNodeWithTag("series_detail_watchlist").assertIsFocused()
        press("series_detail_watchlist", Key.DirectionLeft)
        compose.onNodeWithTag("series_episode_retry").assertIsFocused()
        press("series_episode_retry", Key.Enter)
        waitFor("series_detail_play_episode")
        compose.runOnIdle { compose.activity.completeEpisodes() }
        waitFor("series_episode_list")
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        press("series_detail_play_episode", Key.DirectionDown)
        compose.onNodeWithTag("series_selected_episode").assertIsFocused()
        press("series_selected_episode", Key.DirectionDown)
        compose.onNodeWithTag("series_selected_episode").assertIsFocused()
        compose.runOnIdle { org.junit.Assert.assertEquals(12, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
    }

    @Test fun leavingDetailsCancelsEpisodesAndAllowsASecondVisit() {
        openDetails()
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 1 }
        compose.onNodeWithTag("series_back").performClick()
        waitFor("series_row_page")
        compose.waitUntil(5_000) { compose.activity.cancelledEpisodes.get() == 1 }
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        press("focused_poster_card", Key.Enter)
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 2 }
        compose.runOnIdle { compose.activity.completeEpisodes() }
        waitFor("series_episode_list")
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
    }

    @Test fun catalogRefreshPreservesLoadedEpisodesAndSelectedSeason() {
        openDetails()
        compose.runOnIdle { compose.activity.completeEpisodes() }
        waitFor("series_episode_list")
        press("series_detail_play_episode", Key.DirectionDown)
        press("series_selected_episode", Key.DirectionLeft)
        press("series_season_1", Key.DirectionDown)
        press("series_season_2", Key.DirectionRight)
        compose.waitUntil(5_000) { (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId == 21 }
        compose.onNodeWithTag("series_selected_episode").assertIsFocused()
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("series_refreshing")
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("series_selected_episode").assertIsFocused()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(21, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
            org.junit.Assert.assertEquals(1, compose.activity.episodeRequests.get())
        }
        compose.runOnIdle { compose.activity.holder.invalidateCachedCatalog() }
        waitFor("series_refreshing")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("series_refresh_retry")
        compose.onNodeWithTag("series_refresh_retry").performClick()
        waitFor("series_refreshing")
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        compose.runOnIdle { org.junit.Assert.assertEquals(21, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
    }

    private fun openDetails() {
        waitFor("series_loading")
        compose.runOnIdle { compose.activity.complete() }
        waitFor("series_row_page")
        org.junit.Assert.assertEquals(0, compose.activity.episodeRequests.get())
        press("series_search_open", Key.DirectionDown)
        press("focused_poster_card", Key.DirectionRight)
        org.junit.Assert.assertEquals(0, compose.activity.episodeRequests.get())
        press("focused_poster_card", Key.Enter)
        waitFor("series_detail_page")
    }

    private fun waitFor(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
}
