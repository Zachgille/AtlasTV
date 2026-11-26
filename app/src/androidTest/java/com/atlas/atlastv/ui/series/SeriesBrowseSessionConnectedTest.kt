package com.atlas.atlastv.ui.series

import android.content.Intent
import android.os.Process
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SeriesBrowseSessionCheckActivity
import com.atlas.atlastv.ui.browse.openGridOptions
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SeriesBrowseSessionConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun id() = requireNotNull(InstrumentationRegistry.getArguments().getString("check_id"))
    private fun launch(empty: Boolean = false, shelfCount: Int = 2) = ActivityScenario.launch<SeriesBrowseSessionCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SeriesBrowseSessionCheckActivity::class.java)
            .putExtra("check_id", id()).putExtra("empty_category", empty).putExtra("shelf_count", shelfCount))
    private fun tag(tag: String) { compose.waitUntil(12_000) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
    private fun focused(tag: String) { compose.waitUntil(12_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun save(activity: SeriesBrowseSessionCheckActivity): SeriesBrowseSession {
        compose.waitForIdle(); runBlocking { withTimeout(10_000) { activity.store.awaitSaved() } }
        return Gson().fromJson(activity.sessionFile.readText(), SeriesBrowseSession::class.java)
    }
    @Test fun deepShelfCardReturnsFromDetailsAndReopensAtTheSavedTitle() {
        launch(shelfCount = 18).use { scenario ->
            lateinit var activity: SeriesBrowseSessionCheckActivity; scenario.onActivity { activity = it }
            tag("series_search_open")
            press("series_search_open", Key.DirectionDown); focused("focused_poster_card")
            repeat(8) { press("focused_poster_card", Key.DirectionRight) }
            assertEquals(9, save(activity).shelves!!.rows.getValue("shows").selectedId)
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("series_detail_title")
            compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 9")
            native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
            assertEquals(9, save(activity).shelves!!.rows.getValue("shows").selectedId)
            press("focused_poster_card", Key.DirectionCenter); tag("series_detail_title")
            compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 9")
            native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
            scenario.recreate(); scenario.onActivity { activity = it }; tag("series_search_open")
            press("series_search_open", Key.DirectionDown); focused("focused_poster_card")
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("series_detail_title")
            compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 9")
        }
    }

    @Test fun stageIndependentAllAndDeepCategoryPositions() {
        launch().use { scenario ->
            lateinit var activity: SeriesBrowseSessionCheckActivity; scenario.onActivity { activity = it }
            tag("series_all_open"); compose.onNodeWithTag("series_all_open").performClick(); focused("all_shows_tile_0")
            compose.openGridOptions("all_shows"); compose.onNodeWithTag("all_shows_last").performClick(); focused("all_shows_tile_512")
            native(KeyEvent.KEYCODE_BACK); focused("series_all_open")
            compose.onNodeWithTag("series_categories_open").performClick(); focused("show_category_choice_0")
            press("show_category_choice_0", Key.PageDown); focused("show_category_choice_10")
            repeat(4) { press("show_category_choice_${10 + it}", Key.DirectionDown) }; focused("show_category_choice_14")
            native(KeyEvent.KEYCODE_DPAD_CENTER); focused("show_category_tile_0")
            compose.openGridOptions("show_category"); compose.onNodeWithTag("show_category_last").performClick(); focused("show_category_tile_512")
            val stored = save(activity)
            assertEquals("category", stored.surface); assertEquals("Drama 14", stored.categoryName)
            assertEquals(513, stored.all.selectedId); assertEquals(1513, stored.category!!.selectedId)
            assertTrue(stored.categoriesIndex > 0); assertTrue(stored.category!!.scrollIndex > 0)
            assertEquals(0, activity.episodeRequests.get())
            File(activity.cacheDir, "show-browse-${id()}.pid").writeText(Process.myPid().toString())
        }
    }
    @Test fun restoreAfterForceStopPreservesCategoryDetailReturnAndIndependentAllPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(File(context.cacheDir, "show-browse-${id()}.pid").readText().toInt(), Process.myPid())
        val saved = Gson().fromJson(File(context.cacheDir, "show-browse-${id()}.json").readText(), SeriesBrowseSession::class.java)
        launch().use { scenario ->
            lateinit var activity: SeriesBrowseSessionCheckActivity; scenario.onActivity { activity = it }
            focused("show_category_tile_512"); assertEquals(1513, activity.categories.grid!!.selectedKey)
            assertEquals(saved.category, save(activity).category)
            assertEquals(0, activity.episodeRequests.get())
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("series_detail_title"); tag("series_episode_list")
            compose.onNodeWithTag("series_detail_title").assertTextEquals("Show 513")
            press("series_detail_play_episode", Key.DirectionDown)
            press("series_selected_episode", Key.DirectionLeft)
            press("series_season_1", Key.DirectionDown)
            press("series_season_2", Key.DirectionRight); focused("series_selected_episode")
            assertEquals(21, (activity.catalog.state as SeriesCatalogState.Ready).selectedEpisodeId)
            native(KeyEvent.KEYCODE_DPAD_CENTER); tag("session_playback_back")
            assertEquals(21, activity.playedEpisodeId)
            compose.onNodeWithTag("session_playback_back").performClick(); tag("series_detail_play_episode")
            assertEquals(21, (activity.catalog.state as SeriesCatalogState.Ready).selectedEpisodeId)
            assertEquals(1, activity.episodeRequests.get())
            compose.onNodeWithTag("series_back").performClick(); focused("show_category_tile_512")
            native(KeyEvent.KEYCODE_BACK); focused("show_category_choice_14")
            assertEquals(saved.categoriesIndex, activity.categories.categories.scrollIndex)
            native(KeyEvent.KEYCODE_BACK); focused("series_categories_open")
            compose.onNodeWithTag("series_all_open").performClick(); focused("all_shows_tile_512")
            assertEquals(513, activity.all.selectedKey); assertEquals(saved.all, save(activity).all)
            native(KeyEvent.KEYCODE_BACK); focused("series_all_open"); assertEquals("browse", save(activity).surface)
            scenario.recreate(); scenario.onActivity { activity = it }; tag("series_all_open")
            compose.onNodeWithTag("all_shows_screen").assertDoesNotExist(); assertFalse(activity.all.open)
        }
    }
    @Test fun missingCategoryStillOffersBackToTheSavedCategoryChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "show-browse-${id()}.json")
        val store = FileSeriesBrowseSessionStore(file)
        store.schedule(SeriesBrowseSession("fixture-owner", "category", categoryName = "Drama 14",
            category = SeriesGridPosition(1513, 512, 510, 0), selectedCategory = "Drama 14", categoriesIndex = 10))
        runBlocking { withTimeout(10_000) { store.awaitSaved() } }
        launch(empty = true).use {
            focused("show_category_back"); tag("show_category_empty")
            native(KeyEvent.KEYCODE_BACK); focused("show_category_choice_14")
        }
    }
}
