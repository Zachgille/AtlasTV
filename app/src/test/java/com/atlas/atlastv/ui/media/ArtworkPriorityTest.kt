package com.atlas.atlastv.ui.media

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkPriorityTest {
    @Test fun visibleRequestsOvertakeQueuedPrefetchWithoutExceedingTheLimit() = runTest {
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 1) { 1 }
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val occupied = async { loader.load("occupied") { gate.await(); "done" } }
        runCurrent()
        val speculative = (1..3).map { id -> async { loader.load("nearby-$id", null, ArtworkPriority.Prefetch) {
            order += "nearby-$id"; "done"
        } } }
        runCurrent()
        val visible = async { loader.load("visible") { order += "visible"; "done" } }
        runCurrent(); gate.complete(Unit)
        occupied.await(); visible.await(); speculative.forEach { it.await() }
        assertEquals(listOf("visible", "nearby-1", "nearby-2", "nearby-3"), order)
    }

    @Test fun speculationAdmitsOnlyOneAndLeavesCapacityForThreeVisibleDownloads() = runTest {
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 4) { 1 }
        val gate = CompletableDeferred<Unit>()
        var nearbyStarts = 0; var visibleStarts = 0; var active = 0; var peak = 0
        suspend fun fetch(): String { active++; peak = maxOf(peak, active); try { gate.await(); return "done" } finally { active-- } }
        val nearby = (1..5).map { id -> async { loader.load("nearby-$id", null, ArtworkPriority.Prefetch) { nearbyStarts++; fetch() } } }
        runCurrent(); assertEquals(1, nearbyStarts)
        val visible = (1..3).map { id -> async { loader.load("visible-$id") { visibleStarts++; fetch() } } }
        runCurrent(); assertEquals(3, visibleStarts); assertEquals(4, active)
        nearby.last().cancelAndJoin(); gate.complete(Unit)
        nearby.dropLast(1).forEach { it.await() }; visible.forEach { it.await() }
        assertEquals(4, nearbyStarts); assertEquals(4, peak); assertEquals(0, active)
    }

    @Test fun smallerVisibleSubscriberPromotesAndSharesALargerQueuedPrefetch() = runTest {
        val loader = ArtworkLoadCoordinator<Int, String>(backgroundScope, 32, 1) { 1 }
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val occupied = async { loader.load(0) { gate.await(); "occupied" } }
        runCurrent()
        val earlier = async { loader.load(900, null, ArtworkPriority.Prefetch) { order += 900; "earlier" } }
        val bigger = async { loader.load(512, null, ArtworkPriority.Prefetch) { order += 512; "shared" } }
        runCurrent()
        val smaller = async { loader.load(256, null, reusableKey = { it == 512 }) { error("Must share larger decode") } }
        runCurrent(); gate.complete(Unit)
        occupied.await(); assertEquals("shared", smaller.await()); assertEquals("shared", bigger.await()); earlier.await()
        assertEquals(listOf(512, 900), order)
        assertEquals("shared", loader.load(512) { error("Must retain larger memory key") })
    }

    @Test fun visibilityChangesReorderQueuedWorkWithoutRestartingIt() = runTest {
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 1) { 1 }
        val gate = CompletableDeferred<Unit>()
        val priority = MutableStateFlow(ArtworkPriority.Prefetch)
        val order = mutableListOf<String>()
        val occupied = async { loader.load("occupied") { gate.await(); "done" } }; runCurrent()
        val earlier = async { loader.load("earlier", null, ArtworkPriority.Prefetch) { order += "earlier"; "done" } }
        val moving = async { loader.load("moving", null, priorityChanges = priority) { order += "moving"; "done" } }
        runCurrent(); priority.value = ArtworkPriority.Visible; runCurrent()
        gate.complete(Unit); occupied.await(); moving.await(); earlier.await()
        assertEquals(listOf("moving", "earlier"), order)
        assertEquals(0, priority.subscriptionCount.value)
    }

    @Test fun departingVisibleSubscriberDemotesButDoesNotCancelRemainingPrefetch() = runTest {
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 1) { 1 }
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val occupied = async { loader.load("occupied") { gate.await(); "done" } }; runCurrent()
        val earlier = async { loader.load("earlier", null, ArtworkPriority.Prefetch) { order += "earlier"; "done" } }
        val nearby = async { loader.load("shared", null, ArtworkPriority.Prefetch) { order += "shared"; "done" } }
        runCurrent()
        val foreground = async { loader.load("shared") { error("No duplicate work") } }
        runCurrent(); foreground.cancelAndJoin()
        assertFalse(nearby.isCompleted)
        gate.complete(Unit); occupied.await(); earlier.await(); nearby.await()
        assertEquals(listOf("earlier", "shared"), order)
    }

    @Test fun offscreenTransitionDemotesPendingWorkAndCanceledWaiterCannotConsumeCapacity() = runTest {
        val loader = ArtworkLoadCoordinator<String, String>(backgroundScope, 32, 1) { 1 }
        val gate = CompletableDeferred<Unit>()
        val priority = MutableStateFlow(ArtworkPriority.Visible)
        val order = mutableListOf<String>()
        val occupied = async { loader.load("occupied") { gate.await(); "done" } }; runCurrent()
        val earlier = async { loader.load("earlier", null, ArtworkPriority.Prefetch) { order += "earlier"; "done" } }
        val moving = async { loader.load("moving", null, priorityChanges = priority) { order += "moving"; "done" } }
        val abandoned = async { loader.load("abandoned") { error("Canceled waiter must not fetch") } }
        runCurrent(); priority.value = ArtworkPriority.Prefetch; runCurrent(); abandoned.cancelAndJoin()
        val visible = async { loader.load("visible") { order += "visible"; "done" } }; runCurrent()
        gate.complete(Unit); occupied.await(); earlier.await(); moving.await(); visible.await()
        assertEquals(listOf("visible", "earlier", "moving"), order)
        assertEquals(0, priority.subscriptionCount.value)
    }
}
