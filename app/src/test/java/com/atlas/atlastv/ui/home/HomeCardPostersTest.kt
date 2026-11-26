package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.media.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCardPostersTest {
    private val account = XtreamCredentials("https://poster.invalid", "opaque", "opaque")
    private fun card(n: Int = 1, type: HomeContentType = HomeContentType.Movie, parent: Int? = null) = HomeCard("$type-$n", "Title", type, "", n, parent, statusText = "")
    private fun ref(n: Int) = atlasImageRefFromRaw("provider-image://sha256/" + n.toString(16).padStart(64, '0'))
    private fun saved(c: HomeCard = card(), time: Long = 1, poster: AtlasImageRef = ref(1)) = SavedHomeHero(providerHomeHeroKey(account), c.heroCacheIdentity()!!, time, time, null, HomeHeroMetadata(poster = poster))
    private fun ready(cards: List<HomeCard>) = HomeShellState.Ready("test", null, listOf(HomeContentRow(HomeRowIds.ContinueWatching, "Continue Watching", cards)), cards.firstOrNull()?.id)
    @Test fun readOnlyRepositoryUsesNewestDistinctPortraitsWithoutDownloadStampOrSave() = runTest {
        var own = saved(); var detail = saved(time = 2, poster = ref(2))
        val store = object : HomeHeroStore {
            override suspend fun read(key: String, id: String) = own
            override suspend fun save(value: SavedHomeHero) = error("no write")
        }
        val repo = HomeHeroRepository(store, { _, _ -> error("no download") }, { error("no catalog scan") }, { _, _ -> detail })
        assertEquals(listOf(ref(2), ref(1)), repo.savedPosters(account, card()))
        detail = detail.copy(metadata = HomeHeroMetadata(poster = ref(1))); assertEquals(listOf(ref(1)), repo.savedPosters(account, card()))
        detail = detail.copy(providerKey = "wrong"); assertEquals(listOf(ref(1)), repo.savedPosters(account, card()))
        own = own.copy(metadataKey = "show-1"); assertTrue(repo.savedPosters(account, card()).isEmpty())
        own = saved().copy(metadata = HomeHeroMetadata(backdrop = ref(1))); assertTrue(repo.savedPosters(account, card()).isEmpty())
        own = saved(poster = AtlasImageRef(AtlasImageState.ProviderImage, providerImageToken = "unsafe")); assertTrue(repo.savedPosters(account, card()).isEmpty())
    }
    @Test fun optionalFailureStillReadsDetailAndEpisodeRequiresTypedParent() = runTest {
        var detail = saved(card(99, HomeContentType.SeriesEpisode, 1))
        var reads = 0
        val store = object : HomeHeroStore {
            override suspend fun read(key: String, id: String): SavedHomeHero? { reads++; error("read") }
            override suspend fun save(value: SavedHomeHero) = error("write")
        }
        val repo = HomeHeroRepository(store, { _, _ -> error("network") }, detailFallback = { _, _ -> detail })
        assertEquals(listOf(ref(1)), repo.savedPosters(account, card(99, HomeContentType.SeriesEpisode, 1)))
        assertTrue(repo.savedPosters(account, card()).isEmpty())
        detail = detail.copy(providerKey = "wrong"); assertTrue(repo.savedPosters(account, card(99, HomeContentType.SeriesEpisode, 1)).isEmpty())
        val before = reads; assertTrue(repo.savedPosters(account, card(99, HomeContentType.SeriesEpisode)).isEmpty()); assertTrue(repo.savedPosters(account, card(0)).isEmpty()); assertEquals(before, reads)
    }
    @Test fun repositoryStorageCancellationPropagates() = runTest {
        val store = object : HomeHeroStore {
            override suspend fun read(key: String, id: String): SavedHomeHero? = throw CancellationException()
            override suspend fun save(value: SavedHomeHero) { }
        }
        try { HomeHeroRepository(store, { _, _ -> null }).savedPosters(account, card()); fail() } catch (_: CancellationException) { }
    }
    @Test fun savedLookupCannotBlockHomeAndLateAccountCompletionIsRejected() = runTest {
        val gate = CompletableDeferred<Unit>(); var owner = "first"
        val cards = ready(listOf(card())); val holder = HomeShellStateHolder({ HomeLoadResult(cards) }, StandardTestDispatcher(testScheduler),
            heroContext = { owner }, loadSavedPosters = { withContext(NonCancellable) { gate.await() }; listOf(ref(1)) })
        holder.loadHome(); val job = launch { holder.loadCardPosters(cards) }; runCurrent()
        assertSame(cards, holder.state); assertFalse(holder.isRefreshing); assertTrue(holder.posterFallbacks(card()).isEmpty())
        owner = "second"; gate.complete(Unit); job.join(); assertTrue(holder.posterFallbacks(card()).isEmpty())
    }
    @Test fun newerRowWinsAndFailureRetainsOnlyCurrentAccountAndTypedTitles() = runTest {
        var block = true; val gate = CompletableDeferred<Unit>(); var owner = "first"
        val holder = HomeShellStateHolder({ HomeLoadResult(ready(emptyList())) }, StandardTestDispatcher(testScheduler), heroContext = { owner },
            loadSavedPosters = { if (it.streamId == 1 && block) withContext(NonCancellable) { gate.await() }; listOf(ref(it.streamId)) })
        val old = launch { holder.loadCardPosters(ready(listOf(card()))) }; runCurrent()
        holder.loadCardPosters(ready(listOf(card(2)))); gate.complete(Unit); old.join()
        assertTrue(holder.posterFallbacks(card()).isEmpty()); assertEquals(listOf(ref(2)), holder.posterFallbacks(card(2)))
        owner = "other"; assertTrue(holder.posterFallbacks(card(2)).isEmpty())
    }
    @Test fun rowReadsAreBoundedParentDeduplicatedAndCancelledWorkNeverPublishes() = runTest {
        val calls = mutableListOf<String>(); var failReads = false
        val holder = HomeShellStateHolder({ HomeLoadResult(ready(emptyList())) }, StandardTestDispatcher(testScheduler), loadSavedPosters = {
            calls.add(it.heroCacheIdentity()!!); if (failReads) error("optional"); listOf(ref(1), ref(2), ref(3)) })
        val cards = listOf(card(99, HomeContentType.SeriesEpisode, 1), card(100, HomeContentType.SeriesEpisode, 1)) + (1..20).map { card(it) }
        holder.loadCardPosters(ready(cards)); assertEquals(12, calls.size); assertEquals(1, calls.count { it == "show-1" }); assertEquals(2, holder.posterFallbacks(cards.first()).size)
        failReads = true; holder.loadCardPosters(ready(listOf(card()))); assertEquals(listOf(ref(1), ref(2)), holder.posterFallbacks(card())); assertTrue(holder.posterFallbacks(card(2)).isEmpty())
        val cancelled = HomeShellStateHolder({ HomeLoadResult(ready(emptyList())) }, StandardTestDispatcher(testScheduler), loadSavedPosters = { awaitCancellation() })
        val job = launch { cancelled.loadCardPosters(ready(listOf(card()))) }; runCurrent(); job.cancelAndJoin(); assertTrue(cancelled.posterFallbacks(card()).isEmpty())
    }
}
