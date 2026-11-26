package com.atlas.atlastv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SafeCatalogItemEntity::class, MovieWatchlistEntity::class, WatchProgressEntity::class, SeriesWatchlistEntity::class, ProviderArtworkEntity::class, ArtworkMigrationEntity::class, ShowDetailsCacheEntity::class, MovieDetailsCacheEntity::class, HomeHeroCacheEntity::class, CatalogDiscoveryEntity::class, DiscoveryBackfillEntity::class, CatalogLanguageCheckpoint::class],
    version = 11,
    exportSchema = true,
)
abstract class AtlasDatabase : RoomDatabase() {
    abstract fun safeCatalogDao(): SafeCatalogDao
    abstract fun catalogTitleRepairDao(): CatalogTitleRepairDao
    abstract fun movieWatchlistDao(): MovieWatchlistDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun seriesWatchlistDao(): SeriesWatchlistDao
    abstract fun myListDao(): MyListDao
    abstract fun providerArtworkDao(): ProviderArtworkDao
    abstract fun showDetailsCacheDao(): ShowDetailsCacheDao
    abstract fun movieDetailsCacheDao(): MovieDetailsCacheDao

    abstract fun homeHeroCacheDao(): HomeHeroCacheDao

    companion object {
        val Migration10To11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_language_checkpoint` (`id` INTEGER NOT NULL, `policyRevision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                CatalogLanguageVerification.install(db)
            }
        }
        val Migration9To10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_discovery` (`contentType` TEXT NOT NULL, `itemId` INTEGER NOT NULL, `catalogAtMs` INTEGER NOT NULL, `variantKey` TEXT NOT NULL, `releaseSort` INTEGER, `releaseSource` TEXT, `ratingMilli` INTEGER, `ratingSource` TEXT, `addedAtMs` INTEGER, `changedAtMs` INTEGER, `genres` TEXT NOT NULL, `imdbId` TEXT, `ratingVotes` INTEGER, `ratingDataset` TEXT, PRIMARY KEY(`contentType`, `itemId`))")
                for (column in listOf("releaseSort", "ratingMilli", "addedAtMs", "changedAtMs"))
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_discovery_contentType_${column}` ON `catalog_discovery` (`contentType`, `$column`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_discovery_contentType_variantKey_itemId` ON `catalog_discovery` (`contentType`, `variantKey`, `itemId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `discovery_backfill` (`contentType` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `catalogAtMs` INTEGER NOT NULL, `providerKey` TEXT NOT NULL, `attemptedAtMs` INTEGER NOT NULL, `complete` INTEGER NOT NULL, PRIMARY KEY(`contentType`, `categoryId`))")
                // Metadata is prepared in bounded background batches after cached browsing opens.
            }
        }
        val Migration8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `home_hero_cache` (`providerKey` TEXT NOT NULL, `metadataKey` TEXT NOT NULL, `requestedAtMs` INTEGER NOT NULL, `savedAtMs` INTEGER NOT NULL, `catalogAtMs` INTEGER, `accessedAtMs` INTEGER NOT NULL, `payloadBytes` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`providerKey`, `metadataKey`))")
            }
        }
        val Migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `movie_details_cache` (`providerKey` TEXT NOT NULL, `streamId` INTEGER NOT NULL, `requestedAtMs` INTEGER NOT NULL, `savedAtMs` INTEGER NOT NULL, `catalogAtMs` INTEGER, `accessedAtMs` INTEGER NOT NULL, `payloadBytes` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`providerKey`, `streamId`))")
            }
        }
        val Migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `show_details_cache` (`providerKey` TEXT NOT NULL, `seriesId` INTEGER NOT NULL, `requestedAtMs` INTEGER NOT NULL, `savedAtMs` INTEGER NOT NULL, `catalogAtMs` INTEGER, `accessedAtMs` INTEGER NOT NULL, `payloadBytes` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`providerKey`, `seriesId`))")
            }
        }
        val Migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `provider_artwork` (`token` TEXT NOT NULL, `address` TEXT NOT NULL, PRIMARY KEY(`token`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `artwork_migrations` (`source` TEXT NOT NULL, PRIMARY KEY(`source`))")
            }
        }

        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movie_watchlist_items` (
                        `streamId` INTEGER NOT NULL,
                        `title` TEXT,
                        `categoryName` TEXT,
                        `containerExtension` TEXT,
                        `addedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`streamId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `watch_progress_records` (
                        `contentType` TEXT NOT NULL,
                        `streamId` INTEGER NOT NULL,
                        `seriesId` INTEGER,
                        `title` TEXT NOT NULL,
                        `seriesTitle` TEXT,
                        `episodeTitle` TEXT,
                        `categoryName` TEXT,
                        `extension` TEXT,
                        `durationMs` INTEGER,
                        `positionMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        PRIMARY KEY(`contentType`, `streamId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series_watchlist_items` (
                        `seriesId` INTEGER NOT NULL,
                        `title` TEXT,
                        `categoryName` TEXT,
                        `addedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`seriesId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `safe_catalog_items_v4`")
                db.execSQL("DROP INDEX IF EXISTS `index_safe_catalog_items_contentType_providerOrder`")
                db.execSQL("DROP INDEX IF EXISTS `index_safe_catalog_items_contentType_normalizedTitle`")
                db.execSQL("DROP INDEX IF EXISTS `index_safe_catalog_items_contentType_categoryName`")
                db.execSQL("DROP INDEX IF EXISTS `index_safe_catalog_items_contentType_categoryId`")
                db.execSQL("DROP INDEX IF EXISTS `index_safe_catalog_items_contentType_addedAtMs`")
                db.execSQL("ALTER TABLE `safe_catalog_items` RENAME TO `safe_catalog_items_v4`")
                db.execSQL(
                    """
                    CREATE TABLE `safe_catalog_items` (
                        `contentType` TEXT NOT NULL,
                        `itemId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `normalizedTitle` TEXT NOT NULL,
                        `categoryId` TEXT,
                        `categoryName` TEXT NOT NULL,
                        `providerOrder` INTEGER NOT NULL,
                        `addedAtMs` INTEGER,
                        `containerExtension` TEXT,
                        `posterState` TEXT NOT NULL,
                        `posterSafeUri` TEXT,
                        `posterProviderToken` TEXT,
                        `posterRedactedSource` TEXT,
                        `importedAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`contentType`, `itemId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `safe_catalog_items` (
                        `contentType`, `itemId`, `title`, `normalizedTitle`, `categoryId`, `categoryName`,
                        `providerOrder`, `addedAtMs`, `containerExtension`, `posterState`, `posterSafeUri`,
                        `posterProviderToken`, `posterRedactedSource`, `importedAtMs`, `updatedAtMs`
                    )
                    SELECT
                        `contentType`, `itemId`, `title`, lower(`title`), NULL, `categoryName`,
                        `providerOrder`, NULL, `containerExtension`, `posterState`, `posterSafeUri`,
                        `posterProviderToken`, `posterRedactedSource`, `updatedAtMs`, `updatedAtMs`
                    FROM `safe_catalog_items_v4`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `safe_catalog_items_v4`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_catalog_items_contentType_providerOrder` ON `safe_catalog_items` (`contentType`, `providerOrder`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_catalog_items_contentType_normalizedTitle` ON `safe_catalog_items` (`contentType`, `normalizedTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_catalog_items_contentType_categoryName` ON `safe_catalog_items` (`contentType`, `categoryName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_catalog_items_contentType_categoryId` ON `safe_catalog_items` (`contentType`, `categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_catalog_items_contentType_addedAtMs` ON `safe_catalog_items` (`contentType`, `addedAtMs`)")
            }
        }
    }
}
