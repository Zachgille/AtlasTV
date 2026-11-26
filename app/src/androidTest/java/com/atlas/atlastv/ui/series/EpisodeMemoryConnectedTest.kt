package com.atlas.atlastv.ui.series

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SeriesLoadingCheckActivity
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual details/D-pad flow with private cache preferences, no account/catalog/playback edits. */
class EpisodeMemoryConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SeriesLoadingCheckActivity>()
    @Test fun resumeEpisodeSurvivesSeasonReentryAndNewActivityIgnoresBrowsingChoice() {
        val file = File(compose.activity.cacheDir, "episode-memory-check-${System.nanoTime()}.json")
        val played = AtomicInteger()
        val restoredPlayback = AtomicInteger()
        val show = SeriesItem("Episode Memory Fixture", "English", 5)
        val episodes = listOf(11, 12, 21, 22, 23).map { id ->
            SeriesEpisodeItem("Episode ${id % 10}", id / 10, id % 10, id, "mp4", "room://episode/$id")
        }
        fun fixture() {
            compose.activityRule.scenario.onActivity { activity ->
                val source = object : SeriesCatalogSource {
                    override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
                        val prefs = SeriesPreferencesStore(file).load()
                        return SeriesCatalogState.Ready("fixture", null, listOf(show), emptyList(), selectedSeriesId = 5,
                            selectedSeasonNumber = prefs.selectedSeasonNumber, selectedEpisodeId = prefs.selectedEpisodeId,
                            watchProgress = mapOf(22 to WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 22, 5,
                                title = show.name, positionMs = 80_000, durationMs = 600_000, updatedAtMs = 1)))
                    }
                    override suspend fun savePreferences(preferences: SeriesPreferences) { SeriesPreferencesStore(file).save(preferences) }
                }
                val holder = SeriesCatalogStateHolder(source, activity.lifecycleScope,
                    loadEpisodes = { SeriesEpisodeLoadResult.Success(episodes) }, episodeContext = { "fixture-provider" })
                activity.setContent { AtlasTvTheme {
                    SeriesScreen(holder, onBack = {}, onEpisodeSelected = { _, episode -> played.set(episode.episodeId) },
                        openSelectedSeriesDetailsRequest = 1, selectedSeriesForDetails = show,
                        selectedEpisodeForDetails = episodes.first { it.episodeId == 22 }.takeIf { restoredPlayback.get() == 0 },
                        onPlaybackEpisodeRestored = { restoredPlayback.incrementAndGet() })
                } }
            }
        }
        fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
        fun playLabel(text: String) { compose.waitUntil(12_000) {
            compose.onAllNodes(hasTestTag("series_detail_play_episode") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        } }
        try {
            fixture(); playLabel("Resume S2 E2")
            compose.waitUntil(5_000) { restoredPlayback.get() == 1 }
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("series_detail_play_episode").assertIsFocused(); true }.getOrDefault(false) }
            native(KeyEvent.KEYCODE_DPAD_DOWN)
            native(KeyEvent.KEYCODE_DPAD_LEFT)
            native(KeyEvent.KEYCODE_DPAD_RIGHT)
            playLabel("Resume S2 E2")
            compose.onNodeWithTag("series_selected_episode").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_DOWN)
            playLabel("Play S2 E3")
            compose.waitUntil(5_000) { SeriesPreferencesStore(file).load().selectedEpisodeId == 23 }
            compose.activityRule.scenario.recreate(); fixture(); playLabel("Resume S2 E2")
            compose.onNodeWithTag("series_detail_play_episode").performClick()
            assertEquals(22, played.get())
            assertEquals(1, restoredPlayback.get())
        } finally { file.delete(); File(file.parentFile, "${file.name}.pending").delete() }
    }
}
