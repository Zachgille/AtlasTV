package com.atlas.atlastv.ui.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedSearchStateHolderTest {
    @Test fun rapidTypingQueriesOnlyTheLatestTextAfterDebounce() = runTest {
        val calls = mutableListOf<String>()
        val holder = UnifiedSearchStateHolder(this, { calls += it; result(it) }, StandardTestDispatcher(testScheduler))
        holder.search("b")
        runCurrent()
        advanceTimeBy(100)
        holder.search("bl")
        runCurrent()
        advanceTimeBy(100)
        holder.search("blindspot")
        runCurrent()
        assertEquals(UnifiedSearchState.Loading, holder.state)
        advanceTimeBy(249)
        runCurrent()
        assertTrue(calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("blindspot"), calls)
        assertEquals("blindspot", (holder.state as UnifiedSearchState.Ready).query)
    }

    @Test fun slowSearchReturnsImmediatelyAndIsCancelledByNewText() = runTest {
        var cancelled = false
        val holder = UnifiedSearchStateHolder(this, { query ->
            if (query == "old") {
                try { delay(10_000) } finally { cancelled = true }
            }
            result(query)
        }, StandardTestDispatcher(testScheduler))
        holder.search("old")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(UnifiedSearchState.Loading, holder.state)
        holder.search("new")
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("new", (holder.state as UnifiedSearchState.Ready).query)
    }

    @Test fun lateNonCooperativeResultCannotOverwriteNewerResults() = runTest {
        val holder = UnifiedSearchStateHolder(this, { query ->
            if (query == "old") withContext(NonCancellable) { delay(2_000) }
            result(query)
        }, StandardTestDispatcher(testScheduler))
        holder.search("old")
        advanceTimeBy(250)
        runCurrent()
        holder.search("new")
        advanceTimeBy(250)
        runCurrent()
        assertEquals("new", (holder.state as UnifiedSearchState.Ready).query)
        advanceUntilIdle()
        assertEquals("new", (holder.state as UnifiedSearchState.Ready).query)
    }

    @Test fun clearingCancelsPendingWorkAndImmediatelyRestoresPrompt() = runTest {
        val holder = UnifiedSearchStateHolder(this, { query ->
            withContext(NonCancellable) { delay(2_000) }
            result(query)
        }, StandardTestDispatcher(testScheduler))
        holder.search("old")
        advanceTimeBy(250)
        runCurrent()
        holder.search("")
        assertEquals("", (holder.state as UnifiedSearchState.Ready).query)
        advanceUntilIdle()
        assertEquals(0, (holder.state as UnifiedSearchState.Ready).totalCount)
    }

    @Test fun blankQueriesNeverReadCatalogAndInputIsBounded() = runTest {
        val calls = mutableListOf<String>()
        val holder = UnifiedSearchStateHolder(this, { calls += it; result(it) }, StandardTestDispatcher(testScheduler))
        holder.search("   ")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        holder.search("a".repeat(100))
        advanceUntilIdle()
        assertEquals(MaxUnifiedSearchQueryLength, calls.single().length)
    }

    @Test fun errorsAreSafeAndSameQueryCanBeRetried() = runTest {
        var fail = true
        val holder = UnifiedSearchStateHolder(this, { query ->
            if (fail) throw IllegalStateException("http://private.example/password")
            result(query)
        }, StandardTestDispatcher(testScheduler))
        holder.search("title")
        advanceUntilIdle()
        val error = holder.state as UnifiedSearchState.Error
        assertFalse(error.message.contains("http"))
        assertFalse(error.message.contains("password"))
        fail = false
        holder.search("title")
        advanceUntilIdle()
        assertEquals("title", (holder.state as UnifiedSearchState.Ready).query)
    }

    @Test fun leavingSearchCancelsWithoutPublishingErrorOrResults() = runTest {
        val holder = UnifiedSearchStateHolder(this, { query -> delay(1_000); result(query) }, StandardTestDispatcher(testScheduler))
        holder.search("title")
        advanceTimeBy(250)
        runCurrent()
        holder.cancel()
        advanceUntilIdle()
        assertEquals(UnifiedSearchState.Loading, holder.state)
    }

    @Test fun cancellationFromSourceIsNotShownAsAnError() = runTest {
        val holder = UnifiedSearchStateHolder(this, { throw CancellationException("leaving") }, StandardTestDispatcher(testScheduler))
        holder.search("title")
        advanceUntilIdle()
        assertEquals(UnifiedSearchState.Loading, holder.state)
    }

    private fun result(query: String) = UnifiedSearchState.Ready(query, 1, 2, emptyList())
}
