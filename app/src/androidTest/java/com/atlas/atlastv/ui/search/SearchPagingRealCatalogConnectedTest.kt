package com.atlas.atlastv.ui.search

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

/** Opt-in navigation/metadata only. Never seeds, syncs, plays video or changes watchlists. */
class SearchPagingRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun fullMovieAndShowSearchReturnsToExactResultsAndKeepsQueryAndSavedState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_search")
        val context = compose.activity
        fun saved() = context.filesDir.listFiles().orEmpty().filter { it.isFile && (it.name=="provider_settings.json" || it.name.contains("watch") || it.name.contains("progress")) }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = saved(); val dao = AtlasDatabaseProvider.database(context).safeCatalogDao()
        val counts = runBlocking(Dispatchers.IO) { dao.countForType("movie") to dao.countForType("series") }
        val movies = runBlocking(Dispatchers.IO) { dao.countSearchItemsForType("movie","love") }
        val shows = runBlocking(Dispatchers.IO) { dao.countSearchItemsForType("series","love") }
        assertTrue(movies>300 && shows>18)
        val showIndex = minOf(140,shows-1)
        waitFor("top_nav_item_search"); compose.onNodeWithTag("top_nav_item_search").performClick()
        compose.onNodeWithTag("unified_search_field").performTextInput("love"); waitFor("unified_search_movies_all")
        compose.onNodeWithTag("unified_search_movies_all").performClick(); waitFor("search_all_grid")
        compose.onNodeWithTag("search_all_count").assertDoesNotExist()
        val movie = runBlocking(Dispatchers.IO) { dao.searchPage("movie","love",300,1).single().toMovieItem("room://safe") }
        scrollTo(300,movie.title); compose.onNodeWithTag("search_all_tile_300").performClick(); waitFor("movie_detail_title")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(movie.title))
        compose.onNodeWithTag("movie_detail_back").performClick(); focused("search_all_tile_300")
        compose.openGridOptions("search_all"); compose.onNodeWithTag("search_all_last").performClick()
        val lastMovie = runBlocking(Dispatchers.IO) { dao.searchPage("movie","love",movies-1,1).single().toMovieItem("room://safe") }
        waitItem(movies-1,lastMovie.title); focused("search_all_tile_${movies-1}")
        compose.onNodeWithTag("search_all_back").performClick(); focused("unified_search_movies_all")
        compose.onNodeWithTag("unified_search_field").assertTextEquals("love")
        // Scroll the lazy parent before targeting the Shows continuation action.
        compose.onNodeWithTag("unified_search_results").performScrollToIndex(1)
        waitFor("unified_search_shows_all"); compose.onNodeWithTag("unified_search_shows_all").performClick(); waitFor("search_all_grid")
        compose.onNodeWithTag("search_all_count").assertDoesNotExist()
        val show = runBlocking(Dispatchers.IO) { dao.searchPage("series","love",showIndex,1).single().toSeriesItem() }
        scrollTo(showIndex,show.name); compose.onNodeWithTag("search_all_tile_$showIndex").performClick(); waitFor("series_detail_title")
        compose.onNodeWithTag("series_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(show.name))
        compose.onNodeWithTag("series_detail_watchlist").assertIsDisplayed()
        compose.onNodeWithTag("series_back").performClick(); focused("search_all_tile_$showIndex")
        compose.openGridOptions("search_all"); compose.onNodeWithTag("search_all_last").performClick()
        val lastShow = runBlocking(Dispatchers.IO) { dao.searchPage("series","love",shows-1,1).single().toSeriesItem() }
        waitItem(shows-1,lastShow.name); focused("search_all_tile_${shows-1}")
        compose.onNodeWithTag("search_all_back").performClick(); focused("unified_search_shows_all")
        compose.onNodeWithTag("unified_search_field").assertTextEquals("love")
        compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        assertEquals(movies, runBlocking(Dispatchers.IO) { dao.countSearchItemsForType("movie", "love") })
        assertEquals(shows, runBlocking(Dispatchers.IO) { dao.countSearchItemsForType("series", "love") })
        assertEquals(before,saved()); assertEquals(counts,runBlocking(Dispatchers.IO) { dao.countForType("movie") to dao.countForType("series") })
        println("Search paging acceptance: $movies Movies / $shows Shows; offsets 300 and $showIndex and both last items; exact detail/entry return; query and saved hashes unchanged")
    }
    private fun waitItem(index: Int,title: String) { compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("search_all_tile_$index") and hasContentDescription(title)).fetchSemanticsNodes().isNotEmpty() } }
    private fun scrollTo(index: Int,title: String) { compose.onNodeWithTag("search_all_grid").performScrollToIndex(index); waitItem(index,title) }
    private fun focused(tag: String) { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
