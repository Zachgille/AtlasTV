package com.atlas.atlastv.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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

/** Opt-in real-catalog acceptance, with no fixture or account/watch-state writes. */
class HomeRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realHomeLoadsWithoutStealingNavigationFocusAndSurvivesReturningFromSearch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("hero_area")
        val saved = savedHashes()
        val dao = AtlasDatabaseProvider.database(compose.activity).safeCatalogDao()
        val before = runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) }
        assertTrue(before.first > 0 && before.second > 0)
        waitFor("hero_area")
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        press("top_nav_item_search", Key.DirectionDown)
        compose.onNodeWithTag("home_hero_primary").assertIsFocused()
        press("home_hero_primary", Key.DirectionDown)
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
        compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_screen")
        compose.onNodeWithTag("unified_search_back").performClick()
        waitFor("hero_area")
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        assertEquals(saved, savedHashes())
        val after = runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) }
        assertEquals(before, after)
        println("Home acceptance: saved account/watch hashes unchanged; catalog counts $after")
    }

    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun savedHashes() = compose.activity.filesDir.listFiles().orEmpty()
        .filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("progress") || it.name.contains("watchlist")) }
        .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }
}

