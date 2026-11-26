package com.atlas.atlastv.ui.home

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.ui.movies.MovieInfoLoader
import com.atlas.atlastv.ui.series.SeriesEpisodeLoader
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.parser.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeHeroMetadataTest {
    private fun card(id: Int, type: HomeContentType = HomeContentType.Movie, seriesId: Int? = null) =
        HomeCard("$type-$id", "Title $id", type, "Movies", id, seriesId, statusText = "")
    private fun holder(dispatcher: CoroutineDispatcher, load: suspend (HomeCard) -> HomeHeroMetadata?) =
        HomeShellStateHolder({ HomeLoadResult(HomeShellState.Error("unused")) }, dispatcher, load)

    @Test fun rapidFocusChangesCancelBeforeRequestingProviderMetadata() = runTest {
        val calls = mutableListOf<Int>()
        val holder = holder(StandardTestDispatcher(testScheduler)) { calls += it.streamId; HomeHeroMetadata("Story") }
        val first = launch { holder.ensureHeroMetadata(card(1)) }
        advanceTimeBy(100); first.cancelAndJoin()
        val second = launch { holder.ensureHeroMetadata(card(2)) }
        advanceTimeBy(100); second.cancelAndJoin()
        launch { holder.ensureHeroMetadata(card(3)) }; advanceUntilIdle()
        assertEquals(listOf(3), calls); assertEquals("movie-3", holder.heroState!!.key)
    }

    @Test fun lateNonCooperativeCompletionCannotReplaceTheCurrentPreview() = runTest {
        val holder = holder(StandardTestDispatcher(testScheduler)) {
            if (it.streamId == 1) withContext(NonCancellable) { delay(1_000) }
            HomeHeroMetadata("Story ${it.streamId}")
        }
        launch { holder.ensureHeroMetadata(card(1)) }; advanceTimeBy(300)
        launch { holder.ensureHeroMetadata(card(2)) }; advanceUntilIdle()
        assertEquals(HomeHeroState("movie-2", HomeHeroMetadata("Story 2")), holder.heroState)
    }

    @Test fun identitiesSeparateMoviesFromShowsAndEpisodesReuseTheirParentShowMetadata() = runTest {
        val calls = mutableListOf<String>()
        val holder = holder(StandardTestDispatcher(testScheduler)) { calls += it.heroMetadataKey(); HomeHeroMetadata("Story") }
        holder.ensureHeroMetadata(card(1)); holder.ensureHeroMetadata(card(1, HomeContentType.Show))
        holder.ensureHeroMetadata(card(99, HomeContentType.SeriesEpisode, 1))
        assertEquals(listOf("movie-1", "show-1"), calls)
        assertEquals("show-1", holder.heroState!!.key)
        assertEquals("Episodes", card(1, HomeContentType.Show).heroActionLabel())
        assertEquals("Resume", card(99, HomeContentType.SeriesEpisode, 1).copy(progressLabel = "Resume at 5:00").heroActionLabel())
    }

    @Test fun metadataCacheIsBoundedAndRecentlyUsedInformationSurvivesReentry() = runTest {
        var calls = 0
        val holder = holder(StandardTestDispatcher(testScheduler)) { calls++; HomeHeroMetadata("Story") }
        for (id in 1..16) holder.ensureHeroMetadata(card(id))
        holder.ensureHeroMetadata(card(1)); holder.ensureHeroMetadata(card(17)); holder.ensureHeroMetadata(card(1))
        assertEquals(17, calls)
        holder.ensureHeroMetadata(card(2)); assertEquals(18, calls)
    }

    @Test fun catalogRefreshInvalidatesPreviewCacheButProgressRefreshKeepsIt() = runTest {
        var calls = 0
        val holder = holder(StandardTestDispatcher(testScheduler)) { HomeHeroMetadata("Story ${++calls}") }
        holder.ensureHeroMetadata(card(1)); holder.refresh(); holder.ensureHeroMetadata(card(1))
        assertEquals(1, calls)
        holder.refresh(clearHeroMetadata = true); holder.ensureHeroMetadata(card(1))
        assertEquals("Story 2", holder.heroState!!.metadata.synopsis)
    }

    @Test fun optionalFailureAndCancellationKeepBasicPreviewAndAllowRetry() = runTest {
        var calls = 0
        val holder = holder(StandardTestDispatcher(testScheduler)) {
            when (++calls) { 1 -> error("private failure"); 2 -> awaitCancellation(); else -> HomeHeroMetadata("Recovered") }
        }
        holder.ensureHeroMetadata(card(1)); assertEquals(HomeHeroState("movie-1"), holder.heroState)
        val job = launch { holder.ensureHeroMetadata(card(1)) }; advanceTimeBy(300); job.cancelAndJoin()
        holder.ensureHeroMetadata(card(1)); assertEquals("Recovered", holder.heroState!!.metadata.synopsis)
    }

    @Test fun seriesPreviewParsesMetadataWithoutMaterializingEpisodesAndUsesParentId() = runBlocking {
        val json = """{"info":{"plot":"<p>A mystery.</p>","release_date":"2015-09-21","backdrop_path":"https://example.test/private-pass/art.jpg"},"episodes":{"1":[{"id":"invalid","episode_num":{},"title":[]}]}}"""
        val parsed = (XtreamJsonParser().parseSeriesMetadata(json) as XtreamParseResult.Success).value
        assertTrue(parsed.episodes.isEmpty()); assertEquals("2015-09-21", parsed.releaseDate)
        var requested = ""
        val downloader = object : TextDownloader {
            override suspend fun download(url: String): TextDownloadResult { requested = url; return TextDownloadResult.Success(json, json.length.toLong()) }
        }
        val metadata = HomeHeroMetadataLoader(MovieInfoLoader(downloader), SeriesEpisodeLoader(downloader))
            .load(XtreamCredentials("https://example.test", "private-user", "private-pass"), card(99, HomeContentType.SeriesEpisode, 31))!!
        assertTrue(requested.contains("series_id=31")); assertFalse(requested.contains("series_id=99"))
        assertEquals("A mystery.", metadata.synopsis); assertEquals("2015", metadata.releaseYear)
        assertFalse(metadata.toString().contains("example.test")); assertFalse(metadata.toString().contains("private-pass"))
    }
}
