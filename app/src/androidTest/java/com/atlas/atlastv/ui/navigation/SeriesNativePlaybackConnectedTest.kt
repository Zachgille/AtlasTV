package com.atlas.atlastv.ui.navigation

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real episode journey using native Android keys, with no fixture/catalog/account writes. */
class SeriesNativePlaybackConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeCenterStartsTheSelectedEpisodeAndBackReturnsToItsDetails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_search")
        compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field")
        compose.onNodeWithTag("unified_search_field").performTextInput("Blindspot")
        waitFor("unified_search_movies")
        native(KeyEvent.KEYCODE_BACK) // Dismiss the TV keyboard before entering results.
        native(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("focused_unified_search_result_movie-92710").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("focused_unified_search_result_show-36621")
        native(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("series_episode_list")
        compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("live_tv_player_screen")
        native(KeyEvent.KEYCODE_MEDIA_PAUSE)
        waitFor("normal_playback_overlay")
        native(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("normal_playback_overlay").fetchSemanticsNodes().isEmpty() }
        native(KeyEvent.KEYCODE_BACK)
        waitFor("series_episode_list")
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Blindspot")
        compose.onNodeWithTag("series_selected_episode").assertExists()
    }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
