package com.atlas.atlastv.ui.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.HomeHeroCheckActivity
import com.atlas.atlastv.debug.CardProgressCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeFooterConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun selected(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("focused_home_card")), useUnmergedTree = true)
    private fun visible() = assertHomeFooterVisible(selected("focused_poster_card"), compose.onNodeWithTag("home_rows_viewport"), selected("poster_card_footer"), selected("poster_card_title"))
    private fun first() { waitTag("home_hero_primary"); focused("top_nav_item_search")
        val initial = paintedBounds(compose.onAllNodesWithTag("poster_card", useUnmergedTree = true).onFirst())
        val viewport = paintedBounds(compose.onNodeWithTag("home_rows_viewport"))
        assertTrue("First poster fits before entering rows", initial.top >= viewport.top && initial.bottom <= viewport.bottom)
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_home_card"); visible() }
    @Test fun longTwoLineTitlesKeepThePaintedFooterAcrossRowsAndDeepDetailsReturn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<HomeHeroCheckActivity>(Intent(context, HomeHeroCheckActivity::class.java)
            .putExtra("discovery_check", true).putExtra("long_discovery_title", true)
            .putExtra("movie_title", "The Complete Adventures of a Very Long Movie Title (2010)")).use { scenario ->
            first()
            native(KeyEvent.KEYCODE_DPAD_DOWN); visible()
            native(KeyEvent.KEYCODE_DPAD_DOWN); visible()
            repeat(11) { native(KeyEvent.KEYCODE_DPAD_RIGHT) }; focused("focused_home_card"); visible()
            val layouts = mutableListOf<TextLayoutResult>()
            selected("poster_card_title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(2, layouts.single().lineCount)
            val before = paintedBounds(selected("focused_poster_card"))
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("home_hero_destination")
            compose.onNodeWithTag("home_hero_destination").assertTextEquals("Details discover-12")
            native(KeyEvent.KEYCODE_BACK); focused("focused_home_card"); visible()
            assertEquals(before, paintedBounds(selected("focused_poster_card")))
            scenario.onActivity { it.complete("movie-12", HomeHeroMetadata("Late metadata stays inside the fixed hero.", "Adventure", "2010", 125)) }
            waitTag("hero_synopsis"); focused("focused_home_card"); visible()
            assertEquals(before, paintedBounds(selected("focused_poster_card")))
            native(KeyEvent.KEYCODE_DPAD_DOWN); visible(); native(KeyEvent.KEYCODE_DPAD_DOWN); visible()
            native(KeyEvent.KEYCODE_DPAD_UP); visible()
        }
    }
    @Test fun movieEpisodeAndUnknownDurationKeepCompleteProgressAndReturn() {
        ActivityScenario.launch(CardProgressCheckActivity::class.java).use { scenario ->
            first(); selected("card_time_label").assertTextEquals("45m left")
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("focused_home_card"); visible()
            selected("card_episode_label").assertTextEquals("S1 E2"); selected("card_time_label").assertTextEquals("30m left")
            val before = paintedBounds(selected("focused_poster_card"))
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("card_progress_return"); scenario.onActivity { assertEquals(31, it.selectedId) }
            compose.onNodeWithTag("card_progress_return").performClick(); focused("focused_home_card"); visible()
            assertEquals(before, paintedBounds(selected("focused_poster_card")))
            native(KeyEvent.KEYCODE_DPAD_RIGHT); visible(); selected("card_time_label").assertTextEquals("At 2:00")
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "home-footer-controlled.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
}
