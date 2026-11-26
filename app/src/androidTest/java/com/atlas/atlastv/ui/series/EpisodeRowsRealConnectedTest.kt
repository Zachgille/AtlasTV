package com.atlas.atlastv.ui.series

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Read-only real provider metadata and saved checkpoints. Does not play, import or edit personal state. */
class EpisodeRowsRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun child(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("series_selected_episode")), useUnmergedTree = true)
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    @Test fun actualEpisodeRowsMatchProviderRuntimeAndSavedProgressAcrossSeasons() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_search")
        val dao = AtlasDatabaseProvider.database(compose.activity).watchProgressDao()
        val original = runBlocking(Dispatchers.IO) { dao.records() }
        val credentials = runBlocking(Dispatchers.IO) { ProviderSettingsStore(File(compose.activity.filesDir, ProviderSettingsStore.FileName)).load().settings.credentialsOrNull() }
        assumeTrue(credentials != null)
        val load = runBlocking(Dispatchers.IO) { SeriesEpisodeLoader().loadEpisodes(credentials!!, 36621) }
        assertTrue("Real provider episodes must be available", load is SeriesEpisodeLoadResult.Success)
        val episodes = SeriesEpisodeState.Ready((load as SeriesEpisodeLoadResult.Success).episodes)
        compose.onNodeWithTag("top_nav_item_search").performClick(); waitFor("unified_search_field")
        compose.onNodeWithTag("unified_search_field").performTextInput("Blindspot")
        waitFor("unified_search_movies"); compose.onNodeWithTag("unified_search_results").performScrollToIndex(1)
        waitFor("unified_search_result_show-36621"); compose.onNodeWithTag("unified_search_result_show-36621").performClick()
        waitFor("series_episode_list"); native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_LEFT)
        for (season in episodes.seasons()) {
            val seasonTag = "series_season_${season.seasonNumber ?: "unknown"}"
            compose.onNodeWithTag("series_season_list").performScrollToNode(hasTestTag(seasonTag))
            compose.onNodeWithTag(seasonTag).performClick()
            compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag("series_selected_episode").assertIsFocused(); true }.getOrDefault(false) }
            val first = episodes.episodesForSeason(season.seasonNumber).first()
            val record = original.mapNotNull { it.toWatchProgressRecordOrNull() }.firstOrNull { it.streamId == first.episodeId && it.contentType == com.atlas.atlastv.playback.WatchProgressContentType.SeriesEpisode }
            val expected = first.rowPresentation(36621, record)
            child("series_episode_name").assertTextEquals(first.displayEpisodeName("Blindspot (2015)")).assertIsDisplayed()
            if (expected.durationLabel != null) child("series_episode_duration").assertTextEquals(expected.durationLabel).assertIsDisplayed()
            else child("series_episode_duration").assertDoesNotExist()
            if (expected.progress != null) child("series_episode_progress_label").assertTextEquals(expected.progress.timeLabel).assertIsDisplayed()
            else child("series_episode_progress_label").assertDoesNotExist()
            if (expected.progress?.fraction != null) assertEquals(expected.progress.fraction!!,
                child("card_progress_bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.00001f)
            else child("card_progress_bar").assertDoesNotExist()
            child("series_episode_thumbnail").assertIsDisplayed()
            if (season == episodes.seasons().first()) {
                val secondId = episodes.episodesForSeason(season.seasonNumber)[1].episodeId
                val secondBounds = compose.onNodeWithTag("series_episode_$secondId").fetchSemanticsNode().boundsInRoot
                assertTrue("Two real episodes must fit", secondBounds.bottom <= compose.onNodeWithTag("series_episode_list").fetchSemanticsNode().boundsInRoot.bottom)
                // Wait for actual image bytes when the provider supplies them; fallbacks remain usable.
                if (first.thumbnail.providerImageToken != null) compose.waitUntil(15_000) {
                    compose.onAllNodes(hasTestTag("loaded_detail_backdrop") and hasAnyAncestor(hasTestTag("series_selected_episode")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val second = episodes.episodesForSeason(season.seasonNumber)[1]
                if (second.thumbnail.providerImageToken != null) compose.waitUntil(15_000) {
                    compose.onAllNodes(hasTestTag("loaded_detail_backdrop") and hasAnyAncestor(hasTestTag("series_episode_$secondId")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                if (load.metadata.backdrop.providerImageToken != null) compose.waitUntil(15_000) {
                    compose.onAllNodes(hasTestTag("loaded_detail_backdrop") and hasAnyAncestor(hasTestTag("series_detail_header")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(compose.activity.cacheDir, "episode-rows-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            native(KeyEvent.KEYCODE_DPAD_LEFT); compose.onNodeWithTag(seasonTag).assertIsFocused()
        }
        assertEquals(original, runBlocking(Dispatchers.IO) { dao.records() })
        println("Real episode metadata/progress verified for ${episodes.seasons().size} seasons without starting video")
    }
}
