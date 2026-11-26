package com.atlas.atlastv.ui.home

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in normal Home/MainActivity routing; no personal fixture, refresh, membership or video actions. */
class HomeDiscoveryRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun focusedCardIn(row: String) = compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag(row))).fetchSemanticsNodes().isNotEmpty()
    private fun details(title: String, movie: Boolean) {
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag(if (movie) "movie_detail_title" else "series_detail_title")
        compose.onNodeWithTag(if (movie) "movie_detail_title" else "series_detail_title").assertTextEquals(title)
        if (movie) { waitTag("movie_detail_play"); compose.onNodeWithTag("movie_detail_play").assertIsDisplayed() }
        compose.onAllNodesWithTag("live_tv_player_screen").assertCountEquals(0)
        compose.onAllNodesWithTag("playback_full_screen_surface").assertCountEquals(0)
        native(KeyEvent.KEYCODE_BACK); waitTag("hero_title")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("focused_home_card").assertIsFocused(); true }.getOrDefault(false) }
        assertTrue(focusedCardIn("popular_row")); assertEquals(title, text("hero_title"))
        fun selected(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("focused_home_card")), useUnmergedTree = true)
        assertHomeFooterVisible(selected("focused_poster_card"), compose.onNodeWithTag("home_rows_viewport"), selected("poster_card_footer"), selected("poster_card_title"))
    }
    @Test fun actualDiscoveryMovieShowAndDeepMovieOpenDetailsWithoutChangingPersonalState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitTag("home_hero_primary")
        val db = AtlasDatabaseProvider.database(compose.activity)
        val beforeProgress = runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }
        val account = File(compose.activity.filesDir, "provider_settings.json"); val beforeAccount = account.readBytes()
        val counts = runBlocking(Dispatchers.IO) { db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie) to db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series) }
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_DOWN)
        var moves = 0
        while (!focusedCardIn("popular_row") && moves++ < 5) native(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Discover is reachable by remote", focusedCardIn("popular_row"))
        val movie = text("hero_title"); details(movie, true)
        native(KeyEvent.KEYCODE_DPAD_RIGHT); val show = text("hero_title"); details(show, false)
        repeat(9) { native(KeyEvent.KEYCODE_DPAD_RIGHT) }; val deepMovie = text("hero_title"); details(deepMovie, true)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "home-footer-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        assertEquals(beforeProgress, runBlocking(Dispatchers.IO) { db.watchProgressDao().records() })
        assertArrayEquals(beforeAccount, account.readBytes())
        assertEquals(counts, runBlocking(Dispatchers.IO) { db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie) to db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series) })
    }
}
