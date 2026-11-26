package com.atlas.atlastv.ui.browse

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Small immutable navigation snapshots; one IO writer per file, independent of Activity lifetime. */
internal class NavigationSnapshotFile<T : Any>(file: File, private val type: Class<T>) {
    private val entry = entries.computeIfAbsent(file.absoluteFile.toPath().normalize().toString()) { Entry(file) }
    suspend fun load(): T? = withContext(Dispatchers.IO) {
        synchronized(entry) { entry.latest }?.let { return@withContext type.cast(it.value) }
        val disk = entry.lock.withLock {
            runCatching {
                if (!entry.file.isFile || entry.file.length() > 65_536) null
                else gson.fromJson(entry.file.readText(Charsets.UTF_8), type)
            }.getOrNull()
        }
        synchronized(entry) { entry.latest?.let { type.cast(it.value) } ?: disk }
    }
    fun schedule(value: T) = synchronized(entry) {
        if (entry.latest?.value != value) {
            val write = Write(++entry.version, value)
            entry.latest = write
            entry.requests.trySend(write)
        }
        Unit
    }
    suspend fun awaitSaved() {
        val version = synchronized(entry) { entry.version }
        entry.saved.first { it >= version }
    }
    private data class Write(val version: Long, val value: Any)
    private class Entry(val file: File) {
        val lock = Mutex()
        val requests = Channel<Write>(Channel.CONFLATED)
        val saved = MutableStateFlow(0L)
        var version = 0L
        var latest: Write? = null
        init { scope.launch {
            requests.receiveAsFlow().collectLatest { write ->
                delay(150)
                var failures = 0
                while (true) {
                    try {
                        lock.withLock {
                            val bytes = gson.toJson(write.value).toByteArray(Charsets.UTF_8)
                            require(bytes.size <= 65_536)
                            file.parentFile?.mkdirs()
                            val pending = File(file.parentFile, "${file.name}.pending")
                            try {
                                FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
                                try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                                catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                            } finally { pending.delete() }
                        }
                        saved.value = maxOf(saved.value, write.version)
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { delay((1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000)); failures++ }
                }
            }
        } }
    }
    companion object {
        private val gson = Gson()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val entries = ConcurrentHashMap<String, Entry>()
    }
}
