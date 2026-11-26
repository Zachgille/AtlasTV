package com.atlas.atlastv.ui.movies

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.StartOverCheckActivity
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Normal Movie controls with private Room/files; never edits the viewer's library. */
class MovieWatchActionsConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch() = ActivityScenario.launch<StartOverCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), StartOverCheckActivity::class.java)
            .putExtra("check_id", UUID.randomUUID().toString()).putExtra("movie_actions", true))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching {
        compose.onNodeWithTag(tag).assertIsFocused(); true
    }.getOrDefault(false) } }
    private fun text(tag: String, value: String) { compose.waitUntil(12_000) { runCatching {
        compose.onNodeWithTag(tag).assertTextEquals(value); true
    }.getOrDefault(false) } }
    private fun ready() { focused("movie_detail_play") }
    private fun openFromPlay() {
        native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_watchlist")
        native(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (compose.onAllNodesWithTag("movie_detail_start_over").fetchSemanticsNodes().isNotEmpty()) {
            focused("movie_detail_start_over"); native(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        focused("movie_detail_more"); openMore()
    }
    private fun openMore() { native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_action_mark") }
    private fun close() {
        native(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movie_actions_dialog").fetchSemanticsNodes().isEmpty() }
        focused("movie_detail_more")
    }
    private fun saved(activity: StartOverCheckActivity, message: String) { compose.waitUntil(12_000) {
        !activity.movieActions.saving && !activity.movieActions.failed && activity.movieActions.message == message
    } }
    private fun records(activity: StartOverCheckActivity) = runBlocking { activity.store.loadAsync().records }
    private fun record(activity: StartOverCheckActivity) = records(activity).first { it.key == "movie:1" }
    private fun otherState(activity: StartOverCheckActivity) {
        assertEquals(activity.baseline.filterNot { it.key == "movie:1" }.sortedBy { it.key },
            records(activity).filterNot { it.key == "movie:1" }.sortedBy { it.key })
        assertEquals(listOf(1), runBlocking { activity.database.movieWatchlistDao().items() }.map { it.streamId })
        assertEquals(listOf(7), runBlocking { activity.database.seriesWatchlistDao().items() }.map { it.seriesId })
        assertEquals(0, activity.startCount)
    }
    private fun controlsFit() {
        val hero = compose.onNodeWithTag("movie_detail_hero").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("movie_detail_play", "movie_detail_watchlist", "movie_detail_start_over", "movie_detail_more", "movie_detail_back")) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull() ?: continue
            val bounds = node.boundsInRoot
            assertTrue("$tag must fit the hero", bounds.left >= hero.left && bounds.right <= hero.right &&
                bounds.top >= hero.top && bounds.bottom <= hero.bottom)
        }
    }

    @Test fun remoteWatchedUndoAndUnwatchedPersistAcrossRecreationWithoutChangingWatchlists() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready(); controlsFit()
            text("movie_detail_play", "Resume")
            val original = record(activity)
            openFromPlay(); text("movie_actions_title", "A Clean Movie"); text("movie_action_mark", "Mark watched")
            native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched")
            assertTrue(record(activity).completed)
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("movie_action_undo"); native(KeyEvent.KEYCODE_DPAD_CENTER)
            saved(activity, "Undone")
            assertEquals(original, record(activity).copy(updatedAtMs = original.updatedAtMs)); otherState(activity)
            close(); text("movie_detail_play", "Resume")
            openMore(); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched"); close()
            text("movie_detail_resume_label", "Watched"); text("movie_detail_play", "Play")
            scenario.recreate(); scenario.onActivity { activity = it }; ready(); controlsFit()
            text("movie_detail_resume_label", "Watched"); assertTrue(record(activity).completed)
            openFromPlay(); text("movie_action_mark", "Mark unwatched"); native(KeyEvent.KEYCODE_DPAD_CENTER)
            saved(activity, "Marked unwatched"); close()
            assertFalse(record(activity).completed); assertEquals(0L, record(activity).positionMs); otherState(activity)
            scenario.recreate(); scenario.onActivity { activity = it }; ready(); controlsFit()
            text("movie_detail_play", "Play"); compose.onNodeWithTag("movie_detail_resume_label").assertDoesNotExist()
            assertFalse(record(activity).completed); assertEquals(0L, record(activity).positionMs); otherState(activity)
        }
    }

    @Test fun pendingAndFailedSaveCannotClaimWatchedAndRetryThenUndoRestoresTheCheckpoint() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }; ready()
            val original = record(activity); val gate = CompletableDeferred<Unit>()
            compose.runOnIdle { activity.manualSaveGate = gate; activity.failManualSave = true }
            openFromPlay(); native(KeyEvent.KEYCODE_DPAD_CENTER)
            text("movie_action_mark", "Saving…"); compose.onNodeWithTag("movie_action_undo").assertIsNotEnabled()
            native(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals(0, activity.manualWrites)
            compose.runOnIdle { gate.complete(Unit) }
            text("movie_action_mark", "Retry save"); compose.onNodeWithTag("movie_action_undo").assertIsNotEnabled()
            assertEquals(original, record(activity)); close(); text("movie_detail_resume_label", "Not saved")
            openMore(); text("movie_action_mark", "Retry save")
            compose.runOnIdle { activity.failManualSave = false; activity.manualSaveGate = null }
            native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Marked watched")
            compose.onNodeWithTag("movie_action_undo").assertIsEnabled(); assertTrue(record(activity).completed)
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_CENTER); saved(activity, "Undone"); close()
            text("movie_detail_play", "Resume")
            assertEquals(original, record(activity).copy(updatedAtMs = original.updatedAtMs)); otherState(activity)
        }
    }
}
