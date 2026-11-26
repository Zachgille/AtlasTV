package com.atlas.atlastv.ui.series

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

/** Real read-only navigation and episode metadata; no playback, sync or personal-state mutations. */
class SeriesGridRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun fullShowsGridReachesDeepAndLastDetailsAndReturnsWithoutChangingSavedData() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_shows")
        val context = compose.activity
        fun saved() = context.filesDir.listFiles().orEmpty().filter { it.isFile &&
            (it.name == "provider_settings.json" || it.name.contains("watch") || it.name.contains("progress")) }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = saved()
        val dao = AtlasDatabaseProvider.database(context).safeCatalogDao()
        val total = runBlocking(Dispatchers.IO) { dao.countForType("series") }
        assertTrue(total > 6000)
        waitFor("top_nav_item_shows"); compose.onNodeWithTag("top_nav_item_shows").performClick()
        waitFor("series_all_open"); compose.onNodeWithTag("series_all_open").performClick()
        waitFor("all_shows_grid"); compose.onNodeWithTag("all_shows_count").assertDoesNotExist()
        // Begin synthetic deep scrolling after the normal entry focus has settled.
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("all_shows_tile_0").assertIsFocused(); true }.getOrDefault(false) }
        val index = 6001
        val expected = runBlocking(Dispatchers.IO) { dao.itemForTypeAtOffset("series", index)!!.toSeriesItem() }
        compose.onNodeWithTag("all_shows_grid").performScrollToIndex(index)
        try {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("all_shows_tile_$index") and hasContentDescription(expected.name)).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            val focusedTags = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "untagged" }
            }
            val visibleTags = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.TestTag)).fetchSemanticsNodes()
                .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
            println("Deep grid read failure: focused=$focusedTags; visible=$visibleTags")
            val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.cacheDir, "all_shows-deep-read-failure.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            capture.recycle()
            throw failure
        }
        compose.onNodeWithTag("all_shows_tile_$index").performClick(); waitFor("series_detail_title")
        compose.onNodeWithTag("series_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(expected.name))
        assertPrimaryActions()
        compose.onNodeWithTag("series_back").performClick(); focused("all_shows_tile_$index")
        compose.openGridOptions("all_shows"); compose.onNodeWithTag("all_shows_last").performClick()
        val last = runBlocking(Dispatchers.IO) { dao.itemForTypeAtOffset("series", total - 1)!!.toSeriesItem() }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("all_shows_tile_${total - 1}") and hasContentDescription(last.name)).fetchSemanticsNodes().isNotEmpty() }
        focused("all_shows_tile_${total - 1}")
        compose.onNodeWithTag("all_shows_tile_${total - 1}").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        waitFor("series_detail_title"); compose.onNodeWithTag("series_detail_title").assertTextEquals(com.atlas.atlastv.ui.catalog.displayTitle(last.name))
        assertPrimaryActions()
        compose.onNodeWithTag("series_back").performClick(); focused("all_shows_tile_${total - 1}")
        compose.onNodeWithTag("all_shows_back").performClick(); waitFor("series_all_open")
        compose.onNodeWithTag("series_all_open").assertIsFocused()
        assertEquals(before, saved()); assertEquals(total, runBlocking(Dispatchers.IO) { dao.countForType("series") })
        println("Shows grid acceptance: $total shows; offset $index and last reached; exact return focus; account/watch hashes unchanged")
    }
    private fun assertPrimaryActions() {
        compose.onNodeWithTag("series_detail_watchlist").assertIsDisplayed()
        compose.onNodeWithTag("series_back").assertIsDisplayed()
        compose.onNode(hasTestTag("series_detail_play_episode") or hasTestTag("series_episode_retry")).assertIsDisplayed()
    }
    private fun focused(tag: String) { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
