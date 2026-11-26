package com.atlas.atlastv.data.db

import androidx.room.*

@Entity(tableName = "show_details_cache", primaryKeys = ["providerKey", "seriesId"])
data class ShowDetailsCacheEntity(val providerKey: String, val seriesId: Int, val requestedAtMs: Long,
    val savedAtMs: Long, val catalogAtMs: Long?, val accessedAtMs: Long, val payloadBytes: Int, val payload: String)

data class ShowCacheSize(val providerKey: String, val seriesId: Int, val payloadBytes: Int)

@Dao
interface ShowDetailsCacheDao {
    @Query("SELECT * FROM show_details_cache WHERE providerKey = :key AND seriesId = :id")
    suspend fun entry(key: String, id: Int): ShowDetailsCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: ShowDetailsCacheEntity)
    @Query("UPDATE show_details_cache SET accessedAtMs = :time WHERE providerKey = :key AND seriesId = :id AND requestedAtMs = :request")
    suspend fun touch(key: String, id: Int, request: Long, time: Long)
    @Query("DELETE FROM show_details_cache WHERE providerKey = :key AND seriesId = :id AND requestedAtMs = :request")
    suspend fun deleteVersion(key: String, id: Int, request: Long)
    @Query("DELETE FROM show_details_cache WHERE providerKey = :key AND seriesId = :id")
    suspend fun delete(key: String, id: Int)
    @Query("SELECT providerKey, seriesId, payloadBytes FROM show_details_cache ORDER BY accessedAtMs ASC, providerKey ASC, seriesId ASC")
    suspend fun sizes(): List<ShowCacheSize>
    @Transaction suspend fun saveBounded(value: ShowDetailsCacheEntity, maxEntries: Int, maxBytes: Long) {
        val previous = entry(value.providerKey, value.seriesId)
        if (previous != null && previous.requestedAtMs > value.requestedAtMs) return
        put(value)
        val rows = sizes()
        var count = rows.size; var bytes = rows.sumOf { it.payloadBytes.toLong() }
        for (row in rows) {
            if (count <= maxEntries && bytes <= maxBytes) break
            delete(row.providerKey, row.seriesId); count--; bytes -= row.payloadBytes
        }
    }
}
