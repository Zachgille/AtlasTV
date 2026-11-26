package com.atlas.atlastv.ui.movies

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Normal installed app. Only optional metadata/artwork are saved; no personal video or actions. */
class MovieDetailsCacheRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun open() {
        waitFor("top_nav_item_search"); compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("Inception")
        waitFor("unified_search_result_movie-353496"); compose.onNodeWithTag("unified_search_result_movie-353496").performClick()
        waitFor("movie_detail_synopsis")
    }
    @Test fun realMovieReopensFromSavedDetailsAndReturnsToTheExactSearchResultWithoutPersonalWrites() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val activity = compose.activity; val db = AtlasDatabaseProvider.database(activity)
        val before = runBlocking { db.watchProgressDao().records() }
        val accountFile = File(activity.filesDir, ProviderSettingsStore.FileName); val bytes = accountFile.readBytes()
        val account = ProviderSettingsStore(accountFile).load().settings.credentialsOrNull()!!
        open()
        val row = runBlocking { db.movieDetailsCacheDao().entry(providerMovieDetailsKey(account), 353496) }
        assertNotNull("Normal Movie opening persists safe details", row)
        val saved = MovieDetailsCacheCodec.decode(row!!.payload)!!
        assertNotNull(saved.synopsis); assertEquals("2010", saved.releaseYear)
        assertTrue("Payload excludes account values and raw addresses", !row.payload.contains(account.password) && !row.payload.contains(account.username) && !row.payload.contains("http://") && !row.payload.contains("https://"))
        compose.activityRule.scenario.recreate(); open()
        compose.onNodeWithTag("movie_detail_saved_info").assertIsDisplayed()
        waitFor("loaded_detail_backdrop")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Inception")
        compose.onNodeWithTag("movie_detail_play").assertIsFocused().assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("movie_detail_refresh").assertIsFocused().assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("movie_detail_play").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "movie-cache-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_BACK); waitFor("focused_unified_search_result_movie-353496")
        compose.onNodeWithTag("focused_unified_search_result_movie-353496").assertIsFocused()
        assertEquals(before, runBlocking { db.watchProgressDao().records() }); assertArrayEquals(bytes, accountFile.readBytes())
    }
}
