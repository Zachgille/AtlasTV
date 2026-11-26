package com.atlas.atlastv.playback

import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.data.db.safeProgressText
import com.atlas.atlastv.data.db.sanitizedWatchProgressRecord
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.ConcurrentHashMap

enum class WatchProgressContentType(val storageKey: String, val displayName: String) {
    Movie("movie", "Movie"),
    SeriesEpisode("series_episode", "Series episode");

    companion object {
        fun fromStorageKey(value: String?): WatchProgressContentType? =
            entries.firstOrNull { it.storageKey == value }
    }
}

data class WatchProgressRecord(
    val contentType: WatchProgressContentType,
    val streamId: Int,
    val seriesId: Int? = null,
    val title: String,
    val seriesTitle: String? = null,
    val episodeTitle: String? = null,
    val categoryName: String? = null,
    val extension: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean = false,
) {
    val key: String
        get() = key(contentType, streamId)

    companion object {
        fun key(contentType: WatchProgressContentType, streamId: Int): String =
            "${contentType.storageKey}:$streamId"
    }
}

data class WatchProgressSnapshot(
    val records: List<WatchProgressRecord> = emptyList(),
) {
    fun recordFor(contentType: WatchProgressContentType, streamId: Int): WatchProgressRecord? =
        records.firstOrNull { it.contentType == contentType && it.streamId == streamId }

    fun continueWatching(limit: Int = 12): List<WatchProgressRecord> =
        records
            .filter { record -> WatchProgressPolicy.isResumeEligible(record) }
            .sortedByDescending { it.updatedAtMs }
            // Only the shelf is grouped; every episode checkpoint remains in records.
            // Unknown parents stay separate rather than being guessed from display titles.
            .distinctBy { record ->
                val parent = record.seriesId?.takeIf { it > 0 }
                if (record.contentType == WatchProgressContentType.SeriesEpisode && parent != null)
                    "series:$parent" else record.key
            }
            .take(limit)
}

class WatchProgressStore(
    private val file: File,
    private val roomStore: WatchProgressRoomStore? = null,
) {
    // Shared across screen-owned instances. Normal callers use the suspend API/coordinator.
    val identity: String = file.absoluteFile.toPath().normalize().toString()
    private val lock = locks.computeIfAbsent(identity) { Mutex() }

    fun load(): WatchProgressSnapshot = runBlocking { loadAsync() }
    fun saveRecord(record: WatchProgressRecord) = runBlocking { saveRecordAsync(record) }
    fun saveRecords(records: List<WatchProgressRecord>) = runBlocking { saveRecordsAsync(records) }
    fun clear() = runBlocking { clearAsync() }

    suspend fun loadAsync(): WatchProgressSnapshot = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked() }
    }

    private suspend fun loadLocked(): WatchProgressSnapshot {
        val roomSnapshot = roomStore?.load()
        if (roomSnapshot != null && roomSnapshot.records.isNotEmpty()) return roomSnapshot
        if (!file.exists()) return roomSnapshot ?: WatchProgressSnapshot()
        val jsonSnapshot = loadJson()
        if (jsonSnapshot.records.isNotEmpty() && roomStore != null) {
            roomStore.seedIfEmpty(jsonSnapshot.records)
            return roomStore.load()
        }
        return jsonSnapshot
    }

    suspend fun saveRecordAsync(record: WatchProgressRecord, playbackStartedAtMs: Long? = null): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked().records
            val previous = current.firstOrNull { it.key == record.key }
            // A deliberate rewind/replay must replace existing progress, even below 30 seconds.
            if (!WatchProgressPolicy.shouldPersist(record) && previous == null) return@withLock
            if (previous != null && previous.updatedAtMs > record.updatedAtMs) return@withLock
            val safe = record.sanitized()
            if (roomStore != null && playbackStartedAtMs != null && WatchProgressPolicy.isResumeEligible(safe)) {
                // Before removing the last Room entry, prevent an old migration mirror reseeding it.
                val markerName = when (safe.contentType) {
                    WatchProgressContentType.Movie -> com.atlas.atlastv.ui.movies.MoviesWatchlistStore.RoomInitializedFileName
                    WatchProgressContentType.SeriesEpisode -> com.atlas.atlastv.ui.series.SeriesWatchlistStore.RoomInitializedFileName
                }
                val marker = File(file.parentFile, markerName)
                marker.parentFile?.mkdirs()
                check(marker.exists() || marker.createNewFile())
                roomStore.saveStartedPlayback(safe, playbackStartedAtMs)
            } else roomStore?.saveRecord(safe)
            val next = roomStore?.load()?.records
                ?: (current.filterNot { it.key == safe.key } + safe).sortedByDescending { it.updatedAtMs }
            write(next)
        }
    }

    /** Targeted batch edit; never replaces the complete progress collection. */
    suspend fun saveBatchAsync(records: List<WatchProgressRecord>): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked().records.associateBy { it.key }.toMutableMap()
            val accepted = records.filter { value ->
                val previous = current[value.key]
                (previous != null || WatchProgressPolicy.shouldPersist(value)) &&
                    (previous == null || previous.updatedAtMs <= value.updatedAtMs)
            }.map { it.sanitized() }
            if (accepted.isEmpty()) return@withLock
            roomStore?.saveBatch(accepted)
            accepted.forEach { current[it.key] = it }
            write(roomStore?.load()?.records ?: current.values.sortedByDescending { it.updatedAtMs })
        }
    }

    suspend fun saveRecordsAsync(records: List<WatchProgressRecord>): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val safe = records.map { it.sanitized() }.sortedByDescending { it.updatedAtMs }
            roomStore?.saveRecords(safe)
            write(safe)
        }
    }

    suspend fun clearAsync(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            // Persist an empty export so an old JSON file cannot reseed a cleared database.
            write(emptyList())
            roomStore?.clear()
        }
    }

    private fun write(records: List<WatchProgressRecord>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.pending")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(StoredWatchProgress(records.map { it.toStored() })).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun WatchProgressRecord.sanitized(): WatchProgressRecord =
        sanitizedWatchProgressRecord()

    private fun WatchProgressRecord.toStored(): StoredWatchProgressRecord =
        StoredWatchProgressRecord(
            contentType = contentType.storageKey,
            streamId = streamId,
            seriesId = seriesId,
            title = title,
            seriesTitle = seriesTitle,
            episodeTitle = episodeTitle,
            categoryName = categoryName,
            extension = extension,
            durationMs = durationMs,
            positionMs = positionMs,
            updatedAtMs = updatedAtMs,
            completed = completed,
        )

    private fun StoredWatchProgressRecord.toRecordOrNull(): WatchProgressRecord? {
        val type = WatchProgressContentType.fromStorageKey(contentType) ?: return null
        val safeStreamId = streamId ?: return null
        return WatchProgressRecord(
            contentType = type,
            streamId = safeStreamId,
            seriesId = seriesId,
            title = title?.safeProgressText("Untitled") ?: "Untitled",
            seriesTitle = seriesTitle?.safeProgressText("Series"),
            episodeTitle = episodeTitle?.safeProgressText("Episode"),
            categoryName = categoryName?.safeProgressText("Uncategorized"),
            extension = extension?.trim()?.trimStart('.')?.take(12),
            durationMs = durationMs?.takeIf { it > 0L },
            positionMs = (positionMs ?: 0L).coerceAtLeast(0L),
            updatedAtMs = updatedAtMs ?: 0L,
            completed = completed ?: false,
        )
    }

    private fun loadJson(): WatchProgressSnapshot {
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return WatchProgressSnapshot()
        val stored = gson.fromJson(text, StoredWatchProgress::class.java)
            ?: error("Invalid progress data")
        return WatchProgressSnapshot(stored.records.mapNotNull { it.toRecordOrNull() })
    }

    private data class StoredWatchProgress(
        val records: List<StoredWatchProgressRecord> = emptyList(),
    )

    private data class StoredWatchProgressRecord(
        val contentType: String? = null,
        val streamId: Int? = null,
        val seriesId: Int? = null,
        val title: String? = null,
        val seriesTitle: String? = null,
        val episodeTitle: String? = null,
        val categoryName: String? = null,
        val extension: String? = null,
        val durationMs: Long? = null,
        val positionMs: Long? = null,
        val updatedAtMs: Long? = null,
        val completed: Boolean? = null,
    )

    companion object {
        const val FileName = "watch_progress.json"

        private val locks = ConcurrentHashMap<String, Mutex>()

        private val gson = GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
    }
}

object WatchProgressPolicy {
    const val MinMeaningfulPositionMs: Long = 30_000L
    const val MinResumePositionMs: Long = 30_000L
    const val CompletionPercentThreshold: Double = 0.92
    const val CompletionRemainingMs: Long = 120_000L

    fun shouldPersist(record: WatchProgressRecord): Boolean =
        record.completed || record.positionMs >= MinMeaningfulPositionMs

    fun isCompleted(positionMs: Long, durationMs: Long?): Boolean {
        if (durationMs == null || durationMs <= 0L) return false
        return positionMs >= (durationMs * CompletionPercentThreshold).toLong() ||
            durationMs - positionMs <= minOf(CompletionRemainingMs, (durationMs * (1 - CompletionPercentThreshold)).toLong())
    }

    fun isResumeEligible(record: WatchProgressRecord): Boolean =
        !record.completed &&
            record.positionMs >= MinResumePositionMs &&
            record.durationMs?.let { duration -> duration > 0L && !isCompleted(record.positionMs, duration) } != false

    fun resumePositionMs(record: WatchProgressRecord?): Long? =
        record?.positionMs?.takeIf { isResumeEligible(record) }
}

fun Long.formatWatchProgressTime(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun WatchProgressRecord.resumeLabel(): String =
    buildString {
        append("Resume at ")
        append(positionMs.formatWatchProgressTime())
        durationMs?.takeIf { it > 0L }?.let { duration ->
            val percent = ((positionMs.toDouble() / duration.toDouble()) * 100).toInt().coerceIn(0, 100)
            append(" (").append(percent).append("%)")
        }
    }
