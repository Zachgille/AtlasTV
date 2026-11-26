package com.atlas.atlastv.ui.series

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest

/** Explicit opt-in. Reads the user's catalog; no fixtures, playback, sync or watchlist changes. */
class SeriesRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realSeriesLoadSearchAndReturnWithoutChangingAccountOrWatchState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val saved = savedHashes()
        val dao = AtlasDatabaseProvider.database(compose.activity).safeCatalogDao()
        val before = runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) }
        assertTrue(before.first > 0)
        waitFor("hero_area")
        compose.onNodeWithTag("top_nav_item_shows").performClick()
        waitFor("series_row_page")
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        press("series_search_open", Key.DirectionDown)
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        press("focused_poster_card", Key.DirectionRight)
        compose.onNodeWithTag("focused_poster_card").assertIsFocused()
        compose.onNodeWithTag("series_search_open").performClick()
        waitFor("series_search_field")
        compose.onNodeWithTag("series_search_field").performTextInput("love")
        val expected = runBlocking(Dispatchers.IO) {
            com.atlas.atlastv.data.db.SafeCatalogRepository(dao).countSearchSeries("love")
        }
        assertTrue(expected > 0)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("series_search_result_count").fetchSemanticsNodes().any { node ->
                node.config[SemanticsProperties.Text].any { it.text == "$expected results" }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("series_refreshing").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("series_search_field").assertIsFocused()
        compose.onNodeWithTag("series_search_result_count").assertTextEquals("$expected results")
        compose.onNodeWithTag("series_clear_search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("series_refreshing").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("series_search_open").assertIsFocused()
        compose.onNodeWithTag("series_back").performClick()
        waitFor("hero_area")
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        assertEquals(saved, savedHashes())
        val after = runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) }
        assertEquals(before, after)
        println("Series acceptance: account/watch hashes unchanged; catalog counts $after; full search count $expected")
    }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun savedHashes() = compose.activity.filesDir.listFiles().orEmpty()
        .filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("progress") || it.name.contains("watchlist")) }
        .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }
}
