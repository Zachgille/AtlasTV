package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.openGridOptions

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.MovieGridCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MovieGridConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MovieGridCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun open() { waitFor("movies_all_open"); compose.onNodeWithTag("movies_all_open").performClick(); waitFor("all_movies_loading") }

    @Test fun backIsUsableDuringInitialLoadAndCancelsScreenWork() {
        open(); compose.onNodeWithTag("all_movies_back").assertIsFocused()
        compose.onNodeWithTag("all_movies_back").performClick()
        waitFor("movies_all_open"); compose.onNodeWithTag("movies_all_open").assertIsFocused()
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() == 1 }
    }

    @Test fun remoteReachesLastPartialRowAndDetailsReturnRestoresExactTileAfterPlaybackComposition() {
        open(); compose.runOnIdle { compose.activity.complete() }; waitFor("all_movies_tile_0")
        compose.waitUntil(5_000) { compose.onNodeWithTag("all_movies_tile_0").isFocused() }
        repeat(4) { index -> press("all_movies_tile_$index", Key.DirectionRight) }
        press("all_movies_tile_4", Key.DirectionRight); compose.onNodeWithTag("all_movies_tile_4").assertIsFocused()
        press("all_movies_tile_4", Key.DirectionDown); compose.onNodeWithTag("all_movies_tile_9").assertIsFocused()
        press("all_movies_tile_9", Key.PageDown)
        compose.waitUntil(5_000) { compose.onNodeWithTag("all_movies_tile_49").isFocused() }
        compose.openGridOptions("all_movies"); compose.onNodeWithTag("all_movies_last").performClick()
        compose.waitUntil(5_000) { compose.activity.requests.get() >= 2 }
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("all_movies_tile_512") and hasContentDescription("Movie 513")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("all_movies_tile_512").assertIsFocused()
        press("all_movies_tile_512", Key.DirectionDown); compose.onNodeWithTag("all_movies_tile_512").assertIsFocused()
        press("all_movies_tile_512", Key.Enter)
        waitFor("movie_detail_title"); compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 513")
        compose.onAllNodesWithTag("movie_detail_position").assertCountEquals(0)
        compose.onNodeWithTag("movie_detail_play").performClick(); waitFor("grid_check_playback_back")
        compose.onNodeWithTag("grid_check_playback_back").performClick(); waitFor("movie_detail_play")
        compose.onNodeWithTag("movie_detail_back").performClick(); waitFor("all_movies_tile_512")
        compose.waitUntil(5_000) { compose.onNodeWithTag("all_movies_tile_512").isFocused() }
        assertTrue(compose.activity.grid.state.value.items.size <= 200)
    }

    @Test fun failedDeepPageRetainsOldWindowAndPlaceholderOffersRemoteRetry() {
        open(); compose.runOnIdle { compose.activity.complete() }; waitFor("all_movies_tile_0")
        compose.openGridOptions("all_movies"); compose.onNodeWithTag("all_movies_last").performClick()
        compose.waitUntil(5_000) { compose.activity.requests.get() == 2 }
        compose.runOnIdle { compose.activity.fail() }; waitFor("all_movies_error")
        assertNotNull(compose.activity.grid.state.value.item(0))
        press("all_movies_tile_512", Key.Enter)
        compose.waitUntil(5_000) { compose.activity.requests.get() == 3 }
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("all_movies_tile_512") and hasContentDescription("Movie 513")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("all_movies_tile_512").assertIsFocused()
        compose.onNodeWithTag("all_movies_error").assertDoesNotExist()
    }
    private fun SemanticsNodeInteraction.isFocused(): Boolean = runCatching { assertIsFocused(); true }.getOrDefault(false)
}
