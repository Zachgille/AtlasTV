package com.atlas.atlastv.ui.media

import kotlinx.coroutines.*

enum class ArtworkPriority { Visible, Prefetch }

internal class ArtworkDemand(@Volatile var priority: ArtworkPriority)

/** Visible demand can yield offscreen HTTP work, without canceling its shared subscribers. */
internal class ArtworkWorkQueue(private val capacity: Int) {
    private class Yielded : CancellationException("Artwork yielded to visible demand")
    private class Waiter(val demand: ArtworkDemand) {
        val ready = CompletableDeferred<Unit>()
        var attempt: Job? = null
        var yielded = false
    }
    private data class Actions(val grants: List<Waiter>, val cancellations: List<Job>)
    private val lock = Any()
    private val pending = mutableListOf<Waiter>()
    private val active = mutableSetOf<Waiter>()
    init { require(capacity > 0) }

    fun updatePriority(demand: ArtworkDemand, priority: ArtworkPriority) {
        apply(synchronized(lock) { demand.priority = priority; dispatch() })
    }

    suspend fun <T> withPermit(demand: ArtworkDemand, work: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            val waiter = Waiter(demand)
            apply(synchronized(lock) { pending += waiter; dispatch() })
            try {
                waiter.ready.await()
                return supervisorScope {
                    val attempt = async(start = CoroutineStart.LAZY) { work() }
                    val yielded = synchronized(lock) { waiter.attempt = attempt; waiter.yielded }
                    if (yielded) attempt.cancel(Yielded()) else attempt.start()
                    attempt.await()
                }
            } catch (_: Yielded) {
                // Parent/last-subscriber cancellation always wins over this internal retry.
                currentCoroutineContext().ensureActive()
            } finally {
                // Keep the permit until cancellation cleanup has finished; don't exceed capacity.
                apply(synchronized(lock) { pending.remove(waiter); active.remove(waiter); dispatch() })
            }
        }
    }

    private fun apply(actions: Actions) {
        // Neither continuation dispatch nor cancellation handlers execute under the queue lock.
        actions.cancellations.forEach { it.cancel(Yielded()) }
        actions.grants.forEach { it.ready.complete(Unit) }
    }

    private fun dispatch(): Actions {
        val grants = mutableListOf<Waiter>()
        while (active.size < capacity) {
            val next = pending.firstOrNull { it.demand.priority == ArtworkPriority.Visible }
                ?: pending.firstOrNull()?.takeIf { active.none { it.demand.priority == ArtworkPriority.Prefetch } }
                ?: break
            pending.remove(next)
            active += next
            grants += next
        }
        val needed = (pending.count { it.demand.priority == ArtworkPriority.Visible } - active.count { it.yielded })
            .coerceAtLeast(0)
        val victims = active.filter { !it.yielded && it.demand.priority == ArtworkPriority.Prefetch }.take(needed)
        victims.forEach { it.yielded = true }
        return Actions(grants, victims.mapNotNull { it.attempt })
    }
}
