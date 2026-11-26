package com.atlas.atlastv.ui.catalog

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.*
import com.atlas.atlastv.ui.home.HomeHeroMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual production screens, isolated cache/fake destinations; no personal writes or provider video. */
class TitleFormatConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun completeLine(tag: String) {
        val lines = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(lines) }
        assertTrue(tag, lines.isNotEmpty()); assertFalse(tag, lines.single().hasVisualOverflow)
    }
    private fun capture(file: File) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun movieHasNoFormatTextAndNativeReturnKeepsTheOriginalMovie() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MovieDetailsCacheCheckActivity::class.java)
            .putExtra("movie_title", "A Movie (2020) HEVC [4K]").putExtra("blocked", true)
        ActivityScenario.launch<MovieDetailsCacheCheckActivity>(intent).use { s ->
            waitTag("movie_detail_synopsis"); focused("movie_detail_play")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
            compose.onNodeWithTag("movie_detail_year").assertTextEquals("2020 · Drama · 2h 28m")
            completeLine("movie_detail_year")
            s.onActivity { assertEquals("A Movie (2020) HEVC [4K]", it.movieTitle); assertEquals(0, it.downloads); capture(File(it.cacheDir, "title-format-movie.png")) }
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movie_cache_destination")
            s.onActivity { assertEquals(1, it.openedMovieId) }
            native(KeyEvent.KEYCODE_BACK); waitTag("movie_detail_title"); focused("movie_detail_play")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
        }
    }
    @Test fun showHasNoFormatTextAndKeepsAllSeasonsAndEpisodesUsable() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShowDetailsCacheCheckActivity::class.java)
            .putExtra("show_title", "A Clean Show (2020) [FHD / H.264]").putExtra("blocked", true)
        ActivityScenario.launch<ShowDetailsCacheCheckActivity>(intent).use { s ->
            waitTag("series_episode_list"); focused("series_detail_play_episode")
            compose.onNodeWithTag("series_detail_title").assertTextEquals("A Clean Show")
            compose.onNodeWithTag("series_detail_seasons").assertTextEquals("2020 · Drama · 24 seasons")
            completeLine("series_detail_seasons")
            s.onActivity { assertEquals("A Clean Show (2020) [FHD / H.264]", it.show.name); assertEquals(0, it.downloads); capture(File(it.cacheDir, "title-format-show.png")) }
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_season_2"); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_selected_episode")
            repeat(2) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
            compose.onNode(hasTestTag("series_episode_name") and hasAnyAncestor(hasTestTag("series_selected_episode")), useUnmergedTree = true).assertTextEquals("Episode 3")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("show_cache_destination")
            s.onActivity { assertEquals(67, it.openedEpisodeId); assertEquals(0, it.downloads) }
            native(KeyEvent.KEYCODE_BACK); waitTag("series_episode_list"); focused("series_detail_play_episode")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
            compose.onNodeWithTag("series_detail_title").assertTextEquals("A Clean Show")
        }
    }
    @Test fun homeCardAndLateHeroMetadataUseCleanTitlesWithoutChangingSelection() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), HomeHeroCheckActivity::class.java)
            .putExtra("movie_title", "A Movie (2010) HEVC")
        ActivityScenario.launch<HomeHeroCheckActivity>(intent).use { s ->
            waitTag("home_hero_primary"); focused("top_nav_item_search")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary")
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("home_hero_details")
            s.onActivity { it.complete(value = HomeHeroMetadata("A dream changes everything.", "Drama", "2010", 148)) }
            waitTag("hero_synopsis"); focused("home_hero_details")
            compose.onNodeWithTag("hero_title").assertTextEquals("A Movie")
            compose.onNodeWithTag("hero_metadata").assertTextEquals("2010 · Drama · 2h 28m")
            completeLine("hero_metadata")
            compose.onNode(hasTestTag("poster_card_title") and hasAnyAncestor(hasTestTag("home_card_continue-movie")), useUnmergedTree = true).assertTextEquals("A Movie")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("home_hero_destination")
            s.onActivity { assertEquals("continue-movie", it.selectedCardId); assertEquals("A Movie (2010) HEVC", it.movieTitle); it.returnHome() }
            waitTag("hero_title"); focused("home_hero_details")
            compose.onNodeWithTag("hero_metadata").assertTextEquals("2010 · Drama · 2h 28m")
        }
    }
}
