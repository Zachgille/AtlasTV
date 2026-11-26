package com.atlas.atlastv.ui.series

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
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

/** Normal app, read-only account/watch state. Only optional last-good details/artwork are cached. */
class ShowDetailsCacheRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun openShow() {
        waitFor("top_nav_item_search"); compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("Blindspot")
        waitFor("unified_search_movies"); compose.onNodeWithTag("unified_search_results").performScrollToIndex(1)
        waitFor("unified_search_result_show-36621"); compose.onNodeWithTag("unified_search_result_show-36621").performClick()
        waitFor("series_episode_list")
    }
    @Test fun realShowReopensWithSavedDetailsAllFiveSeasonsAndUnchangedPersonalState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val activity = compose.activity; val db = AtlasDatabaseProvider.database(activity)
        val before = runBlocking { db.watchProgressDao().records() }
        val accountFile = File(activity.filesDir, ProviderSettingsStore.FileName); val accountBytes = accountFile.readBytes()
        val account = ProviderSettingsStore(accountFile).load().settings.credentialsOrNull()!!
        openShow()
        val row = runBlocking { db.showDetailsCacheDao().entry(providerDetailsKey(account), 36621) }
        assertNotNull("Normal Show opening must persist complete episodes", row)
        val cache = ShowDetailsCacheCodec.decode(row!!.payload)!!
        assertEquals(5, SeriesEpisodeState.Ready(cache.episodes).seasons().size)
        assertTrue("Payload excludes account values and addresses", !row.payload.contains(account.password) && !row.payload.contains(account.username) && !row.payload.contains("http://") && !row.payload.contains("https://"))
        compose.activityRule.scenario.recreate(); openShow()
        compose.onNodeWithTag("series_detail_saved_info").assertIsDisplayed()
        compose.onNodeWithTag("series_detail_title").assertTextEquals("Blindspot")
        for (season in 1..5) {
            compose.onNodeWithTag("series_season_list").performScrollToNode(hasTestTag("series_season_$season"))
            compose.onNodeWithTag("series_season_$season").performClick()
            compose.onNodeWithTag("series_detail_play_episode").assertTextContains("S$season", substring = true)
        }
        compose.onNodeWithTag("series_season_list").performScrollToNode(hasTestTag("series_season_1")); compose.onNodeWithTag("series_season_1").performClick()
        compose.onNodeWithTag("series_detail_play_episode").performSemanticsAction(SemanticsActions.RequestFocus)
        native(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("series_detail_refresh_episodes").assertIsFocused().assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "show-cache-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_BACK); waitFor("focused_unified_search_result_show-36621")
        compose.onNodeWithTag("focused_unified_search_result_show-36621").assertIsFocused()
        assertEquals(before, runBlocking { db.watchProgressDao().records() }); assertArrayEquals(accountBytes, accountFile.readBytes())
    }
}
