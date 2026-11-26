package com.atlas.atlastv.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackPreferencesStateHolderTest {
    private class Store : PlaybackPreferencesStore {
        var value = false
        var loadDelay = 0L
        var writeDelay = 0L
        var failLoad = false
        var failSave = false
        val writes = mutableListOf<Boolean>()
        override suspend fun loadAutoplay(): Boolean { delay(loadDelay); if (failLoad) error("private credentials"); return value }
        override suspend fun saveAutoplay(enabled: Boolean) { writes += enabled; delay(writeDelay); if (failSave) error("private credentials"); value = enabled }
    }
    @Test fun defaultOffAndSlowLoadingNeverGateVideoOrEnableBeforeKnownValue() = runTest {
        val store = Store().apply { value = true; loadDelay = 1_000 }
        val holder = PlaybackPreferencesStateHolder(store, backgroundScope)
        runCurrent(); assertFalse(holder.state.enabled); assertTrue(holder.state.loading)
        holder.toggleOrRetry(); assertTrue(store.writes.isEmpty())
        advanceTimeBy(1_000); runCurrent(); assertTrue(holder.state.enabled); assertTrue(holder.state.loaded)
    }
    @Test fun failedReadStaysOffAndExplicitRetryLoadsPersistedPreferenceWithoutLeakingErrors() = runTest {
        val store = Store().apply { value = true; failLoad = true }
        val holder = PlaybackPreferencesStateHolder(store, backgroundScope); runCurrent()
        assertFalse(holder.state.enabled); assertTrue(holder.state.loadFailed)
        assertFalse(holder.state.actionLabel.contains("private"))
        store.failLoad = false; holder.toggleOrRetry(); runCurrent()
        assertTrue(holder.state.enabled); assertFalse(holder.state.loadFailed)
    }
    @Test fun enablingRequiresDurableAcknowledgmentAndRepeatedPressesStartOneWrite() = runTest {
        val store = Store().apply { writeDelay = 1_000 }
        val holder = PlaybackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.toggleOrRetry(); holder.toggleOrRetry(); runCurrent()
        assertFalse(holder.state.enabled); assertTrue(holder.state.saving); assertEquals(listOf(true), store.writes)
        advanceTimeBy(1_000); runCurrent(); assertTrue(holder.state.enabled); assertTrue(store.value)
    }
    @Test fun failedEnableRemainsOffAndRetryWritesTheSameRequestedValue() = runTest {
        val store = Store().apply { failSave = true }
        val holder = PlaybackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.toggleOrRetry(); runCurrent(); assertFalse(holder.state.enabled); assertTrue(holder.state.saveFailed)
        store.failSave = false; holder.toggleOrRetry(); runCurrent()
        assertEquals(listOf(true, true), store.writes); assertTrue(holder.state.enabled); assertTrue(store.value)
    }
    @Test fun disableIsImmediateAndFailedSaveCannotTurnItBackOn() = runTest {
        val store = Store().apply { value = true; writeDelay = 1_000; failSave = true }
        val holder = PlaybackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.toggleOrRetry(); assertFalse(holder.state.enabled); runCurrent()
        advanceTimeBy(1_000); runCurrent(); assertFalse(holder.state.enabled); assertTrue(holder.state.saveFailed)
        store.failSave = false; holder.toggleOrRetry(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(false, false), store.writes); assertFalse(store.value); assertFalse(holder.state.saveFailed)
    }
}
