package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.data.db.MovieWatchlistEntity
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.movieWatchlistEntityFromSnapshot
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class MoviesWatchlistStore(
    private val file: File,
    private val roomStore: MoviesWatchlistRoomStore? = null,
) {
    private val roomInitialized = File(file.parentFile, RoomInitializedFileName)
    fun loadWatchlistIds(): Set<Int> {
        val store = roomStore ?: return loadJsonSnapshot().watchlist.mapTo(linkedSetOf()) { it.streamId }
        val ids = runBlocking(Dispatchers.IO) { store.loadIds() }
        if (ids.isNotEmpty() || roomInitialized.exists()) {
            markRoomInitialized()
            return ids
        }
        val snapshot = loadJsonSnapshot()
        if (snapshot.watchlist.isNotEmpty()) runBlocking(Dispatchers.IO) { store.saveSnapshots(snapshot.toRoomEntities()) }
        markRoomInitialized()
        return snapshot.watchlist.mapTo(linkedSetOf()) { it.streamId }
    }

    private fun markRoomInitialized() {
        if (!roomInitialized.exists()) {
            roomInitialized.parentFile?.mkdirs()
            check(roomInitialized.createNewFile() || roomInitialized.exists())
        }
    }

    fun saveWatchlist(movies: List<MovieItem>, watchlistStreamIds: Set<Int>) {
        val supplied = movies
            .filter { it.streamId in watchlistStreamIds }
            .map { movie ->
                WatchlistItem(
                    streamId = movie.streamId,
                    title = movie.title,
                    categoryName = movie.categoryName,
                    containerExtension = movie.containerExtension,
                )
            }
        val existingJson = loadJsonSnapshot().watchlist.associateBy { it.streamId }
        val existingEntities = roomStore?.let { store -> runBlocking(Dispatchers.IO) { store.loadItems() } }.orEmpty().associateBy { it.streamId }
        val existingRoom = existingEntities.mapValues { (_, it) -> WatchlistItem(it.streamId, it.title, it.categoryName, it.containerExtension) }
        val suppliedById = supplied.associateBy { it.streamId }
        val watchlistItems = watchlistStreamIds.map { id ->
            suppliedById[id] ?: existingRoom[id] ?: existingJson[id] ?: WatchlistItem(id)
        }
        roomStore?.let { store ->
            runBlocking(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                store.saveSnapshots(WatchlistSnapshot(watchlistItems).toRoomEntities().map { existingEntities[it.streamId] ?: it.copy(addedAtMs = now) })
            }
        }
        if (roomStore != null) {
            markRoomInitialized()
            // Room is authoritative after migration; an optional legacy mirror cannot undo a committed save.
            try { file.writeText(json.toJson(WatchlistSnapshot(watchlistItems)), Charsets.UTF_8) } catch (_: java.io.IOException) { }
        } else file.writeText(json.toJson(WatchlistSnapshot(watchlistItems)), Charsets.UTF_8)
    }

    fun clear() {
        if (roomStore != null) {
            loadWatchlistIds()
            runBlocking(Dispatchers.IO) { roomStore.clear() }
            // Stale migration JSON is ignored once Room has been initialized.
            if (file.exists()) file.delete()
        } else if (file.exists()) check(file.delete())
    }

    private fun loadJsonSnapshot(): WatchlistSnapshot =
        runCatching {
            if (!file.exists()) return WatchlistSnapshot()
            json.fromJson(file.readText(Charsets.UTF_8), WatchlistSnapshot::class.java)
                ?: WatchlistSnapshot()
        }.getOrDefault(WatchlistSnapshot())

    companion object {
        const val RoomInitializedFileName = "movies_watchlist_room_initialized"
        const val FileName = "movies_watchlist.json"
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

private data class WatchlistSnapshot(
    val watchlist: List<WatchlistItem> = emptyList(),
)

private data class WatchlistItem(
    val streamId: Int,
    val title: String? = null,
    val categoryName: String? = null,
    val containerExtension: String? = null,
)

private fun WatchlistSnapshot.toRoomEntities(): List<MovieWatchlistEntity> =
    watchlist.mapIndexed { index, item ->
        movieWatchlistEntityFromSnapshot(
            streamId = item.streamId,
            title = item.title,
            categoryName = item.categoryName,
            containerExtension = item.containerExtension,
            addedAtMs = watchlist.size.toLong() - index,
        )
    }
