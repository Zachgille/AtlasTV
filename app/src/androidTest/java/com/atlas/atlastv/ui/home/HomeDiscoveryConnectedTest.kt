package com.atlas.atlastv.ui.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.HomeHeroCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Home with native remote input; callbacks record intent without starting personal video. */
class HomeDiscoveryConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(onlyDiscovery: Boolean = false) = ActivityScenario.launch<HomeHeroCheckActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext, HomeHeroCheckActivity::class.java).putExtra("discovery_check", true).putExtra("discovery_only", onlyDiscovery))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun row(index: Int) {
        waitTag("home_hero_primary"); focused("top_nav_item_search")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_home_card")
        repeat(index) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
        focused("focused_home_card")
    }
    private fun select(expected: String) { native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("home_hero_destination"); compose.onNodeWithTag("home_hero_destination").assertTextEquals(expected) }
    private fun backCard() { native(KeyEvent.KEYCODE_BACK); focused("focused_home_card") }
    @Test fun discoveryMovieShowAndDeepMovieOpenDetailsAndReturnToExactCards() {
        launch().use { s ->
            row(2); select("Details discover-movie"); backCard()
            native(KeyEvent.KEYCODE_DPAD_RIGHT); select("Details discover-show"); backCard()
            repeat(10) { native(KeyEvent.KEYCODE_DPAD_RIGHT) }; compose.onNodeWithTag("hero_title").assertTextEquals("Discover Movie 12")
            select("Details discover-12"); backCard(); compose.onNodeWithTag("hero_title").assertTextEquals("Discover Movie 12")
            s.onActivity { assertEquals(listOf("discover-movie", "discover-show", "discover-12"), it.detailSelections); assertTrue(it.primarySelections.isEmpty()); assertEquals("discover-12", it.holder.selectedCardId) }
        }
    }
    @Test fun watchlistMovieWithResumeAndShowOpenDetailsInsteadOfPlayback() {
        launch().use { s ->
            row(1); compose.onNodeWithTag("home_hero_primary").assertTextEquals("Resume")
            select("Details saved-movie"); backCard(); native(KeyEvent.KEYCODE_DPAD_RIGHT)
            select("Details watch-show"); backCard()
            s.onActivity { assertEquals(listOf("saved-movie", "watch-show"), it.detailSelections); assertTrue(it.primarySelections.isEmpty()) }
        }
    }
    @Test fun recentMovieAndShowCardsUseDetailsAndKeepTheirRowReturn() {
        launch().use { s ->
            row(3); select("Details recent-movie"); backCard(); native(KeyEvent.KEYCODE_DPAD_DOWN)
            select("Details recent-show"); backCard()
            s.onActivity { assertEquals(listOf("recent-movie", "recent-show"), it.detailSelections); assertTrue(it.primarySelections.isEmpty()); assertEquals("recent-show", it.holder.selectedCardId) }
        }
    }
    @Test fun continueMovieAndEpisodeStayDirectAndHeroResumeRemainsDeliberate() {
        launch().use { s ->
            row(0); select("Primary continue-movie"); backCard(); native(KeyEvent.KEYCODE_DPAD_RIGHT)
            select("Primary continue-show"); backCard()
            native(KeyEvent.KEYCODE_DPAD_UP); focused("home_hero_primary"); select("Primary continue-show")
            native(KeyEvent.KEYCODE_BACK); focused("home_hero_primary")
            s.onActivity { assertEquals(listOf("continue-movie", "continue-show", "continue-show"), it.primarySelections); assertTrue(it.detailSelections.isEmpty()) }

        }
    }
    @Test fun discoveryHeroPlayAndShowEpisodesStayExplicitActions() {
        launch(onlyDiscovery = true).use { s ->
            waitTag("home_hero_primary"); focused("top_nav_item_search"); native(KeyEvent.KEYCODE_DPAD_DOWN)
            focused("home_hero_primary"); compose.onNodeWithTag("home_hero_primary").assertTextEquals("Play")
            select("Primary discover-movie"); native(KeyEvent.KEYCODE_BACK); focused("home_hero_primary")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_home_card"); native(KeyEvent.KEYCODE_DPAD_RIGHT)
            native(KeyEvent.KEYCODE_DPAD_UP); focused("home_hero_primary"); compose.onNodeWithTag("home_hero_primary").assertTextEquals("Episodes")
            select("Primary discover-show")
            s.onActivity { assertEquals(listOf("discover-movie", "discover-show"), it.primarySelections); assertTrue(it.detailSelections.isEmpty()) }
        }
    }
}

