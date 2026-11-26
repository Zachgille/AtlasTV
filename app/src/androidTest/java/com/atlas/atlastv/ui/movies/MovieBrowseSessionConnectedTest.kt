package com.atlas.atlastv.ui.movies

import android.content.Intent
import android.os.Process
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.MovieBrowseSessionCheckActivity
import com.atlas.atlastv.ui.browse.openGridOptions
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MovieBrowseSessionConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun id() = requireNotNull(InstrumentationRegistry.getArguments().getString("check_id"))
    private fun launch(empty: Boolean = false, shelfCount: Int = 2) = ActivityScenario.launch<MovieBrowseSessionCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), MovieBrowseSessionCheckActivity::class.java)
            .putExtra("check_id", id()).putExtra("empty_category", empty).putExtra("shelf_count", shelfCount))
    private fun tag(tag: String) { compose.waitUntil(12_000) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
    private fun focused(tag: String) { compose.waitUntil(12_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun save(activity: MovieBrowseSessionCheckActivity): MovieBrowseSession {
        compose.waitForIdle(); runBlocking { withTimeout(10_000) { activity.store.awaitSaved() } }
        return Gson().fromJson(activity.sessionFile.readText(), MovieBrowseSession::class.java)
    }
    @Test fun deepShelfCardReturnsFromDetailsAndReopensAtTheSavedTitle() {
        launch(shelfCount = 18).use { scenario ->
            lateinit var activity: MovieBrowseSessionCheckActivity; scenario.onActivity { activity = it }
            tag("movies_search_open")
            press("movies_search_open", Key.DirectionDown); focused("focused_poster_card")
            repeat(8) { press("focused_poster_card", Key.DirectionRight) }
            assertEquals(9, save(activity).shelves!!.rows.getValue("movies").selectedId)
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("movie_detail_title")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 9")
            native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
            assertEquals(9, save(activity).shelves!!.rows.getValue("movies").selectedId)
            press("focused_poster_card", Key.DirectionCenter); tag("movie_detail_title")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 9")
            native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
            scenario.recreate(); scenario.onActivity { activity = it }; tag("movies_search_open")
            press("movies_search_open", Key.DirectionDown); focused("focused_poster_card")
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("movie_detail_title")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 9")
        }
    }

    @Test fun stageIndependentAllAndDeepCategoryPositions() {
        launch().use { scenario ->
            lateinit var activity: MovieBrowseSessionCheckActivity; scenario.onActivity { activity = it }
            tag("movies_all_open"); compose.onNodeWithTag("movies_all_open").performClick(); focused("all_movies_tile_0")
            compose.openGridOptions("all_movies"); compose.onNodeWithTag("all_movies_last").performClick(); focused("all_movies_tile_512")
            native(KeyEvent.KEYCODE_BACK); focused("movies_all_open")
            compose.onNodeWithTag("movies_categories_open").performClick(); focused("movie_category_choice_0")
            press("movie_category_choice_0", Key.PageDown); focused("movie_category_choice_10")
            repeat(4) { press("movie_category_choice_${10 + it}", Key.DirectionDown) }; focused("movie_category_choice_14")
            native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_category_tile_0")
            compose.openGridOptions("movie_category"); compose.onNodeWithTag("movie_category_last").performClick(); focused("movie_category_tile_512")
            val stored = save(activity)
            assertEquals("category", stored.surface); assertEquals("Drama 14", stored.categoryName)
            assertEquals(513, stored.all.selectedId); assertEquals(1513, stored.category!!.selectedId)
            assertTrue(stored.categoriesIndex > 0); assertTrue(stored.category!!.scrollIndex > 0)
            File(activity.cacheDir, "movie-browse-${id()}.pid").writeText(Process.myPid().toString())
        }
    }
    @Test fun restoreAfterForceStopPreservesCategoryDetailReturnAndIndependentAllPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(File(context.cacheDir, "movie-browse-${id()}.pid").readText().toInt(), Process.myPid())
        val saved = Gson().fromJson(File(context.cacheDir, "movie-browse-${id()}.json").readText(), MovieBrowseSession::class.java)
        launch().use { scenario ->
            lateinit var activity: MovieBrowseSessionCheckActivity; scenario.onActivity { activity = it }
            focused("movie_category_tile_512"); assertEquals(1513, activity.categories.grid!!.selectedKey)
            assertEquals(saved.category, save(activity).category)
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("movie_detail_title")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("Movie 513")
            compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_category_tile_512")
            native(KeyEvent.KEYCODE_BACK); focused("movie_category_choice_14")
            assertEquals(saved.categoriesIndex, activity.categories.categories.scrollIndex)
            native(KeyEvent.KEYCODE_BACK); focused("movies_categories_open")
            compose.onNodeWithTag("movies_all_open").performClick(); focused("all_movies_tile_512")
            assertEquals(513, activity.all.selectedKey); assertEquals(saved.all, save(activity).all)
            native(KeyEvent.KEYCODE_BACK); focused("movies_all_open"); assertEquals("browse", save(activity).surface)
            scenario.recreate(); scenario.onActivity { activity = it }; tag("movies_all_open")
            compose.onNodeWithTag("all_movies_screen").assertDoesNotExist(); assertFalse(activity.all.open)
        }
    }
    @Test fun missingCategoryStillOffersBackToTheSavedCategoryChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "movie-browse-${id()}.json")
        val store = FileMovieBrowseSessionStore(file)
        store.schedule(MovieBrowseSession("fixture-owner", "category", categoryName = "Drama 14",
            category = MovieGridPosition(1513, 512, 510, 0), selectedCategory = "Drama 14", categoriesIndex = 10))
        runBlocking { withTimeout(10_000) { store.awaitSaved() } }
        launch(empty = true).use {
            focused("movie_category_back"); tag("movie_category_empty")
            native(KeyEvent.KEYCODE_BACK); focused("movie_category_choice_14")
        }
    }
}
