package com.atlas.atlastv.data.db

import androidx.room.Entity
import androidx.room.Index

/** Safe catalog metadata only; no provider addresses, account fields or playback state. */
@Entity(tableName = "catalog_discovery", primaryKeys = ["contentType", "itemId"], indices = [
    Index(value = ["contentType", "releaseSort"]), Index(value = ["contentType", "ratingMilli"]),
    Index(value = ["contentType", "addedAtMs"]), Index(value = ["contentType", "changedAtMs"]),
    Index(value = ["contentType", "variantKey", "itemId"]),
])
data class CatalogDiscoveryEntity(
    val contentType: String,
    val itemId: Int,
    val catalogAtMs: Long,
    val variantKey: String,
    val releaseSort: Int? = null,
    val releaseSource: String? = null,
    val ratingMilli: Int? = null,
    val ratingSource: String? = null,
    val addedAtMs: Long? = null,
    val changedAtMs: Long? = null,
    val genres: String = "",
    val imdbId: String? = null,
    val ratingVotes: Int? = null,
    val ratingDataset: String? = null,
)

@Entity(tableName = "discovery_backfill", primaryKeys = ["contentType", "categoryId"])
data class DiscoveryBackfillEntity(val contentType: String, val categoryId: String,
    val catalogAtMs: Long, val providerKey: String, val attemptedAtMs: Long, val complete: Boolean)
