package com.atlas.atlastv.playback

import com.atlas.atlastv.data.db.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class PendingProgressWrite(val record: WatchProgressRecord, val batch: Long? = null, val startedAtMs: Long? = null)

interface PendingProgressJournal {
    suspend fun read(): List<PendingProgressWrite>
    suspend fun write(values: List<PendingProgressWrite>)
}

/** One coordinator owns this file. The journal never contains playback addresses or credentials. */
class FilePendingProgressJournal(private val file: File) : PendingProgressJournal {
    override suspend fun read(): List<PendingProgressWrite> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        if (file.length() > MaxBytes) throw IOException("Progress recovery file exceeds its size limit")
        val text = file.readText(Charsets.UTF_8)
        try {
            val stored = gson.fromJson(text, Snapshot::class.java) ?: error("Invalid progress recovery data")
            if (stored.version > 1) throw IOException("Unsupported progress recovery version")
            require(stored.version == 1)
            val entries = requireNotNull(stored.entries)
            require(entries.size <= MaxRecords)
            val values = entries.map { entry ->
                val entity = requireNotNull(entry.record)
                val record = requireNotNull(entity.toWatchProgressRecordOrNull())
                require(record.streamId > 0 && record.updatedAtMs >= 0)
                require(entry.batch == null || entry.batch > 0)
                require(entry.startedAtMs == null || (entry.startedAtMs > 0 && entry.batch == null))
                PendingProgressWrite(record.sanitizedWatchProgressRecord(), entry.batch, entry.startedAtMs)
            }
            require(values.distinctBy { it.record.key }.size == values.size)
            values
        } catch (invalid: RuntimeException) {
            // A malformed optional journal must not make committed viewing history unreadable.
            Files.move(file.toPath(), File(file.parentFile, "${file.name}.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            emptyList()
        }
    }

    override suspend fun write(values: List<PendingProgressWrite>): Unit = withContext(Dispatchers.IO) {
        require(values.size <= MaxRecords && values.distinctBy { it.record.key }.size == values.size)
        val bytes = gson.toJson(Snapshot(1, values.map {
            Entry(it.record.toWatchProgressEntity(), it.batch, it.startedAtMs)
        })).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxBytes)
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { pending.delete() }
    }

    private data class Snapshot(val version: Int, val entries: List<Entry>?)
    private data class Entry(val record: WatchProgressEntity?, val batch: Long?, val startedAtMs: Long?)
    companion object {
        private const val MaxRecords = 10_000
        private const val MaxBytes = 16 * 1024 * 1024
        private val gson = Gson()
    }
}
