package com.atlas.atlastv.data.discovery

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.DiscoveryCheckActivity
import com.atlas.atlastv.ui.home.assertHomeFooterVisible
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DiscoveryHomeConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<DiscoveryCheckActivity>()
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused() { compose.waitUntil(8000) { runCatching { compose.onNodeWithTag("focused_home_card").assertIsFocused();true }.getOrDefault(false) } }
    private fun part(tag: String)=compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("focused_home_card")),useUnmergedTree=true)
    private fun inRow(id: String) = compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag("home_row_$id"))).fetchSemanticsNodes().isNotEmpty()
    @Test fun actualRankedHomePutsBothNewAndRatedTypesFirstAndRestoresDeepExactDetailsCard() {
        waitTag("home_hero_primary");compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        compose.onAllNodesWithText("Format:",substring=true).assertCountEquals(0)
        native(KeyEvent.KEYCODE_DPAD_DOWN);native(KeyEvent.KEYCODE_DPAD_DOWN);focused()
        assertTrue(inRow("movies_new"));part("poster_card_title").assertTextEquals("New Movie 600")
        native(KeyEvent.KEYCODE_DPAD_DOWN);focused();assertTrue(inRow("series_new"));part("poster_card_title").assertTextEquals("New Show 300")
        native(KeyEvent.KEYCODE_DPAD_DOWN);focused();assertTrue(inRow("movies_rated"))
        native(KeyEvent.KEYCODE_DPAD_DOWN);focused();assertTrue(inRow("series_rated"))
        repeat(11) {native(KeyEvent.KEYCODE_DPAD_RIGHT)};focused()
        val title=part("poster_card_title").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val bounds=part("focused_poster_card").fetchSemanticsNode().boundsInRoot
        assertHomeFooterVisible(part("focused_poster_card"),compose.onNodeWithTag("home_rows_viewport"),part("poster_card_footer"),part("poster_card_title"))
        native(KeyEvent.KEYCODE_DPAD_CENTER);waitTag("discovery_destination")
        compose.activityRule.scenario.onActivity { assertEquals(311,it.selected!!.streamId);assertTrue(it.destination!!.startsWith("Details")) }
        native(KeyEvent.KEYCODE_BACK);focused();assertTrue(inRow("series_rated"));part("poster_card_title").assertTextEquals(title)
        assertEquals(bounds,part("focused_poster_card").fetchSemanticsNode().boundsInRoot)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir,"discovery-controlled.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
