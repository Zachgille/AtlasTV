package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.openGridOptions

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in retained-library navigation only. No sync/video/watchlist/personal preference fixtures. */
class MovieCategoryRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun assertPaintedBack() {
        val bounds = compose.onNodeWithTag("movie_categories_back").fetchSemanticsNode().boundsInRoot
        Thread.sleep(300)
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        var bright = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val color = capture.getPixel(x, y)
            if (android.graphics.Color.alpha(color) > 180 && android.graphics.Color.red(color) > 180 && android.graphics.Color.green(color) > 180 && android.graphics.Color.blue(color) > 180) bright++
        }
        File(compose.activity.cacheDir, "movie-categories-render-checked.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(compose.activity.cacheDir, "category-render-proof.txt").writeText("bounds=$bounds; visible bright pixels=$bright; frame=${capture.width}x${capture.height}")
        capture.recycle()
        assertTrue("Actual Back text must remain painted after deep category return; bounds=$bounds; bright pixels=$bright", bright > 30)
    }
    @Test fun retainedCategoriesBeyondTheRowCapAndDeepLastMovieKeepExactReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_movies")
        val dao = AtlasDatabaseProvider.database(compose.activity).safeCatalogDao()
        val categories = runBlocking(Dispatchers.IO) { RoomMovieCategoriesSource(dao).load() }
        assertTrue(categories.size > 8)
        compose.onNodeWithTag("top_nav_item_movies").performClick(); waitFor("movies_categories_open")
        compose.onNodeWithTag("movies_categories_open").performClick(); waitFor("movie_categories_list")
        val index = categories.indices.maxBy { categories[it].itemCount }
        val selected = categories[index]; assertTrue(selected.itemCount > 200)
        compose.onNodeWithTag("movie_categories_list").performScrollToIndex(index)
        compose.onNodeWithTag("movie_category_choice_$index").performClick(); focused("movie_category_tile_0")
        compose.onNodeWithTag("movie_category_count").assertDoesNotExist()
        val deep = minOf(1001, selected.itemCount - 2)
        val expected = runBlocking(Dispatchers.IO) { dao.movieCategoryPage(selected.id, deep, 1).single() }
        compose.onNodeWithTag("movie_category_grid").performScrollToIndex(deep)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("movie_category_tile_$deep") and hasContentDescription(expected.title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("movie_category_tile_$deep").performClick(); waitFor("movie_detail_title")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(expected.title))
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_category_tile_$deep")
        compose.openGridOptions("movie_category"); compose.onNodeWithTag("movie_category_last").performClick()
        val lastIndex = selected.itemCount - 1
        val last = runBlocking(Dispatchers.IO) { dao.movieCategoryPage(selected.id, lastIndex, 1).single() }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("movie_category_tile_$lastIndex") and hasContentDescription(last.title)).fetchSemanticsNodes().isNotEmpty() }
        focused("movie_category_tile_$lastIndex"); native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_detail_title")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(last.title))
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_category_tile_$lastIndex")
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_choice_$index")
        assertPaintedBack()
        // This category was not among the eight row summaries, regardless of where the largest fell.
        val extraIndex = categories.lastIndex
        compose.onNodeWithTag("movie_categories_list").performScrollToIndex(extraIndex)
        compose.onNodeWithTag("movie_category_choice_$extraIndex").performClick(); focused("movie_category_tile_0")
        val extra = categories[extraIndex]
        compose.onNodeWithTag("movie_category_count").assertDoesNotExist()
        native(KeyEvent.KEYCODE_BACK); focused("movie_category_choice_$extraIndex")
        assertPaintedBack()
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "movie-categories-refined.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }; capture.recycle()
        native(KeyEvent.KEYCODE_BACK); focused("movies_categories_open")
        println("Real Movie categories: ${categories.size} categories; selected category ${selected.itemCount} titles; deep/last and beyond-row-cap return passed")
    }
}
