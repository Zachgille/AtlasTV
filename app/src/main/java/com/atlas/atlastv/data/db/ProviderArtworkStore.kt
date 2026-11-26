package com.atlas.atlastv.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Private addresses stay behind opaque tokens; never serialize these rows into catalog/UI state. */
@Entity(tableName = "provider_artwork")
data class ProviderArtworkEntity(@PrimaryKey val token: String, val address: String)

@Entity(tableName = "artwork_migrations")
data class ArtworkMigrationEntity(@PrimaryKey val source: String)

@Dao
interface ProviderArtworkDao {
    @Query("SELECT address FROM provider_artwork WHERE token = :token LIMIT 1")
    suspend fun address(token: String): String?

    @Query("SELECT COUNT(*) FROM provider_artwork")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entries: List<ProviderArtworkEntity>)

    @Query("SELECT COUNT(*) FROM artwork_migrations WHERE source = :source")
    suspend fun migrated(source: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markMigrated(value: ArtworkMigrationEntity)

    @Transaction
    suspend fun register(entries: List<ProviderArtworkEntity>) {
        entries.chunked(500).forEach { insert(it) }
    }

    @Transaction
    suspend fun importLegacy(entries: List<ProviderArtworkEntity>) {
        if (migrated(LegacySource) != 0) return
        register(entries)
        markMigrated(ArtworkMigrationEntity(LegacySource))
    }

    @Query("DELETE FROM provider_artwork")
    suspend fun clearAddresses()

    @Transaction
    suspend fun clear() {
        clearAddresses()
        // An intentional clear must not silently import an older compatibility file again.
        markMigrated(ArtworkMigrationEntity(LegacySource))
    }

    companion object { const val LegacySource = "provider_image_index_v1" }
}

class ProviderArtworkRoomStore(private val dao: ProviderArtworkDao) {
    suspend fun resolve(token: String) = dao.address(token)
    suspend fun register(entries: Map<String, String>) = dao.register(entries.map { ProviderArtworkEntity(it.key, it.value) })
    suspend fun needsLegacyImport() = dao.migrated(ProviderArtworkDao.LegacySource) == 0
    suspend fun importLegacy(entries: Map<String, String>) = dao.importLegacy(entries.map { ProviderArtworkEntity(it.key, it.value) })
    suspend fun clear() = dao.clear()
}
