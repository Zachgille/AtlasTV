package com.atlas.atlastv.ui.search

import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SearchPagingCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Native actual Search, isolated data, and rendered border pixels rather than logical focus alone. */
class SearchPresentationConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SearchPagingCheckActivity>()
    private fun waitTag(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun hideIme() {
        compose.runOnIdle { compose.activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(8_000) {
            var hidden = false
            compose.activityRule.scenario.onActivity { hidden = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
            hidden
        }
    }
    private fun search(query: String = "love") { waitTag("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput(query); waitTag("unified_search_movies_all"); hideIme(); focused("unified_search_field") }
    private fun accent(tag: String, expected: Boolean) {
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val c = pixels[pixels.width / 2, 1]
        val blue = c.blue > .6f && c.blue > c.red * 1.5f
        assertEquals("Painted accent on $tag", expected, blue)
    }
    @Test fun onlyTheFocusedInputButtonOrResultHasAnAccentAndClearIsNativeReachable() {
        search(); accent("unified_search_input_outline", true); accent("unified_search_clear", false); accent("unified_search_back", false)
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_unified_search_result_movie-1"); accent("unified_search_input_outline", false)
        native(KeyEvent.KEYCODE_DPAD_UP); focused("unified_search_field"); hideIme(); accent("unified_search_input_outline", true)
        native(KeyEvent.KEYCODE_DPAD_UP); focused("unified_search_back"); accent("unified_search_input_outline", false); accent("unified_search_back", true)
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("unified_search_clear"); accent("unified_search_clear", true); accent("unified_search_input_outline", false); accent("unified_search_back", false)
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("unified_search_field"); assertEquals("", compose.onNodeWithTag("unified_search_field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("unified_search_prompt").assertDoesNotExist(); compose.onNodeWithTag("unified_search_results").assertDoesNotExist()
        accent("unified_search_input_outline", true); assertEquals(1, compose.activity.searches.get())
    }
    @Test fun cleanHeadingsKeepBackendTotalsAndNativeSeeAllAndExactPreviewReturn() {
        search(); compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        compose.onAllNodesWithTag("unified_search_row_title").onFirst().assertTextEquals("Movies")
        assertEquals(714, (compose.activity.holder.state as UnifiedSearchState.Ready).totalCount)
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_unified_search_result_movie-1")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("unified_search_movies_all"); accent("unified_search_movies_all", true); accent("unified_search_input_outline", false)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("search_all_loading"); native(KeyEvent.KEYCODE_BACK)
        focused("unified_search_movies_all"); compose.onNodeWithTag("unified_search_field").assertTextEquals("love")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("focused_unified_search_result_movie-1"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("search_check_detail_title"); compose.onNodeWithTag("search_check_detail_title").assertTextEquals("love Movie 1")
        compose.onNodeWithTag("search_check_detail_back").performClick(); focused("focused_unified_search_result_movie-1")
        compose.onNodeWithTag("unified_search_field").assertTextEquals("love"); accent("unified_search_input_outline", false)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "search-clean-controlled.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun pendingEmptyAndFailedSearchHaveOneUsefulStatusWithUsableClearAndRetry() {
        waitTag("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("slow"); waitTag("unified_search_loading"); hideIme()
        compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist(); compose.onAllNodesWithText("Searching Movies and Shows…").assertCountEquals(1)
        native(KeyEvent.KEYCODE_DPAD_UP); focused("unified_search_back"); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("unified_search_clear"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("unified_search_field")
        compose.onNodeWithTag("unified_search_field").performTextInput("missing"); waitTag("unified_search_empty"); hideIme()
        compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist(); assertEquals(0, (compose.activity.holder.state as UnifiedSearchState.Ready).totalCount)
        compose.onNodeWithTag("unified_search_field").performTextReplacement("fail"); waitTag("unified_search_error"); hideIme()
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("unified_search_retry"); accent("unified_search_retry", true); accent("unified_search_input_outline", false)
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("focused_unified_search_result_movie-1"); compose.onNodeWithTag("unified_search_error").assertDoesNotExist()
    }
    @Test fun longQueriesLeaveCompleteClearAndBackControlsInsideTheViewport() {
        search("A long query with spaces ".repeat(20)); compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        val screen = compose.onNodeWithTag("unified_search_screen").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("unified_search_bar").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("unified_search_input_outline").fetchSemanticsNode().boundsInRoot
        val clear = compose.onNodeWithTag("unified_search_clear").fetchSemanticsNode().boundsInRoot
        assertTrue(input.right < clear.left); assertTrue(clear.right <= bar.right && clear.bottom <= bar.bottom); assertTrue(bar.right <= screen.right)
        compose.onNodeWithTag("unified_search_clear").assertIsDisplayed(); compose.onNodeWithTag("unified_search_back").assertIsDisplayed()
        assertEquals(MaxUnifiedSearchQueryLength, compose.activity.holder.query.length)
        native(KeyEvent.KEYCODE_DPAD_UP); focused("unified_search_back"); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("unified_search_clear"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("unified_search_field")
        assertEquals("", compose.onNodeWithTag("unified_search_field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text); hideIme(); native(KeyEvent.KEYCODE_DPAD_UP); focused("unified_search_back"); native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("search_check_home")
    }
}
