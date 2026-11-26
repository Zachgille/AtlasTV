package com.atlas.atlastv.data.db

import androidx.room.*

@Entity(tableName = "movie_details_cache", primaryKeys = ["providerKey", "streamId"])
data class MovieDetailsCacheEntity(val providerKey: String, val streamId: Int, val requestedAtMs: Long,
    val savedAtMs: Long, val catalogAtMs: Long?, val accessedAtMs: Long, val payloadBytes: Int, val payload: String)

data class MovieCacheSize(val providerKey: String, val streamId: Int, val payloadBytes: Int)

@Dao
interface MovieDetailsCacheDao {
    @Query("SELECT * FROM movie_details_cache WHERE providerKey = :key AND streamId = :id")
    suspend fun entry(key: String, id: Int): MovieDetailsCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: MovieDetailsCacheEntity)
    @Query("UPDATE movie_details_cache SET accessedAtMs = :time WHERE providerKey = :key AND streamId = :id AND requestedAtMs = :request")
    suspend fun touch(key: String, id: Int, request: Long, time: Long)
    @Query("DELETE FROM movie_details_cache WHERE providerKey = :key AND streamId = :id AND requestedAtMs = :request")
    suspend fun deleteVersion(key: String, id: Int, request: Long)
    @Query("DELETE FROM movie_details_cache WHERE providerKey = :key AND streamId = :id")
    suspend fun delete(key: String, id: Int)
    @Query("SELECT providerKey, streamId, payloadBytes FROM movie_details_cache ORDER BY accessedAtMs ASC, providerKey ASC, streamId ASC")
    suspend fun sizes(): List<MovieCacheSize>
    @Transaction suspend fun saveBounded(value: MovieDetailsCacheEntity, maxEntries: Int, maxBytes: Long) {
        val previous = entry(value.providerKey, value.streamId)
        if (previous != null && previous.requestedAtMs > value.requestedAtMs) return
        put(value)
        val rows = sizes()
        var count = rows.size; var bytes = rows.sumOf { it.payloadBytes.toLong() }
        for (row in rows) {
            if (count <= maxEntries && bytes <= maxBytes) break
            delete(row.providerKey, row.streamId); count--; bytes -= row.payloadBytes
        }
    }
}
