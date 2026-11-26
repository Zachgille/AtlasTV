package com.atlas.atlastv.ui.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.HomeHeroCacheCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeHeroCacheUiConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(stale: Boolean = false, blocked: Boolean = false, noCache: Boolean = false): ActivityScenario<HomeHeroCacheCheckActivity> {
        val i = Intent(InstrumentationRegistry.getInstrumentation().targetContext, HomeHeroCacheCheckActivity::class.java)
            .putExtra("stale", stale).putExtra("blocked", blocked).putExtra("no_cache", noCache)
        return ActivityScenario.launch(i)
    }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false } } }; compose.onNodeWithTag(tag).assertIsFocused() }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun row() { focused("top_nav_item_search"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_home_card") }
    private fun refresh() { native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("home_hero_details"); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("catalog_refresh_prompt"); native(KeyEvent.KEYCODE_DPAD_CENTER) }
    private fun calls(s: ActivityScenario<HomeHeroCacheCheckActivity>, expected: Int) { compose.waitUntil(8_000) { var count = 0; s.onActivity { count = it.requests.size }; count == expected } }
    private fun state(s: ActivityScenario<HomeHeroCacheCheckActivity>, check: (HomeHeroState?) -> Boolean) { compose.waitUntil(8_000) { var result = false; s.onActivity { result = check(it.holder.heroState) }; result } }

    @Test fun freshDiskReopeningAndParentShowPreviewNeedNoProviderAndKeepExactCardReturn() {
        launch().use { s ->
            waitFor("hero_synopsis"); compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved movie-1."); compose.onNodeWithTag("hero_saved_info").assertIsDisplayed()
            row(); native(KeyEvent.KEYCODE_DPAD_RIGHT); waitFor("hero_synopsis"); compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
            compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved show-1.")
            native(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("home_cache_destination"); compose.onNodeWithTag("home_cache_destination").assertTextEquals("episode")
            native(KeyEvent.KEYCODE_BACK); focused("focused_home_card"); compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
            s.onActivity { assertTrue(it.requests.isEmpty()) }
            s.recreate(); waitFor("hero_synopsis"); focused("top_nav_item_search"); row(); native(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved show-1."); s.onActivity { assertTrue(it.requests.isEmpty()) }
        }
    }
    @Test fun staleSavedPreviewAppearsWhileProviderIsBlockedAndFailureCannotMoveCardFocus() {
        launch(stale = true, blocked = true).use { s ->
            waitFor("hero_synopsis"); calls(s, 1); compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved movie-1.")
            row(); focused("focused_home_card"); s.onActivity { it.release.complete(Unit) }
            state(s) { it?.fromSaved == true }; compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved movie-1."); focused("focused_home_card")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("home_cache_destination"); native(KeyEvent.KEYCODE_BACK); focused("focused_home_card")
            compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved movie-1.")
        }
    }
    @Test fun libraryRefreshBypassesFreshSnapshotAndPartialThenEmptyRepliesKeepFieldsAndRefreshFocus() {
        launch().use { s ->
            waitFor("hero_synopsis"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary")
            s.onActivity { it.failRefresh = false }; refresh(); calls(s, 1)
            state(s) { it?.metadata?.synopsis == "Updated movie-1." }; focused("catalog_refresh_prompt")
            compose.onNodeWithTag("hero_metadata").assertTextEquals("2020 · Drama · 2h 28m")
            s.onActivity { it.emptyRefresh = true }; native(KeyEvent.KEYCODE_DPAD_CENTER); calls(s, 2)
            state(s) { it?.fromSaved == true }; compose.onNodeWithTag("hero_synopsis").assertTextEquals("Updated movie-1."); focused("catalog_refresh_prompt")
            native(KeyEvent.KEYCODE_DPAD_LEFT); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("home_hero_primary")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("home_cache_destination"); native(KeyEvent.KEYCODE_BACK); focused("home_hero_primary")
        }
    }
    @Test fun noSavedPreviewNeverBlocksPrimaryActionAndRefreshRecoveryPersistsAcrossRecreation() {
        launch(noCache = true).use { s ->
            waitFor("home_hero_primary"); calls(s, 1); compose.onNodeWithTag("hero_synopsis").assertDoesNotExist()
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("home_cache_destination")
            native(KeyEvent.KEYCODE_BACK); focused("home_hero_primary"); s.onActivity { it.failRefresh = false }; refresh()
            state(s) { it?.metadata?.synopsis == "Updated movie-1." }; focused("catalog_refresh_prompt")
            s.recreate(); waitFor("hero_synopsis"); compose.onNodeWithTag("hero_saved_info").assertIsDisplayed()
            s.onActivity { assertTrue(it.requests.isEmpty()) }
        }
    }
    @Test fun accountChangeHidesSavedMetadataImmediatelyAndOldAccountCannotLeakIntoAnotherPreview() {
        launch(blocked = true).use { s ->
            waitFor("hero_synopsis"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary")
            s.onActivity { it.activeAccount = it.activeAccount.copy(password = "another") }
            calls(s, 1); compose.onNodeWithTag("hero_synopsis").assertDoesNotExist(); focused("home_hero_primary")
            s.onActivity { it.release.complete(Unit) }; compose.waitForIdle(); compose.onNodeWithTag("hero_synopsis").assertDoesNotExist()
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("home_cache_destination")
        }
    }
    @Test fun rapidMovieShowEpisodeNavigationRetainsOnlyCurrentParentPreviewAndSafeReturn() {
        launch(stale = true, blocked = true).use { s ->
            waitFor("hero_synopsis"); calls(s, 1); row(); native(KeyEvent.KEYCODE_DPAD_RIGHT)
            state(s) { it?.key == "show-1" && it.fromSaved }; native(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("hero_title").assertTextEquals("A Show"); focused("focused_home_card")
            s.onActivity { it.release.complete(Unit) }; compose.waitForIdle()
            compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved show-1."); focused("focused_home_card")
            native(KeyEvent.KEYCODE_DPAD_UP); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("home_hero_details")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("home_cache_destination"); native(KeyEvent.KEYCODE_BACK); focused("home_hero_details")
            compose.onNodeWithTag("hero_synopsis").assertTextEquals("Saved show-1.")
        }
    }
    @Test fun savedInformationLeavesAllActionsAndFirstRowVisibleWithLongCleanTitle() {
        launch().use { s ->
            waitFor("hero_synopsis"); s.onActivity { it.movieTitle = "An Extremely Long Movie Title With Many Words And A Subtitle And More Words (2020)"; it.holder.refresh() }
            compose.waitForIdle(); compose.onNodeWithTag("hero_title").assertTextEquals("An Extremely Long Movie Title With Many Words And A Subtitle And More Words")
            val hero = compose.onNodeWithTag("hero_area").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("home_hero_primary", "home_hero_details", "catalog_refresh_prompt")) {
                compose.onNodeWithTag(tag).assertIsDisplayed(); val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(tag, b.left >= hero.left && b.right <= hero.right && b.bottom <= hero.bottom)
            }
            assertTrue(compose.onNodeWithTag("continue_watching_row").fetchSemanticsNode().boundsInRoot.bottom <= compose.onNodeWithTag("home_screen").fetchSemanticsNode().boundsInRoot.bottom)
            assertTrue(compose.onNodeWithTag("hero_synopsis").fetchSemanticsNode().boundsInRoot.bottom <= compose.onNodeWithTag("home_hero_primary").fetchSemanticsNode().boundsInRoot.top)
            compose.onAllNodesWithText("Saved details").assertCountEquals(0); focused("top_nav_item_search")
        }
    }
}
