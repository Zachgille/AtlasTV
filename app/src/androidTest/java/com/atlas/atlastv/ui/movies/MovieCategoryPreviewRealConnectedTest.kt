package com.atlas.atlastv.ui.movies

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.home.assertHomeFooterVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in real Room/category/navigation only. No fixture writes, personal refresh or video. */
class MovieCategoryPreviewRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun cardPart(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("focused_movie_more_like_this_card")), useUnmergedTree = true)
    private fun cardTitle() = cardPart("poster_card_title").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun hashes() = compose.activity.filesDir.walkTopDown().filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("watchlist") || it.name.contains("progress") || it.name == "playback_preferences.preferences_pb") }
        .associate { it.relativeTo(compose.activity.filesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    @Test fun actualCategoryComesFromRoomAndDeepMovieBackReturnsToItsPosterThenOriginalSearchResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitTag("home_hero_primary"); focused("top_nav_item_search")
        val db = AtlasDatabaseProvider.database(compose.activity); val dao = db.safeCatalogDao()
        val original = runBlocking(Dispatchers.IO) { dao.searchPage(SafeCatalogContentTypes.Movie, "spiderhead", 0, 50).first { it.title.endsWith(" HEVC") } }
        val category = runBlocking(Dispatchers.IO) { dao.categoryPage(SafeCatalogContentTypes.Movie, original.categoryName, 0, 13).filter { it.itemId != original.itemId }.take(12) }
        assertEquals(12, category.size)
        val beforeHashes = hashes(); val beforeProgress = runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }
        val counts = runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) }
        val browse = MoviesPreferencesStore(File(compose.activity.filesDir, MoviesPreferencesStore.FileName)); val beforeBrowse = browse.load()
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("unified_search_field")
        compose.onNodeWithTag("unified_search_field").performTextInput(original.title)
        val searchId = "movie-${original.itemId}"
        waitTag("unified_search_result_$searchId")
        compose.activityRule.scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(8_000) { var hidden = false; compose.activityRule.scenario.onActivity { hidden = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }; hidden }
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_unified_search_result_$searchId")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movie_more_like_this_title"); focused("movie_detail_play")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals(displayTitle(original.title))
        compose.onNodeWithTag("movie_more_like_this_title").assertTextEquals("More in this category")
        compose.onAllNodesWithText("Format:", substring = true).assertCountEquals(0)
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_movie_more_like_this_card")
        for ((index, movie) in category.withIndex()) {
            if (index > 0) native(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("Category item $index", displayTitle(movie.title), cardTitle())
        }
        val selected = category.last()
        assertHomeFooterVisible(cardPart("focused_poster_card"), compose.onNodeWithTag("movie_category_preview_viewport"), cardPart("poster_card_footer"), cardPart("poster_card_title"))
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movie_detail_title"); focused("movie_detail_play")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals(displayTitle(selected.title))
        compose.onAllNodesWithTag("playback_full_screen_surface").assertCountEquals(0)
        native(KeyEvent.KEYCODE_BACK); waitTag("movie_more_like_this_title"); focused("focused_movie_more_like_this_card")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals(displayTitle(original.title)); assertEquals(displayTitle(selected.title), cardTitle())
        assertHomeFooterVisible(cardPart("focused_poster_card"), compose.onNodeWithTag("movie_category_preview_viewport"), cardPart("poster_card_footer"), cardPart("poster_card_title"))
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "movie-category-preview-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_BACK); waitTag("unified_search_field"); focused("focused_unified_search_result_$searchId")
        assertEquals(original.title, compose.onNodeWithTag("unified_search_field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(beforeHashes, hashes()); assertEquals(beforeProgress, runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }); assertEquals(counts, runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) })
        assertEquals(beforeBrowse.copy(selectedMovieStreamId = original.itemId, selectedMovieTitle = original.title, selectedMovieCategory = original.categoryName), browse.load())
    }
}
