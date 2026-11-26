package com.atlas.atlastv.ui.series

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.ShowDetailsCacheCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShowDetailsCacheUiConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(stale: Boolean = false, blocked: Boolean = false, noCache: Boolean = false) =
        ActivityScenario.launch<ShowDetailsCacheCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), ShowDetailsCacheCheckActivity::class.java)
            .putExtra("stale", stale).putExtra("blocked", blocked).putExtra("no_cache", noCache))
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun details(activity: ShowDetailsCacheCheckActivity) = (activity.holder.state as SeriesCatalogState.Ready).episodeState as SeriesEpisodeState.Ready
    private fun downloads(scenario: ActivityScenario<ShowDetailsCacheCheckActivity>, expected: Int) {
        compose.waitUntil(8_000) { var count = 0; scenario.onActivity { count = it.downloads }; count == expected }
    }
    @Test fun freshSavedDetailsReopenFromDiskWithoutProviderAndEverySeasonAndDeepEpisodeRemainSelectable() {
        launch(blocked = true).use { scenario ->
            waitFor("series_episode_list"); focused("series_detail_play_episode")
            scenario.onActivity { assertEquals(0, it.downloads); assertEquals(1536, details(it).episodes.size) }
            scenario.recreate(); waitFor("series_episode_list"); focused("series_detail_play_episode")
            scenario.onActivity { assertEquals(0, it.downloads); assertTrue(details(it).saved) }
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
            repeat(23) { index -> native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_${index + 2}") }
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_selected_episode")
            repeat(63) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
            scenario.onActivity { assertEquals(1536, (it.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("show_cache_destination")
            scenario.onActivity { assertEquals(1536, it.openedEpisodeId); assertEquals(0, it.downloads) }
            native(KeyEvent.KEYCODE_BACK); waitFor("series_episode_list"); focused("series_detail_play_episode")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
            compose.onNode(hasTestTag("series_episode_name") and hasAnyAncestor(hasTestTag("series_selected_episode")), useUnmergedTree = true).assertTextEquals("Episode 64")
        }
    }
    @Test fun staleSavedListCanBeNavigatedBeforeProviderRespondsAndFailureCannotMoveSelectionOrFocus() {
        launch(stale = true, blocked = true).use { scenario ->
            waitFor("series_episode_list"); focused("series_detail_play_episode")
            downloads(scenario, 1); scenario.onActivity { assertTrue(details(it).refreshing) }
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
            scenario.onActivity { assertEquals(2, (it.holder.state as SeriesCatalogState.Ready).selectedEpisodeId); it.release.complete(Unit) }
            compose.waitUntil(8_000) { var failed = false; scenario.onActivity { failed = details(it).refreshFailed }; failed }
            focused("series_selected_episode")
            scenario.onActivity { assertEquals(2, (it.holder.state as SeriesCatalogState.Ready).selectedEpisodeId); assertEquals(1536, details(it).episodes.size) }
            compose.onNodeWithTag("series_detail_seasons").assertTextContains("Refresh failed", substring = true)
        }
    }
    @Test fun explicitRemoteRefreshRetainsFocusAndGoodMetadataThenEmptyResponseKeepsCompleteList() {
        launch().use { scenario ->
            waitFor("series_episode_list"); focused("series_detail_play_episode")
            scenario.onActivity { it.failRefresh = false }
            native(KeyEvent.KEYCODE_DPAD_UP); focused("series_detail_refresh_episodes"); native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8_000) { var count = 0; scenario.onActivity { count = details(it).episodes.size }; count == 1537 }
            focused("series_detail_refresh_episodes")
            compose.onNodeWithTag("series_detail_synopsis").assertTextEquals("A saved synopsis.")
            scenario.onActivity { assertEquals(1, it.downloads); it.emptyRefresh = true }
            native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8_000) { var failed = false; scenario.onActivity { failed = details(it).refreshFailed }; failed }
            scenario.onActivity { assertEquals(1537, details(it).episodes.size); assertEquals(2, it.downloads) }
            focused("series_detail_refresh_episodes"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_detail_play_episode")
            val second = compose.onNodeWithTag("series_episode_2").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("series_episode_list").fetchSemanticsNode().boundsInRoot
            assertTrue("Two complete episode rows must fit", second.bottom <= viewport.bottom)
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
            lateinit var capture: File
            scenario.onActivity { capture = File(it.cacheDir, "show-cache-controlled.png") }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            capture.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    @Test fun repeatedRefreshWhileWaitingStartsOneRequestAndReturningDownStillReachesEpisodes() {
        launch(blocked = true).use { scenario ->
            waitFor("series_episode_list"); focused("series_detail_play_episode")
            native(KeyEvent.KEYCODE_DPAD_UP); focused("series_detail_refresh_episodes"); native(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(4) { native(KeyEvent.KEYCODE_DPAD_CENTER) }
            downloads(scenario, 1); scenario.onActivity { it.release.complete(Unit) }
            compose.waitUntil(8_000) { var failed = false; scenario.onActivity { failed = details(it).refreshFailed }; failed }
            focused("series_detail_refresh_episodes"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_detail_play_episode")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        }
    }
    @Test fun missingCacheOffersUsableRetryAndSuccessfulRetryLoadsAndPersistsEpisodes() {
        launch(noCache = true).use { scenario ->
            waitFor("series_episode_retry"); compose.onNodeWithTag("series_episode_retry").performSemanticsAction(SemanticsActions.RequestFocus)
            scenario.onActivity { assertEquals(1, it.downloads); it.failRefresh = false }
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_episode_list"); focused("series_detail_play_episode")
            scenario.onActivity { assertEquals(1537, details(it).episodes.size); assertEquals(2, it.downloads) }
            scenario.recreate(); waitFor("series_episode_list")
            scenario.onActivity { assertEquals(0, it.downloads); assertEquals(1537, details(it).episodes.size); assertTrue(details(it).saved) }
        }
    }
    @Test fun removedFocusedEpisodeRestoresAnExistingRowWithoutInterruptingRemoteNavigation() {
        launch(stale = true, blocked = true).use { scenario ->
            waitFor("series_episode_list"); focused("series_detail_play_episode"); downloads(scenario, 1)
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
            scenario.onActivity { it.failRefresh = false; it.removedEpisodeId = 2; it.release.complete(Unit) }
            compose.waitUntil(8_000) { var updated = false; scenario.onActivity { updated = !details(it).saved && !details(it).refreshing }; updated }
            scenario.onActivity { assertEquals(1, (it.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
            focused("series_selected_episode"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
            scenario.onActivity { assertEquals(3, (it.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
        }
    }
    @Test fun removedFocusedSeasonRestoresAnExistingSeasonWithoutStealingHeaderFocus() {
        launch(stale = true, blocked = true).use { scenario ->
            waitFor("series_episode_list"); focused("series_detail_play_episode"); downloads(scenario, 1)
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_2")
            scenario.onActivity { it.failRefresh = false; it.removedSeason = 2; it.release.complete(Unit) }
            compose.waitUntil(8_000) { var updated = false; scenario.onActivity { updated = !details(it).saved && !details(it).refreshing }; updated }
            focused("series_season_1"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_3")
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_selected_episode")
            scenario.onActivity { assertEquals(3, (it.holder.state as SeriesCatalogState.Ready).selectedSeasonNumber) }
        }
    }
}
