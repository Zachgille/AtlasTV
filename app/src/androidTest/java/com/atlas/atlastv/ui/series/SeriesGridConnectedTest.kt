package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.browse.openGridOptions

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.SeriesGridCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SeriesGridConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SeriesGridCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun open() { waitFor("series_all_open"); compose.onNodeWithTag("series_all_open").performClick(); waitFor("all_shows_loading") }
    private fun complete() { compose.runOnIdle { compose.activity.complete() }; waitFor("all_shows_tile_0") }
    private fun focused(tag: String) { compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }

    @Test fun loadingBackCancelsPageAndReturnsToEntryWithoutFetchingEpisodes() {
        open(); compose.onNodeWithTag("all_shows_back").assertIsFocused()
        compose.onNodeWithTag("all_shows_back").performClick(); waitFor("series_all_open")
        compose.onNodeWithTag("series_all_open").assertIsFocused()
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() == 1 }
        assertEquals(0, compose.activity.episodeRequests.get())
    }

    @Test fun remoteDeepShowSeasonAndPlaybackReturnKeepExactContext() {
        open(); complete(); focused("all_shows_tile_0")
        repeat(4) { press("all_shows_tile_$it", Key.DirectionRight) }
        press("all_shows_tile_4", Key.DirectionRight); focused("all_shows_tile_4")
        press("all_shows_tile_4", Key.DirectionDown); focused("all_shows_tile_9")
        press("all_shows_tile_9", Key.PageDown); focused("all_shows_tile_49")
        assertEquals(0, compose.activity.episodeRequests.get())
        compose.openGridOptions("all_shows"); compose.onNodeWithTag("all_shows_last").performClick()
        compose.waitUntil(5_000) { compose.activity.requests.get() == 2 }
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("all_shows_tile_512") and hasContentDescription("Show 513")).fetchSemanticsNodes().isNotEmpty() }
        focused("all_shows_tile_512"); assertEquals(0, compose.activity.episodeRequests.get())
        press("all_shows_tile_512", Key.DirectionDown); focused("all_shows_tile_512")
        press("all_shows_tile_512", Key.Enter); waitFor("series_detail_title")
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 513")
        compose.onAllNodesWithTag("series_detail_position").assertCountEquals(0)
        compose.onNodeWithTag("series_detail_play_episode").assertIsDisplayed().assertIsFocused()
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 1 }
        compose.runOnIdle { compose.activity.completeEpisodes() }; waitFor("series_episode_list")
        press("series_detail_play_episode", Key.DirectionDown)
        press("series_selected_episode", Key.DirectionLeft)
        press("series_season_1", Key.DirectionDown)
        press("series_season_2", Key.DirectionRight)
        focused("series_selected_episode")
        assertEquals(21, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
        press("series_selected_episode", Key.Enter); waitFor("grid_check_playback_back")
        compose.onNodeWithTag("grid_check_playback_back").performClick(); waitFor("series_detail_play_episode")
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 513")
        assertEquals(21, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
        assertEquals(2, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedSeasonNumber)
        assertEquals(1, compose.activity.episodeRequests.get())
        compose.onNodeWithTag("series_back").performClick(); waitFor("all_shows_tile_512"); focused("all_shows_tile_512")
        assertTrue(compose.activity.grid.state.value.items.size <= 200)
    }

    @Test fun failedDeepWindowRetriesThenLeavingPendingEpisodesCancelsAndRestoresTile() {
        open(); complete(); compose.openGridOptions("all_shows"); compose.onNodeWithTag("all_shows_last").performClick()
        compose.waitUntil(5_000) { compose.activity.requests.get() == 2 }
        compose.runOnIdle { compose.activity.fail() }; waitFor("all_shows_error")
        assertNotNull(compose.activity.grid.state.value.item(0)); assertEquals(0, compose.activity.episodeRequests.get())
        press("all_shows_tile_512", Key.Enter)
        compose.waitUntil(5_000) { compose.activity.requests.get() == 3 }
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("all_shows_tile_512") and hasContentDescription("Show 513")).fetchSemanticsNodes().isNotEmpty() }
        focused("all_shows_tile_512"); press("all_shows_tile_512", Key.Enter); waitFor("series_detail_page")
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 1 }
        compose.onNodeWithTag("series_back").performClick(); waitFor("all_shows_tile_512"); focused("all_shows_tile_512")
        compose.waitUntil(5_000) { compose.activity.cancelledEpisodes.get() == 1 }
        press("all_shows_tile_512", Key.Enter)
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 2 }
        compose.runOnIdle { compose.activity.failEpisodes() }; waitFor("series_episode_retry")
        compose.onNodeWithTag("series_episode_retry").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.activity.episodeRequests.get() == 3 }
        compose.runOnIdle { compose.activity.completeEpisodes() }; waitFor("series_episode_list")
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
    }
}
