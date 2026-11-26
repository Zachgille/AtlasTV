package com.atlas.atlastv.ui.series

import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in installed-library check: no fixture catalog/account/watchlist writes and no video. */
class SeriesDetailPresentationRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realShowArtworkAndSynopsisKeepPrimaryActionsAndEverySeasonReachable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_search")
        compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field")
        compose.onNodeWithTag("unified_search_field").performTextInput("Blindspot")
        waitFor("unified_search_movies")
        compose.onNodeWithTag("unified_search_results").performScrollToIndex(1)
        waitFor("unified_search_result_show-36621")
        compose.onNodeWithTag("unified_search_result_show-36621").performClick()
        waitFor("loaded_detail_backdrop")
        waitFor("series_episode_list")
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Blindspot")
        compose.onNodeWithTag("series_detail_synopsis").assertIsDisplayed()
        compose.onNodeWithTag("series_detail_seasons").assertTextContains("2015", substring = true).assertTextContains("5 seasons", substring = true)
        compose.onNodeWithTag("series_detail_play_episode").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("series_detail_watchlist").assertIsDisplayed()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "series-detail-refined.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        press("series_detail_play_episode", Key.DirectionDown)
        press("series_selected_episode", Key.DirectionLeft)
        for (season in 1..4) press("series_season_$season", Key.DirectionDown)
        compose.onNodeWithTag("series_season_5").assertIsFocused().assertIsDisplayed()
        press("series_season_5", Key.DirectionRight)
        compose.onNodeWithTag("series_selected_episode").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("I Came to Sleigh").assertIsDisplayed()
    }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
