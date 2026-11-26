package com.atlas.atlastv.ui.navigation

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
import com.atlas.atlastv.ui.series.SeriesCatalogState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual normal details, preparation and Media3; all records/settings are isolated cache fixtures. */
class StartOverConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(series: Boolean = false, position: Long = 45_000, completed: Boolean = false,
        unavailable: Boolean = false, broken: Boolean = false, audioOnly: Boolean = false,
        failPrepare: Boolean = false, stallPrepare: Boolean = false): ActivityScenario<StartOverCheckActivity> =
        ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), StartOverCheckActivity::class.java)
            .putExtra("check_id", UUID.randomUUID().toString()).putExtra("series", series).putExtra("position", position)
            .putExtra("completed", completed).putExtra("unavailable", unavailable).putExtra("broken_media", broken)
            .putExtra("audio_only", audioOnly).putExtra("fail_prepare", failPrepare).putExtra("stall_prepare", stallPrepare))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun details(activity: StartOverCheckActivity) {
        waitFor(if (activity.isSeries) "series_detail_page" else "movie_detail_screen")
        if (activity.isSeries && activity.series.available) compose.waitUntil(10_000) {
            (activity.seriesHolder.state as? SeriesCatalogState.Ready)?.selectedEpisodeId == 103
        }
    }
    private fun action(activity: StartOverCheckActivity) = if (activity.isSeries) "series_detail_start_over" else "movie_detail_start_over"
    private fun open(activity: StartOverCheckActivity) {
        details(activity); waitFor(action(activity))
        val play = if (activity.isSeries) "series_detail_play_episode" else "movie_detail_play"
        val saved = if (activity.isSeries) "series_detail_watchlist" else "movie_detail_watchlist"
        compose.onNodeWithTag(play).performSemanticsAction(SemanticsActions.RequestFocus)
        native(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag(saved).assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag(action(activity)).assertIsFocused().assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("start_over_dialog")
        compose.onNodeWithTag("start_over_cancel").assertIsFocused()
    }
    private fun confirm() {
        native(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag("start_over_confirm").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun stored(activity: StartOverCheckActivity): List<WatchProgressRecord> = runBlocking {
        activity.writer.awaitSaved(); activity.store.loadAsync().records.sortedBy { it.key }
    }
    private fun original(activity: StartOverCheckActivity) = activity.baseline.sortedBy { it.key }
    private fun target(activity: StartOverCheckActivity) = WatchProgressRecord.key(
        if (activity.isSeries) WatchProgressContentType.SeriesEpisode else WatchProgressContentType.Movie,
        if (activity.isSeries) 103 else 1)
    private fun activeRecord(activity: StartOverCheckActivity) = stored(activity).first { it.key == target(activity) }
    private fun playingFromZero(activity: StartOverCheckActivity) {
        waitFor("playback_progress_label")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pause", substring = false).fetchSemanticsNodes().isNotEmpty() }
        native(KeyEvent.KEYCODE_MEDIA_PAUSE)
        compose.waitUntil(10_000) { activeRecord(activity).let { it.updatedAtMs > activity.baseline.first { row -> row.key == it.key }.updatedAtMs && it.positionMs < 10_000 } }
        assertEquals(0L, activity.prepared!!.resumeMs); assertTrue(activity.prepared!!.startedFromBeginning)
        assertEquals(1, activity.startCount)
        val record = activeRecord(activity); assertFalse(record.completed)
        assertEquals(original(activity).filter { it.key != target(activity) }, stored(activity).filter { it.key != target(activity) })
        assertEquals(listOf(1), runBlocking { activity.database.movieWatchlistDao().items() }.map { it.streamId })
        assertEquals(listOf(7), runBlocking { activity.database.seriesWatchlistDao().items() }.map { it.seriesId })
    }
    @Test fun movieCancelBackAndConfirmUseTheRemoteKeepAllActionsVisibleAndOnlyRestartThatMovie() {
        launch().use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            details(activity); waitFor(action(activity))
            val hero = compose.onNodeWithTag("movie_detail_hero").fetchSemanticsNode().boundsInRoot
            listOf("movie_detail_play", "movie_detail_watchlist", action(activity), "movie_detail_back").forEach {
                val bounds = compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Complete $it in hero", bounds.left >= hero.left && bounds.right <= hero.right && bounds.bottom <= hero.bottom)
            }
            open(activity); native(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag(action(activity)).assertIsFocused(); assertEquals(original(activity), stored(activity)); assertEquals(0, activity.startCount)
            native(KeyEvent.KEYCODE_DPAD_CENTER); compose.onNodeWithTag("start_over_cancel").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_CENTER); compose.onNodeWithTag(action(activity)).assertIsFocused()
            assertEquals(original(activity), stored(activity))
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("start_over_dialog").fetchSemanticsNodes().isEmpty() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(activity.cacheDir, "start-over-controls.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            native(KeyEvent.KEYCODE_DPAD_CENTER); confirm(); playingFromZero(activity)
            val reopened = Room.databaseBuilder(activity, AtlasDatabase::class.java, activity.database.openHelper.databaseName!!).build()
            try { assertEquals(stored(activity), runBlocking { WatchProgressRoomStore(reopened.watchProgressDao()).load().records.sortedBy { it.key } }) }
            finally { reopened.close() }
        }
    }
    @Test fun selectedSecondSeasonEpisodeRestartsAndReturnsToItsExactSelectionWithoutResettingOthers() {
        launch(series = true).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            open(activity); compose.onNodeWithTag("start_over_title").assertTextEquals("A Clean Show · S2 E2")
            confirm(); playingFromZero(activity)
            assertEquals(103, activity.prepared!!.record.streamId); assertEquals(7, activity.prepared!!.record.seriesId)
            native(KeyEvent.KEYCODE_BACK); native(KeyEvent.KEYCODE_BACK); details(activity)
            val ready = activity.seriesHolder.state as SeriesCatalogState.Ready
            assertEquals(2, ready.selectedSeasonNumber); assertEquals(103, ready.selectedEpisodeId)
            compose.onNodeWithTag("series_detail_play_episode").assertIsFocused()
        }
    }
    @Test fun smallPeekHasStartOverWithoutInventingResumeAndCancelDoesNotChangeProgress() {
        launch(position = 1_000).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            details(activity); compose.onNodeWithTag("movie_detail_play").assert(hasText("Play"))
            open(activity); native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag(action(activity)).assertIsFocused(); assertEquals(original(activity), stored(activity))
        }
    }
    @Test fun unstartedCompletedAndUnavailableTitlesHaveNoRedundantRestartAction() {
        for (series in listOf(false, true)) for (state in listOf("unstarted", "completed", "unavailable")) {
            launch(series = series, position = if (state == "unstarted") 0 else 45_000, completed = state == "completed", unavailable = state == "unavailable").use { scenario ->
                lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
                details(activity); compose.onNodeWithTag(action(activity)).assertDoesNotExist()
                assertEquals(original(activity), stored(activity)); assertEquals(0, activity.startCount)
            }
        }
    }
    @Test fun failedLocalVideoAndRetryLeaveThePreviousCheckpointIntact() {
        launch(broken = true).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            open(activity); confirm(); waitFor("playback_retry_focused")
            assertEquals(original(activity), stored(activity))
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("playback_retry_focused")
            assertEquals(original(activity), stored(activity))
            native(KeyEvent.KEYCODE_BACK); details(activity); assertEquals(original(activity), stored(activity))
        }
    }
    @Test fun readyAudioWithoutAVideoFrameCannotEraseTheOldCheckpointOnPauseOrExit() {
        launch(audioOnly = true).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            open(activity); confirm()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Pause", substring = false).fetchSemanticsNodes().isNotEmpty() }
            native(KeyEvent.KEYCODE_MEDIA_PAUSE); assertEquals(original(activity), stored(activity))
            native(KeyEvent.KEYCODE_BACK); native(KeyEvent.KEYCODE_BACK); details(activity)
            assertEquals(original(activity), stored(activity))
        }
    }
    @Test fun preparationRetryPreservesStartOverAndDoesNotResumeAtTheOldPoint() {
        launch(failPrepare = true).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            open(activity); confirm(); waitFor("resume_lookup_retry")
            compose.onNodeWithTag("resume_lookup_retry").assertIsFocused(); assertEquals(original(activity), stored(activity))
            compose.runOnIdle { activity.failPrepare = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
            playingFromZero(activity)
        }
    }
    @Test fun backingOutOfSlowPreparationPreservesProgressAndRejectsTheLateStart() {
        launch(stallPrepare = true).use { scenario ->
            lateinit var activity: StartOverCheckActivity; scenario.onActivity { activity = it }
            open(activity); confirm(); waitFor("resume_lookup_back"); native(KeyEvent.KEYCODE_BACK); details(activity)
            compose.runOnIdle { activity.prepareRelease.complete(Unit) }; compose.waitForIdle()
            assertEquals(0, activity.startCount); assertNull(activity.prepared); assertEquals(original(activity), stored(activity))
        }
    }
}
