package com.atlas.atlastv.ui.search

import android.content.Intent
import android.os.Process
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SearchSessionCheckActivity
import com.atlas.atlastv.ui.browse.openGridOptions
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run the two selected phases with an external force-stop between them. */
class SearchSessionConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun id() = requireNotNull(InstrumentationRegistry.getArguments().getString("check_id"))
    private fun launch() = ActivityScenario.launch<SearchSessionCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SearchSessionCheckActivity::class.java).putExtra("check_id", id()))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun tag(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(12_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun save(activity: SearchSessionCheckActivity): SearchSession {
        compose.waitForIdle()
        runBlocking { withTimeout(10_000) { activity.store.awaitSaved() } }
        return Gson().fromJson(activity.sessionFile.readText(), SearchSession::class.java)
    }

    @Test fun stageDeepMovieResultAndScrollContext() {
        launch().use { scenario ->
            lateinit var activity: SearchSessionCheckActivity; scenario.onActivity { activity = it }
            focused("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("find"); tag("unified_search_movies_all")
            compose.onNodeWithTag("unified_search_field").performKeyInput { pressKey(Key.DirectionDown) }; focused("focused_unified_search_result_movie-1")
            compose.onNodeWithTag("focused_unified_search_result_movie-1").performKeyInput { pressKey(Key.DirectionLeft) }; focused("unified_search_movies_all")
            compose.onNodeWithTag("unified_search_movies_all").performKeyInput { pressKey(Key.Enter) }; focused("search_all_tile_0")
            compose.openGridOptions("search_all"); compose.onNodeWithTag("search_all_last").performClick(); focused("search_all_tile_512")
            val stored = save(activity)
            assertEquals("find", stored.query); assertEquals(513, stored.grid!!.selectedId)
            assertEquals(512, stored.grid!!.selectedIndex)
            assertTrue(stored.grid!!.position.index > 0)
            assertTrue(activity.holder.activeGrid!!.holder.state.value.items.size <= 200)
            File(activity.cacheDir, "search-session-${id()}.pid").writeText(Process.myPid().toString())
        }
    }

    @Test fun restoreAfterForceStopThenReturnFromDetailsAndClearWithoutResurrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(File(context.cacheDir, "search-session-${id()}.pid").readText().toInt(), Process.myPid())
        val original = Gson().fromJson(File(context.cacheDir, "search-session-${id()}.json").readText(), SearchSession::class.java)
        launch().use { scenario ->
            lateinit var activity: SearchSessionCheckActivity; scenario.onActivity { activity = it }
            focused("search_all_tile_512")
            assertEquals("find", activity.holder.query); assertEquals(513, activity.holder.activeGrid!!.holder.selectedKey)
            assertEquals(original.grid!!.position, save(activity).grid!!.position)
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("search_session_detail")
            compose.onNodeWithTag("search_session_detail").assertTextEquals("find Movie 513")
            native(KeyEvent.KEYCODE_BACK); focused("search_all_tile_512")
            native(KeyEvent.KEYCODE_BACK); focused("unified_search_movies_all")
            compose.onNodeWithTag("unified_search_field").assertTextEquals("find")
            native(KeyEvent.KEYCODE_BACK); focused("unified_search_field")
            assertEquals("", save(activity).query)
            scenario.recreate(); scenario.onActivity { activity = it }; focused("unified_search_field")
            compose.onNodeWithTag("unified_search_field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            assertNull(activity.holder.activeGrid); assertNull(save(activity).selectedResultId)
        }
    }

    @Test fun restoredEmptyGridKeepsBackUsableAndReturnsToTheSearchField() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val emptyId = id() + "-empty"
        val store = FileSearchSessionStore(File(context.cacheDir, "search-session-$emptyId.json"))
        store.schedule(SearchSession("fixture-owner", "gone", grid = SearchGridSession("unified_search_movies", 513, 512, SearchScrollPosition(510, 0))))
        runBlocking { withTimeout(10_000) { store.awaitSaved() } }
        ActivityScenario.launch<SearchSessionCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), SearchSessionCheckActivity::class.java)
            .putExtra("check_id", emptyId).putExtra("empty_catalog", true)).use {
            focused("search_all_back")
            native(KeyEvent.KEYCODE_BACK); focused("unified_search_field")
            compose.onNodeWithTag("unified_search_field").assertTextEquals("gone")
            compose.onNodeWithTag("unified_search_clear").performClick(); compose.onNodeWithTag("unified_search_field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
    }
}
