package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.openGridOptions

import androidx.compose.ui.input.key.Key
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
import java.security.MessageDigest

/** Opt-in real navigation only; no playback, sync, settings or watchlist changes. */
class MovieGridRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun fullGridReachesDeepAndLastMovieAndReturnsToExactTileWithoutChangingSavedData() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_movies")
        val context = compose.activity
        fun saved() = context.filesDir.listFiles().orEmpty().filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("watch") || it.name.contains("progress")) }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = saved()
        val dao = AtlasDatabaseProvider.database(context).safeCatalogDao()
        val total = runBlocking(Dispatchers.IO) { dao.countForType("movie") }
        assertTrue(total > 10000)
        waitFor("top_nav_item_movies"); compose.onNodeWithTag("top_nav_item_movies").performClick()
        waitFor("movies_all_open"); compose.onNodeWithTag("movies_all_open").performClick()
        waitFor("all_movies_grid"); compose.onNodeWithTag("all_movies_count").assertDoesNotExist()
        // Begin synthetic deep scrolling after the normal entry focus has settled.
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("all_movies_tile_0").assertIsFocused(); true }.getOrDefault(false) }
        // Traverse well beyond every existing preview/search cap using the virtual grid's scroll semantics.
        val index = 10001
        val expected = runBlocking(Dispatchers.IO) { dao.itemForTypeAtOffset("movie", index)!!.toMovieItem("room://safe") }
        compose.onNodeWithTag("all_movies_grid").performScrollToIndex(index)
        try {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("all_movies_tile_$index") and hasContentDescription(expected.title)).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            val focusedTags = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "untagged" }
            }
            val visibleTags = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.TestTag)).fetchSemanticsNodes()
                .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
            println("Deep grid read failure: focused=$focusedTags; visible=$visibleTags")
            val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.cacheDir, "all_movies-deep-read-failure.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            capture.recycle()
            throw failure
        }
        compose.onNodeWithTag("all_movies_tile_$index").performClick()
        waitFor("movie_detail_title"); compose.onNodeWithTag("movie_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(expected.title))
        compose.onNodeWithTag("movie_detail_back").performClick()
        try {
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("all_movies_tile_$index").assertIsFocused(); true }.getOrDefault(false) }
        } catch (failure: Throwable) {
            val tags = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "untagged" } }
            val allTags = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.TestTag)).fetchSemanticsNodes()
                .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
            println("Grid return failure: focused tags=$tags; visible tags=$allTags")
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.cacheDir, "movie-grid-return-failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            throw failure
        }
        compose.openGridOptions("all_movies"); compose.onNodeWithTag("all_movies_last").performClick()
        val last = runBlocking(Dispatchers.IO) { dao.itemForTypeAtOffset("movie", total - 1)!!.toMovieItem("room://safe") }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("all_movies_tile_${total - 1}") and hasContentDescription(last.title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("all_movies_tile_${total - 1}").assertIsFocused()
        compose.onNodeWithTag("all_movies_tile_${total - 1}").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        waitFor("movie_detail_title"); compose.onNodeWithTag("movie_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(last.title))
        compose.onNodeWithTag("movie_detail_back").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("all_movies_tile_${total - 1}").assertIsFocused(); true }.getOrDefault(false) }
        compose.onNodeWithTag("all_movies_back").performClick(); waitFor("movies_all_open")
        compose.onNodeWithTag("movies_all_open").assertIsFocused()
        assertEquals(before, saved()); assertEquals(total, runBlocking(Dispatchers.IO) { dao.countForType("movie") })
        println("Movie grid acceptance: $total movies; deep offset $index and last item reached; exact return focus; account/watch hashes unchanged")
    }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
