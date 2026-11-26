package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.browse.openGridOptions

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SeriesCategoryCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SeriesCategoryConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SeriesCategoryCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun seeAll(row: Int = 0) {
        waitFor("series_row_page"); compose.onNodeWithTag("series_row_page").performScrollToIndex(row)
        compose.onNodeWithTag("show_category_see_all_category-$row").performClick()
        waitFor("show_category_screen")
    }
    private fun picker() { waitFor("series_categories_open"); compose.onNodeWithTag("series_categories_open").performClick(); waitFor("show_categories_screen") }

    @Test fun nativeRemoteReachesCategoriesBeyondThePreviewCapAndSingletonDetailsReturnExactly() {
        waitFor("series_categories_open"); focused("series_search_open")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_categories_open"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_category_choice_0")
        for (index in 10..90 step 10) { native(KeyEvent.KEYCODE_PAGE_DOWN); focused("show_category_choice_$index") }
        native(KeyEvent.KEYCODE_PAGE_DOWN); focused("show_category_choice_99")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("show_category_tile_0")
        compose.onNodeWithTag("show_category_count").assertDoesNotExist(); assertEquals(1, compose.activity.browser.grid!!.state.value.total)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_detail_play_episode")
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 99 1")
        compose.onNodeWithTag("series_back").performClick(); focused("show_category_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("show_category_choice_99")
        native(KeyEvent.KEYCODE_BACK); focused("series_categories_open")
    }
    @Test fun rowHeaderIsRemoteReachableAndDeepGridSurvivesPlaybackCompositionAndReturnsToSeeAll() {
        waitFor("series_row_page"); focused("series_search_open")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
        native(KeyEvent.KEYCODE_DPAD_UP); focused("show_category_see_all_category-0")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("show_category_tile_0")
        compose.openGridOptions("show_category"); compose.onNodeWithTag("show_category_last").performClick(); focused("show_category_tile_512")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_detail_play_episode")
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 0 513")
        compose.onNodeWithTag("series_detail_play_episode").performClick(); waitFor("category_check_playback_back")
        compose.onNodeWithTag("category_check_playback_back").performClick(); waitFor("series_back")
        compose.onNodeWithTag("series_back").performClick(); focused("show_category_tile_512")
        assertTrue(compose.activity.browser.grid!!.state.value.items.size <= 200)
        native(KeyEvent.KEYCODE_BACK); focused("show_category_see_all_category-0")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
    }
    @Test fun deepPreviewRowReturnsToItsOwnVisibleHeader() {
        seeAll(8); focused("show_category_tile_0"); native(KeyEvent.KEYCODE_BACK)
        focused("show_category_see_all_category-8")
        compose.onNodeWithTag("show_category_see_all_category-8").assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
    }
    @Test fun backDuringGridLoadingCancelsAndFailedGridOffersRemoteRetry() {
        compose.runOnIdle { compose.activity.gridsReleased = false }; seeAll()
        waitFor("show_category_loading"); focused("show_category_back"); native(KeyEvent.KEYCODE_BACK)
        focused("show_category_see_all_category-0")
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() >= 1 }
        compose.runOnIdle { compose.activity.gridsReleased = true; compose.activity.gridsFail = true }
        seeAll(); waitFor("show_category_error"); focused("show_category_back")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("show_category_retry")
        compose.runOnIdle { compose.activity.gridsFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_category_tile_0"); compose.onNodeWithTag("show_category_error").assertDoesNotExist()
    }
    @Test fun categoriesLoadingBackAndFailedReadRetryKeepNavigationUsable() {
        compose.runOnIdle { compose.activity.categoriesReleased = false }; picker()
        waitFor("show_categories_loading"); focused("show_categories_back"); native(KeyEvent.KEYCODE_BACK)
        focused("series_categories_open")
        compose.runOnIdle { compose.activity.categoriesReleased = true; compose.activity.categoriesFail = true }; picker()
        waitFor("show_categories_error"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("show_categories_retry")
        compose.runOnIdle { compose.activity.categoriesFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("show_category_choice_0"); compose.onNodeWithTag("show_categories_error").assertDoesNotExist()
    }
    @Test fun refreshedCategoryCanBecomeEmptyWithUsableBackInsteadOfOldGhostTitles() {
        seeAll(); focused("show_category_tile_0")
        compose.runOnIdle { compose.activity.categoryEmpty = true; compose.activity.shows.invalidateCachedCatalog() }
        waitFor("show_category_empty"); focused("show_category_back")
        compose.onNodeWithTag("show_category_empty").assertTextEquals("No shows remain in this category.")
        compose.onAllNodesWithTag("show_category_tile_0").assertCountEquals(0)
        native(KeyEvent.KEYCODE_BACK); focused("show_category_see_all_category-0")
    }
    @Test fun switchingCategoryDiscardsThePreviousWindowAndStartsInTheNewCategory() {
        seeAll(); focused("show_category_tile_0"); compose.openGridOptions("show_category"); compose.onNodeWithTag("show_category_last").performClick(); focused("show_category_tile_512")
        val old = compose.activity.browser.grid
        native(KeyEvent.KEYCODE_BACK); focused("show_category_see_all_category-0"); picker()
        compose.onNodeWithTag("show_categories_list").performScrollToIndex(2)
        compose.onNodeWithTag("show_category_choice_2").performClick(); focused("show_category_tile_0")
        assertNotSame(old, compose.activity.browser.grid)
        compose.onNodeWithTag("show_category_tile_0").assert(hasContentDescription("Show 2 1"))
        assertEquals("Category 002", compose.activity.browser.categoryName)
    }
    @Test fun refreshRemovingTheFocusedCategorySelectsAnExistingCategoryWithoutStrandingFocus() {
        picker(); focused("show_category_choice_0")
        compose.runOnIdle { compose.activity.removedCategory = "Category 000"; compose.activity.shows.invalidateCachedCatalog() }
        compose.waitUntil(10_000) { compose.activity.browser.categories.state.value.items.size == 99 }
        focused("show_category_choice_0")
        compose.onNodeWithTag("show_category_choice_0").assertContentDescriptionEquals("Category 001")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("show_category_tile_0")
        compose.onNodeWithTag("show_category_tile_0").assert(hasContentDescription("Show 1 1"))
    }
    @Test fun backTextStaysPaintedWhileACategoryIsFocusedAfterGridReturn() {
        picker(); focused("show_category_choice_0")
        compose.onNodeWithTag("show_categories_list").performScrollToIndex(99)
        compose.onNodeWithTag("show_category_choice_99").performClick(); focused("show_category_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("show_category_choice_99")
        compose.onNodeWithTag("show_categories_back").assertIsDisplayed().assertIsNotFocused()
        // Semantics alone miss an empty cached drawing layer. Check the real Onn frame for Back's text.
        Thread.sleep(300)
        val bounds = compose.onNodeWithTag("show_categories_back").fetchSemanticsNode().boundsInRoot
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        var bright = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 180 && android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 && android.graphics.Color.blue(pixel) > 180) bright++
        }
        if (bright <= 30) java.io.File(compose.activity.cacheDir, "category-back-paint-failure.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        assertTrue("Back text must be painted while unfocused; bright pixels=$bright", bright > 30)
    }
    @Test fun deepCategoryShowLoadsEverySeasonAndReturnsFromTheLastEpisodeWithoutLosingContext() {
        seeAll(); focused("show_category_tile_0")
        assertEquals(0, compose.activity.episodeRequests.get())
        compose.openGridOptions("show_category"); compose.onNodeWithTag("show_category_last").performClick(); focused("show_category_tile_512")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_episode_list")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_2")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_3")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_selected_episode")
        repeat(3) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
        val before = compose.activity.shows.state as SeriesCatalogState.Ready
        assertEquals(3, before.selectedSeasonNumber); assertEquals(51334, before.selectedEpisodeId)
        focused("series_selected_episode"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("category_check_playback_back"); compose.onNodeWithTag("category_check_playback_back").performClick()
        waitFor("series_detail_play_episode"); compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 0 513")
        val returned = compose.activity.shows.state as SeriesCatalogState.Ready
        assertEquals(3, returned.selectedSeasonNumber); assertEquals(51334, returned.selectedEpisodeId)
        assertEquals(1, compose.activity.episodeRequests.get())
        compose.onNodeWithTag("series_back").performClick(); focused("show_category_tile_512")
        native(KeyEvent.KEYCODE_BACK); focused("show_category_see_all_category-0")
    }
    @Test fun pendingCategoryShowEpisodesCancelOnBackAndRetryFailureReturnsToTheSameTile() {
        compose.runOnIdle { compose.activity.episodesReleased = false }
        seeAll(); focused("show_category_tile_0"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("series_detail_page")
        compose.waitUntil(8_000) { compose.activity.episodeRequests.get() == 1 }
        compose.onNodeWithTag("series_back").performClick(); focused("show_category_tile_0")
        compose.waitUntil(8_000) { compose.activity.cancelledEpisodes.get() == 1 }
        compose.runOnIdle { compose.activity.episodesReleased = true; compose.activity.episodesFail = true }
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_episode_retry")
        compose.runOnIdle { compose.activity.episodesFail = false }
        compose.onNodeWithTag("series_episode_retry").performClick(); waitFor("series_episode_list")
        assertEquals(3, compose.activity.episodeRequests.get())
        compose.onNodeWithTag("series_back").performClick(); focused("show_category_tile_0")
    }
}
