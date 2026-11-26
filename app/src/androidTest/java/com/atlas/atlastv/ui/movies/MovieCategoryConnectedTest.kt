package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.openGridOptions

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.MovieCategoryCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MovieCategoryConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MovieCategoryCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun seeAll(row: Int = 0) {
        waitFor("movies_row_page"); compose.onNodeWithTag("movies_row_page").performScrollToIndex(row)
        compose.onNodeWithTag("movie_category_see_all_category-$row").performClick()
        waitFor("movie_category_screen")
    }
    private fun picker() { waitFor("movies_categories_open"); compose.onNodeWithTag("movies_categories_open").performClick(); waitFor("movie_categories_screen") }

    @Test fun nativeRemoteReachesCategoriesBeyondThePreviewCapAndSingletonDetailsReturnExactly() {
        waitFor("movies_categories_open"); focused("movies_search_open")
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movies_categories_open"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("movie_category_choice_0")
        for (index in 10..90 step 10) { native(KeyEvent.KEYCODE_PAGE_DOWN); focused("movie_category_choice_$index") }
        native(KeyEvent.KEYCODE_PAGE_DOWN); focused("movie_category_choice_99")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_category_tile_0")
        compose.onNodeWithTag("movie_category_count").assertDoesNotExist(); assertEquals(1, compose.activity.browser.grid!!.state.value.total)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_detail_play")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 99 1")
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_category_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_choice_99")
        native(KeyEvent.KEYCODE_BACK); focused("movies_categories_open")
    }
    @Test fun rowHeaderIsRemoteReachableAndDeepGridSurvivesPlaybackCompositionAndReturnsToSeeAll() {
        waitFor("movies_row_page"); focused("movies_search_open")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
        native(KeyEvent.KEYCODE_DPAD_UP); focused("movie_category_see_all_category-0")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_category_tile_0")
        compose.openGridOptions("movie_category"); compose.onNodeWithTag("movie_category_last").performClick(); focused("movie_category_tile_512")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_detail_play")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 0 513")
        compose.onNodeWithTag("movie_detail_play").performClick(); waitFor("category_check_playback_back")
        compose.onNodeWithTag("category_check_playback_back").performClick(); waitFor("movie_detail_back")
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_category_tile_512")
        assertTrue(compose.activity.browser.grid!!.state.value.items.size <= 200)
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_see_all_category-0")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
    }
    @Test fun deepPreviewRowReturnsToItsOwnVisibleHeader() {
        seeAll(8); focused("movie_category_tile_0"); native(KeyEvent.KEYCODE_BACK)
        focused("movie_category_see_all_category-8")
        compose.onNodeWithTag("movie_category_see_all_category-8").assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
    }
    @Test fun backDuringGridLoadingCancelsAndFailedGridOffersRemoteRetry() {
        compose.runOnIdle { compose.activity.gridsReleased = false }; seeAll()
        waitFor("movie_category_loading"); focused("movie_category_back"); native(KeyEvent.KEYCODE_BACK)
        focused("movie_category_see_all_category-0")
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() >= 1 }
        compose.runOnIdle { compose.activity.gridsReleased = true; compose.activity.gridsFail = true }
        seeAll(); waitFor("movie_category_error"); focused("movie_category_back")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_category_retry")
        compose.runOnIdle { compose.activity.gridsFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("movie_category_tile_0"); compose.onNodeWithTag("movie_category_error").assertDoesNotExist()
    }
    @Test fun categoriesLoadingBackAndFailedReadRetryKeepNavigationUsable() {
        compose.runOnIdle { compose.activity.categoriesReleased = false }; picker()
        waitFor("movie_categories_loading"); focused("movie_categories_back"); native(KeyEvent.KEYCODE_BACK)
        focused("movies_categories_open")
        compose.runOnIdle { compose.activity.categoriesReleased = true; compose.activity.categoriesFail = true }; picker()
        waitFor("movie_categories_error"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_categories_retry")
        compose.runOnIdle { compose.activity.categoriesFail = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("movie_category_choice_0"); compose.onNodeWithTag("movie_categories_error").assertDoesNotExist()
    }
    @Test fun refreshedCategoryCanBecomeEmptyWithUsableBackInsteadOfOldGhostTitles() {
        seeAll(); focused("movie_category_tile_0")
        compose.runOnIdle { compose.activity.categoryEmpty = true; compose.activity.movies.invalidateCachedCatalog() }
        waitFor("movie_category_empty"); focused("movie_category_back")
        compose.onNodeWithTag("movie_category_empty").assertTextEquals("No movies remain in this category.")
        compose.onAllNodesWithTag("movie_category_tile_0").assertCountEquals(0)
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_see_all_category-0")
    }
    @Test fun switchingCategoryDiscardsThePreviousWindowAndStartsInTheNewCategory() {
        seeAll(); focused("movie_category_tile_0"); compose.openGridOptions("movie_category"); compose.onNodeWithTag("movie_category_last").performClick(); focused("movie_category_tile_512")
        val old = compose.activity.browser.grid
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_see_all_category-0"); picker()
        compose.onNodeWithTag("movie_categories_list").performScrollToIndex(2)
        compose.onNodeWithTag("movie_category_choice_2").performClick(); focused("movie_category_tile_0")
        assertNotSame(old, compose.activity.browser.grid)
        compose.onNodeWithTag("movie_category_tile_0").assert(hasContentDescription("Movie 2 1"))
        assertEquals("Category 002", compose.activity.browser.categoryName)
    }
    @Test fun refreshRemovingTheFocusedCategorySelectsAnExistingCategoryWithoutStrandingFocus() {
        picker(); focused("movie_category_choice_0")
        compose.runOnIdle { compose.activity.removedCategory = "Category 000"; compose.activity.movies.invalidateCachedCatalog() }
        compose.waitUntil(10_000) { compose.activity.browser.categories.state.value.items.size == 99 }
        focused("movie_category_choice_0")
        compose.onNodeWithTag("movie_category_choice_0").assertContentDescriptionEquals("Category 001")
        native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_category_tile_0")
        compose.onNodeWithTag("movie_category_tile_0").assert(hasContentDescription("Movie 1 1"))
    }
    @Test fun backTextStaysPaintedWhileACategoryIsFocusedAfterGridReturn() {
        picker(); focused("movie_category_choice_0")
        compose.onNodeWithTag("movie_categories_list").performScrollToIndex(99)
        compose.onNodeWithTag("movie_category_choice_99").performClick(); focused("movie_category_tile_0")
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_choice_99")
        compose.onNodeWithTag("movie_categories_back").assertIsDisplayed().assertIsNotFocused()
        // Semantics alone miss an empty cached drawing layer. Check the real Onn frame for Back's text.
        Thread.sleep(300)
        val bounds = compose.onNodeWithTag("movie_categories_back").fetchSemanticsNode().boundsInRoot
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
}
