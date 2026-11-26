package com.atlas.atlastv.ui.search

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

data class SearchScrollPosition(val index: Int = 0, val offset: Int = 0)
data class SearchGridSession(val rowId: String, val selectedId: Int?, val selectedIndex: Int,
    val position: SearchScrollPosition)
data class SearchSession(val owner: String, val query: String, val selectedResultId: String? = null,
    val entryRowId: String? = null, val position: SearchScrollPosition = SearchScrollPosition(),
    val rows: Map<String, SearchScrollPosition> = emptyMap(), val grid: SearchGridSession? = null)

interface SearchSessionStore {
    suspend fun load(owner: String): SearchSession?
    fun schedule(session: SearchSession)
    suspend fun awaitSaved()
}

/** One small account-bound navigation snapshot; process-owned, conflated IO outlives screen disposal. */
class FileSearchSessionStore(private val file: File) : SearchSessionStore {
    private val entry = entries.computeIfAbsent(file.absoluteFile.toPath().normalize().toString()) { Entry(file) }

    override suspend fun load(owner: String): SearchSession? = withContext(Dispatchers.IO) {
        val pending = synchronized(entry) { entry.latest }
        if (pending != null) return@withContext pending.session.takeIf { it.owner == owner }
        val disk = entry.lock.withLock {
            runCatching {
                if (!file.isFile || file.length() > 65_536) return@runCatching null
                gson.fromJson(file.readText(Charsets.UTF_8), SearchSession::class.java)
                    ?.validated()?.takeIf { it.owner == owner }
            }.getOrNull()
        }
        synchronized(entry) {
            val latest = entry.latest
            if (latest != null) latest.session.takeIf { it.owner == owner } else disk
        }
    }

    override fun schedule(session: SearchSession) {
        val safe = session.validated() ?: return
        synchronized(entry) {
            if (entry.latest?.session == safe) return
            val write = Write(++entry.version, safe)
            entry.latest = write
            entry.requests.trySend(write)
        }
    }

    override suspend fun awaitSaved() {
        val version = synchronized(entry) { entry.version }
        entry.saved.first { it >= version }
    }

    private data class Write(val version: Long, val session: SearchSession)
    private class Entry(file: File) {
        val lock = Mutex()
        val requests = Channel<Write>(Channel.CONFLATED)
        val saved = MutableStateFlow(0L)
        var version = 0L
        var latest: Write? = null
        init { processScope.launch {
            requests.receiveAsFlow().collectLatest { value ->
                delay(150)
                var failures = 0
                while (true) {
                    try {
                        lock.withLock {
                            val bytes = gson.toJson(value.session).toByteArray(Charsets.UTF_8)
                            file.parentFile?.mkdirs()
                            val pending = File(file.parentFile, "${file.name}.pending")
                            try {
                                FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
                                try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                                catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                            } finally { pending.delete() }
                        }
                        saved.value = maxOf(saved.value, value.version)
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { delay((1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000)); failures++ }
                }
            }
        } }
    }

    companion object {
        const val FileName = "search_session.json"
        private val gson = Gson()
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val entries = ConcurrentHashMap<String, Entry>()
    }
}

private val searchRows = setOf("unified_search_movies", "unified_search_shows")
internal fun SearchSession.validated(): SearchSession? = runCatching {
    require(owner.length in 1..128 && owner.matches(Regex("[A-Za-z0-9_-]+")))
    require(query.length <= MaxUnifiedSearchQueryLength && query.none { it.isISOControl() } && !query.contains("://"))
    require(query.isNotBlank() || (grid == null && selectedResultId == null && entryRowId == null))
    require(selectedResultId == null || selectedResultId.matches(Regex("(?:movie|show)-[1-9][0-9]{0,9}")))
    require(entryRowId == null || entryRowId in searchRows)
    fun SearchScrollPosition.safe() = copy(index = index.coerceIn(0, 10_000_000), offset = offset.coerceIn(0, 100_000))
    val safeGrid = grid?.let {
        require(it.rowId in searchRows && (it.selectedId == null || it.selectedId > 0))
        it.copy(selectedIndex = it.selectedIndex.coerceIn(0, 10_000_000), position = it.position.safe())
    }
    copy(position = position.safe(), rows = rows.filterKeys { it in searchRows }.mapValues { it.value.safe() }, grid = safeGrid)
}.getOrNull()
