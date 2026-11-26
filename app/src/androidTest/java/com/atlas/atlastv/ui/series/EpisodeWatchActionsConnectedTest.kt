package com.atlas.atlastv.ui.series

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.debug.StartOverCheckActivity
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Normal episode rows/menu and actual ordered Room/file writes; all state is cache-only. */
class EpisodeWatchActionsConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(many: Boolean = false, unavailable: Boolean = false) = ActivityScenario.launch<StartOverCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), StartOverCheckActivity::class.java)
            .putExtra("check_id", UUID.randomUUID().toString()).putExtra("series", true).putExtra("episode_actions", true)
            .putExtra("many_episodes", many).putExtra("unavailable", unavailable))
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun ready(activity: StartOverCheckActivity) { waitFor("series_episode_list"); compose.waitUntil(8_000) {
        (activity.seriesHolder.state as? SeriesCatalogState.Ready)?.selectedEpisodeId == 103
    }; focused("series_detail_play_episode") }
    private fun rowChild(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("series_selected_episode")), useUnmergedTree = true)
    private fun open() { native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_episode_more"); native(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("episode_actions_dialog"); focused("episode_action_mark") }
    private fun close() { native(KeyEvent.KEYCODE_BACK); compose.waitUntil(5_000) { compose.onAllNodesWithTag("episode_actions_dialog").fetchSemanticsNodes().isEmpty() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000); focused("series_episode_more") }
    private fun disk(activity: StartOverCheckActivity) = runBlocking { activity.store.loadAsync().records.sortedBy { it.key } }
    private fun saved(activity: StartOverCheckActivity, message: String) { compose.waitUntil(15_000) { !activity.episodeActions.saving && !activity.episodeActions.failed && activity.episodeActions.message == message } }
    private fun failed(activity: StartOverCheckActivity) { compose.waitUntil(12_000) { activity.episodeActions.failed }; compose.onNodeWithTag("episode_action_mark").assertTextEquals("Retry save") }
    private fun unchangedOthers(activity: StartOverCheckActivity, id: Int) {
        val key = WatchProgressRecord.key(WatchProgressContentType.SeriesEpisode, id)
        assertEquals(activity.baseline.filterNot { it.key == key }.sortedBy { it.key }, disk(activity).filterNot { it.key == key })
        assertEquals(listOf(1), runBlocking { activity.database.movieWatchlistDao().items() }.map { it.streamId })
        assertEquals(listOf(7), runBlocking { activity.database.seriesWatchlistDao().items() }.map { it.seriesId })
        assertEquals(0, activity.startCount)
    }
    private fun screenshot(activity: StartOverCheckActivity, filename: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(activity.cacheDir, filename).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun partialEpisodeMarkAndUndoUseTheRemotePreserveExactProgressAndKeepTwoRowsVisible() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode"); open()
            compose.onNodeWithTag("episode_action_mark").assertTextEquals("Mark watched")
            compose.onNodeWithTag("episode_actions_title").assertTextEquals("A fresh start")
            native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched"); focused("episode_action_mark")
            assertTrue(disk(activity).first { it.key == "series_episode:103" }.completed); unchangedOthers(activity, 103)
            screenshot(activity, "episode-actions-controlled.png")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("episode_action_undo"); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Undone")
            val restored = disk(activity).first { it.key == "series_episode:103" }
            assertEquals(activity.baseline.first { it.key == restored.key }, restored.copy(updatedAtMs = activity.baseline.first { it.key == restored.key }.updatedAtMs))
            focused("episode_action_undo"); close(); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_selected_episode")
            rowChild("series_episode_progress_label").assertTextEquals("10m left")
            val more = compose.onNodeWithTag("series_episode_more").fetchSemanticsNode().boundsInRoot
            val row = compose.onNodeWithTag("series_selected_episode").fetchSemanticsNode().boundsInRoot
            assertTrue(more.left >= row.left && more.right <= row.right && more.bottom <= row.bottom)
            val viewport = compose.onNodeWithTag("series_episode_list").fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithTag("series_episode_102").fetchSemanticsNode().boundsInRoot
            assertTrue(first.top >= viewport.top && row.bottom <= viewport.bottom)
            screenshot(activity, "episode-actions-row-controlled.png")
        }
    }
    @Test fun completedEpisodeCanBeMarkedUnwatchedAndUndoRestoresItsCompletionAfterDiskReopen() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_UP); focused("series_selected_episode")
            compose.runOnIdle { assertEquals(102, (activity.seriesHolder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
            open(); compose.onNodeWithTag("episode_action_mark").assertTextEquals("Mark unwatched")
            native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked unwatched")
            val cleared = disk(activity).first { it.key == "series_episode:102" }; assertFalse(cleared.completed); assertEquals(0L, cleared.positionMs)
            close(); native(KeyEvent.KEYCODE_DPAD_LEFT); rowChild("series_episode_progress_label").assertDoesNotExist(); open()
            compose.onNodeWithTag("episode_action_mark").assertTextEquals("Mark watched")
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Undone")
            val restored = disk(activity).first { it.key == "series_episode:102" }; assertTrue(restored.completed); assertEquals(600_000L, restored.positionMs)
            native(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("episode_action_mark").assertTextEquals("Mark unwatched")
            native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked unwatched")
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Undone")
            val reopened = Room.databaseBuilder(activity, AtlasDatabase::class.java, activity.database.openHelper.databaseName!!).build()
            try { assertEquals(disk(activity), runBlocking { WatchProgressRoomStore(reopened.watchProgressDao()).load().records.sortedBy { it.key } }) } finally { reopened.close() }
            unchangedOthers(activity, 102)
        }
    }
    @Test fun allSeasonsAndDeepFinalUnknownRuntimeEpisodeRemainSelectableAndUndoReturnsItToUnstarted() {
        launch(many = true).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_2")
            for (season in 3..24) { native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_$season") }
            native(KeyEvent.KEYCODE_DPAD_RIGHT); repeat(3) { native(KeyEvent.KEYCODE_DPAD_DOWN) }; focused("series_selected_episode")
            compose.runOnIdle { assertEquals(24004, (activity.seriesHolder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
            rowChild("series_episode_duration").assertDoesNotExist(); open(); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched")
            val marked = disk(activity).first { it.key == "series_episode:24004" }; assertTrue(marked.completed); assertNull(marked.durationMs); assertEquals(0L, marked.positionMs)
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Undone"); close(); native(KeyEvent.KEYCODE_DPAD_LEFT)
            rowChild("series_episode_progress_label").assertDoesNotExist(); focused("series_selected_episode")
            compose.runOnIdle { assertEquals(24, (activity.seriesHolder.state as SeriesCatalogState.Ready).selectedSeasonNumber) }
            assertFalse(disk(activity).first { it.key == "series_episode:24004" }.completed); unchangedOthers(activity, 24004)
        }
    }
    @Test fun failedSaveShowsRemoteRetryPreservesDiskAndTargetsTheOriginalEpisode() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            compose.runOnIdle { activity.failManualSave = true }
            native(KeyEvent.KEYCODE_DPAD_DOWN); open(); native(KeyEvent.KEYCODE_DPAD_CENTER); failed(activity); focused("episode_action_mark")
            assertEquals(activity.baseline.sortedBy { it.key }, disk(activity)); compose.onNodeWithTag("episode_action_undo").assertIsNotEnabled()
            close(); native(KeyEvent.KEYCODE_DPAD_LEFT); rowChild("series_episode_progress_label").assertTextEquals("Not saved")
            native(KeyEvent.KEYCODE_DPAD_UP); open(); compose.onNodeWithTag("episode_action_mark").assertTextEquals("Retry save")
            compose.onNodeWithText("Unsaved change: S2 E2").assertIsDisplayed()
            compose.runOnIdle { activity.failManualSave = false }; native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched")
            focused("episode_action_mark"); assertTrue(disk(activity).first { it.key == "series_episode:103" }.completed)
            assertTrue(disk(activity).first { it.key == "series_episode:102" }.completed); unchangedOthers(activity, 103)
        }
    }
    @Test fun slowSaveIgnoresRepeatedActivationAndLeavingMenuDoesNotMoveEpisodeFocusOnAcknowledgement() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            val gate = CompletableDeferred<Unit>(); compose.runOnIdle { activity.manualSaveGate = gate }
            native(KeyEvent.KEYCODE_DPAD_DOWN); open(); repeat(5) { native(KeyEvent.KEYCODE_DPAD_CENTER) }
            compose.onNodeWithTag("episode_action_mark").assertTextEquals("Saving…"); assertEquals(0, activity.manualWrites)
            close(); native(KeyEvent.KEYCODE_DPAD_LEFT); native(KeyEvent.KEYCODE_DPAD_UP); focused("series_selected_episode")
            gate.complete(Unit); saved(activity, "Marked watched"); focused("series_selected_episode")
            compose.runOnIdle { assertEquals(102, (activity.seriesHolder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
            assertEquals(1, activity.manualWrites); unchangedOthers(activity, 103)
        }
    }
    @Test fun lookupFailureRetriesAndUndoSaveFailureDoesNotClaimRestorationBeforeAcknowledgement() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_DOWN); open(); compose.runOnIdle { activity.failManualLoad = true }
            native(KeyEvent.KEYCODE_DPAD_CENTER); failed(activity); assertEquals(activity.baseline.sortedBy { it.key }, disk(activity))
            compose.runOnIdle { activity.failManualLoad = false }; native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched")
            compose.runOnIdle { activity.failManualSave = true }; native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_CENTER); failed(activity)
            focused("episode_action_undo"); assertTrue(disk(activity).first { it.key == "series_episode:103" }.completed)
            native(KeyEvent.KEYCODE_DPAD_UP); focused("episode_action_mark"); compose.runOnIdle { activity.failManualSave = false }
            native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Undone"); assertEquals(45_000L, disk(activity).first { it.key == "series_episode:103" }.positionMs)
            assertFalse(disk(activity).first { it.key == "series_episode:103" }.completed); unchangedOthers(activity, 103)
        }
    }
    @Test fun actualPlaybackAfterMarkingInvalidatesOldUndoAndReturnsToExactEpisode() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_DOWN); open(); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched"); close()
            native(KeyEvent.KEYCODE_DPAD_LEFT); native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("playback_progress_label")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
            native(KeyEvent.KEYCODE_MEDIA_PAUSE); native(KeyEvent.KEYCODE_BACK); native(KeyEvent.KEYCODE_BACK); waitFor("series_episode_list")
            focused("series_detail_play_episode"); native(KeyEvent.KEYCODE_DPAD_DOWN); open()
            compose.onNodeWithTag("episode_action_undo").assertDoesNotExist()
            compose.runOnIdle { assertEquals(103, (activity.seriesHolder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
            assertEquals(1, activity.startCount); assertEquals(0L, activity.prepared!!.resumeMs ?: 0L)
        }
    }
    @Test fun cancelledMenuDoesNotWriteAndUnavailableShowHasNoEpisodeActions() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_DOWN); open(); close(); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_selected_episode")
            assertEquals(activity.baseline.sortedBy { it.key }, disk(activity)); assertEquals(0, activity.manualWrites)
        }
        launch(unavailable = true).use { waitFor("series_detail_page"); compose.onNodeWithTag("series_episode_more").assertDoesNotExist(); compose.onNodeWithTag("series_episode_list").assertDoesNotExist() }
    }
}
