package com.atlas.atlastv.ui.media

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosterArtworkFallbackTest {
    private fun ref(n: Int) = atlasImageRefFromRaw("provider-image://sha256/" + n.toString(16).padStart(64, '0'))
    @Test fun failedValidPrimaryTriesNewestThenOlderSavedPosterWithinBound() = runTest {
        val calls = mutableListOf<String>()
        val result = PosterArtworkFallback<String>().load(ref(1), listOf(ref(2), ref(3), ref(4))) {
            calls.add(it); if (it == ref(3).providerImageToken) "decoded" else if (it == ref(2).providerImageToken) error("optional") else null
        }
        assertEquals(listOf(ref(1).providerImageToken, ref(1).providerImageToken, ref(2).providerImageToken, ref(3).providerImageToken), calls)
        assertEquals("decoded", result!!.value); assertTrue(result.saved)
    }
    @Test fun workingCatalogAndWorkingSavedImageSurviveLaterAlternativesWithoutRefetch() = runTest {
        val session = PosterArtworkFallback<String>(); var calls = 0
        val first = session.load(ref(1), emptyList()) { calls++; "catalog" }
        assertSame(first, session.load(ref(1), listOf(ref(2))) { error("must retain") }); assertEquals(1, calls); assertFalse(first!!.saved)
        val fallback = PosterArtworkFallback<String>()
        val saved = fallback.load(AtlasImageRef.Missing, listOf(ref(2))) { "saved" }
        assertSame(saved, fallback.load(AtlasImageRef.Missing, listOf(ref(3))) { error("must retain") }); assertTrue(saved!!.saved)
    }
    @Test fun lateSavedLookupDoesNotRetryAlreadyFailedPrimaryAndUnsafeDuplicatesAreSkipped() = runTest {
        val session = PosterArtworkFallback<String>(); var primaryCalls = 0
        assertNull(session.load(ref(1), emptyList()) { primaryCalls++; null })
        val unsafe = AtlasImageRef(AtlasImageState.ProviderImage, providerImageToken = "http://private.invalid")
        val result = session.load(ref(1), listOf(unsafe, ref(2))) { assertEquals(ref(2).providerImageToken, it); "good" }
        assertTrue(result!!.saved); assertEquals(2, primaryCalls)
        var duplicates = 0
        assertNull(PosterArtworkFallback<String>().load(ref(1), listOf(ref(1), ref(1))) { duplicates++; null }); assertEquals(2, duplicates)
    }
    @Test fun changedSavedCandidatesCanRecoverWithoutRetryingFailedCatalog() = runTest {
        val session = PosterArtworkFallback<String>(); var primaryCalls = 0
        assertNull(session.load(ref(1), listOf(ref(2))) { if (it == ref(1).providerImageToken) primaryCalls++; null })
        assertEquals("replacement", session.load(ref(1), listOf(ref(3))) { assertEquals(ref(3).providerImageToken, it); "replacement" }!!.value)
        assertEquals(2, primaryCalls)
    }
    @Test fun cancellationRejectsNonCooperativeOldResultAndDoesNotPoisonLaterRetry() = runTest {
        val session = PosterArtworkFallback<String>(); val gate = CompletableDeferred<Unit>()
        var published = false
        val job = launch { session.load(ref(1), listOf(ref(2))) { withContext(NonCancellable) { gate.await() }; "old" }; published = true }
        runCurrent(); job.cancel(); gate.complete(Unit); job.join(); assertFalse(published)
        assertEquals("new", session.load(ref(1), listOf(ref(2))) { "new" }!!.value)
    }
    @Test fun missingHiddenAndLocalArtworkDoNotCauseInventedNetworkRequests() = runTest {
        assertNull(PosterArtworkFallback<String>().load(AtlasImageRef.Missing, emptyList()) { error("network") })
        assertNull(PosterArtworkFallback<String>().load(AtlasImageRef(AtlasImageState.ProviderImageHidden), emptyList()) { error("network") })
        assertNull(PosterArtworkFallback<String>().load(atlasImageRefFromRaw("fixture://poster/test"), listOf(ref(1))) { error("network") })
    }
}
