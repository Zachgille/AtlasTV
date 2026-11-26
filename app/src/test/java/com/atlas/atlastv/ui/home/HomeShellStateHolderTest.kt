package com.atlas.atlastv.ui.home

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeShellStateHolderTest {
    @Test fun initialLoadingSuspendsWithoutBlockingCaller() = runTest {
        val holder = HomeShellStateHolder({ delay(1_000); result("loaded") }, StandardTestDispatcher(testScheduler))
        launch { holder.loadHome() }
        runCurrent()
        assertEquals(HomeShellState.Loading, holder.state)
        assertTrue(holder.isRefreshing)
        advanceUntilIdle()
        assertEquals("loaded", (holder.state as HomeShellState.Ready).source)
        assertFalse(holder.isRefreshing)
    }

    @Test fun refreshKeepsLastGoodContentAndWatchStateOnFailure() = runTest {
        var fail = false
        val holder = HomeShellStateHolder({
            if (fail) { delay(1_000); throw IllegalStateException("http://private/password") }
            result("saved")
        }, StandardTestDispatcher(testScheduler))
        launch { holder.loadHome() }
        advanceUntilIdle()
        val saved = holder.state
        val progress = holder.continueWatching
        fail = true
        launch { holder.loadHome() }
        runCurrent()
        assertSame(saved, holder.state)
        assertTrue(holder.isRefreshing)
        advanceUntilIdle()
        assertSame(saved, holder.state)
        assertEquals(progress, holder.continueWatching)
        assertNotNull(holder.refreshError)
        assertFalse(holder.refreshError!!.contains("http"))
        assertFalse(holder.isRefreshing)
    }

    @Test fun newerLoadWinsEvenIfOlderWorkFinishesLate() = runTest {
        var calls = 0
        val holder = HomeShellStateHolder({
            if (++calls == 1) { withContext(NonCancellable) { delay(2_000) }; result("old") }
            else result("new")
        }, StandardTestDispatcher(testScheduler))
        launch { holder.loadHome() }
        runCurrent()
        launch { holder.loadHome() }
        runCurrent()
        assertEquals("new", (holder.state as HomeShellState.Ready).source)
        advanceUntilIdle()
        assertEquals("new", (holder.state as HomeShellState.Ready).source)
    }

    @Test fun leavingHomeCancelsWithoutShowingAnErrorOrPublishingLateData() = runTest {
        val holder = HomeShellStateHolder({ withContext(NonCancellable) { delay(1_000) }; result("late") }, StandardTestDispatcher(testScheduler))
        val job = launch { holder.loadHome() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(HomeShellState.Loading, holder.state)
        assertNull(holder.refreshError)
        assertFalse(holder.isRefreshing)
    }

    @Test fun initialFailureHasSafeCopyAndCanBeRetried() = runTest {
        var fail = true
        val holder = HomeShellStateHolder({
            if (fail) throw IllegalStateException("http://private/password")
            result("recovered")
        }, StandardTestDispatcher(testScheduler))
        launch { holder.loadHome() }
        advanceUntilIdle()
        val error = holder.state as HomeShellState.Error
        assertFalse(error.message.contains("http"))
        assertFalse(error.needsSetup)
        fail = false
        launch { holder.loadHome() }
        advanceUntilIdle()
        assertEquals("recovered", (holder.state as HomeShellState.Ready).source)
        assertNull(holder.refreshError)
    }

    @Test fun emptyCatalogHasAnExplicitSettingsPath() = runTest {
        val empty = HomeShellState.Error("Open Settings", needsSetup = true)
        val holder = HomeShellStateHolder({ HomeLoadResult(empty) }, StandardTestDispatcher(testScheduler))
        launch { holder.loadHome() }
        advanceUntilIdle()
        assertSame(empty, holder.state)
    }

    @Test fun emptyRefreshNeverRemovesLastGoodRows() = runTest {
        var empty = false
        val holder = HomeShellStateHolder({
            if (empty) HomeLoadResult(HomeShellState.Error("Open Settings", needsSetup = true)) else result("saved")
        }, StandardTestDispatcher(testScheduler))
        launch { holder.loadHome() }
        advanceUntilIdle()
        empty = true
        launch { holder.loadHome() }
        advanceUntilIdle()
        assertEquals("saved", (holder.state as HomeShellState.Ready).source)
        assertNotNull(holder.refreshError)
    }

    @Test fun invalidationIsImmediateAndDoesNotStartDetachedWork() = runTest {
        var calls = 0
        val holder = HomeShellStateHolder({ calls++; result("saved") }, StandardTestDispatcher(testScheduler))
        holder.refresh()
        holder.refresh()
        assertEquals(2L, holder.refreshVersion)
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test fun leadingHomeIsUsableWhileSecondaryRowsAreBlockedAndFocusSurvivesCompletion() = runTest {
        val holder = HomeShellStateHolder({ error("single-stage loader must not run") }, StandardTestDispatcher(testScheduler),
            catalogUpdates = { flow {
                emit(result("leading").copy(isComplete = false))
                delay(5_000)
                emit(result("complete"))
            } })
        val job = launch { holder.loadHome() }
        runCurrent()
        assertEquals("leading", (holder.state as HomeShellState.Ready).source)
        assertTrue(job.isActive)
        assertTrue(holder.isRefreshing)
        holder.rememberDeparture("chosen", HomeReturnTarget.Card)
        holder.rowScrollIndex = 3
        holder.horizontalPositions["movies_new"] = 7 to 12
        advanceUntilIdle()
        assertEquals("complete", (holder.state as HomeShellState.Ready).source)
        assertEquals("chosen", holder.selectedCardId)
        assertEquals("chosen", holder.returnFocus!!.cardId)
        assertEquals(3, holder.rowScrollIndex)
        assertEquals(7 to 12, holder.horizontalPositions["movies_new"])
        assertFalse(holder.isRefreshing)
    }

    @Test fun refreshRetainsEntireLastGoodStateUntilCompleteReplacement() = runTest {
        var refresh = false
        val holder = HomeShellStateHolder({ error("unused") }, StandardTestDispatcher(testScheduler), catalogUpdates = { flow {
            if (!refresh) emit(result("saved-all-rows"))
            else { emit(result("new-leading-only").copy(isComplete = false)); delay(5_000); emit(result("new-all-rows")) }
        } })
        holder.loadHome()
        val saved = holder.state
        val progress = holder.continueWatching
        refresh = true
        launch { holder.loadHome() }; runCurrent()
        assertSame(saved, holder.state)
        assertEquals(progress, holder.continueWatching)
        assertTrue(holder.isRefreshing)
        advanceUntilIdle()
        assertEquals("new-all-rows", (holder.state as HomeShellState.Ready).source)
    }

    @Test fun secondaryFailureRetainsAlreadyUsableHomeAndAllowsRetry() = runTest {
        var fail = true
        val holder = HomeShellStateHolder({ error("unused") }, StandardTestDispatcher(testScheduler), catalogUpdates = { flow {
            emit(result("leading").copy(isComplete = false)); delay(1_000)
            if (fail) error("http://private/password") else emit(result("complete"))
        } })
        launch { holder.loadHome() }; advanceUntilIdle()
        assertEquals("leading", (holder.state as HomeShellState.Ready).source)
        assertTrue(holder.continueWatching.isNotEmpty())
        assertNotNull(holder.refreshError)
        assertFalse(holder.refreshError!!.contains("http"))
        assertFalse(holder.isRefreshing)
        fail = false
        launch { holder.loadHome() }; advanceUntilIdle()
        assertEquals("complete", (holder.state as HomeShellState.Ready).source)
        assertNull(holder.refreshError)
    }

    @Test fun leavingDuringSecondaryWorkCancelsItWithoutReplacingLeadingHome() = runTest {
        var finished = false
        val holder = HomeShellStateHolder({ error("unused") }, StandardTestDispatcher(testScheduler), catalogUpdates = { flow {
            emit(result("leading").copy(isComplete = false)); delay(5_000)
            finished = true; emit(result("late"))
        } })
        val job = launch { holder.loadHome() }; runCurrent()
        job.cancel(); advanceUntilIdle()
        assertFalse(finished)
        assertEquals("leading", (holder.state as HomeShellState.Ready).source)
        assertNull(holder.refreshError)
        assertFalse(holder.isRefreshing)
    }

    @Test fun olderSecondaryRowsCannotReplaceNewerCatalog() = runTest {
        var calls = 0
        val holder = HomeShellStateHolder({ error("unused") }, StandardTestDispatcher(testScheduler), catalogUpdates = { flow {
            if (++calls == 1) {
                emit(result("old-leading").copy(isComplete = false))
                withContext(NonCancellable) { delay(5_000) }
                emit(result("old-complete"))
            } else emit(result("new-complete"))
        } })
        launch { holder.loadHome() }; runCurrent()
        launch { holder.loadHome() }; runCurrent()
        assertEquals("new-complete", (holder.state as HomeShellState.Ready).source)
        advanceUntilIdle()
        assertEquals("new-complete", (holder.state as HomeShellState.Ready).source)
        assertNull(holder.refreshError)
    }

    @Test fun ownerChangeAndCatalogInvalidationRejectLateSecondaryRows() = runTest {
        for (changeOwner in listOf(false, true)) {
            var owner = "first"
            val holder = HomeShellStateHolder({ error("unused") }, StandardTestDispatcher(testScheduler), heroContext = { owner },
                catalogUpdates = { flow { emit(result("leading").copy(isComplete = false)); delay(1_000); emit(result("obsolete")) } })
            launch { holder.loadHome() }; runCurrent()
            if (changeOwner) owner = "second" else holder.refresh()
            advanceUntilIdle()
            assertEquals("leading", (holder.state as HomeShellState.Ready).source)
            assertNull(holder.refreshError)
            assertFalse(holder.isRefreshing)
        }
    }

    private fun result(source: String) = HomeLoadResult(
        HomeShellState.Ready(source, 0L, emptyList(), null),
        listOf(WatchProgressRecord(WatchProgressContentType.Movie, 1, title = "Saved movie", positionMs = 255_000, updatedAtMs = 1)),
    )
}
