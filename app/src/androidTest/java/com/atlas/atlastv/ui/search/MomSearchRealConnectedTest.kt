package com.atlas.atlastv.ui.search

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy
import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Retained catalog. Only the separately opted-in save test may add the user-requested Mom Show. */
class MomSearchRealConnectedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { ui.waitUntil(45_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun key(code: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); ui.waitForIdle() }
    private fun database() = AtlasDatabaseProvider.database(ui.activity)
    private fun optIn() { assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true") }
    private fun openMom() {
        val source = RoomUnifiedSearchSource(SafeCatalogRepository(database().safeCatalogDao()))
        val expected = runBlocking(Dispatchers.IO) { source.search("Mom") }
        val show = (expected.rows.single { it.id == "unified_search_shows" }.items.first() as UnifiedSearchItem.Show).series
        assertEquals(4107,show.seriesId); assertEquals("Mom",show.name); assertEquals("ENGLISH SERIES",show.categoryName)
        assertFalse(EnglishCatalogPolicy.excludesTitle(show.name)); assertFalse(PlaylistCategoryFilter().excludes(show.categoryName))
        val window = runBlocking(Dispatchers.IO) { RoomSearchGridSource(database().safeCatalogDao(),"series","Mom").load(0,null) }
        assertEquals(4107,searchResultId(window.items.first()))
        waitFor("top_nav_item_search"); ui.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field"); ui.onNodeWithTag("unified_search_field").performTextInput("Mom")
        val movie = "focused_unified_search_result_${expected.rows.first { it.id == "unified_search_movies" }.items.first().id}"
        waitFor("unified_search_movies")
        ui.runOnIdle { ui.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        ui.waitUntil(8_000) { ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitFor(movie); ui.onNodeWithTag(movie).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitFor("focused_unified_search_result_show-4107")
        ui.onNodeWithTag("focused_unified_search_result_show-4107").assertIsFocused().assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_detail_title")
        ui.onNodeWithTag("series_detail_title").assertTextEquals("Mom")
        waitFor("series_detail_watchlist")
    }
    @Test fun momIsFirstShowMatchAndNativeDetailsReturnToItsExactSearchResultWithoutPersonalEdits() {
        optIn()
        val db=database()
        val before=runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items() to db.watchProgressDao().records() }
        val account=File(ui.activity.filesDir,"provider_settings.json").readBytes()
        openMom()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ui.activity.cacheDir,"mom-details-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        ui.onNodeWithTag("series_back").performClick(); waitFor("focused_unified_search_result_show-4107")
        ui.onNodeWithTag("focused_unified_search_result_show-4107").assertIsFocused()
        assertEquals(before,runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items() to db.watchProgressDao().records() })
        assertArrayEquals(account,File(ui.activity.filesDir,"provider_settings.json").readBytes())
    }
    @Test fun explicitlyAuthorizedMomSavePreservesOtherTitlesAndReopensInMyListAndShowsWatchlist() {
        optIn()
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.saveMomToWatchlist") == "true")
        val db=database()
        val before=runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items() }
        val movieBefore=runBlocking(Dispatchers.IO) { db.movieWatchlistDao().items() }
        val progressBefore=runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }
        val account=File(ui.activity.filesDir,"provider_settings.json").readBytes()
        openMom()
        if (before.none { it.seriesId == 4107 }) {
            ui.onNodeWithTag("series_detail_watchlist").assertTextEquals("+ Watchlist").performClick()
        }
        ui.waitUntil(15_000) { runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items().any { it.seriesId == 4107 } } }
        ui.waitUntil(15_000) { ui.onAllNodes(hasTestTag("series_detail_watchlist") and hasText("− Watchlist")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("series_detail_watchlist").assertTextEquals("− Watchlist")
        val after=runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items() }
        assertEquals(before.filter { it.seriesId != 4107 },after.filter { it.seriesId != 4107 })
        assertEquals(before.size+(if (before.none { it.seriesId==4107 }) 1 else 0),after.size)
        assertEquals("Mom",after.single { it.seriesId==4107 }.title)
        assertEquals(movieBefore,runBlocking(Dispatchers.IO) { db.movieWatchlistDao().items() })
        assertEquals(progressBefore,runBlocking(Dispatchers.IO) { db.watchProgressDao().records() })
        assertArrayEquals(account,File(ui.activity.filesDir,"provider_settings.json").readBytes())
        ui.activityRule.scenario.recreate()
        waitFor("top_nav_item_my_list"); ui.onNodeWithTag("top_nav_item_my_list").performClick(); waitFor("my_list_screen")
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Mom").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Mom").assertIsDisplayed()
        ui.onNodeWithTag("my_list_back").performClick(); waitFor("top_nav_item_shows")
        ui.onNodeWithTag("top_nav_item_shows").performClick(); waitFor("series_watchlist_open")
        ui.onNodeWithTag("series_watchlist_open").performClick(); waitFor("show_watchlist_screen")
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Mom").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Mom").assertIsDisplayed()
        assertTrue(runBlocking(Dispatchers.IO) { AtlasDatabaseProvider.database(ui.activity).seriesWatchlistDao().items().any { it.seriesId==4107 } })
        println("Authorized Mom Show 4107 saved and reopened; every other save/progress/account preserved")
    }
}
