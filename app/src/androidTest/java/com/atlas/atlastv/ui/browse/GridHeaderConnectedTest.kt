package com.atlas.atlastv.ui.browse

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.GridHeaderCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GridHeaderConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<GridHeaderCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun open() {
        focused("grid_header_tile_0"); native(KeyEvent.KEYCODE_DPAD_UP); focused("grid_header_back")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("grid_header_options"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("grid_header_first")
    }
    private fun last() { native(KeyEvent.KEYCODE_DPAD_DOWN); focused("grid_header_last"); native(KeyEvent.KEYCODE_DPAD_CENTER) }

    @Test fun cleanHeaderNativeMenuBackAndCancelKeepTheSameGridSelection() {
        focused("grid_header_tile_0")
        compose.onNodeWithTag("grid_header_count").assertDoesNotExist(); compose.onNodeWithTag("grid_header_position").assertDoesNotExist()
        compose.onNodeWithTag("grid_header_first").assertDoesNotExist(); compose.onNodeWithTag("grid_header_last").assertDoesNotExist()
        open(); native(KeyEvent.KEYCODE_BACK); focused("grid_header_options"); assertEquals(0, compose.activity.grid.state.value.selectedIndex)
        native(KeyEvent.KEYCODE_DPAD_CENTER); native(KeyEvent.KEYCODE_DPAD_UP); focused("grid_header_options_cancel")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("grid_header_options"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("grid_header_tile_0")
        compose.onNodeWithTag("grid_header_options_dialog").assertDoesNotExist()
    }
    @Test fun nativeLastAndFirstReachTheCompletePartialRowAndRestoreExactDeepDetail() {
        open(); last(); focused("grid_header_tile_512"); compose.onNodeWithTag("grid_header_tile_512").assert(hasContentDescription("Movie 513"))
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("grid_header_detail_back"); compose.runOnIdle { assertEquals(512, compose.activity.destination) }
        native(KeyEvent.KEYCODE_BACK); focused("grid_header_tile_512")
        compose.openGridOptions("grid_header"); focused("grid_header_first"); native(KeyEvent.KEYCODE_DPAD_CENTER); focused("grid_header_tile_0")
        assertEquals(513, compose.activity.grid.state.value.total); assertTrue(compose.activity.grid.state.value.items.size <= 200)
    }
    @Test fun dismissalFromDeepSelectionReturnsToOptionsThenTheExactTile() {
        open(); last(); focused("grid_header_tile_512"); compose.openGridOptions("grid_header")
        native(KeyEvent.KEYCODE_BACK); focused("grid_header_options"); assertEquals(512, compose.activity.grid.state.value.selectedIndex)
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("grid_header_tile_512")
        compose.openGridOptions("grid_header"); native(KeyEvent.KEYCODE_BACK); focused("grid_header_options")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("grid_header_back"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("grid_header_tile_512")
    }
    @Test fun pendingDeepLoadAndFailedRetryRemainUsableAfterMenuJump() {
        open(); compose.runOnIdle { compose.activity.blockReads = true; compose.activity.failReads = true }; last()
        focused("grid_header_tile_512"); compose.waitUntil(8_000) { compose.activity.requests.get() == 2 }
        compose.runOnIdle { compose.activity.release.complete(Unit) }; waitFor("grid_header_error")
        compose.runOnIdle { assertNotNull(compose.activity.grid.state.value.item(0)); compose.activity.failReads = false }
        native(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("grid_header_tile_512") and hasContentDescription("Movie 513")).fetchSemanticsNodes().isNotEmpty() }
        focused("grid_header_tile_512"); compose.onNodeWithTag("grid_header_error").assertDoesNotExist()
    }
    @Test fun refreshToEmptyWhileMenuOpenClosesItAndRestoresUsableBack() {
        open(); compose.runOnIdle { compose.activity.total = 0; compose.activity.revision++ }
        waitFor("grid_header_empty"); focused("grid_header_back")
        compose.onNodeWithTag("grid_header_options_dialog").assertDoesNotExist(); compose.onNodeWithTag("grid_header_options").assertDoesNotExist()
        compose.onNodeWithTag("grid_header_title").assertTextEquals("All Movies")
    }
    @Test fun singleItemHasNoInertMenuAndLongTitleCannotCoverHeaderControls() {
        focused("grid_header_tile_0"); compose.runOnIdle {
            compose.activity.headerTitle = "An Extremely Long Category Name With More Words And Another Long Subtitle ".repeat(8)
        }
        compose.waitForIdle(); val header = compose.onNodeWithTag("grid_header_header").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("grid_header_title").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithTag("grid_header_back").fetchSemanticsNode().boundsInRoot
        val options = compose.onNodeWithTag("grid_header_options").fetchSemanticsNode().boundsInRoot
        assertTrue(back.left >= header.left && options.right <= header.right && title.left >= back.right && title.right <= options.left)
        assertTrue(compose.onNodeWithTag("grid_header_grid").fetchSemanticsNode().boundsInRoot.top >= header.bottom)
        compose.runOnIdle { compose.activity.total = 1; compose.activity.revision++ }; focused("grid_header_tile_0")
        compose.onNodeWithTag("grid_header_options").assertDoesNotExist(); assertEquals(1, compose.activity.grid.state.value.total)
    }
}
