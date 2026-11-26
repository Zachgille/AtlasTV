package com.atlas.atlastv.ui.navigation

import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.playback.NextEpisodeState
import com.atlas.atlastv.playback.NextEpisodeStateHolder
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in provider metadata only. Does not start video or alter personal catalog/watch state. */
class NextEpisodeProviderConnectedTest {
    @Test fun realBlindspotNextLookupFollowsSeasonBoundariesAndHasNoSuccessorAfterFinalEpisode() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = withContext(Dispatchers.IO) {
            ProviderSettingsStore(File(context.filesDir, ProviderSettingsStore.FileName)).load().settings.credentialsOrNull()
        }
        assertNotNull("Saved provider required", credentials)
        val holder = NextEpisodeStateHolder({ id -> SeriesEpisodeLoader().loadEpisodes(credentials!!, id) })
        // The retained provider's Blindspot identity, already used by the real Show acceptance checks.
        holder.ensureNext(36621, 0)
        val loaded = holder.loadedFor(36621)
        assertNotNull("Provider episode metadata is unavailable", loaded)
        val seasons = loaded!!.seasons().filter { it.seasonNumber != null && it.seasonNumber!! > 0 }
        assertTrue("Expected multiple numbered seasons", seasons.size >= 2)
        val firstSeason = loaded.episodesForSeason(seasons.first().seasonNumber)
        val secondSeason = loaded.episodesForSeason(seasons[1].seasonNumber)
        assertTrue(firstSeason.size >= 2)
        holder.ensureNext(36621, firstSeason.first().episodeId)
        assertEquals(firstSeason[1].episodeId, (holder.state as NextEpisodeState.Available).episode.episodeId)
        holder.ensureNext(36621, firstSeason.last().episodeId)
        assertEquals(secondSeason.first().episodeId, (holder.state as NextEpisodeState.Available).episode.episodeId)
        val last = loaded.seasons().flatMap { loaded.episodesForSeason(it.seasonNumber) }.last()
        holder.ensureNext(36621, last.episodeId)
        assertEquals(NextEpisodeState.End, holder.state)
    }
}
