package com.atlas.atlastv.playback

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackStartStateHolderTest {
    private fun record(id: Int = 1) = WatchProgressRecord(WatchProgressContentType.Movie, id, title = "Test", positionMs = 255_000, updatedAtMs = 10)
    @Test fun waitsForResumeReadWithoutStartingAtZero() = runTest {
        val holder = PlaybackStartStateHolder(backgroundScope) { delay(1_000); WatchProgressSnapshot(listOf(record())) }
        holder.start(record().copy(positionMs = 0))
        runCurrent()
        assertEquals(PlaybackStartState.Loading, holder.state.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(255_000L, (holder.state.value as PlaybackStartState.Ready).resumeMs)
    }
    @Test fun failureIsSafeAndRetryUsesSameTitle() = runTest {
        var fail = true
        val holder = PlaybackStartStateHolder(backgroundScope) { if (fail) error("http://private/password") else WatchProgressSnapshot(listOf(record())) }
        holder.start(record()); runCurrent()
        assertEquals(PlaybackStartState.Failed, holder.state.value)
        fail = false; holder.retry(); runCurrent()
        assertEquals(1, (holder.state.value as PlaybackStartState.Ready).record.streamId)
    }
    @Test fun backRejectsEvenUncooperativeLateLookup() = runTest {
        val holder = PlaybackStartStateHolder(backgroundScope) { withContext(NonCancellable) { delay(1_000) }; WatchProgressSnapshot(listOf(record())) }
        holder.start(record()); runCurrent(); holder.cancel()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(PlaybackStartState.Idle, holder.state.value)
    }
    @Test fun newerTitleSupersedesOlderLookupAndCompletedTitleStartsFresh() = runTest {
        var requests = 0
        val holder = PlaybackStartStateHolder(backgroundScope) {
            val request = ++requests
            withContext(NonCancellable) { delay(if (request == 1) 1_000 else 100) }
            WatchProgressSnapshot(listOf(record(), record(2).copy(completed = true)))
        }
        holder.start(record()); runCurrent(); holder.start(record(2)); runCurrent()
        advanceTimeBy(100); runCurrent()
        val ready = holder.state.value
        assertEquals(2, (ready as PlaybackStartState.Ready).record.streamId)
        assertNull(ready.resumeMs)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ready, holder.state.value)
    }

    @Test fun preparationFailureAndRetryKeepUrlPrivateAndBackCancelsSlowPreparation() = runTest {
        var fail = true
        val holder = PlaybackStartStateHolder(backgroundScope) { WatchProgressSnapshot(listOf(record())) }
        holder.start(record()) { if (fail) error("http://private/password") else "private playback URL" }
        runCurrent()
        assertEquals(PlaybackStartState.Failed, holder.state.value)
        fail = false; holder.retry(); runCurrent()
        assertEquals("private playback URL", (holder.state.value as PlaybackStartState.Ready).playbackUrl)
        holder.start(record()) { withContext(NonCancellable) { delay(1_000) }; "late playback URL" }
        runCurrent(); holder.cancel(); advanceTimeBy(1_000); runCurrent()
        assertEquals(PlaybackStartState.Idle, holder.state.value)
    }
}
