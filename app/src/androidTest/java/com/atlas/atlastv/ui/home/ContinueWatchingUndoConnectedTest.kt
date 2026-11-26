package com.atlas.atlastv.ui.home

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual Home/editor, ordered writer, Room and file export; cache fixtures only. */
class ContinueWatchingUndoConnectedTest {
    @get:Rule val ui = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AtlasDatabase::class.java).build()
    private val file = File(instrumentation.targetContext.cacheDir, "continue-undo-${System.nanoTime()}.json")
    private val store = WatchProgressStore(file, WatchProgressRoomStore(db.watchProgressDao()))
    private val gate = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()
    @Volatile private var blocked = false
    @Volatile private var fail = false
    private var played = 0
    private val writer = WatchProgressCoordinator(object : WatchProgressBackend {
        override suspend fun load() = store.loadAsync()
        override suspend fun save(record: WatchProgressRecord) = store.saveRecordAsync(record)
        override suspend fun saveBatch(records: List<WatchProgressRecord>) {
            calls.incrementAndGet()
            if (blocked) gate.await()
            if (fail) error("private storage failure")
            store.saveBatchAsync(records)
        }
    }, scope, retryDelayMs = 30_000)
    private val actions = ContinueWatchingActionsStateHolder(writer, scope)
    private lateinit var home: HomeShellStateHolder
    private fun movie() = WatchProgressRecord(WatchProgressContentType.Movie,1,title = "Arrival",positionMs = 90_000,durationMs = 600_000,updatedAtMs = 10)
    private fun episode(id: Int, completed: Boolean = false) = WatchProgressRecord(WatchProgressContentType.SeriesEpisode,id,seriesId = 3,
        title = "The Sample Show",seriesTitle = "The Sample Show",episodeTitle = "Episode $id",categoryName = "Shows",extension = "mkv",
        positionMs = 120_000,durationMs = 600_000,updatedAtMs = 5,completed = completed)
    private var original = emptyList<WatchProgressRecord>()
    private fun start(records: List<WatchProgressRecord> = listOf(movie(),episode(1),episode(2),episode(3,true))) {
        original = records; runBlocking { store.saveRecordsAsync(records) }
        home = HomeShellStateHolder({ HomeLoadResult(HomeShellMapper().map("fixture",0,
            listOf(MovieItem("Arrival","Movies",1,"mp4","room://fixture")),listOf(SeriesItem("The Sample Show","Shows",3)),
            emptySet(),continueWatching = writer.loadSnapshot().continueWatching())) })
        ui.setContent { LumaireTheme {
            val revision by writer.revision.collectAsState()
            LaunchedEffect(revision) { home.refresh() }
            AtlasHomeScreen(home,onLiveTvSelected = {},onMoviesSelected = {},onSeriesSelected = {},
                onHomeCardSelected = { played++ },onRemoveContinueWatching = actions::remove,continueWatchingActions = actions)
        } }
        waitFor("continue_watching_edit")
        focus("continue_watching_edit"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("continue_watching_editor"); ui.onNodeWithTag("continue_watching_done").assertIsFocused()
    }
    private fun waitFor(tag: String) { ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focus(tag: String) { ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus); ui.onNodeWithTag(tag).assertIsFocused() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); ui.waitForIdle() }
    private fun removeMovie() { key(KeyEvent.KEYCODE_DPAD_DOWN); ui.onNodeWithTag("continue_watching_remove_continue_watching-movie-1").assertIsFocused(); key(KeyEvent.KEYCODE_DPAD_CENTER) }
    private fun acknowledged() { ui.waitUntil(10_000) { actions.canUndo } }
    private fun assertRestored() = runBlocking {
        val rows = WatchProgressRoomStore(db.watchProgressDao()).load().records.associateBy { it.key }
        original.forEach { assertEquals(it,rows.getValue(it.key).copy(updatedAtMs = it.updatedAtMs)) }
        val reopened = WatchProgressStore(file,WatchProgressRoomStore(db.watchProgressDao())).loadAsync().records.associateBy { it.key }
        assertEquals(rows,reopened); assertEquals(0,played)
    }

    @Test fun pendingRemovalAllowsBackAndHomeRetryThenUndoWithoutFocusTheft() {
        blocked = true; fail = true; start(); removeMovie()
        ui.waitUntil(5_000) { calls.get() == 1 }
        assertTrue(actions.saving); assertFalse(actions.canUndo)
        ui.onNodeWithTag("continue_watching_done").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals(1,calls.get())
        key(KeyEvent.KEYCODE_BACK); waitFor("home_hero_primary")
        ui.onNodeWithTag("top_nav_item_search").assertIsFocused()
        gate.complete(Unit)
        ui.waitUntil(10_000) { actions.failed }
        ui.onNodeWithTag("top_nav_item_search").assertIsFocused()
        runBlocking { assertEquals(original.associateBy { it.key },store.loadAsync().records.associateBy { it.key }) }
        // Navigate from the normal Home hero to the compact recovery action.
        key(KeyEvent.KEYCODE_DPAD_DOWN); ui.onNodeWithTag("home_hero_primary").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN); ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        ui.onNodeWithTag("continue_watching_recovery_action").assertTextEquals("Retry")
        fail = false; key(KeyEvent.KEYCODE_DPAD_CENTER); acknowledged()
        ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        ui.onNodeWithTag("continue_watching_recovery_action").assertTextEquals("Undo")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(10_000) { actions.message == "Restored" }; assertRestored()
        ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER); ui.onNodeWithTag("home_hero_primary").assertIsFocused()
    }

    @Test fun showRemovalAndUndoRestoreEveryUnfinishedEpisodeAndKeepWatchedMovieIdentity() {
        start()
        focus("continue_watching_remove_continue_watching-show-3"); key(KeyEvent.KEYCODE_DPAD_CENTER); acknowledged()
        runBlocking {
            val rows = store.loadAsync().records.associateBy { it.key }
            assertEquals(movie(),rows[movie().key]); assertEquals(episode(3,true),rows[episode(3).key])
            assertEquals(0L,rows.getValue(episode(1).key).positionMs); assertEquals(0L,rows.getValue(episode(2).key).positionMs)
        }
        ui.onNodeWithTag("continue_watching_done").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN); ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER); ui.waitUntil(10_000) { actions.message == "Restored" }; assertRestored()
        key(KeyEvent.KEYCODE_BACK); waitFor("home_hero_primary"); assertEquals(0,played)
    }

    @Test fun lastRemovalKeepsUndoAndDoneReachableAndNewPlaybackDisablesOldUndo() {
        start(listOf(movie())); removeMovie(); acknowledged()
        ui.onNodeWithText("You’re all caught up.").assertExists()
        ui.onNodeWithTag("continue_watching_done").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN); ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        val bounds = ui.onNodeWithTag("continue_watching_recovery_action").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.bottom <= ui.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(300)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.cacheDir,"continue-undo-refined.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        writer.openSession(movie().key)
        ui.waitUntil(5_000) { !actions.canUndo }
        ui.onNodeWithTag("continue_watching_recovery_action").assertTextEquals("Done")
        key(KeyEvent.KEYCODE_DPAD_CENTER); ui.onNodeWithTag("continue_watching_done").assertIsFocused()
        key(KeyEvent.KEYCODE_BACK); waitFor("home_hero_primary"); assertEquals(0,played)
    }

    @Test fun failedUndoRetryRestoresOriginalTargetAndKeepsEditorFocus() {
        start(); removeMovie(); acknowledged()
        key(KeyEvent.KEYCODE_DPAD_DOWN); fail = true; key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(10_000) { actions.failed }
        ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
        ui.onNodeWithTag("continue_watching_recovery_action").assertTextEquals("Retry")
        fail = false; key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(10_000) { actions.message == "Restored" }; assertRestored()
        ui.onNodeWithTag("continue_watching_recovery_action").assertIsFocused()
    }
    @After fun close() { scope.cancel(); db.close(); file.delete(); File(file.parentFile,"${file.name}.pending").delete() }
}
