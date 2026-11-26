package com.atlas.atlastv.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "catalog_language_checkpoint")
data class CatalogLanguageCheckpoint(@PrimaryKey val id: Int = 1, val policyRevision: Int)

data class CatalogLanguageTrigger(val name: String, val sql: String?)

/** Bump Revision whenever title OR category eligibility changes. Missing state always rechecks. */
object CatalogLanguageVerification {
    const val Revision = 2
    val triggers = listOf("INSERT", "UPDATE", "DELETE").associate { operation ->
        val name = "catalog_language_dirty_${operation.lowercase(java.util.Locale.ROOT)}"
        name to "CREATE TRIGGER $name AFTER $operation ON safe_catalog_items BEGIN DELETE FROM catalog_language_checkpoint; END"
    }

    fun hasValidTriggers(actual: List<CatalogLanguageTrigger>): Boolean =
        actual.associate { it.name to it.sql } == triggers

    /** Runs on upgrades and on every normal open, repairing missing/altered invalidation triggers. */
    fun install(db: SupportSQLiteDatabase) {
        val actual = db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name IN ('catalog_language_dirty_insert','catalog_language_dirty_update','catalog_language_dirty_delete')").use { cursor ->
            buildList { while (cursor.moveToNext()) add(CatalogLanguageTrigger(cursor.getString(0), cursor.getString(1))) }
        }
        if (hasValidTriggers(actual)) return
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM catalog_language_checkpoint")
            triggers.forEach { (name, sql) ->
                db.execSQL("DROP TRIGGER IF EXISTS $name")
                db.execSQL(sql)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) { install(db) }
    }
}
