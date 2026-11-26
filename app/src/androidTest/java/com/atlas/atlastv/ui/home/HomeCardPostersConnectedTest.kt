package com.atlas.atlastv.ui.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.HomeCardPostersCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeCardPostersConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(blocked: Boolean = false) = ActivityScenario.launch<HomeCardPostersCheckActivity>(
        Intent(InstrumentationRegistry.getInstrumentation().targetContext, HomeCardPostersCheckActivity::class.java).putExtra("blocked", blocked))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun within(tag: String, card: String) = compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(card)), useUnmergedTree = true)
    private fun art(card: String) { compose.waitUntil(12_000) { within("loaded_provider_poster", card).fetchSemanticsNodes().size == 1 } }
    private fun row() { focused("top_nav_item_search"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_home_card") }
    private fun color(card: String, red: Boolean = false, green: Boolean = false) {
        val b = within("loaded_provider_poster", card).fetchSemanticsNodes().single().boundsInRoot
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val pixel = capture.getPixel(b.center.x.toInt(), b.top.toInt() + 12); capture.recycle()
        val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val blue = android.graphics.Color.blue(pixel)
        if (red) assertTrue("decoded red saved portrait", r > g * 1.5 && r > blue * 1.5)
        else if (green) assertTrue("decoded green catalog portrait", g > r * 1.5 && g > blue * 1.3)
        else assertTrue("decoded blue saved portrait", blue > r * 1.5 && blue > g * 1.3)
    }
    @Test fun missingCatalogAndFailedValidCatalogUseSavedMovieAndParentShowPortraits() {
        launch().use { s ->
            art("home_card_movie-1"); art("home_card_episode-99"); art("home_card_movie-2")
            within("saved_card_poster", "home_card_movie-1").assertCountEquals(1)
            within("saved_card_poster", "home_card_episode-99").assertCountEquals(1)
            within("saved_card_poster", "home_card_movie-2").assertCountEquals(0)
            color("home_card_movie-1"); color("home_card_episode-99", red = true); color("home_card_movie-2", green = true)
            within("loaded_provider_poster", "home_card_movie-3").assertCountEquals(0)
            compose.onNodeWithTag("card_episode_label", useUnmergedTree = true).assertTextEquals("S1 E2")
            val bars = compose.onAllNodesWithTag("card_progress_bar", useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(3, bars.size); assertEquals(1, bars.map { it.boundsInRoot.top }.distinct().size)
            s.onActivity { assertTrue(it.calls.contains(it.failed.providerImageToken)); assertTrue(it.calls.contains(it.newestBad.providerImageToken)); assertTrue(it.calls.contains(it.red.providerImageToken)) }
            val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "home-posters-controlled.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; capture.recycle()
        }
    }
    @Test fun lateSavedPostersPreserveFocusGeometryWorkingCatalogAndDeepReturn() {
        launch(blocked = true).use { s ->
            art("home_card_movie-2"); row()
            val before = compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot
            within("loaded_provider_poster", "focused_home_card").assertCountEquals(0)
            val working = within("loaded_provider_poster", "home_card_movie-2").fetchSemanticsNodes().single().boundsInRoot
            var primaryCalls = 0; s.onActivity { primaryCalls = it.calls.count { token -> token == it.green.providerImageToken } }
            s.onActivity { it.release.complete(Unit) }; art("focused_home_card"); art("home_card_episode-99"); focused("focused_home_card")
            assertEquals(before, compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot)
            assertEquals(working, within("loaded_provider_poster", "home_card_movie-2").fetchSemanticsNodes().single().boundsInRoot)
            s.onActivity { assertEquals(primaryCalls, it.calls.count { token -> token == it.green.providerImageToken }) }
            color("focused_home_card")
            repeat(8) { native(KeyEvent.KEYCODE_DPAD_RIGHT) }; focused("focused_home_card")
            var selected: String? = null; s.onActivity { selected = it.holder.selectedCardId }
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("poster_check_destination"); compose.onNodeWithTag("poster_check_destination").assertTextEquals(selected!!)
            native(KeyEvent.KEYCODE_BACK); focused("focused_home_card"); s.onActivity { assertEquals(selected, it.holder.selectedCardId) }
        }
    }
    @Test fun accountChangeRemovesOldSavedPortraitsWithoutStealingFocusAndRejectsBlockedReads() {
        launch().use { s ->
            art("home_card_movie-1"); art("home_card_episode-99"); row()
            s.onActivity { it.account = it.account.copy(password = "another") }
            compose.waitUntil(8_000) { within("saved_card_poster", "focused_home_card").fetchSemanticsNodes().isEmpty() }
            within("loaded_provider_poster", "focused_home_card").assertCountEquals(0)
            within("saved_card_poster", "home_card_episode-99").assertCountEquals(0); focused("focused_home_card")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("poster_check_destination"); native(KeyEvent.KEYCODE_BACK); focused("focused_home_card")
        }
        launch(blocked = true).use { s ->
            waitTag("home_hero_primary"); row(); s.onActivity { it.account = it.account.copy(password = "another"); it.release.complete(Unit) }
            compose.waitForIdle(); within("saved_card_poster", "focused_home_card").assertCountEquals(0); focused("focused_home_card")
        }
    }
}
