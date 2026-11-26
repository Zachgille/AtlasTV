package com.atlas.atlastv.ui.movies

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

/** Opt-in installed-library metadata/navigation, without personal fixture writes or video. */
class MovieDetailPresentationRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realMovieShowsLandscapeSynopsisAndReturnsToItsExactSearchResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_search"); compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("Inception")
        waitFor("unified_search_result_movie-353496"); compose.onNodeWithTag("unified_search_result_movie-353496").performClick()
        waitFor("loaded_detail_backdrop"); waitFor("movie_detail_synopsis")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Inception")
        compose.onNodeWithTag("movie_detail_year").assertTextContains("2010", substring = true)
        compose.onNodeWithTag("movie_detail_synopsis").assertIsDisplayed()
        compose.onNodeWithTag("movie_detail_play").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("movie_detail_watchlist").assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "movie-detail-refined.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        press("movie_detail_play", Key.DirectionRight)
        compose.onNodeWithTag("movie_detail_watchlist").assertIsFocused()
        press("movie_detail_watchlist", Key.DirectionRight)
        compose.onNodeWithTag("movie_detail_back").assertIsFocused(); press("movie_detail_back", Key.Enter)
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").assertTextEquals("Inception")
        compose.onNodeWithTag("focused_unified_search_result_movie-353496").assertIsFocused()
    }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
