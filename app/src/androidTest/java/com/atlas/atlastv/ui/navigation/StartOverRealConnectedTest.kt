package com.atlas.atlastv.ui.navigation

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in read-only real details. Cancel only: no video, import, watchlist or progress action. */
class StartOverRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    @Test fun actualResumableShowHasVisibleRestartSafeCancelAndExactSearchReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val activity = compose.activity
        val db = AtlasDatabaseProvider.database(activity)
        val original = runBlocking { db.watchProgressDao().records() }
        val account = File(activity.filesDir, "provider_settings.json").readBytes()
        assertTrue(original.any { it.contentType == "series_episode" && it.seriesId == 36621 && it.positionMs > 0 && !it.completed })
        waitFor("top_nav_item_search"); compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("Blindspot")
        waitFor("unified_search_movies"); compose.onNodeWithTag("unified_search_results").performScrollToIndex(1)
        waitFor("unified_search_result_show-36621"); compose.onNodeWithTag("unified_search_result_show-36621").performClick()
        waitFor("series_season_list"); compose.onNodeWithTag("series_season_list").performScrollToNode(hasTestTag("series_season_1"))
        compose.onNodeWithTag("series_season_1").performClick(); waitFor("series_detail_start_over")
        compose.onNodeWithTag("series_detail_play_episode").assert(hasText("Resume S1 E1"))
        compose.onNodeWithTag("series_detail_play_episode").performSemanticsAction(SemanticsActions.RequestFocus)
        native(KeyEvent.KEYCODE_DPAD_RIGHT); native(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("series_detail_start_over").assertIsFocused().assertIsDisplayed()
        val button = compose.onNodeWithTag("series_detail_start_over").fetchSemanticsNode().boundsInRoot
        val hero = compose.onNodeWithTag("series_detail_header").fetchSemanticsNode().boundsInRoot
        assertTrue(button.right <= hero.right && button.bottom <= hero.bottom)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("loaded_detail_backdrop") and hasAnyAncestor(hasTestTag("series_detail_header")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(activity.cacheDir, "start-over-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("start_over_dialog")
        compose.onNodeWithTag("start_over_cancel").assertIsFocused()
        compose.onNodeWithTag("start_over_title").assertTextEquals("Blindspot · S1 E1")
        native(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("start_over_dialog").fetchSemanticsNodes().isEmpty() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        compose.onNodeWithTag("series_detail_start_over").assertIsFocused()
        assertEquals(original, runBlocking { db.watchProgressDao().records() }); assertArrayEquals(account, File(activity.filesDir, "provider_settings.json").readBytes())
        native(KeyEvent.KEYCODE_BACK); waitFor("focused_unified_search_result_show-36621")
        compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag("focused_unified_search_result_show-36621").assertIsFocused(); true }.getOrDefault(false) }
        assertEquals(original, runBlocking { db.watchProgressDao().records() })
    }
}
