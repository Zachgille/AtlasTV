package com.atlas.atlastv.ui.home

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.catalog.cardWatchProgress
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in read-only real saved progress presentation; never starts video or removes a title. */
class CardProgressRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun actualContinueWatchingUsesSavedCheckpointAndKeepsRemoteEditAndResumeReachable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("home_hero_primary")
        val dao = AtlasDatabaseProvider.database(compose.activity).watchProgressDao()
        val original = runBlocking(Dispatchers.IO) { dao.records() }
        val first = WatchProgressSnapshot(original.mapNotNull { it.toWatchProgressRecordOrNull() }).continueWatching()
            .filter { !com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter().excludes(it.categoryName.orEmpty()) && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.seriesTitle ?: it.title) }
            .filter { it.contentType != WatchProgressContentType.SeriesEpisode || it.seriesId != null }.firstOrNull()
        assumeTrue(first != null)
        val record = first!!; val expected = record.cardWatchProgress(); waitFor("card_time_label")
        val parent = hasAnyAncestor(hasTestTag("home_card_${record.homeContinueCardId()}") or hasTestTag("focused_home_card"))
        compose.onNode(hasTestTag("card_time_label") and parent, useUnmergedTree = true).assertTextEquals(expected.timeLabel).assertIsDisplayed()
        expected.episodeLabel?.let { compose.onNode(hasTestTag("card_episode_label") and parent, useUnmergedTree = true).assertTextEquals(it) }
        expected.fraction?.let { assertEquals(it, compose.onNode(hasTestTag("card_progress_bar") and parent, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.00001f) }
        val row = compose.onNodeWithTag("continue_watching_row").fetchSemanticsNode().boundsInRoot
        assertTrue(row.bottom <= compose.onNodeWithTag("home_screen").fetchSemanticsNode().boundsInRoot.bottom)
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "card-progress-refined.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; capture.recycle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle()
        compose.onNodeWithTag("home_hero_primary").assertIsFocused().assertTextEquals("Resume")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle(); compose.onNodeWithTag("focused_home_card").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); compose.waitForIdle(); compose.onNodeWithTag("continue_watching_edit").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("continue_watching_editor")
        compose.onNodeWithText(expected.summary).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitFor("home_screen")
        assertEquals(original, runBlocking(Dispatchers.IO) { dao.records() })
        println("Real compact card reflects saved ${record.contentType.storageKey} progress without mutation")
    }
}
