package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.NextEpisodeState
import com.atlas.atlastv.playback.NextEpisodeStateHolder
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShowDetailsCacheTest {
    private val account = XtreamCredentials("http://provider.test", "opaque-user", "opaque-password")
    private fun episodes(count: Int = 3) = (1..count).map { SeriesEpisodeItem("Episode $it", (it - 1) / 64 + 1,
        (it - 1) % 64 + 1, it, "mp4", "http://private/opaque-password/video", 600_000) }
    private fun success(count: Int = 3) = SeriesEpisodeLoadResult.Success(episodes(count),
        SeriesShowMetadata("A mystery.", "Drama", "2020", poster = atlasImageRefFromRaw("fixture://poster/show")))
    private fun saved(result: SeriesEpisodeLoadResult.Success = success(), time: Long = 100, stamp: Long? = 1) =
        SavedShowDetails(providerDetailsKey(account), 7, time, time, stamp, result.copy(fromSaved = true))
    private class Store(var value: SavedShowDetails? = null) : ShowDetailsStore {
        var readFails = false; var saveFails = false; var writes = 0
        override suspend fun read(key: String, id: Int): SavedShowDetails? {
            if (readFails) error("private storage error")
            return value?.takeIf { it.providerKey == key && it.seriesId == id }
        }
        override suspend fun save(value: SavedShowDetails) {
            if (saveFails) error("private storage error")
            writes++
            if ((this.value?.requestedAtMs ?: Long.MIN_VALUE) <= value.requestedAtMs) this.value = value
        }
    }
    @Test fun opaqueAccountKeySeparatesCredentialsAndEndpointsWithoutExposingThem() {
        val key = providerDetailsKey(account)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(key, providerDetailsKey(account.copy(baseUrl = " http://provider.test/// ")))
        for (other in listOf(account.copy(username = "opaque-user "), account.copy(password = "opaque-password "),
            account.copy(baseUrl = "http://other.test"), account.copy(username = "a", password = "bc"),
            account.copy(username = "ab", password = "c"))) assertNotEquals(key, providerDetailsKey(other))
        assertNotEquals(providerDetailsKey(account.copy(username = "a", password = "bc")),
            providerDetailsKey(account.copy(username = "ab", password = "c")))
    }
    @Test fun complete10457EpisodeListRoundTripsWithSeasonAndEpisodeIdentity() {
        val decoded = ShowDetailsCacheCodec.decode(ShowDetailsCacheCodec.encode(success(10457))!!)!!
        assertEquals(10457, decoded.episodes.size)
        assertEquals(10457, decoded.episodes.last().episodeId)
        assertEquals(episodes(10457).map { Triple(it.episodeId, it.seasonNumber, it.episodeNumber) },
            decoded.episodes.map { Triple(it.episodeId, it.seasonNumber, it.episodeNumber) })
        assertEquals(600_000L, decoded.episodes.last().durationMs)
        assertEquals("A mystery.", decoded.metadata.synopsis)
        assertTrue(decoded.fromSaved)
        assertTrue(decoded.episodes.all { it.redactedStreamUrl.isEmpty() })
    }
    @Test fun invalidCachedRuntimeIsDroppedWithoutLosingAnEpisode() {
        val input = success().copy(episodes = listOf(episodes().first().copy(durationMs = Long.MAX_VALUE)))
        val decoded = ShowDetailsCacheCodec.decode(ShowDetailsCacheCodec.encode(input)!!)!!
        assertEquals(1, decoded.episodes.single().episodeId); assertNull(decoded.episodes.single().durationMs)
        val raw = com.google.gson.JsonParser.parseString(ShowDetailsCacheCodec.encode(success())!!).asJsonObject
        raw.getAsJsonArray("episodes")[0].asJsonObject.addProperty("duration", Long.MAX_VALUE)
        val legacy = ShowDetailsCacheCodec.decode(raw.toString())!!
        assertEquals(3, legacy.episodes.size); assertNull(legacy.episodes.first().durationMs)
        assertEquals(600_000L, legacy.episodes[1].durationMs)
    }
    @Test fun payloadExcludesAddressesAndSanitizesUnsafeTextExtensionAndArtwork() {
        val input = success().copy(episodes = listOf(episodes().first().copy(
            title = "Pilot https://private/opaque-password", containerExtension = "http://private/password",
            thumbnail = atlasImageRefFromRaw("https://private/opaque-password/image.jpg"))),
            metadata = SeriesShowMetadata("<p>Story</p> https://private/opaque-password", "Drama", "2020"))
        val json = ShowDetailsCacheCodec.encode(input)!!
        assertFalse(json.contains("private")); assertFalse(json.contains("opaque-password")); assertFalse(json.contains("http"))
        val decoded = ShowDetailsCacheCodec.decode(json)!!
        assertEquals("mp4", decoded.episodes.single().containerExtension)
        assertEquals("Pilot", decoded.episodes.single().title)
        assertEquals("Story", decoded.metadata.synopsis)
        assertEquals("", decoded.episodes.single().redactedStreamUrl)
    }
    @Test fun malformedVersionEmptyDuplicateAndInvalidIdentityCannotBecomePartialCache() {
        assertNull(ShowDetailsCacheCodec.decode("{"))
        assertNull(ShowDetailsCacheCodec.decode("null"))
        assertNull(ShowDetailsCacheCodec.decode(ShowDetailsCacheCodec.encode(success())!!.replace("\"version\":1", "\"version\":2")))
        assertNull(ShowDetailsCacheCodec.encode(success(0)))
        assertNull(ShowDetailsCacheCodec.encode(success().copy(episodes = listOf(episodes().first(), episodes().first()))))
        assertNull(ShowDetailsCacheCodec.encode(success().copy(episodes = listOf(episodes().first().copy(episodeId = 0)))))
    }
    @Test fun unsafeLocalArtworkInEditedPayloadIsNotRestored() {
        val json = ShowDetailsCacheCodec.encode(success())!!.replace("fixture://poster/show", "http://private/password")
        assertNull(ShowDetailsCacheCodec.decode(json)!!.metadata.poster.safeUri)
        assertNull(ShowDetailsCacheCodec.decode(json)!!.metadata.poster.providerImageToken)
    }
    @Test fun oversizedListIsRefusedAsAWhole() {
        val huge = success(45000).copy(episodes = episodes(45000).map { it.copy(title = "a".repeat(160)) })
        assertNull(ShowDetailsCacheCodec.encode(huge))
        assertNull(ShowDetailsCacheCodec.decode(" ".repeat(ShowDetailsCacheCodec.MaxEntryBytes + 1)))
    }
    @Test fun freshCacheAvoidsProviderLookupAndOtherAccountsCannotReadIt() = runTest {
        val store = Store(saved()); var calls = 0
        val repo = ShowDetailsRepository(store, { _, _ -> calls++; success() }, { 1 }, { 101 })
        assertTrue((repo.forPlayback(account, 7) as SeriesEpisodeLoadResult.Success).fromSaved)
        assertEquals(0, calls)
        assertNull(repo.saved(account.copy(password = "other"), 7)); assertNull(repo.saved(account, 8))
    }
    @Test fun staleImportAndFutureDatesRequireRefresh() = runTest {
        val repo = ShowDetailsRepository(Store(), { _, _ -> success() }, { 2 }, { 100 })
        assertTrue(repo.stale(saved(stamp = 1)))
        assertTrue(repo.stale(saved(time = 101, stamp = 2)))
        assertFalse(repo.stale(saved(time = 99, stamp = 2)))
        assertTrue(repo.stale(saved(time = 100 - ShowDetailsRepository.FreshMs, stamp = 2)))
    }
    @Test fun failedAndEmptyRefreshKeepLastGoodListAndDoNotWrite() = runTest {
        for (result in listOf(SeriesEpisodeLoadResult.Failure("Unavailable"), success(0))) {
            val store = Store(saved()); val previous = store.value
            val repo = ShowDetailsRepository(store, { _, _ -> result }, { 2 }, { 200 })
            assertEquals(previous!!.result, repo.forPlayback(account, 7))
            assertEquals(previous, store.value); assertEquals(0, store.writes)
        }
    }
    @Test fun missingNewMetadataRetainsExistingSynopsisAndArtwork() = runTest {
        val repo = ShowDetailsRepository(Store(saved()), { _, _ -> success(4).copy(metadata = SeriesShowMetadata()) }, { 1 }, { 200 })
        val result = repo.refresh(account, 7) as SeriesEpisodeLoadResult.Success
        assertEquals(4, result.episodes.size); assertEquals(success().metadata, result.metadata)
    }
    @Test fun optionalReadWriteAndCatalogFailuresCannotBlockOnlineContent() = runTest {
        for (readFails in listOf(false, true)) {
            val store = Store().apply { this.readFails = readFails; saveFails = true }
            val repo = ShowDetailsRepository(store, { _, _ -> success() }, { error("private catalog error") }, { 200 })
            assertEquals(3, (repo.forPlayback(account, 7) as SeriesEpisodeLoadResult.Success).episodes.size)
        }
    }
    @Test fun offlineWithoutCacheHasTypedFailureAndCancellationPropagates() = runTest {
        val repo = ShowDetailsRepository(Store(), { _, _ -> SeriesEpisodeLoadResult.Failure("Unavailable") })
        assertEquals(SeriesEpisodeLoadResult.Failure("Unavailable"), repo.forPlayback(account, 7))
        val cancelled = ShowDetailsRepository(Store(), { _, _ -> throw CancellationException("leave") })
        try { cancelled.forPlayback(account, 7); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
    @Test fun lateOldProviderRequestCannotOverwriteNewSnapshot() = runTest {
        val store = Store(); val release = CompletableDeferred<Unit>(); var calls = 0
        val repo = ShowDetailsRepository(store, { _, _ -> if (++calls == 1) { release.await(); success(1) } else success(4) }, clockMs = { 100 })
        val old = launch { repo.refresh(account, 7) }; runCurrent()
        repo.refresh(account, 7); release.complete(Unit); old.join()
        assertEquals(4, store.value!!.result.episodes.size)
    }
    private fun catalog() = SeriesCatalogState.Ready("check", 0, listOf(SeriesItem("Example", "Drama", 7)), emptyList())
    private fun source() = object : SeriesCatalogSource { override suspend fun load(previous: SeriesCatalogState.Ready?) = catalog() }
    @Test fun savedListIsUsableWhileRefreshWaitsAndFailureRetainsExactDeepSelection() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder = SeriesCatalogStateHolder(source(), backgroundScope, StandardTestDispatcher(testScheduler),
            loadEpisodes = { release.await(); SeriesEpisodeLoadResult.Failure("Unavailable") },
            loadSavedEpisodes = { saved(success(1536)) }, savedNeedsRefresh = { true }, episodeContext = { providerDetailsKey(account) })
        holder.loadCachedCatalog(); val load = launch { holder.ensureEpisodesForSelectedSeries(7) }; runCurrent()
        var ready = holder.state as SeriesCatalogState.Ready
        assertEquals(1536, (ready.episodeState as SeriesEpisodeState.Ready).episodes.size)
        assertTrue((ready.episodeState as SeriesEpisodeState.Ready).refreshing)
        holder.selectSeason(24); holder.rememberEpisode(episodes(1536).last())
        release.complete(Unit); load.join(); ready = holder.state as SeriesCatalogState.Ready
        assertEquals(24, ready.selectedSeasonNumber); assertEquals(1536, ready.selectedEpisodeId)
        assertTrue((ready.episodeState as SeriesEpisodeState.Ready).refreshFailed)
        assertFalse((ready.episodeState as SeriesEpisodeState.Ready).refreshing)
    }
    @Test fun freshSavedDetailsNeedNoDownloadButExplicitRefreshUpdatesCompleteList() = runTest {
        var calls = 0
        val holder = SeriesCatalogStateHolder(source(), backgroundScope, StandardTestDispatcher(testScheduler),
            loadEpisodes = { calls++; success(4) }, loadSavedEpisodes = { saved() }, savedNeedsRefresh = { false })
        holder.loadCachedCatalog(); holder.ensureEpisodesForSelectedSeries(7)
        assertEquals(0, calls); assertTrue(((holder.state as SeriesCatalogState.Ready).episodeState as SeriesEpisodeState.Ready).saved)
        holder.retryEpisodes(); holder.ensureEpisodesForSelectedSeries(7)
        assertEquals(1, calls); assertEquals(4, ((holder.state as SeriesCatalogState.Ready).episodeState as SeriesEpisodeState.Ready).episodes.size)
    }
    @Test fun removedSelectedEpisodeFallsBackWithinSelectedSeasonAfterRefresh() = runTest {
        val holder = SeriesCatalogStateHolder(source(), backgroundScope, StandardTestDispatcher(testScheduler),
            loadEpisodes = { success(2) }, loadSavedEpisodes = { saved() }, savedNeedsRefresh = { false })
        holder.loadCachedCatalog(); holder.ensureEpisodesForSelectedSeries(7); holder.rememberEpisode(episodes().last())
        holder.retryEpisodes(); holder.ensureEpisodesForSelectedSeries(7)
        assertEquals(1, (holder.state as SeriesCatalogState.Ready).selectedSeasonNumber)
        assertEquals(1, (holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }
    @Test fun cancelledRefreshRetainsSavedListAndStopsSpinner() = runTest {
        val holder = SeriesCatalogStateHolder(source(), backgroundScope, StandardTestDispatcher(testScheduler),
            loadEpisodes = { awaitCancellation() }, loadSavedEpisodes = { saved() }, savedNeedsRefresh = { true })
        holder.loadCachedCatalog(); val load = launch { holder.ensureEpisodesForSelectedSeries(7) }; runCurrent(); load.cancelAndJoin()
        val ready = (holder.state as SeriesCatalogState.Ready).episodeState as SeriesEpisodeState.Ready
        assertTrue(ready.saved); assertFalse(ready.refreshing); assertEquals(3, ready.episodes.size)
    }
    @Test fun accountChangeHidesMemoryAndLateRefreshThenLoadsNewNamespace() = runTest {
        var context = providerDetailsKey(account); val release = CompletableDeferred<Unit>(); var calls = 0
        val holder = SeriesCatalogStateHolder(source(), backgroundScope, StandardTestDispatcher(testScheduler),
            loadEpisodes = { if (++calls == 1) { release.await(); success(4) } else success(2) },
            loadSavedEpisodes = { saved() }, savedNeedsRefresh = { true }, episodeContext = { context })
        holder.loadCachedCatalog(); val old = launch { holder.ensureEpisodesForSelectedSeries(7) }; runCurrent()
        context = "another-account"
        assertEquals(SeriesEpisodeState.Idle, holder.visibleEpisodeState(holder.state as SeriesCatalogState.Ready).episodeState)
        holder.ensureEpisodesForSelectedSeries(7); release.complete(Unit); old.join()
        assertEquals(2, ((holder.state as SeriesCatalogState.Ready).episodeState as SeriesEpisodeState.Ready).episodes.size)
    }
    @Test fun nextEpisodeMemoryIsInvalidatedWhenAccountChanges() = runTest {
        var context = "one"; var calls = 0
        val holder = NextEpisodeStateHolder({ calls++; success(if (context == "one") 3 else 1) }, StandardTestDispatcher(testScheduler), { context })
        holder.ensureNext(7, 1); assertTrue(holder.state is NextEpisodeState.Available)
        context = "two"; assertNull(holder.loadedFor(7)); holder.ensureNext(7, 1)
        assertEquals(NextEpisodeState.End, holder.state); assertEquals(2, calls)
    }
    @Test fun explicitNextEpisodeRetryUsesFreshProviderLookupDespiteFreshSavedCache() = runTest {
        var normal = 0; var refreshed = 0
        val holder = NextEpisodeStateHolder({ normal++; success(1) }, StandardTestDispatcher(testScheduler),
            refreshEpisodes = { refreshed++; success(2) })
        holder.ensureNext(7, 1); assertEquals(NextEpisodeState.End, holder.state)
        holder.retry(); holder.ensureNext(7, 1)
        assertTrue(holder.state is NextEpisodeState.Available); assertEquals(1, normal); assertEquals(1, refreshed)
        var calls = 0
        val repo = ShowDetailsRepository(Store(saved()), { _, _ -> calls++; success(4) }, { 1 }, { 101 })
        assertEquals(4, (repo.forPlayback(account, 7, forceRefresh = true) as SeriesEpisodeLoadResult.Success).episodes.size)
        assertEquals(1, calls)
    }
    @Test fun episodeNameRemovesRepeatedShowWhenOnlyTheShowIncludesItsYear() {
        assertEquals("Episode 1", episodes().first().copy(title = "A Clean Show - S1E1").displayEpisodeName("A Clean Show (2020)"))
        assertEquals("Pilot", episodes().first().copy(title = "A Clean Show - S1E1 - Pilot").displayEpisodeName("A Clean Show (2020)"))
        assertEquals("Pilot", episodes().first().copy(title = "A Clean Show (2020) - S1E1 - Pilot").displayEpisodeName("A Clean Show (2020)"))
    }
}
