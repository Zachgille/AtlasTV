package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.browse.openGridOptions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in personal-data navigation only; no additions/removals/import/playback fixtures. */
class MovieWatchlistGridRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    @Test fun retainedWatchlistOrHonestEmptyStateIsReachableAndReturnsToItsTopAction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_movies")
        val dao = AtlasDatabaseProvider.database(compose.activity).movieWatchlistDao()
        val count = runBlocking(Dispatchers.IO) { dao.count() }
        compose.onNodeWithTag("top_nav_item_movies").performClick(); waitFor("movies_watchlist_open")
        compose.onNodeWithTag("movies_watchlist_open").performClick(); waitFor("movie_watchlist_screen")
        if (count == 0) {
            waitFor("movie_watchlist_empty"); focused("movie_watchlist_back")
            compose.onNodeWithTag("movie_watchlist_count").assertDoesNotExist()
            compose.onNodeWithTag("movie_watchlist_first").assertDoesNotExist(); compose.onNodeWithTag("movie_watchlist_last").assertDoesNotExist()
        } else {
            focused("movie_watchlist_tile_0")
            compose.openGridOptions("movie_watchlist"); compose.onNodeWithTag("movie_watchlist_last").performClick(); focused("movie_watchlist_tile_${count - 1}")
            val expected = runBlocking(Dispatchers.IO) { dao.page(count - 1, 1).single().toMovieItem() }
            compose.onNodeWithTag("movie_watchlist_tile_${count - 1}").performClick(); waitFor("movie_detail_title")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(expected.title))
            compose.onNodeWithTag("movie_detail_back").performClick(); focused("movie_watchlist_tile_${count - 1}")
        }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.cacheDir, "movie-watchlist-refined.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.onNodeWithTag("movie_watchlist_back").performClick(); focused("movies_watchlist_open")
        assertEquals(count, runBlocking(Dispatchers.IO) { dao.count() })
        println("Real Movie Watchlist navigation preserved $count saved titles")
    }
}
