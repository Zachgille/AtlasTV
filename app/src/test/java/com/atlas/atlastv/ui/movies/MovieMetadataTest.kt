package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieMetadataTest {
    private val credentials = XtreamCredentials("https://example.test", "private-user", "private-pass")
    private fun loader(json: String, register: suspend (List<Pair<String?, String?>>) -> Unit = {}) =
        MovieInfoLoader(object : TextDownloader {
            override suspend fun download(url: String) = TextDownloadResult.Success(json, json.length.toLong())
        }, registerArtwork = register)

    @Test fun metadataUsesMovieEndpointAndOpaqueArtworkWithoutLeakingAddresses() = runBlocking {
        val endpoint = XtreamEndpointBuilder().build(credentials, XtreamEndpoint.VodInfo, vodId = 12)
        assertTrue(endpoint.contains("action=get_vod_info")); assertTrue(endpoint.contains("vod_id=12")); assertFalse(endpoint.contains("series_id"))
        assertFalse(XtreamEndpointBuilder().buildRedacted(credentials, XtreamEndpoint.VodInfo, vodId = 12).contains("private-pass"))
        var entries: List<Pair<String?, String?>> = emptyList()
        val metadata = loader("""{"info":{"plot":"<p>A dream &amp; a secret.</p> https://example.test/private-pass",
            "genre":"Action / Mystery","releasedate":"2010-07-16","duration_secs":"8880",
            "movie_image":"https://example.test/private-pass/poster.jpg",
            "backdrop_path":[null,42,"https://example.test/movie/private-user/private-pass/12.mp4","https://example.test/private-pass/scene.jpg"]}}""") { entries = it }.load(credentials, 12)!!
        assertEquals("A dream & a secret.", metadata.synopsis); assertEquals("2010", metadata.releaseYear)
        assertEquals(148, metadata.durationMinutes); assertEquals("Action / Mystery", metadata.genre)
        assertEquals(AtlasImageState.ProviderImage, metadata.backdrop.state); assertEquals(2, entries.size)
        assertFalse(metadata.toString().contains("private-pass")); assertFalse(metadata.toString().contains("example.test"))
    }

    @Test fun malformedOptionalFieldsRemainOptionalAndAliasesAreSupported() = runBlocking {
        for (info in listOf("null", "[]", "42", """{"plot":{},"genre":[],"releasedate":"unknown","duration_secs":-1,"backdrop_path":{}}""")) {
            assertEquals(MovieMetadata(), loader("{\"info\":$info}").load(credentials, 1))
        }
        assertNull(loader("not json").load(credentials, 1))
        val metadata = loader("""{"info":{"description":"A story","release_date":"2020-01-01","cover_big":"https://example.test/art.jpg","backdrop_path":"https://example.test/scene.jpg","duration_secs":999999}}""").load(credentials, 1)!!
        assertEquals("A story", metadata.synopsis); assertEquals("2020", metadata.releaseYear)
        assertNull(metadata.durationMinutes); assertEquals(AtlasImageState.ProviderImage, metadata.poster.state)
        assertEquals(AtlasImageState.ProviderImage, metadata.backdrop.state)
    }

    @Test fun movieRuntimeAcceptsClockFallbackAndRejectsNumericWraparound() = runBlocking {
        assertEquals(120, loader("""{"info":{"duration":"02:00:00"}}""").load(credentials, 1)!!.durationMinutes)
        assertEquals(42, loader("""{"info":{"duration_secs":2520.0}}""").load(credentials, 1)!!.durationMinutes)
        assertEquals(90, loader("""{"info":{"duration_secs":4294974496,"duration":"01:30:00"}}""").load(credentials, 1)!!.durationMinutes)
        for (info in listOf("""{"duration_secs":4294974496}""", """{"duration":"90"}""", """{"duration":"01:90:00"}"""))
            assertNull(loader("{\"info\":$info}").load(credentials, 1)!!.durationMinutes)
    }
    @Test fun matchedPlaybackRuntimeWinsWithoutChangingOtherMetadataOrTheRecord() {
        val metadata = MovieMetadata("Story", durationMinutes = 120)
        val record = com.atlas.atlastv.playback.WatchProgressRecord(com.atlas.atlastv.playback.WatchProgressContentType.Movie,
            1, title = "Movie", durationMs = 5_400_000, positionMs = 60_000, updatedAtMs = 1)
        assertEquals(90, metadata.withPlaybackRuntime(1, record).durationMinutes)
        assertEquals("Story", metadata.withPlaybackRuntime(1, record).synopsis); assertEquals(120, metadata.durationMinutes)
        assertEquals(5_400_000L, record.durationMs)
        assertEquals(120, metadata.withPlaybackRuntime(2, record).durationMinutes)
        assertEquals(120, metadata.withPlaybackRuntime(1, record.copy(contentType = com.atlas.atlastv.playback.WatchProgressContentType.SeriesEpisode)).durationMinutes)
        assertEquals(120, metadata.withPlaybackRuntime(1, record.copy(durationMs = Long.MAX_VALUE)).durationMinutes)
    }

    @Test fun artworkFailureDoesNotDiscardTextAndCancellationPropagates() = runBlocking {
        val json = """{"info":{"plot":"A story","backdrop_path":"https://example.test/art.jpg"}}"""
        assertEquals("A story", loader(json) { error("disk failure") }.load(credentials, 1)!!.synopsis)
        try { loader(json) { throw CancellationException() }.load(credentials, 1); fail("Must propagate cancellation") }
        catch (_: CancellationException) { }
    }

    private val movies = listOf(MovieItem("First", "Movies", 1, "mp4", ""), MovieItem("Second", "Movies", 2, "mp4", ""))
    private fun holder(scope: CoroutineScope, dispatcher: CoroutineDispatcher, load: suspend (Int) -> MovieMetadata?) =
        MoviesCatalogStateHolder(object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("check", 0, movies, emptyList())
        }, scope, dispatcher, load)

    @Test fun lateMetadataCannotOverwriteAnotherMovie() = runTest {
        val holder = holder(backgroundScope, StandardTestDispatcher(testScheduler)) { id ->
            if (id == 1) withContext(NonCancellable) { delay(1000) }
            MovieMetadata("Movie $id")
        }
        holder.loadCachedCatalog(); holder.rememberMovie(movies[0])
        launch { holder.ensureMovieInfo(1) }; runCurrent()
        holder.rememberMovie(movies[1]); launch { holder.ensureMovieInfo(2) }; runCurrent(); advanceUntilIdle()
        assertEquals(MovieInfoState.Ready(2, MovieMetadata("Movie 2")), holder.movieInfoState)
    }

    @Test fun leavingCancelsMetadataAndReentryCanRetryWithoutChangingSelection() = runTest {
        var calls = 0
        val holder = holder(backgroundScope, StandardTestDispatcher(testScheduler)) {
            calls++; if (calls == 1) awaitCancellation() else MovieMetadata("Available")
        }
        holder.loadCachedCatalog(); holder.rememberMovie(movies[0])
        val pending = launch { holder.ensureMovieInfo(1) }; runCurrent(); pending.cancelAndJoin()
        assertEquals(MovieInfoState.Empty, holder.movieInfoState)
        holder.ensureMovieInfo(1)
        assertEquals(1, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
        assertEquals(MovieInfoState.Ready(1, MovieMetadata("Available")), holder.movieInfoState)
    }

    @Test fun normalRefreshAndDetailReturnReuseMetadataButFailureCanRetry() = runTest {
        var calls = 0
        val holder = holder(backgroundScope, StandardTestDispatcher(testScheduler)) { calls++; if (calls == 1) null else MovieMetadata("Available") }
        holder.loadCachedCatalog(); holder.rememberMovie(movies[0]); holder.ensureMovieInfo(1)
        assertEquals(MovieInfoState.Unavailable(1), holder.movieInfoState)
        holder.ensureMovieInfo(1); holder.invalidateCachedCatalog(); holder.ensureCachedCatalog(); holder.ensureMovieInfo(1)
        assertEquals(2, calls); assertEquals(MovieInfoState.Ready(1, MovieMetadata("Available")), holder.movieInfoState)
    }

    @Test fun metadataTextIsBoundedAndHasNoPlaceholderNoise() = runBlocking {
        val metadata = loader("""{"info":{"plot":"${"x".repeat(20000)}","genre":" N/A ","releasedate":"0000"}}""").load(credentials, 1)!!
        assertEquals(4000, metadata.synopsis!!.length); assertNull(metadata.genre); assertNull(metadata.releaseYear)
    }
}
