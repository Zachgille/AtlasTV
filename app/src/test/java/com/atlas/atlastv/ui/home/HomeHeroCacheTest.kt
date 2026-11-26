package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.movies.providerMovieDetailsKey
import com.atlas.atlastv.ui.series.providerDetailsKey
import com.atlas.atlastv.ui.series.ShowDetailsCacheCodec
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeHeroCacheTest {
    private val account = XtreamCredentials("https://preview.invalid", "opaque user", "opaque password")
    private fun card(id: Int = 1, type: HomeContentType = HomeContentType.Movie, parent: Int? = null) =
        HomeCard("$type-$id", "Title $id", type, "Movies", id, parent, statusText = "")
    private val metadata = HomeHeroMetadata("A story.", "Drama", "2020", 148,
        atlasImageRefFromRaw("https://art.invalid/opaque-password/backdrop.jpg"), atlasImageRefFromRaw("fixture://poster/movie"))
    private fun saved(card: HomeCard = card(), time: Long = 100, stamp: Long? = 1) =
        SavedHomeHero(providerHomeHeroKey(account), card.heroCacheIdentity()!!, time, time, stamp, metadata)
    private class Store(var value: SavedHomeHero? = null) : HomeHeroStore {
        var readFails = false; var saveFails = false; var writes = 0
        override suspend fun read(key: String, id: String): SavedHomeHero? {
            if (readFails) error("private read")
            return value?.takeIf { it.providerKey == key && it.metadataKey == id }
        }
        override suspend fun save(value: SavedHomeHero) {
            if (saveFails) error("private save")
            writes++; if ((this.value?.requestedAtMs ?: Long.MIN_VALUE) <= value.requestedAtMs) this.value = value
        }
    }
    private fun holder(dispatcher: CoroutineDispatcher, store: Store, repo: HomeHeroRepository,
        context: () -> String? = { providerHomeHeroKey(account) }, download: suspend (HomeCard) -> HomeHeroMetadata? = { repo.refresh(account, it) },
        clock: () -> Long = { 101 }) = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Error("unused")) }, dispatcher,
        loadHeroMetadata = download, heroDebounceMs = 250, loadSavedHeroMetadata = { repo.saved(account, it) },
        savedHeroNeedsRefresh = repo::stale, heroContext = context, clockMs = clock)

    @Test fun opaqueNamespaceAndTypedParentIdentityDoNotCollide() {
        val key = providerHomeHeroKey(account)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(key, providerMovieDetailsKey(account)); assertNotEquals(key, providerDetailsKey(account))
        assertEquals(key, providerHomeHeroKey(account.copy(baseUrl = " https://preview.invalid/// ")))
        assertNotEquals(key, providerHomeHeroKey(account.copy(password = account.password + " ")))
        assertNotEquals(providerHomeHeroKey(account.copy(username = "a", password = "bc")), providerHomeHeroKey(account.copy(username = "ab", password = "c")))
        assertNotEquals(card().heroCacheIdentity(), card(type = HomeContentType.Show).heroCacheIdentity())
        assertEquals("show-1", card(99, HomeContentType.SeriesEpisode, 1).heroCacheIdentity())
        assertNull(card(99, HomeContentType.SeriesEpisode).heroCacheIdentity()); assertNull(card(0).heroCacheIdentity())
    }
    @Test fun safePreviewRoundTripHasAllFieldsWithoutAccountAddressesOrEpisodeLists() {
        val json = HomeHeroCacheCodec.encode(metadata)!!
        assertEquals(metadata, HomeHeroCacheCodec.decode(json))
        for (value in listOf("https://", "opaque-password", "episodes", "username", "streamUrl")) assertFalse(json.contains(value))
        val cleaned = HomeHeroCacheCodec.safe(metadata.copy(synopsis = "<p>A story &amp; dream.</p> https://private.invalid/pass",
            durationMinutes = -1, releaseYear = "not a year"))
        assertEquals("A story & dream.", cleaned.synopsis); assertNull(cleaned.durationMinutes); assertNull(cleaned.releaseYear)
    }
    @Test fun emptyMalformedWrongVersionOversizedAndUnsafeLocalSnapshotsAreRejected() {
        assertNull(HomeHeroCacheCodec.encode(HomeHeroMetadata()))
        assertNull(HomeHeroCacheCodec.decode("{bad")); assertNull(HomeHeroCacheCodec.decode("{}"))
        assertNull(HomeHeroCacheCodec.decode(HomeHeroCacheCodec.encode(metadata)!!.replace("\"version\":1", "\"version\":9")))
        assertNull(HomeHeroCacheCodec.decode("x".repeat(HomeHeroCacheCodec.MaxEntryBytes + 1)))
        val json = """{"version":1,"backdrop":{"local":"http://private.invalid/art.jpg"},"poster":{}}"""
        assertNull(HomeHeroCacheCodec.decode(json))
    }
    @Test fun showSnapshotPreviewSkipsEpisodeModelsAndStillSanitizesVersionAndOptionalFields() {
        val json = """{"version":1,"synopsis":"<p>A Show.</p>","genre":"Drama","year":"2015","backdrop":{},"poster":{},"episodes":[{"id":[],"number":{},"title":[]}]}"""
        assertNull(ShowDetailsCacheCodec.decode(json))
        assertEquals("A Show.", ShowDetailsCacheCodec.decodeMetadata(json)!!.synopsis)
        assertEquals("2015", ShowDetailsCacheCodec.decodeMetadata(json)!!.releaseYear)
        assertNull(ShowDetailsCacheCodec.decodeMetadata(json.replace("\"version\":1", "\"version\":99")))
        assertNull(ShowDetailsCacheCodec.decodeMetadata("x".repeat(ShowDetailsCacheCodec.MaxEntryBytes + 1)))
    }
    @Test fun freshnessUsesTypedCatalogStampAgeAndClockRollback() = runTest {
        var time = 101L; var stamp: Long? = 1
        val repo = HomeHeroRepository(Store(saved()), { _, _ -> null }, { stamp }, clockMs = { time })
        assertFalse(repo.stale(saved(), card())); time = 100 + HomeHeroRepository.FreshMs; assertTrue(repo.stale(saved(), card()))
        time = 99; assertTrue(repo.stale(saved(), card())); time = 101; stamp = 2; assertTrue(repo.stale(saved(), card()))
        stamp = 1; assertTrue(repo.stale(saved(), card(type = HomeContentType.Show)))
    }
    @Test fun newerDetailSnapshotIsReusedButWrongAccountIdentityAndEmptyFallbackAreRefused() = runTest {
        val store = Store(saved()); var fallback: SavedHomeHero? = saved(time = 200).copy(metadata = metadata.copy(synopsis = "Detail"))
        val repo = HomeHeroRepository(store, { _, _ -> error("no download") }, detailFallback = { _, _ -> fallback })
        assertEquals("Detail", repo.saved(account, card())!!.metadata.synopsis)
        assertEquals("Detail", store.value!!.metadata.synopsis); store.value = saved()
        fallback = fallback!!.copy(providerKey = "wrong"); assertEquals(metadata, repo.saved(account, card())!!.metadata)
        fallback = saved().copy(metadataKey = "show-1"); assertEquals(metadata, repo.saved(account, card())!!.metadata)
        fallback = saved().copy(metadata = HomeHeroMetadata()); assertEquals(metadata, repo.saved(account, card())!!.metadata)
        assertNull(repo.saved(account.copy(password = "another"), card())); assertNull(repo.saved(account, card(2)))
    }
    @Test fun optionalOwnReadFailureStillReusesValidDetailAndOptionalDetailFailureKeepsOwn() = runTest {
        val store = Store(saved()); store.readFails = true
        val repo = HomeHeroRepository(store, { _, _ -> null }, detailFallback = { _, _ -> saved() })
        assertEquals(metadata, repo.saved(account, card())!!.metadata)
        store.readFails = false
        assertEquals(metadata, HomeHeroRepository(store, { _, _ -> null }, detailFallback = { _, _ -> error("private") }).saved(account, card())!!.metadata)
    }
    @Test fun emptyAndFailedRefreshCannotReplaceSavedMetadata() = runTest {
        val store = Store(saved()); var mode = 0
        val repo = HomeHeroRepository(store, { _, _ -> when(mode) { 0 -> null; 1 -> HomeHeroMetadata(); else -> error("private") } })
        assertNull(repo.refresh(account, card())); mode = 1; assertNull(repo.refresh(account, card()))
        mode = 2; try { repo.refresh(account, card()); fail() } catch (_: IllegalStateException) { }
        assertEquals(metadata, store.value!!.metadata); assertEquals(0, store.writes)
    }
    @Test fun partialOnlineRefreshRetainsSavedFieldsAndOptionalSaveOrStampFailureDoesNotDiscardOnline() = runTest {
        val store = Store(saved()); store.saveFails = true
        val repo = HomeHeroRepository(store, { _, _ -> HomeHeroMetadata("New story.") }, { error("stamp") })
        assertEquals(metadata.copy(synopsis = "New story."), repo.refresh(account, card()))
        assertEquals(metadata, store.value!!.metadata)
    }
    @Test fun cancellationOfOptionalStorageStillPropagates() = runTest {
        val cancelled = object : HomeHeroStore {
            override suspend fun read(key: String, id: String): SavedHomeHero? = throw CancellationException()
            override suspend fun save(value: SavedHomeHero) { }
        }
        try { HomeHeroRepository(cancelled, { _, _ -> null }).saved(account, card()); fail() } catch (_: CancellationException) { }
    }
    @Test fun overlappingProviderResultsCannotOverwriteANewerPersistedPreview() = runTest {
        val store = Store(); val release = CompletableDeferred<Unit>(); var calls = 0
        val repo = HomeHeroRepository(store, { _, _ -> if (++calls == 1) { release.await(); HomeHeroMetadata("Old") } else HomeHeroMetadata("New") }, clockMs = { 100 })
        val first = launch { repo.refresh(account, card()) }; runCurrent()
        repo.refresh(account, card()); release.complete(Unit); first.join()
        assertEquals("New", store.value!!.metadata.synopsis)
    }
    @Test fun invalidEpisodeParentAndNonpositiveIdsCannotReadOrDownload() = runTest {
        val badStore = object : HomeHeroStore {
            override suspend fun read(key: String, id: String): SavedHomeHero? = error("read called")
            override suspend fun save(value: SavedHomeHero) = error("write called")
        }
        val repo = HomeHeroRepository(badStore, { _, _ -> error("download called") })
        for (value in listOf(card(0), card(99, HomeContentType.SeriesEpisode))) {
            assertNull(repo.saved(account, value)); assertNull(repo.refresh(account, value))
        }
    }
    @Test fun newHolderShowsFreshDiskSnapshotWithoutProviderWork() = runTest {
        var calls = 0; val store = Store(saved())
        val repo = HomeHeroRepository(store, { _, _ -> calls++; null }, { 1 }, clockMs = { 101 })
        val first = holder(StandardTestDispatcher(testScheduler), store, repo); first.ensureHeroMetadata(card())
        assertEquals(HomeHeroState("movie-1", metadata, true), first.heroState)
        val reopened = holder(StandardTestDispatcher(testScheduler), store, repo); reopened.ensureHeroMetadata(card())
        assertEquals(first.heroState, reopened.heroState); assertEquals(0, calls)
    }
    @Test fun staleSavedPreviewAppearsBeforeSlowRefreshAndSurvivesNullEmptyAndExceptions() = runTest {
        val store = Store(saved()); val release = CompletableDeferred<Unit>(); var mode = 0
        val repo = HomeHeroRepository(store, { _, _ -> release.await(); when(mode) { 0 -> null; 1 -> HomeHeroMetadata(); else -> error("private") } }, { 2 }, clockMs = { 101 })
        val holder = holder(StandardTestDispatcher(testScheduler), store, repo)
        val job = launch { holder.ensureHeroMetadata(card()) }; runCurrent()
        assertEquals(metadata, holder.heroState!!.metadata); assertTrue(holder.heroState!!.fromSaved)
        advanceTimeBy(300); release.complete(Unit); job.join()
        mode = 1; holder.refresh(true); holder.ensureHeroMetadata(card()); assertEquals(metadata, holder.heroState!!.metadata)
        mode = 2; holder.refresh(true); holder.ensureHeroMetadata(card()); assertEquals(metadata, holder.heroState!!.metadata)
    }
    @Test fun explicitLibraryRefreshBypassesFreshSnapshotAndMergesPartialOnlineFields() = runTest {
        var calls = 0; val store = Store(saved())
        val repo = HomeHeroRepository(store, { _, _ -> calls++; HomeHeroMetadata("New") }, { 1 }, clockMs = { 101 })
        val holder = holder(StandardTestDispatcher(testScheduler), store, repo)
        holder.ensureHeroMetadata(card()); holder.refresh(); holder.ensureHeroMetadata(card()); assertEquals(0, calls)
        holder.refresh(true); holder.ensureHeroMetadata(card()); assertEquals(1, calls)
        assertEquals(metadata.copy(synopsis = "New"), holder.heroState!!.metadata); assertFalse(holder.heroState!!.fromSaved)
    }
    @Test fun cancelledRefreshKeepsSavedPreviewUsableAndLaterRetryCanRecover() = runTest {
        var blocked = true; val store = Store(saved())
        val repo = HomeHeroRepository(store, { _, _ -> if (blocked) awaitCancellation() else HomeHeroMetadata("Recovered") }, { 2 }, clockMs = { 101 })
        val holder = holder(StandardTestDispatcher(testScheduler), store, repo)
        val job = launch { holder.ensureHeroMetadata(card()) }; advanceTimeBy(300); job.cancelAndJoin()
        assertEquals(metadata, holder.heroState!!.metadata)
        blocked = false; holder.refresh(true); holder.ensureHeroMetadata(card()); assertEquals("Recovered", holder.heroState!!.metadata.synopsis)
    }
    @Test fun accountChangeImmediatelyHidesOldPreviewAndRejectsLateCompletion() = runTest {
        var owner = providerHomeHeroKey(account); val store = Store(saved())
        val release = CompletableDeferred<Unit>()
        val repo = HomeHeroRepository(store, { _, _ -> null }, { 2 }, clockMs = { 101 })
        val holder = holder(StandardTestDispatcher(testScheduler), store, repo, { owner }, { withContext(NonCancellable) { release.await() }; HomeHeroMetadata("Old account") })
        val job = launch { holder.ensureHeroMetadata(card()) }; advanceTimeBy(300)
        assertNotNull(holder.heroState); owner = "another"; assertNull(holder.heroState)
        release.complete(Unit); job.join(); assertNull(holder.heroState)
    }
    @Test fun slowSavedReadForOldCardCannotReplaceCurrentTitle() = runTest {
        val gate = CompletableDeferred<Unit>()
        val holder = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Error("unused")) }, StandardTestDispatcher(testScheduler),
            loadSavedHeroMetadata = { c -> if(c.streamId == 1) withContext(NonCancellable) { gate.await() }; saved(c) },
            savedHeroNeedsRefresh = { _, _ -> false }, heroContext = { providerHomeHeroKey(account) })
        val old = launch { holder.ensureHeroMetadata(card()) }; runCurrent()
        holder.ensureHeroMetadata(card(2)); gate.complete(Unit); old.join()
        assertEquals("movie-2", holder.heroState!!.key)
    }
    @Test fun wrongSavedIdentityAndAccountAreRefusedAndWarmCacheExpires() = runTest {
        var time = 101L; var calls = 0
        val holder = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Error("unused")) }, StandardTestDispatcher(testScheduler),
            loadHeroMetadata = { calls++; HomeHeroMetadata("Online $calls") },
            loadSavedHeroMetadata = { saved().copy(providerKey = "wrong", metadataKey = "show-1") },
            heroContext = { providerHomeHeroKey(account) }, clockMs = { time })
        holder.ensureHeroMetadata(card()); assertEquals("Online 1", holder.heroState!!.metadata.synopsis)
        holder.ensureHeroMetadata(card()); assertEquals(1, calls)
        time += HomeHeroRepository.FreshMs; holder.ensureHeroMetadata(card()); assertEquals(2, calls)
    }
}
