package com.atlas.atlastv.ui.catalog

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in personal-library read/navigation only. Never refresh, edit membership/progress or play video. */
class TitleFormatRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun hashes() = compose.activity.filesDir.walkTopDown().filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("watchlist") || it.name.contains("progress") || it.name == "playback_preferences.preferences_pb") }
        .associate { it.relativeTo(compose.activity.filesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    @Test fun actualRawTitleStillSearchesWhileResultAndDetailsAreCleanAndReturnIsExact() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitTag("home_hero_primary"); focused("top_nav_item_search")
        val db = AtlasDatabaseProvider.database(compose.activity)
        val dao = db.safeCatalogDao()
        val row = runBlocking(Dispatchers.IO) { dao.searchPage(SafeCatalogContentTypes.Movie, "spiderhead", 0, 50).first { it.title.endsWith(" HEVC") } }
        val beforeHashes = hashes()
        val browseStore = MoviesPreferencesStore(File(compose.activity.filesDir, MoviesPreferencesStore.FileName))
        val beforeBrowse = browseStore.load()
        val progress = runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }
        val counts = runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) }
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("unified_search_field")
        compose.onNodeWithTag("unified_search_field").performTextInput(row.title)
        val id = "movie-${row.itemId}"
        waitTag("unified_search_result_$id")
        val result = compose.onNodeWithTag("unified_search_result_$id")
        result.assertTextContains("Spiderhead", substring = true)
        val visible = result.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        assertFalse(visible, visible.contains("HEVC")); assertFalse(visible, visible.contains("2022"))
        compose.activityRule.scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(8_000) { var hidden = false; compose.activityRule.scenario.onActivity { hidden = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }; hidden }
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_unified_search_result_$id")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movie_detail_title"); focused("movie_detail_play")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Spiderhead")
        compose.onAllNodesWithText("Format:", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("movie_detail_year").assertTextContains("2022", substring = true)
        compose.onNodeWithTag("movie_detail_watchlist").assertIsDisplayed()
        compose.onAllNodesWithTag("playback_full_screen_surface").assertCountEquals(0)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "title-format-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_BACK); waitTag("unified_search_field"); focused("focused_unified_search_result_$id")
        assertEquals(row.title, compose.onNodeWithTag("unified_search_field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(row, runBlocking(Dispatchers.IO) { dao.searchPage(SafeCatalogContentTypes.Movie, "spiderhead", 0, 50).single { it.itemId == row.itemId } })
        assertEquals(beforeBrowse.copy(selectedMovieStreamId = row.itemId, selectedMovieTitle = row.title, selectedMovieCategory = row.categoryName), browseStore.load())
        assertEquals(beforeHashes, hashes()); assertEquals(progress, runBlocking(Dispatchers.IO) { db.watchProgressDao().records() })
        assertEquals(counts, runBlocking(Dispatchers.IO) { dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series) })
    }
}
