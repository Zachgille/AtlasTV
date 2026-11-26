package com.atlas.atlastv.ui.home

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.toWatchProgressRecordOrNull
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy
import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.ui.catalog.cardWatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Actual retained Home and native remote; reads only, never video or personal mutations. */
class ContinueWatchingGroupingRealConnectedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { ui.waitUntil(30_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun key(code: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); ui.waitForIdle() }

    @Test fun retainedHomeShowsEachParentOnceAndEveryCardKeepsItsExactCheckpoint() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("home_hero_primary")
        val db = AtlasDatabaseProvider.database(ui.activity)
        val before = runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }
        val moviesBefore = runBlocking(Dispatchers.IO) { db.movieWatchlistDao().items() }
        val showsBefore = runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items() }
        val eligible = before.mapNotNull { it.toWatchProgressRecordOrNull() }
            .filter(WatchProgressPolicy::isResumeEligible).sortedByDescending { it.updatedAtMs }
        val parentGroups = eligible.filter { it.contentType == WatchProgressContentType.SeriesEpisode && (it.seriesId ?: 0) > 0 }
            .groupBy { it.seriesId }
        val selectedEpisodes = parentGroups.values.map { it.first().key }.toSet()
        val expected = eligible.filter { record ->
            record.contentType != WatchProgressContentType.SeriesEpisode || (record.seriesId ?: 0) <= 0 || record.key in selectedEpisodes
        }.take(12).filter {
            !PlaylistCategoryFilter().excludes(it.categoryName.orEmpty()) && !EnglishCatalogPolicy.excludesTitle(it.seriesTitle ?: it.title)
        }.filter { it.contentType != WatchProgressContentType.SeriesEpisode || it.seriesId != null }
        assertTrue("Retained-library acceptance requires resumable content", expected.isNotEmpty())
        val loaded = runBlocking(Dispatchers.IO) { HomeCatalogSource(ui.activity).load() }
        val cards = (loaded.state as HomeShellState.Ready).rows.first { it.id == HomeRowIds.ContinueWatching }.cards
        assertEquals(expected.map { it.streamId }, cards.map { it.streamId })
        val showCards = cards.filter { it.contentType == HomeContentType.SeriesEpisode }
        assertEquals(showCards.size, showCards.map { it.seriesId }.toSet().size)
        assertEquals(expected, loaded.continueWatching.filter { it.contentType != WatchProgressContentType.SeriesEpisode || it.seriesId != null })

        key(KeyEvent.KEYCODE_DPAD_DOWN); ui.onNodeWithTag("home_hero_primary").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        expected.forEachIndexed { index, record ->
            if (index > 0) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            ui.onNodeWithTag("focused_home_card").assertIsFocused()
            val footer = hasAnyAncestor(hasTestTag("focused_home_card"))
            val progress = record.cardWatchProgress()
            ui.onNode(hasTestTag("card_time_label") and footer, useUnmergedTree = true).assertTextEquals(progress.timeLabel).assertIsDisplayed()
            progress.episodeLabel?.let { ui.onNode(hasTestTag("card_episode_label") and footer, useUnmergedTree = true).assertTextEquals(it).assertIsDisplayed() }
        }
        assertEquals(before, runBlocking(Dispatchers.IO) { db.watchProgressDao().records() })
        assertEquals(moviesBefore, runBlocking(Dispatchers.IO) { db.movieWatchlistDao().items() })
        assertEquals(showsBefore, runBlocking(Dispatchers.IO) { db.seriesWatchlistDao().items() })
        println("Actual retained Home: ${cards.size} grouped cards; all ${before.size} saved checkpoints and both Watchlists unchanged")
    }
}
