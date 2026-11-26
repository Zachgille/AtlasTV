package com.atlas.atlastv.ui.media

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Stable size tiers avoid decoding again for tiny layout changes. Dimensions are physical pixels. */
@ConsistentCopyVisibility
data class ArtworkSize private constructor(val width: Int, val height: Int) {
    companion object {
        fun forPixels(width: Int, height: Int): ArtworkSize = ArtworkSize(tier(width), tier(height))
        private fun tier(value: Int): Int = ((value.coerceIn(1, 1024) + 127) / 128) * 128
        val Poster = forPixels(384, 576)
    }
}

data class ArtworkDecodePlan(val sampleSize: Int, val width: Int, val height: Int) {
    companion object {
        const val MaxDecodedPixels = 2_000_000L
        const val MaxDecodedSide = 4096
        fun forSource(width: Int, height: Int, target: ArtworkSize): ArtworkDecodePlan? {
            if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 100_000_000L) return null
            var scale = min(1.0, max(target.width.toDouble() / width, target.height.toDouble() / height))
            scale = min(scale, sqrt(MaxDecodedPixels.toDouble() / (width.toLong() * height)))
            scale = min(scale, MaxDecodedSide.toDouble() / max(width, height))
            val outputWidth = max(1, (width * scale).toInt())
            val outputHeight = max(1, (height * scale).toInt())
            var sample = 1
            while (width / (sample * 2) >= outputWidth && height / (sample * 2) >= outputHeight) sample *= 2
            while (ceil(width.toDouble() / sample).toLong() * ceil(height.toDouble() / sample).toLong() > MaxDecodedPixels ||
                ceil(max(width, height).toDouble() / sample) > MaxDecodedSide) sample *= 2
            return ArtworkDecodePlan(sample, outputWidth, outputHeight)
        }
    }
}

/** Read at most limit + one sentinel byte even when Content-Length is absent or false. */
internal fun readArtworkBytes(input: InputStream, limit: Int): ByteArray? {
    require(limit > 0 && limit < Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
    val buffer = ByteArray(16 * 1024)
    var count = 0
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - count + 1))
        if (read < 0) return output.toByteArray().takeIf { it.isNotEmpty() }
        if (read == 0) continue
        count += read
        if (count > limit) return null
        output.write(buffer, 0, read)
    }
}

/** Shared loads have subscriber ownership: leaving one card cannot cancel another card's image. */
internal class ArtworkLoadCoordinator<K, V : Any>(
    private val scope: CoroutineScope,
    private val maxMemoryBytes: Long,
    maxConcurrent: Int,
    private val weight: (V) -> Long,
) {
    private class Subscriber(var priority: ArtworkPriority)
    private data class Flight<V>(val result: Deferred<V?>, val demand: ArtworkDemand,
        val subscribers: MutableSet<Subscriber> = mutableSetOf())
    private val lock = Any()
    private val slots = ArtworkWorkQueue(maxConcurrent)
    // Cached posters must not wait behind slow HTTP. Disk/decode work stays bounded separately.
    private val cacheSlots = Semaphore(2)
    private val flights = mutableMapOf<K, Flight<V>>()
    private val memory = LinkedHashMap<K, Pair<V, Long>>(16, .75f, true)
    private var bytes = 0L
    private var memoryGeneration = 0L
    init { require(maxMemoryBytes > 0) }

    suspend fun load(key: K, fetch: suspend () -> V?): V? = load(key, null, fetch = fetch)

    suspend fun load(key: K, readCached: (suspend () -> V?)?, priority: ArtworkPriority = ArtworkPriority.Visible,
        priorityChanges: StateFlow<ArtworkPriority>? = null, reusableKey: ((K) -> Boolean)? = null,
        fetch: suspend () -> V?): V? {
        currentCoroutineContext().ensureActive()
        val subscriber = Subscriber(priorityChanges?.value ?: priority)
        val (flightKey, flight) = synchronized(lock) {
            memory[key]?.let { return it.first }
            val flightKey = if (key in flights) key else flights.keys.firstOrNull { reusableKey?.invoke(it) == true } ?: key
            val flight = flights.getOrPut(flightKey) {
                val generation = memoryGeneration
                val demand = ArtworkDemand(subscriber.priority)
                Flight(scope.async(start = CoroutineStart.LAZY) {
                    val cached = readCached?.let { cacheSlots.withPermit { it() } }
                    val result = cached ?: slots.withPermit(demand) { fetch() }
                    ensureActive()
                    if (result != null) synchronized(lock) {
                        if (generation == memoryGeneration) remember(flightKey, result)
                    }
                    result
                }, demand)
            }
            flight.subscribers += subscriber
            updateDemand(flight)
            flightKey to flight
        }
        return coroutineScope {
            val changes = priorityChanges?.let { priorities -> launch(start = CoroutineStart.UNDISPATCHED) {
                priorities.collect { next -> synchronized(lock) { subscriber.priority = next; updateDemand(flight) } }
            } }
            try { flight.result.start(); flight.result.await() }
            finally {
                changes?.cancel()
                val abandon = synchronized(lock) {
                    flight.subscribers -= subscriber
                    if (flight.subscribers.isEmpty()) {
                        if (flights[flightKey] === flight) flights.remove(flightKey)
                        !flight.result.isCompleted
                    } else { updateDemand(flight); false }
                }
                if (abandon) flight.result.cancel()
            }
        }
    }

    private fun updateDemand(flight: Flight<V>) {
        slots.updatePriority(flight.demand,
            if (flight.subscribers.any { it.priority == ArtworkPriority.Visible }) ArtworkPriority.Visible else ArtworkPriority.Prefetch)
    }

    fun clearMemory() = synchronized(lock) { memory.clear(); bytes = 0; memoryGeneration++; Unit }
    fun cachedMatching(matches: (K) -> Boolean): V? = synchronized(lock) {
        val key = memory.keys.lastOrNull(matches) ?: return@synchronized null
        memory[key]?.first
    }
    internal fun cachedBytes(): Long = synchronized(lock) { bytes }

    private fun remember(key: K, value: V) {
        val size = weight(value)
        if (size !in 1..maxMemoryBytes) return
        memory.remove(key)?.let { bytes -= it.second }
        memory[key] = value to size
        bytes += size
        val iterator = memory.entries.iterator()
        while (bytes > maxMemoryBytes && iterator.hasNext()) { bytes -= iterator.next().value.second; iterator.remove() }
    }
}

/** Disk persistence is optional and bounded; neither a full queue nor fsync delays a poster. */
internal class ArtworkWriteQueue<T>(scope: CoroutineScope, private val write: suspend (T) -> Unit) {
    private val pending = Channel<T>(capacity = 1)
    init {
        scope.launch {
            for (value in pending) {
                try { write(value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* An image can remain memory-only when persistence fails. */ }
            }
        }
    }
    fun offer(value: T): Boolean = pending.trySend(value).isSuccess
}
