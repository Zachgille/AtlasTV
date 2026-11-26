package com.atlas.atlastv.ui.home

import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in retained-library presentation/details return. No personal fixtures, refresh or video. */
class HomeHeroPresentationRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realContinueWatchingHeroShowsArtAndReturnsFromEpisodesToItsHomeAction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("hero_synopsis"); waitFor("loaded_detail_backdrop")
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        val title = compose.onNodeWithTag("hero_title").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.onNodeWithTag("continue_watching_row").assertIsDisplayed()
        val row = compose.onNodeWithTag("continue_watching_row").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("home_screen").fetchSemanticsNode().boundsInRoot
        assertTrue("First Continue Watching row fits completely", row.bottom <= screen.bottom)
        press("top_nav_item_search", Key.DirectionDown)
        compose.onNodeWithTag("home_hero_primary").assertIsFocused().assertIsDisplayed().assertTextEquals("Resume")
        compose.onNodeWithTag("home_hero_details").assertIsDisplayed().assertTextEquals("Episodes")
        compose.onNodeWithTag("catalog_refresh_prompt").assertIsDisplayed()
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "home-hero-refined.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        capture.recycle()
        press("home_hero_primary", Key.DirectionRight); press("home_hero_details", Key.Enter)
        waitFor("series_detail_title"); compose.onNodeWithTag("series_detail_title").assertTextEquals(title)
        compose.onNodeWithTag("series_back").performClick()
        waitFor("hero_synopsis"); compose.onNodeWithTag("hero_title").assertTextEquals(title)
        compose.onNodeWithTag("home_hero_details").assertIsFocused()
        press("home_hero_details", Key.DirectionLeft); compose.onNodeWithTag("home_hero_primary").assertIsFocused()
    }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
