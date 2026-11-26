package com.atlas.atlastv.ui.movies

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.MovieDetailsCacheCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MovieDetailsCacheUiConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(stale: Boolean = false, blocked: Boolean = false, noCache: Boolean = false, unavailable: Boolean = false) =
        ActivityScenario.launch<MovieDetailsCacheCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), MovieDetailsCacheCheckActivity::class.java)
            .putExtra("stale", stale).putExtra("blocked", blocked).putExtra("no_cache", noCache).putExtra("unavailable", unavailable))
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun info(scenario: ActivityScenario<MovieDetailsCacheCheckActivity>, predicate: (MovieInfoState) -> Boolean) {
        compose.waitUntil(8_000) { var matches = false; scenario.onActivity { matches = predicate(it.holder.movieInfoState) }; matches }
    }
    private fun downloads(scenario: ActivityScenario<MovieDetailsCacheCheckActivity>, expected: Int) {
        compose.waitUntil(8_000) { var count = 0; scenario.onActivity { count = it.downloads }; count == expected }
    }
    @Test fun freshSavedDetailsReopenFromDiskWithoutProviderAndPlayAndReturnRemainUsable() {
        launch(blocked = true).use { scenario ->
            waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { assertEquals(0, it.downloads); assertTrue((it.holder.movieInfoState as MovieInfoState.Ready).saved) }
            compose.onNodeWithTag("movie_detail_year").assertTextEquals("2020 · Drama · 2h 28m")
            compose.onNodeWithTag("movie_detail_saved_info").assertIsDisplayed()
            scenario.recreate(); waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { assertEquals(0, it.downloads) }
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_cache_destination")
            scenario.onActivity { assertEquals(1, it.openedMovieId); assertEquals(0, it.downloads) }
            native(KeyEvent.KEYCODE_BACK); waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
        }
    }
    @Test fun staleSavedInformationStaysVisibleWhileProviderWaitsAndFailureKeepsWatchlistFocus() {
        launch(stale = true, blocked = true).use { scenario ->
            waitFor("movie_detail_synopsis"); focused("movie_detail_play"); downloads(scenario, 1)
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_watchlist")
            scenario.onActivity { assertTrue((it.holder.movieInfoState as MovieInfoState.Ready).refreshing); it.release.complete(Unit) }
            info(scenario) { (it as? MovieInfoState.Ready)?.refreshFailed == true }
            focused("movie_detail_watchlist")
            compose.onNodeWithTag("movie_detail_synopsis").assertTextEquals("A saved synopsis.")
            compose.onNodeWithTag("movie_detail_year").assertTextContains("Refresh failed", substring = true)
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_back")
        }
    }
    @Test fun explicitNativeRefreshKeepsFocusAndLastGoodFieldsThenEmptyResponseRetainsThem() {
        launch().use { scenario ->
            waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { it.failRefresh = false }
            native(KeyEvent.KEYCODE_DPAD_UP); focused("movie_detail_refresh"); native(KeyEvent.KEYCODE_DPAD_CENTER)
            info(scenario) { (it as? MovieInfoState.Ready)?.metadata?.synopsis == "A refreshed synopsis." }
            focused("movie_detail_refresh"); compose.onNodeWithTag("movie_detail_year").assertTextEquals("2020 · Drama · 2h 28m")
            scenario.onActivity { assertEquals(1, it.downloads); it.emptyRefresh = true }
            native(KeyEvent.KEYCODE_DPAD_CENTER); info(scenario) { (it as? MovieInfoState.Ready)?.refreshFailed == true }
            focused("movie_detail_refresh"); compose.onNodeWithTag("movie_detail_synopsis").assertTextEquals("A refreshed synopsis.")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_UP); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("movie_detail_back")
        }
    }
    @Test fun repeatedActivationWhileRefreshingStartsOneRequestAndCannotBlockPlayOrBack() {
        launch(blocked = true).use { scenario ->
            waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_UP); native(KeyEvent.KEYCODE_DPAD_CENTER); downloads(scenario, 1)
            repeat(4) { native(KeyEvent.KEYCODE_DPAD_CENTER) }
            scenario.onActivity { assertEquals(1, it.downloads) }
            focused("movie_detail_refresh"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_cache_destination")
            native(KeyEvent.KEYCODE_BACK); waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { assertEquals(1, it.downloads) }
        }
    }
    @Test fun missingCacheHasOptionalFailureAndNativeRetryPersistsInformationForReopening() {
        launch(noCache = true).use { scenario ->
            waitFor("movie_detail_play"); info(scenario) { it is MovieInfoState.Unavailable }
            compose.onNodeWithTag("movie_detail_synopsis").assertDoesNotExist(); focused("movie_detail_play")
            scenario.onActivity { it.failRefresh = false }
            native(KeyEvent.KEYCODE_DPAD_UP); focused("movie_detail_refresh"); native(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("movie_detail_synopsis"); focused("movie_detail_refresh"); downloads(scenario, 2)
            scenario.recreate(); waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { assertEquals(0, it.downloads); assertTrue((it.holder.movieInfoState as MovieInfoState.Ready).saved) }
            compose.onNodeWithTag("movie_detail_synopsis").assertTextEquals("A refreshed synopsis.")
        }
    }
    @Test fun accountChangeImmediatelyHidesOldMetadataAndDoesNotReuseItsCache() {
        launch(blocked = true).use { scenario ->
            waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { it.activeAccount = it.activeAccount.copy(password = "another-account") }
            downloads(scenario, 1); compose.onNodeWithTag("movie_detail_synopsis").assertDoesNotExist()
            focused("movie_detail_play")
            scenario.onActivity { it.release.complete(Unit) }; info(scenario) { it is MovieInfoState.Unavailable }
            compose.onNodeWithTag("movie_detail_synopsis").assertDoesNotExist(); focused("movie_detail_play")
        }
    }
    @Test fun unavailableTitleCannotReadSavedMetadataOrExposeRefreshAndPlay() {
        launch(unavailable = true).use { scenario ->
            waitFor("movie_detail_watchlist"); focused("movie_detail_watchlist")
            compose.onNodeWithTag("movie_detail_play").assertDoesNotExist(); compose.onNodeWithTag("movie_detail_refresh").assertDoesNotExist()
            scenario.onActivity { assertEquals(0, it.downloads); assertEquals(MovieInfoState.Empty, it.holder.movieInfoState) }
            compose.onNodeWithTag("movie_detail_synopsis").assertTextContains("No longer in your library", substring = true)
        }
    }
    @Test fun longSavedTitleStillFitsRefreshAndEveryPrimaryActionWithinTheHero() {
        launch().use { scenario ->
            waitFor("movie_detail_synopsis"); focused("movie_detail_play")
            scenario.onActivity { it.movieTitle = "An Extremely Long Movie Title With Many Words And Another Subtitle And More Words (2020)" }
            compose.waitForIdle()
            val hero = compose.onNodeWithTag("movie_detail_hero").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("movie_detail_refresh", "movie_detail_play", "movie_detail_watchlist", "movie_detail_back")) {
                compose.onNodeWithTag(tag).assertIsDisplayed()
                val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(tag, bounds.left >= hero.left && bounds.right <= hero.right && bounds.top >= hero.top && bounds.bottom <= hero.bottom)
            }
            val synopsis = compose.onNodeWithTag("movie_detail_synopsis").fetchSemanticsNode().boundsInRoot
            val play = compose.onNodeWithTag("movie_detail_play").fetchSemanticsNode().boundsInRoot
            assertTrue(synopsis.bottom <= play.top); focused("movie_detail_play")
        }
    }
}
