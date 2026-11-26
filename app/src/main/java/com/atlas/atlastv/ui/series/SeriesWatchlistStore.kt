package com.atlas.atlastv.ui.series

import com.atlas.atlastv.data.db.SeriesWatchlistEntity
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.seriesWatchlistEntityFromSnapshot
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class SeriesWatchlistStore(
    private val file: File,
    private val roomStore: SeriesWatchlistRoomStore? = null,
) {
    private val roomInitialized = File(file.parentFile, RoomInitializedFileName)
    fun loadWatchlistIds(): Set<Int> {
        val store = roomStore ?: return loadJsonSnapshot().watchlist.mapTo(linkedSetOf()) { it.seriesId }
        val ids = runBlocking(Dispatchers.IO) { store.loadIds() }
        if (ids.isNotEmpty() || roomInitialized.exists()) {
            markRoomInitialized()
            return ids
        }
        val snapshot = loadJsonSnapshot()
        if (snapshot.watchlist.isNotEmpty()) runBlocking(Dispatchers.IO) { store.saveSnapshots(snapshot.toRoomEntities()) }
        markRoomInitialized()
        return snapshot.watchlist.mapTo(linkedSetOf()) { it.seriesId }
    }

    private fun markRoomInitialized() {
        if (!roomInitialized.exists()) {
            roomInitialized.parentFile?.mkdirs()
            check(roomInitialized.createNewFile() || roomInitialized.exists())
        }
    }

    fun saveWatchlist(series: List<SeriesItem>, watchlistSeriesIds: Set<Int>) {
        val supplied = series
            .filter { it.seriesId in watchlistSeriesIds }
            .map { item ->
                WatchlistItem(
                    seriesId = item.seriesId,
                    title = item.name,
                    categoryName = item.categoryName,
                )
            }
        val existingJson = loadJsonSnapshot().watchlist.associateBy { it.seriesId }
        val existingEntities = roomStore?.let { store -> runBlocking(Dispatchers.IO) { store.loadItems() } }.orEmpty().associateBy { it.seriesId }
        val existingRoom = existingEntities.mapValues { (_, it) -> WatchlistItem(it.seriesId, it.title, it.categoryName) }
        val suppliedById = supplied.associateBy { it.seriesId }
        val watchlistItems = watchlistSeriesIds.map { id ->
            suppliedById[id] ?: existingRoom[id] ?: existingJson[id] ?: WatchlistItem(id)
        }
        roomStore?.let { store ->
            runBlocking(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                store.saveSnapshots(WatchlistSnapshot(watchlistItems).toRoomEntities().map { existingEntities[it.seriesId] ?: it.copy(addedAtMs = now) })
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
        const val RoomInitializedFileName = "series_watchlist_room_initialized"
        const val FileName = "series_watchlist.json"
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

private data class WatchlistSnapshot(
    val watchlist: List<WatchlistItem> = emptyList(),
)

private data class WatchlistItem(
    val seriesId: Int,
    val title: String? = null,
    val categoryName: String? = null,
)

private fun WatchlistSnapshot.toRoomEntities(): List<SeriesWatchlistEntity> =
    watchlist.mapIndexed { index, item ->
        seriesWatchlistEntityFromSnapshot(
            seriesId = item.seriesId,
            title = item.title,
            categoryName = item.categoryName,
            addedAtMs = watchlist.size.toLong() - index,
        )
    }
