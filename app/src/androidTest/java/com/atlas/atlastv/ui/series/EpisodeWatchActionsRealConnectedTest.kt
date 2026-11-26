package com.atlas.atlastv.ui.series

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

/** Read-only personal-library acceptance: open and cancel; never mark, Undo or play. */
class EpisodeWatchActionsRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    @Test fun realEpisodeOffersCleanActionsAndCancellationReturnsToTheExactSearchResultWithoutWrites() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val activity = compose.activity
        val dao = AtlasDatabaseProvider.database(activity).watchProgressDao()
        val before = runBlocking { dao.records() }
        val account = File(activity.filesDir, "provider_settings.json").readBytes()
        waitFor("top_nav_item_search"); compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("Blindspot")
        waitFor("unified_search_movies"); compose.onNodeWithTag("unified_search_results").performScrollToIndex(1)
        waitFor("unified_search_result_show-36621"); compose.onNodeWithTag("unified_search_result_show-36621").performClick()
        waitFor("series_season_list"); compose.onNodeWithTag("series_season_list").performScrollToNode(hasTestTag("series_season_1"))
        compose.onNodeWithTag("series_season_1").performClick(); waitFor("series_episode_more")
        compose.onNodeWithTag("series_detail_play_episode").performSemanticsAction(SemanticsActions.RequestFocus)
        native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag("series_episode_more").assertIsFocused().assertIsDisplayed()
        val row = compose.onNodeWithTag("series_selected_episode").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithTag("series_episode_more").fetchSemanticsNode().boundsInRoot
        assertTrue(more.right <= row.right && more.bottom <= row.bottom)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("episode_actions_dialog")
        compose.onNodeWithTag("episode_action_mark").assertIsFocused().assertTextEquals("Mark watched")
        compose.onNodeWithTag("episode_actions_title").assertTextEquals("Woe Has Joined")
        compose.onNodeWithTag("episode_action_undo").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(activity.cacheDir, "episode-actions-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("episode_actions_dialog").fetchSemanticsNodes().isEmpty() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        compose.onNodeWithTag("series_episode_more").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_LEFT); compose.onNodeWithTag("series_selected_episode").assertIsFocused()
        native(KeyEvent.KEYCODE_BACK); waitFor("focused_unified_search_result_show-36621")
        compose.onNodeWithTag("focused_unified_search_result_show-36621").assertIsFocused()
        assertEquals(before, runBlocking { dao.records() }); assertArrayEquals(account, File(activity.filesDir, "provider_settings.json").readBytes())
    }
}
