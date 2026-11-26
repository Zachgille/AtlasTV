package com.atlas.atlastv.data.db

import androidx.room.*

@Entity(tableName = "home_hero_cache", primaryKeys = ["providerKey", "metadataKey"])
data class HomeHeroCacheEntity(val providerKey: String, val metadataKey: String, val requestedAtMs: Long,
    val savedAtMs: Long, val catalogAtMs: Long?, val accessedAtMs: Long, val payloadBytes: Int, val payload: String)
data class HomeHeroCacheSize(val providerKey: String, val metadataKey: String, val payloadBytes: Int)

@Dao
interface HomeHeroCacheDao {
    @Query("SELECT * FROM home_hero_cache WHERE providerKey = :key AND metadataKey = :id")
    suspend fun entry(key: String, id: String): HomeHeroCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: HomeHeroCacheEntity)
    @Query("UPDATE home_hero_cache SET accessedAtMs = :time WHERE providerKey = :key AND metadataKey = :id AND requestedAtMs = :request")
    suspend fun touch(key: String, id: String, request: Long, time: Long)
    @Query("DELETE FROM home_hero_cache WHERE providerKey = :key AND metadataKey = :id AND requestedAtMs = :request")
    suspend fun deleteVersion(key: String, id: String, request: Long)
    @Query("DELETE FROM home_hero_cache WHERE providerKey = :key AND metadataKey = :id")
    suspend fun delete(key: String, id: String)
    @Query("SELECT providerKey, metadataKey, payloadBytes FROM home_hero_cache ORDER BY accessedAtMs ASC, providerKey ASC, metadataKey ASC")
    suspend fun sizes(): List<HomeHeroCacheSize>
    @Transaction suspend fun saveBounded(value: HomeHeroCacheEntity, maxEntries: Int, maxBytes: Long) {
        val previous = entry(value.providerKey, value.metadataKey)
        if (previous != null && previous.requestedAtMs > value.requestedAtMs) return
        put(value)
        val rows = sizes()
        var count = rows.size; var bytes = rows.sumOf { it.payloadBytes.toLong() }
        for (row in rows) {
            if (count <= maxEntries && bytes <= maxBytes) break
            delete(row.providerKey, row.metadataKey); count--; bytes -= row.payloadBytes
        }
    }
}
