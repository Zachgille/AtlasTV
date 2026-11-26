package com.atlas.atlastv.ui.series

import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.parser.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesShowMetadataTest {
    private fun loader(json: String, register: suspend (List<Pair<String?, String?>>) -> Unit = {}) =
        SeriesEpisodeLoader(object : TextDownloader {
            override suspend fun download(url: String) = TextDownloadResult.Success(json, json.length.toLong())
        }, registerArtwork = register)
    private val credentials = XtreamCredentials("http://example.test", "private-user", "private-pass")
    private val episodes = """"episodes":{"1":[{"id":11,"episode_num":1,"title":"Pilot"}]}"""

    @Test fun retainsUsefulMetadataWithOpaqueArtworkAndSelectsTheFirstValidLandscape() = runBlocking {
        var addresses: List<Pair<String?, String?>> = emptyList()
        val result = loader("""{"info":{"plot":"<p>A mystery &amp; a secret.</p> https://example.test/private-pass",
            "genre":"Drama / Crime","releaseDate":"2015-09-21","cover":"https://example.test/private-pass/poster.jpg",
            "backdrop_path":[null,42,"https://example.test/movie/private-user/private-pass/11.mp4","https://example.test/private-pass/scene.jpg"]},$episodes}""") { addresses = it }
            .loadEpisodes(credentials, 1) as SeriesEpisodeLoadResult.Success
        assertEquals(11, result.episodes.single().episodeId)
        assertEquals("A mystery & a secret.", result.metadata.synopsis)
        assertEquals("2015", result.metadata.releaseYear)
        assertEquals("Drama / Crime", result.metadata.genre)
        assertEquals(AtlasImageState.ProviderImage, result.metadata.backdrop.state)
        assertEquals(2, addresses.size)
        assertFalse(result.toString().contains("private-pass"))
        assertFalse(result.toString().contains("example.test"))
    }

    @Test fun absentOrMalformedOptionalInfoDoesNotRemoveEpisodes() = runBlocking {
        for (info in listOf("null", "[]", "42", """{"plot":{},"genre":[],"releaseDate":"unknown","backdrop_path":{}}""")) {
            val result = loader("{\"info\":$info,$episodes}").loadEpisodes(credentials, 1) as SeriesEpisodeLoadResult.Success
            assertEquals(1, result.episodes.size)
            assertEquals(SeriesShowMetadata(), result.metadata)
        }
    }

    @Test fun optionalArtworkStorageFailureDoesNotBlockEpisodesButCancellationIsPreserved() = runBlocking {
        val json = """{"info":{"backdrop_path":"https://example.test/art.jpg"},$episodes}"""
        val result = loader(json) { error("storage unavailable") }.loadEpisodes(credentials, 1) as SeriesEpisodeLoadResult.Success
        assertEquals(1, result.episodes.size)
        try {
            loader(json) { throw CancellationException("cancel") }.loadEpisodes(credentials, 1)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun parsesSingleLandscapeAndAlternateReleaseDateWhileKeepingEpisodeIdentity() {
        val value = (XtreamJsonParser().parseSeriesInfo("""{"info":{"release_date":"2020-01-01","backdrop_path":"https://example.test/art.jpg"},$episodes}""") as XtreamParseResult.Success).value
        assertEquals("2020-01-01", value.releaseDate)
        assertEquals(listOf("https://example.test/art.jpg"), value.backdrops)
        assertEquals(11, value.episodes.single().id)
    }

    @Test fun descriptionsAndYearsRejectPlaceholdersAndRemainBounded() {
        assertNull(cleanShowDescription(" N/A "))
        assertEquals("A story with spaces.", cleanShowDescription("A story\nwith\u0000 spaces."))
        assertEquals(4000, cleanShowDescription("x".repeat(20000))!!.length)
        assertNull(showReleaseYear("unknown")); assertNull(showReleaseYear("0000"))
        assertEquals("2015", showReleaseYear("2015-09-21"))
    }

    @Test fun lateMetadataFromThePreviousShowCannotReplaceTheCurrentShowsDetails() = runTest {
        val series = listOf(SeriesItem("First", "Shows", 1), SeriesItem("Second", "Shows", 2))
        val holder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogState.Ready("check", 0, series, emptyList())
        }, backgroundScope, StandardTestDispatcher(testScheduler), loadEpisodes = { id ->
            if (id == 1) withContext(NonCancellable) { delay(1000) }
            SeriesEpisodeLoadResult.Success(listOf(SeriesEpisodeItem("Pilot", 1, 1, id * 10, "mp4", "")), SeriesShowMetadata("Show $id"))
        })
        holder.loadCachedCatalog()
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent()
        holder.rememberSeries(series[1])
        launch { holder.ensureEpisodesForSelectedSeries() }; runCurrent(); advanceUntilIdle()
        val ready = holder.state as SeriesCatalogState.Ready
        assertEquals(2, ready.selectedSeriesId)
        assertEquals("Show 2", (ready.episodeState as SeriesEpisodeState.Ready).metadata.synopsis)
        assertEquals(20, ready.selectedEpisodeId)
    }
}
