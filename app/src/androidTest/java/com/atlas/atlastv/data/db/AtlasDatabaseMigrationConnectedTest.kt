package com.atlas.atlastv.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasDatabaseMigrationConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migration1To5AddsWatchlistProgressAndCatalogImportColumnsAndKeepsCatalogRows() {
        context.deleteDatabase(DatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DatabaseName), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `safe_catalog_items` (
                    `contentType` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `categoryName` TEXT NOT NULL,
                    `providerOrder` INTEGER NOT NULL,
                    `containerExtension` TEXT,
                    `posterState` TEXT NOT NULL,
                    `posterSafeUri` TEXT,
                    `posterProviderToken` TEXT,
                    `posterRedactedSource` TEXT,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`contentType`, `itemId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO safe_catalog_items (
                    contentType, itemId, title, categoryName, providerOrder, containerExtension,
                    posterState, posterSafeUri, posterProviderToken, posterRedactedSource, updatedAtMs
                ) VALUES (
                    'movie', 2001, 'Fixture Movie', 'Fixture Action', 0, 'mp4',
                    'Missing', NULL, NULL, NULL, 100
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$Version1IdentityHash')")
            db.version = 1
        }

        val database = Room.databaseBuilder(context, AtlasDatabase::class.java, DatabaseName)
            .addMigrations(
                AtlasDatabase.Migration1To2,
                AtlasDatabase.Migration2To3,
                AtlasDatabase.Migration3To4,
                AtlasDatabase.Migration4To5,
                AtlasDatabase.Migration5To6,
                AtlasDatabase.Migration6To7,
                AtlasDatabase.Migration7To8,
                AtlasDatabase.Migration8To9,
                AtlasDatabase.Migration9To10,
                AtlasDatabase.Migration10To11,
            )
            .build()
        val migrated = database.openHelper.writableDatabase

        assertEquals(1, migrated.scalarInt("SELECT COUNT(*) FROM safe_catalog_items"))
        assertEquals(1, migrated.scalarInt("SELECT COUNT(*) FROM safe_catalog_items WHERE normalizedTitle = 'fixture movie'"))
        assertEquals(100, migrated.scalarInt("SELECT importedAtMs FROM safe_catalog_items WHERE itemId = 2001"))
        assertEquals(0, migrated.scalarInt("SELECT COUNT(*) FROM movie_watchlist_items"))
        assertEquals(0, migrated.scalarInt("SELECT COUNT(*) FROM watch_progress_records"))
        assertEquals(0, migrated.scalarInt("SELECT COUNT(*) FROM series_watchlist_items"))
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun migration2To5AddsWatchProgressSeriesWatchlistAndCatalogImportColumns() {
        context.deleteDatabase(DatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DatabaseName), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `safe_catalog_items` (
                    `contentType` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `categoryName` TEXT NOT NULL,
                    `providerOrder` INTEGER NOT NULL,
                    `containerExtension` TEXT,
                    `posterState` TEXT NOT NULL,
                    `posterSafeUri` TEXT,
                    `posterProviderToken` TEXT,
                    `posterRedactedSource` TEXT,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`contentType`, `itemId`)
                )
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                INSERT INTO movie_watchlist_items (
                    streamId, title, categoryName, containerExtension, addedAtMs
                ) VALUES (
                    2001, 'Fixture Movie', 'Fixture Action', 'mp4', 100
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$Version2IdentityHash')")
            db.version = 2
        }

        val database = Room.databaseBuilder(context, AtlasDatabase::class.java, DatabaseName)
            .addMigrations(AtlasDatabase.Migration2To3, AtlasDatabase.Migration3To4, AtlasDatabase.Migration4To5, AtlasDatabase.Migration5To6, AtlasDatabase.Migration6To7, AtlasDatabase.Migration7To8, AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11)
            .build()
        val migrated = database.openHelper.writableDatabase

        assertEquals(1, migrated.scalarInt("SELECT COUNT(*) FROM movie_watchlist_items"))
        assertEquals(0, migrated.scalarInt("SELECT COUNT(*) FROM watch_progress_records"))
        assertEquals(0, migrated.scalarInt("SELECT COUNT(*) FROM series_watchlist_items"))
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun migration3To5AddsSeriesWatchlistAndCatalogImportColumns() {
        context.deleteDatabase(DatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DatabaseName), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `safe_catalog_items` (
                    `contentType` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `categoryName` TEXT NOT NULL,
                    `providerOrder` INTEGER NOT NULL,
                    `containerExtension` TEXT,
                    `posterState` TEXT NOT NULL,
                    `posterSafeUri` TEXT,
                    `posterProviderToken` TEXT,
                    `posterRedactedSource` TEXT,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`contentType`, `itemId`)
                )
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                INSERT INTO watch_progress_records (
                    contentType, streamId, seriesId, title, seriesTitle, episodeTitle, categoryName,
                    extension, durationMs, positionMs, updatedAtMs, completed
                ) VALUES (
                    'series_episode', 9001, 3001, 'Fixture Series - Pilot', 'Fixture Series',
                    'Pilot', 'Fixture Drama', 'mp4', 3600000, 600000, 100, 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$Version3IdentityHash')")
            db.version = 3
        }

        val database = Room.databaseBuilder(context, AtlasDatabase::class.java, DatabaseName)
            .addMigrations(AtlasDatabase.Migration3To4, AtlasDatabase.Migration4To5, AtlasDatabase.Migration5To6, AtlasDatabase.Migration6To7, AtlasDatabase.Migration7To8, AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11)
            .build()
        val migrated = database.openHelper.writableDatabase

        assertEquals(1, migrated.scalarInt("SELECT COUNT(*) FROM watch_progress_records"))
        assertEquals(0, migrated.scalarInt("SELECT COUNT(*) FROM series_watchlist_items"))
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun migration4To5AddsCatalogImportColumnsAndIndexes() {
        context.deleteDatabase(DatabaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DatabaseName), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `safe_catalog_items` (
                    `contentType` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `categoryName` TEXT NOT NULL,
                    `providerOrder` INTEGER NOT NULL,
                    `containerExtension` TEXT,
                    `posterState` TEXT NOT NULL,
                    `posterSafeUri` TEXT,
                    `posterProviderToken` TEXT,
                    `posterRedactedSource` TEXT,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`contentType`, `itemId`)
                )
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                INSERT INTO safe_catalog_items (
                    contentType, itemId, title, categoryName, providerOrder, containerExtension,
                    posterState, posterSafeUri, posterProviderToken, posterRedactedSource, updatedAtMs
                ) VALUES (
                    'series', 3001, 'Fixture Series', 'Fixture Shows', 0, NULL,
                    'Missing', NULL, NULL, NULL, 200
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$Version4IdentityHash')")
            db.version = 4
        }

        val database = Room.databaseBuilder(context, AtlasDatabase::class.java, DatabaseName)
            .addMigrations(AtlasDatabase.Migration4To5, AtlasDatabase.Migration5To6, AtlasDatabase.Migration6To7, AtlasDatabase.Migration7To8, AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11)
            .build()
        val migrated = database.openHelper.writableDatabase

        assertEquals(1, migrated.scalarInt("SELECT COUNT(*) FROM safe_catalog_items WHERE normalizedTitle = 'fixture series'"))
        assertEquals(200, migrated.scalarInt("SELECT importedAtMs FROM safe_catalog_items WHERE itemId = 3001"))
        assertEquals(1, migrated.scalarInt("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_safe_catalog_items_contentType_normalizedTitle'"))
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    private fun SupportSQLiteDatabase.scalarInt(sql: String): Int {
        query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val DatabaseName = "migration-test"
        const val Version1IdentityHash = "3ccbf29d36c5c6e0e5382188541fd923"
        const val Version2IdentityHash = "30447b2cf17e3699314a5d12bf000462"
        const val Version3IdentityHash = "edc3d7c545f182b1c2e54f486b1cdced"
        const val Version4IdentityHash = "8c694ea03672787c8f3547d32e40f80b"
    }
}
