package com.atlas.atlastv.ui.series

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.SeriesLoadingCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Safe controlled show journeys, including every season and episodes beyond 1,000. */
class SeriesUsabilityConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<SeriesLoadingCheckActivity>()
    @Test fun arrivingSynopsisKeepsWatchlistFocusAndPrimaryActionsVisible() {
        open()
        press("series_detail_play_episode", Key.DirectionRight)
        compose.runOnIdle { compose.activity.completeEpisodes(SeriesShowMetadata(
            synopsis = "A stranger arrives with a secret that changes everything.", genre = "Drama / Crime", releaseYear = "2015")) }
        waitFor("series_detail_synopsis")
        compose.onNodeWithTag("series_detail_watchlist").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("series_detail_play_episode").assertIsDisplayed()
        compose.onNodeWithTag("series_detail_synopsis").assertTextEquals("A stranger arrives with a secret that changes everything.")
        compose.onNodeWithTag("series_detail_seasons").assertTextEquals("2015 · Drama / Crime · 2 seasons")
        compose.onNodeWithTag("series_detail_poster").assertIsDisplayed()
        press("series_detail_watchlist", Key.DirectionDown)
        focused("series_selected_episode")
        compose.onNodeWithTag("series_selected_episode").assertIsDisplayed()
    }
    @Test fun longTitleAndSynopsisCannotPushPlayOrEpisodesOutsideTheScreen() {
        waitFor("series_loading")
        compose.runOnIdle { compose.activity.complete("An Extremely Long Television Show Title With Many Words And Another Subtitle (2015)") }
        waitFor("series_row_page")
        press("series_search_open", Key.DirectionDown); press("focused_poster_card", Key.Enter)
        waitFor("series_detail_play_episode")
        compose.runOnIdle { compose.activity.completeEpisodes(SeriesShowMetadata(
            synopsis = "A long description of the story and its characters. ".repeat(80), genre = "Drama / Crime / Mystery", releaseYear = "2015")) }
        waitFor("series_detail_synopsis")
        compose.onNodeWithTag("series_detail_play_episode").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("series_detail_watchlist").assertIsDisplayed()
        val synopsis = compose.onNodeWithTag("series_detail_synopsis").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("series_detail_play_episode").fetchSemanticsNode().boundsInRoot
        assertTrue(synopsis.bottom <= play.top)
        press("series_detail_play_episode", Key.DirectionDown)
        compose.onNodeWithTag("series_selected_episode").assertIsDisplayed().assertIsFocused()
        press("series_selected_episode", Key.Enter); waitFor("series_check_episode_destination")
        compose.runOnIdle { assertEquals(11, compose.activity.openedEpisodeId) }
    }
    @Test fun everySeasonAndDeepEpisodeAreSelectableAndReturnRestoresSelection() {
        open()
        compose.runOnIdle { compose.activity.completeLongEpisodes() }; waitFor("series_episode_list")
        compose.onAllNodesWithText("Show Details").assertCountEquals(0)
        compose.onAllNodesWithText("Choose a season and episode").assertCountEquals(0)
        compose.onAllNodesWithTag("series_detail_position").assertCountEquals(0)
        compose.onNodeWithTag("series_detail_poster").assertIsDisplayed()
        press("series_detail_play_episode",Key.DirectionDown)
        press("series_selected_episode",Key.DirectionLeft)
        for (season in 1..24) {
            press("series_season_$season",Key.DirectionRight)
            focused("series_selected_episode")
            compose.runOnIdle { assertEquals(season,(compose.activity.holder.state as SeriesCatalogState.Ready).selectedSeasonNumber) }
            if (season < 24) { press("series_selected_episode",Key.DirectionLeft); press("series_season_$season",Key.DirectionDown) }
        }
        repeat(63) { press("series_selected_episode",Key.DirectionDown) }
        compose.runOnIdle { assertEquals(24064,(compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
        press("series_selected_episode",Key.Enter); waitFor("series_check_episode_destination")
        compose.runOnIdle { assertEquals(24064,compose.activity.openedEpisodeId); compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("series_episode_list")
        press("series_detail_play_episode",Key.DirectionDown); focused("series_selected_episode")
        compose.onNodeWithTag("series_selected_episode").assertIsDisplayed()
        press("series_selected_episode",Key.DirectionLeft); focused("series_season_24")
    }
    @Test fun watchlistCanBeAddedAndRemovedWithoutStartingAnEpisode() {
        open(); compose.runOnIdle { compose.activity.completeEpisodes() }; waitFor("series_episode_list")
        press("series_detail_play_episode",Key.DirectionRight); press("series_detail_watchlist",Key.Enter)
        compose.runOnIdle { assertTrue(1 in (compose.activity.holder.state as SeriesCatalogState.Ready).watchlistSeriesIds) }
        press("series_detail_watchlist",Key.Enter)
        compose.runOnIdle { assertFalse(1 in (compose.activity.holder.state as SeriesCatalogState.Ready).watchlistSeriesIds); assertNull(compose.activity.openedEpisodeId) }
    }
    private fun open() {
        waitFor("series_loading"); compose.runOnIdle { compose.activity.complete() }; waitFor("series_row_page")
        press("series_search_open",Key.DirectionDown); press("focused_poster_card",Key.Enter); waitFor("series_detail_play_episode")
    }
    private fun press(tag:String,key:Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun focused(tag:String) { compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun waitFor(tag:String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
