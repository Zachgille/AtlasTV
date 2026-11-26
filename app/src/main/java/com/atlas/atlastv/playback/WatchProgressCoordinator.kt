package com.atlas.atlastv.playback

import com.atlas.atlastv.data.db.sanitizedWatchProgressRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface WatchProgressBackend {
    suspend fun load(): WatchProgressSnapshot
    suspend fun save(record: WatchProgressRecord)
    suspend fun saveStartedPlayback(record: WatchProgressRecord, startedAtMs: Long) { save(record) }
    suspend fun saveBatch(records: List<WatchProgressRecord>) { records.forEach { save(it) } }
}

data class ProgressSession internal constructor(val key: String, internal val generation: Long, internal val startedAtMs: Long)

/** Captures are submitted on the player thread; disk work belongs to the process, not a screen. */
class WatchProgressCoordinator(
    private val backend: WatchProgressBackend,
    scope: CoroutineScope,
    private val retryDelayMs: Long = 1_000L,
    private val journal: PendingProgressJournal? = null,
) {
    private data class Pending(val version: Long, val record: WatchProgressRecord, val batch: Long? = null,
        val playbackSession: ProgressSession? = null)
    private val guard = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val known = mutableMapOf<String, WatchProgressRecord>()
    private val sessions = mutableMapOf<String, Long>()
    private val saved = mutableMapOf<String, Long>()
    private val changes = mutableMapOf<String, Long>()
    private val failed = mutableMapOf<String, Long>()
    private val movedSessions = mutableMapOf<String, Long>()
    private var sequence = 0L
    private var generation = 0L
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val journalSignal = Channel<Unit>(Channel.CONFLATED)
    private val recovered = CompletableDeferred<Unit>()
    private val recoveryLock = Mutex()
    private val durable = mutableMapOf<String, Long>()
    private var journalFailed = false
    private val journalRevision = MutableStateFlow(0L)
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed = _saveFailed.asStateFlow()

    init {
        if (journal == null) recovered.complete(Unit) else {
            scope.launch {
                for (ignored in journalSignal) {
                    var failures = 0
                    while (true) {
                        try {
                            recoverPending()
                            val snapshot = synchronized(guard) { pending.values.toList() }
                            journal.write(snapshot.map { PendingProgressWrite(it.record, it.batch, it.playbackSession?.startedAtMs) })
                            synchronized(guard) {
                                snapshot.forEach { durable[it.record.key] = maxOf(durable[it.record.key] ?: -1, it.version) }
                                val recoveringFailure = journalFailed
                                journalFailed = false
                                _saveFailed.value = failed.isNotEmpty()
                                journalRevision.value += 1
                                if (recoveringFailure) _revision.value += 1
                            }
                            break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            reportJournalFailure()
                            withTimeoutOrNull((retryDelayMs * (1L shl failures.coerceAtMost(5))).coerceAtMost(30_000)) { journalSignal.receive() }
                            failures++
                        }
                    }
                }
            }
            journalSignal.trySend(Unit)
        }
        scope.launch {
            recovered.await()
            for (ignored in signal) {
                var failures = 0
                while (true) {
                    val batch = synchronized(guard) {
                        val first = pending.values.firstOrNull() ?: return@synchronized emptyList()
                        if (first.batch == null) listOf(first) else pending.values.filter { it.batch == first.batch }
                    }
                    if (batch.isEmpty()) break
                    try {
                        if (journal != null) journalRevision.first { synchronized(guard) {
                            batch.all { (durable[it.record.key] ?: -1) >= it.version }
                        } }
                        val capture = batch.singleOrNull()
                        val move = capture?.playbackSession?.takeIf { session ->
                            synchronized(guard) { movedSessions[session.key] != session.generation }
                        }
                        if (move != null) backend.saveStartedPlayback(capture!!.record, move.startedAtMs)
                        else if (batch.first().batch == null) backend.save(batch.single().record)
                        else backend.saveBatch(batch.map { it.record })
                        synchronized(guard) {
                            if (move != null) movedSessions[move.key] = move.generation
                            batch.forEach { next ->
                                saved[next.record.key] = next.version
                                if (pending[next.record.key]?.version == next.version) pending.remove(next.record.key)
                                if (failed[next.record.key] == next.version) failed.remove(next.record.key)
                            }
                            _revision.value += 1
                            _saveFailed.value = journalFailed || failed.isNotEmpty()
                        }
                        if (journal != null) journalSignal.trySend(Unit)
                        failures = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        synchronized(guard) {
                            batch.forEach { next ->
                                if (pending[next.record.key]?.version == next.version) failed[next.record.key] = next.version
                            }
                            _saveFailed.value = journalFailed || failed.isNotEmpty()
                            _revision.value += 1
                        }
                        // Retain the latest capture and retry without blocking remote input or resume lookup.
                        withTimeoutOrNull((retryDelayMs * (1L shl failures.coerceAtMost(5))).coerceAtMost(30_000L)) { signal.receive() }
                        failures += 1
                    }
                }
            }
        }
    }

    private fun wake() { journalSignal.trySend(Unit); signal.trySend(Unit) }

    private fun reportJournalFailure() = synchronized(guard) {
        if (!journalFailed) { journalFailed = true; _saveFailed.value = true; _revision.value += 1 }
    }

    private suspend fun recoverPending() {
        if (recovered.isCompleted) return
        recoveryLock.withLock {
            if (recovered.isCompleted) return@withLock
            val values = journal!!.read()
            val committed = if (values.isEmpty()) emptyMap() else backend.load().records.associateBy { it.key }
            synchronized(guard) {
                val batches = mutableMapOf<Long, Long>()
                values.forEach { value ->
                    val record = value.record
                    if (pending[record.key] != null || (committed[record.key]?.updatedAtMs ?: -1) > record.updatedAtMs) return@forEach
                    val version = value.batch?.let { batches.getOrPut(it) { ++sequence } } ?: ++sequence
                    val session = value.startedAtMs?.let { ProgressSession(record.key, ++generation, it) }
                    pending[record.key] = Pending(version, record, value.batch?.let { version }, session)
                    known[record.key] = record; changes[record.key] = version; durable[record.key] = version
                }
            }
            recovered.complete(Unit)
            signal.trySend(Unit)
        }
    }

    fun openSession(key: String): ProgressSession = synchronized(guard) {
        generation += 1
        sessions[key] = generation
        changes[key] = ++sequence
        _revision.value += 1
        ProgressSession(key, generation, System.currentTimeMillis())
    }

    /** Remove only resume-eligible checkpoints. A newer action during the read wins. */
    suspend fun removeFromContinueWatching(type: WatchProgressContentType, id: Int, seriesId: Int? = null): ContinueWatchingRemovalResult {
        if (id <= 0 || (seriesId != null && seriesId <= 0)) return ContinueWatchingRemovalResult.Conflict
        val before = synchronized(guard) { changes.toMap() }
        val records = loadSnapshot().records.filter { record ->
            record.contentType == type && WatchProgressPolicy.isResumeEligible(record) &&
                if (type == WatchProgressContentType.SeriesEpisode && seriesId != null) record.seriesId == seriesId else record.streamId == id
        }
        return synchronized(guard) {
            if (records.isEmpty() || records.any { changes[it.key] != before[it.key] }) return@synchronized ContinueWatchingRemovalResult.Conflict
            enqueueRemoval(records, records.map { it.copy(positionMs = 0) })
        }.also { if (it is ContinueWatchingRemovalResult.Accepted) wake() }
    }

    private fun enqueueRemoval(previous: List<WatchProgressRecord>, values: List<WatchProgressRecord>): ContinueWatchingRemovalResult.Accepted {
        val version = ++sequence
        val applied = values.map { value ->
            sessions.remove(value.key)
            val safe = value.sanitizedWatchProgressRecord().copy(updatedAtMs = maxOf(System.currentTimeMillis(), (known[value.key]?.updatedAtMs ?: value.updatedAtMs) + 1))
            changes[safe.key] = version
            failed.remove(safe.key)
            known[safe.key] = safe
            pending[safe.key] = Pending(version, safe, batch = version)
            safe
        }
        _revision.value += 1
        return ContinueWatchingRemovalResult.Accepted(ContinueWatchingRemoval(version, previous, applied))
    }

    fun removalStatus(edit: ContinueWatchingRemoval): EpisodeEditStatus = synchronized(guard) {
        when {
            edit.applied.any { changes[it.key] != edit.version } -> EpisodeEditStatus.Superseded
            edit.applied.all { (saved[it.key] ?: -1L) >= edit.version } -> EpisodeEditStatus.Saved
            journalFailed || edit.applied.any { failed[it.key] == edit.version } -> EpisodeEditStatus.Failed
            else -> EpisodeEditStatus.Saving
        }
    }

    fun canUndoRemoval(edit: ContinueWatchingRemoval): Boolean = removalStatus(edit) == EpisodeEditStatus.Saved

    suspend fun undoRemoval(edit: ContinueWatchingRemoval): ContinueWatchingRemovalResult = synchronized(guard) {
        if (!canUndoRemoval(edit)) return@synchronized ContinueWatchingRemovalResult.Conflict
        enqueueRemoval(edit.applied, edit.previous)
    }.also { if (it is ContinueWatchingRemovalResult.Accepted) wake() }

    fun retryRemoval(edit: ContinueWatchingRemoval) {
        synchronized(guard) {
            edit.applied.forEach {
                if (changes[it.key] == edit.version && pending[it.key]?.version == edit.version) failed.remove(it.key)
            }
            _revision.value += 1
        }
        wake()
    }

    fun submit(session: ProgressSession, record: WatchProgressRecord, renderedPlayback: Boolean = false) {
        synchronized(guard) {
            if (session.key != record.key || sessions[record.key] != session.generation) return
            val previous = known[record.key]
            if (previous == null && !WatchProgressPolicy.shouldPersist(record)) return
            // Ordering follows capture order, not position. Rewinding is a valid newer update.
            val safe = record.sanitizedWatchProgressRecord().copy(
                updatedAtMs = maxOf(record.updatedAtMs, (previous?.updatedAtMs ?: -1L) + 1L),
            )
            sequence += 1
            changes[record.key] = sequence
            failed.remove(record.key)
            known[record.key] = safe
            val move = session.takeIf { renderedPlayback && WatchProgressPolicy.isResumeEligible(safe) &&
                (safe.contentType == WatchProgressContentType.Movie || (safe.seriesId ?: 0) > 0) }
            pending[record.key] = Pending(sequence, safe, playbackSession = move)
            _revision.value += 1
        }
        wake()
    }

    suspend fun loadSnapshot(): WatchProgressSnapshot {
        try { recoverPending() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            reportJournalFailure()
            journalSignal.trySend(Unit)
        }
        val before = revision.value
        val disk = backend.load()
        return synchronized(guard) {
            disk.records.forEach { record ->
                if (pending[record.key] == null && (known[record.key] == null || (revision.value == before && (known[record.key]?.updatedAtMs ?: -1L) < record.updatedAtMs))) {
                    known[record.key] = record
                }
            }
            // Include unsaved captures so an immediate replay cannot read an older resume point.
            val merged = disk.records.associateBy { it.key }.toMutableMap()
            if (revision.value != before) known.values.forEach { record ->
                if ((merged[record.key]?.updatedAtMs ?: -1L) <= record.updatedAtMs) merged[record.key] = record
            }
            pending.values.forEach {
                if ((merged[it.record.key]?.updatedAtMs ?: -1L) <= it.record.updatedAtMs) merged[it.record.key] = it.record
            }
            WatchProgressSnapshot(merged.values.sortedByDescending { it.updatedAtMs })
        }
    }

    /** Explicit episode edits share playback ordering. A newer session/action invalidates Undo. */
    suspend fun setEpisodeWatched(base: WatchProgressRecord, watched: Boolean): EpisodeEditResult {
        if (base.contentType != WatchProgressContentType.SeriesEpisode || base.streamId <= 0 || base.seriesId == null) return EpisodeEditResult.Conflict
        return setWatched(base, watched)
    }

    suspend fun setMovieWatched(base: WatchProgressRecord, watched: Boolean): EpisodeEditResult {
        if (base.contentType != WatchProgressContentType.Movie || base.streamId <= 0) return EpisodeEditResult.Conflict
        return setWatched(base.copy(seriesId = null, seriesTitle = null, episodeTitle = null), watched)
    }

    private suspend fun setWatched(base: WatchProgressRecord, watched: Boolean): EpisodeEditResult {
        val before = synchronized(guard) { changes[base.key] }
        val snapshot = loadSnapshot()
        return synchronized(guard) {
            if (changes[base.key] != before) return@synchronized EpisodeEditResult.Conflict
            val previous = snapshot.recordFor(base.contentType, base.streamId)
            if (base.contentType == WatchProgressContentType.SeriesEpisode && previous?.seriesId != null &&
                previous.seriesId != base.seriesId) return@synchronized EpisodeEditResult.Conflict
            val source = (previous ?: base).copy(seriesId = base.seriesId,
                durationMs = previous?.durationMs?.takeIf { it > 0 } ?: base.durationMs?.takeIf { it > 0 })
            enqueueEdit(previous, source.copy(completed = watched,
                positionMs = if (watched) source.durationMs?.takeIf { it > 0 } ?: 0L else 0L))
        }.also { if (it is EpisodeEditResult.Accepted) wake() }
    }

    suspend fun undoEpisodeEdit(edit: EpisodeProgressEdit): EpisodeEditResult = synchronized(guard) {
        if (!canUndoLocked(edit)) return@synchronized EpisodeEditResult.Conflict
        // A zero/unwatched tombstone preserves an originally unstarted state without legacy reseeding.
        val restored = edit.previous ?: edit.applied.copy(positionMs = 0, completed = false)
        enqueueEdit(edit.applied, restored)
    }.also { if (it is EpisodeEditResult.Accepted) wake() }

    private fun enqueueEdit(previous: WatchProgressRecord?, value: WatchProgressRecord): EpisodeEditResult.Accepted {
        sessions.remove(value.key)
        val safe = value.sanitizedWatchProgressRecord().copy(updatedAtMs = maxOf(System.currentTimeMillis(), (known[value.key]?.updatedAtMs ?: previous?.updatedAtMs ?: 0L) + 1L))
        sequence += 1
        known[value.key] = safe
        changes[value.key] = sequence
        failed.remove(value.key)
        pending[value.key] = Pending(sequence, safe)
        _revision.value += 1
        return EpisodeEditResult.Accepted(EpisodeProgressEdit(sequence, previous, safe))
    }

    fun editStatus(edit: EpisodeProgressEdit): EpisodeEditStatus = synchronized(guard) {
        when {
            changes[edit.applied.key] != edit.version -> EpisodeEditStatus.Superseded
            (saved[edit.applied.key] ?: -1L) >= edit.version -> EpisodeEditStatus.Saved
            journalFailed || failed[edit.applied.key] == edit.version -> EpisodeEditStatus.Failed
            else -> EpisodeEditStatus.Saving
        }
    }

    fun canUndo(edit: EpisodeProgressEdit): Boolean = synchronized(guard) { canUndoLocked(edit) }
    private fun canUndoLocked(edit: EpisodeProgressEdit): Boolean =
        changes[edit.applied.key] == edit.version && (saved[edit.applied.key] ?: -1L) >= edit.version

    fun retryEdit(edit: EpisodeProgressEdit) {
        synchronized(guard) {
            if (changes[edit.applied.key] != edit.version || pending[edit.applied.key]?.version != edit.version) return
            failed.remove(edit.applied.key)
            _revision.value += 1
        }
        wake()
    }

    /** Captures remain asynchronous; this barrier confirms they reached the recovery file. */
    suspend fun awaitJournaled() {
        if (journal == null) return
        recovered.await()
        val targets = synchronized(guard) { pending.mapValues { it.value.version } }
        journalRevision.first { synchronized(guard) { targets.all { (key, version) -> (durable[key] ?: -1L) >= version } } }
    }

    /** An acknowledgement barrier, useful for explicit verification; never blocks the UI thread. */
    suspend fun awaitSaved() {
        recovered.await()
        val targets = synchronized(guard) { pending.mapValues { it.value.version } }
        revision.first { synchronized(guard) { targets.all { (key, version) -> (saved[key] ?: -1L) >= version } } }
    }

    companion object {
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stores = ConcurrentHashMap<String, WatchProgressCoordinator>()
        fun forStore(store: WatchProgressStore): WatchProgressCoordinator = stores.computeIfAbsent(store.identity) {
            WatchProgressCoordinator(object : WatchProgressBackend {
                override suspend fun load() = store.loadAsync()
                override suspend fun save(record: WatchProgressRecord) = store.saveRecordAsync(record)
                override suspend fun saveStartedPlayback(record: WatchProgressRecord, startedAtMs: Long) =
                    store.saveRecordAsync(record, startedAtMs)
                override suspend fun saveBatch(records: List<WatchProgressRecord>) = store.saveBatchAsync(records)
            }, processScope, journal = FilePendingProgressJournal(File("${store.identity}.queue")))
        }
    }
}
