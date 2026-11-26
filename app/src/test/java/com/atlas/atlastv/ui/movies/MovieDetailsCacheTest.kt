package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.series.providerDetailsKey
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsCacheTest {
    private val account = XtreamCredentials("http://provider.test", "opaque-user", "opaque-password")
    private val metadata = MovieMetadata("A mystery.", "Drama", "2020", 148,
        atlasImageRefFromRaw("https://art.test/opaque-password/backdrop.jpg"), atlasImageRefFromRaw("fixture://poster/movie"))
    private fun saved(id: Int = 1, time: Long = 100, stamp: Long? = 1) = SavedMovieDetails(providerMovieDetailsKey(account), id, time, time, stamp, metadata)
    private class Store(var value: SavedMovieDetails? = null) : MovieDetailsStore {
        var readFails = false; var saveFails = false; var writes = 0
        override suspend fun read(key: String, id: Int): SavedMovieDetails? {
            if (readFails) error("private storage error")
            return value?.takeIf { it.providerKey == key && it.streamId == id }
        }
        override suspend fun save(value: SavedMovieDetails) {
            if (saveFails) error("private storage error")
            writes++; if ((this.value?.requestedAtMs ?: Long.MIN_VALUE) <= value.requestedAtMs) this.value = value
        }
    }
    @Test fun accountKeysAreOpaqueLengthDelimitedAndSeparateMovieFromShowIdentity() {
        val key = providerMovieDetailsKey(account)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(key, providerMovieDetailsKey(account.copy(baseUrl = " http://provider.test/// ")))
        assertNotEquals(key, providerDetailsKey(account))
        for (other in listOf(account.copy(username = "opaque-user "), account.copy(password = "opaque-password "), account.copy(baseUrl = "http://other.test")))
            assertNotEquals(key, providerMovieDetailsKey(other))
        assertNotEquals(providerMovieDetailsKey(account.copy(username = "a", password = "bc")), providerMovieDetailsKey(account.copy(username = "ab", password = "c")))
    }
    @Test fun metadataRoundTripRetainsEveryPresentationFieldAndOpaqueArtworkOnly() {
        val payload = MovieDetailsCacheCodec.encode(metadata)!!
        assertEquals(metadata, MovieDetailsCacheCodec.decode(payload))
        assertFalse(payload.contains("opaque-password")); assertFalse(payload.contains("https://")); assertFalse(payload.contains("art.test"))
        assertFalse(payload.contains("username")); assertFalse(payload.contains("streamUrl"))
    }
    @Test fun unsafeTextAndOutOfRangeValuesCannotProduceAddressOrPlaceholderNoise() {
        val payload = MovieDetailsCacheCodec.encode(metadata.copy(synopsis = "<p>A story &amp; a dream.</p> https://private/opaque-password",
            genre = "N/A", releaseYear = "0000", durationMinutes = 99999))!!
        assertFalse(payload.contains("private")); assertFalse(payload.contains("opaque-password"))
        val decoded = MovieDetailsCacheCodec.decode(payload)!!
        assertEquals("A story & a dream.", decoded.synopsis); assertNull(decoded.genre); assertNull(decoded.releaseYear); assertNull(decoded.durationMinutes)
    }
    @Test fun optionalTextIsBoundedAndUnknownMetadataIsNotCached() {
        val decoded = MovieDetailsCacheCodec.decode(MovieDetailsCacheCodec.encode(MovieMetadata("x".repeat(20000), "g".repeat(1000)))!!)!!
        assertEquals(4000, decoded.synopsis!!.length); assertEquals(100, decoded.genre!!.length)
        assertNull(MovieDetailsCacheCodec.encode(MovieMetadata()))
        assertNull(MovieDetailsCacheCodec.encode(MovieMetadata("N/A", "none", "0000", -1)))
    }
    @Test fun corruptUnsupportedAndOversizedPayloadsAreRejectedRatherThanShown() {
        for (payload in listOf("{", "null", "{}", "[]", " ".repeat(MovieDetailsCacheCodec.MaxEntryBytes + 1),
            MovieDetailsCacheCodec.encode(metadata)!!.replace("\"version\":1", "\"version\":2"))) assertNull(MovieDetailsCacheCodec.decode(payload))
        val edited = MovieDetailsCacheCodec.encode(metadata)!!.replace("fixture://poster/movie", "http://private/password")
        assertNull(MovieDetailsCacheCodec.decode(edited)!!.poster.safeUri)
        assertNull(MovieDetailsCacheCodec.decode(edited)!!.poster.providerImageToken)
    }
    @Test fun savedLookupSeparatesAccountAndMovieIds() = runTest {
        val repo = MovieDetailsRepository(Store(saved()), { _, _ -> metadata })
        assertEquals(metadata, repo.saved(account, 1)!!.metadata)
        assertNull(repo.saved(account.copy(password = "another-account"), 1)); assertNull(repo.saved(account, 2))
    }
    @Test fun freshnessIncludesAgeCatalogStampAndFutureClock() = runTest {
        var stamp = 1L; var now = 101L
        val repo = MovieDetailsRepository(Store(), { _, _ -> metadata }, { stamp }, { now })
        assertFalse(repo.stale(saved())); stamp = 2; assertTrue(repo.stale(saved()))
        stamp = 1; now = 99; assertTrue(repo.stale(saved())); now = 100 + MovieDetailsRepository.FreshMs; assertTrue(repo.stale(saved()))
    }
    @Test fun nullAndEmptyProviderResponsesRetainTheExactPreviousSnapshotWithoutWrites() = runTest {
        for (response in listOf(null, MovieMetadata())) {
            val store = Store(saved()); val previous = store.value
            val repo = MovieDetailsRepository(store, { _, _ -> response }, { 1 }, { 200 })
            assertNull(repo.refresh(account, 1)); assertEquals(previous, store.value); assertEquals(0, store.writes)
        }
    }
    @Test fun partialProviderResponseKeepsLastGoodMetadataAndArtwork() = runTest {
        val store = Store(saved()); val repo = MovieDetailsRepository(store, { _, _ -> MovieMetadata("New synopsis.") }, { 1 }, { 200 })
        assertEquals(metadata.copy(synopsis = "New synopsis."), repo.refresh(account, 1))
        assertEquals(1, store.writes); assertEquals(200L, store.value!!.savedAtMs)
    }
    @Test fun optionalReadWriteAndCatalogErrorsDoNotDiscardOnlineInformation() = runTest {
        val store = Store().apply { readFails = true; saveFails = true }
        val repo = MovieDetailsRepository(store, { _, _ -> metadata }, { error("private catalog error") })
        assertNull(repo.saved(account, 1)); assertEquals(metadata, repo.refresh(account, 1))
    }
    @Test fun cancellationPropagatesAndFailedRefreshDoesNotWrite() = runTest {
        val store = Store(saved()); val previous = store.value
        val repo = MovieDetailsRepository(store, { _, _ -> throw CancellationException("leave") })
        try { repo.refresh(account, 1); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(previous, store.value); assertEquals(0, store.writes)
    }
    @Test fun lateOldProviderRequestCannotOverwriteTheNewerPersistedSnapshot() = runTest {
        val release = CompletableDeferred<Unit>(); var calls = 0; val store = Store()
        val repo = MovieDetailsRepository(store, { _, _ -> if (++calls == 1) { release.await(); metadata.copy(synopsis = "Old") } else metadata.copy(synopsis = "New") }, clockMs = { 100 })
        val old = launch { repo.refresh(account, 1) }; runCurrent()
        repo.refresh(account, 1); release.complete(Unit); old.join()
        assertEquals("New", store.value!!.metadata.synopsis)
    }
    private val movies = listOf(MovieItem("First (2020)", "Movies", 1, "mp4", ""), MovieItem("Second", "Movies", 2, "mp4", ""))
    private class Source(var movies: List<MovieItem>) : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("check", 0, movies, emptyList())
    }
    @Test fun freshSavedDetailsAvoidDownloadAndExplicitRefreshRetainsOldOptionalFields() = runTest {
        var calls = 0
        val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { calls++; MovieMetadata("Updated") }, loadSavedMovieInfo = { saved() }, savedNeedsRefresh = { false }, clockMs = { 101 })
        holder.loadCachedCatalog(); holder.ensureMovieInfo(1); holder.ensureMovieInfo(1)
        assertEquals(0, calls); assertTrue((holder.movieInfoState as MovieInfoState.Ready).saved)
        holder.retryMovieInfo(); holder.ensureMovieInfo(1)
        assertEquals(1, calls); assertEquals(metadata.copy(synopsis = "Updated"), (holder.movieInfoState as MovieInfoState.Ready).metadata)
    }
    @Test fun savedDetailsAreVisibleBeforeNetworkCompletesAndFailureKeepsSameMovie() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { release.await(); null }, loadSavedMovieInfo = { saved() }, savedNeedsRefresh = { true })
        holder.loadCachedCatalog(); val load = launch { holder.ensureMovieInfo(1) }; runCurrent()
        assertEquals(metadata, (holder.movieInfoState as MovieInfoState.Ready).metadata)
        assertTrue((holder.movieInfoState as MovieInfoState.Ready).refreshing)
        release.complete(Unit); load.join()
        val info = holder.movieInfoState as MovieInfoState.Ready
        assertTrue(info.saved); assertTrue(info.refreshFailed); assertFalse(info.refreshing)
        assertEquals(metadata, info.metadata); assertEquals(1, (holder.state as MoviesCatalogState.Ready).selectedMovieStreamId)
    }
    @Test fun emptyResponseAndExceptionKeepLastGoodDetailsWithHonestFailureState() = runTest {
        for (throws in listOf(false, true)) {
            val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
                loadMovieInfo = { if (throws) error("private provider failure") else MovieMetadata() }, loadSavedMovieInfo = { saved() })
            holder.loadCachedCatalog(); holder.ensureMovieInfo(1)
            val info = holder.movieInfoState as MovieInfoState.Ready
            assertTrue(info.refreshFailed); assertEquals(metadata, info.metadata)
        }
    }
    @Test fun optionalSavedReadFailureStillLoadsOnlineAndNoCacheFailureIsRetryable() = runTest {
        var calls = 0
        val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { if (++calls == 1) null else metadata }, loadSavedMovieInfo = { error("private storage error") })
        holder.loadCachedCatalog(); holder.ensureMovieInfo(1); assertEquals(MovieInfoState.Unavailable(1), holder.movieInfoState)
        holder.retryMovieInfo(); holder.ensureMovieInfo(1); assertEquals(metadata, (holder.movieInfoState as MovieInfoState.Ready).metadata)
    }
    @Test fun cancelledRefreshKeepsSavedDetailsAndStopsSpinner() = runTest {
        val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { awaitCancellation() }, loadSavedMovieInfo = { saved() })
        holder.loadCachedCatalog(); val load = launch { holder.ensureMovieInfo(1) }; runCurrent(); load.cancelAndJoin()
        val info = holder.movieInfoState as MovieInfoState.Ready
        assertTrue(info.saved); assertFalse(info.refreshing); assertEquals(metadata, info.metadata)
    }
    @Test fun accountChangeHidesSavedMemoryAndRejectsLateOldCompletion() = runTest {
        var context = providerMovieDetailsKey(account); val release = CompletableDeferred<Unit>(); var calls = 0
        val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { if (++calls == 1) { release.await(); metadata.copy(synopsis = "Old") } else metadata.copy(synopsis = "New") },
            loadSavedMovieInfo = { saved() }, movieInfoContext = { context })
        holder.loadCachedCatalog(); val old = launch { holder.ensureMovieInfo(1) }; runCurrent()
        context = "different-account"; assertEquals(MovieInfoState.Empty, holder.visibleMovieInfoState(1))
        holder.ensureMovieInfo(1); release.complete(Unit); old.join()
        assertEquals("New", (holder.visibleMovieInfoState(1) as MovieInfoState.Ready).metadata.synopsis)
    }
    @Test fun wrongMovieOrUnavailableTitleCannotReadCacheOrContactProvider() = runTest {
        var calls = 0
        val holder = MoviesCatalogStateHolder(Source(movies.map { it.copy(available = false) }), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { calls++; metadata }, loadSavedMovieInfo = { calls++; saved() })
        holder.loadCachedCatalog(); holder.ensureMovieInfo(1); holder.ensureMovieInfo(2)
        assertEquals(0, calls); assertEquals(MovieInfoState.Empty, holder.movieInfoState)
    }
    @Test fun wrongIdOrAccountCacheCannotBeShownForTheSelectedMovie() = runTest {
        for (value in listOf(saved(id = 2), saved().copy(providerKey = "another-account"))) {
            val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
                loadMovieInfo = { null }, loadSavedMovieInfo = { value }, movieInfoContext = { providerMovieDetailsKey(account) })
            holder.loadCachedCatalog(); holder.ensureMovieInfo(1)
            assertEquals(MovieInfoState.Unavailable(1), holder.movieInfoState)
        }
    }
    @Test fun expiredWarmMemoryRefreshesAndLeavingForAnotherMovieRejectsLateSavedLookup() = runTest {
        var now = 101L; var calls = 0
        val holder = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { calls++; metadata.copy(synopsis = "Refreshed") }, loadSavedMovieInfo = { saved() }, savedNeedsRefresh = { false }, clockMs = { now })
        holder.loadCachedCatalog(); holder.ensureMovieInfo(1); now += MovieDetailsRepository.FreshMs; holder.ensureMovieInfo(1)
        assertEquals(1, calls); assertEquals("Refreshed", (holder.movieInfoState as MovieInfoState.Ready).metadata.synopsis)
        val release = CompletableDeferred<Unit>()
        val other = MoviesCatalogStateHolder(Source(movies), backgroundScope, StandardTestDispatcher(testScheduler),
            loadMovieInfo = { metadata.copy(synopsis = "Movie $it") }, loadSavedMovieInfo = { if (it == 1) { withContext(NonCancellable) { release.await() }; saved() } else null })
        other.loadCachedCatalog(); val old = launch { other.ensureMovieInfo(1) }; runCurrent()
        other.rememberMovie(movies[1]); other.ensureMovieInfo(2); release.complete(Unit); old.join()
        assertEquals(2, (other.movieInfoState as MovieInfoState.Ready).streamId)
        assertEquals("Movie 2", (other.movieInfoState as MovieInfoState.Ready).metadata.synopsis)
    }
}
