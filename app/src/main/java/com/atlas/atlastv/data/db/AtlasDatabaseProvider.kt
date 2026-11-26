package com.atlas.atlastv.data.db

import android.content.Context
import androidx.room.Room

object AtlasDatabaseProvider {
    @Volatile
    private var database: AtlasDatabase? = null

    fun database(context: Context): AtlasDatabase =
        database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                AtlasDatabase::class.java,
                DatabaseName,
            ).addMigrations(
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
                .addCallback(CatalogLanguageVerification.callback)
                .build()
                .also { database = it }
        }

    private const val DatabaseName = "atlas_tv.db"
}
