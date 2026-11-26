package com.atlas.atlastv.data.discovery

import androidx.room.Embedded
import androidx.sqlite.db.SimpleSQLiteQuery
import com.atlas.atlastv.data.db.SafeCatalogItemEntity

data class DiscoveryGenreItem(@Embedded val item: SafeCatalogItemEntity, val shelfGenre: String)

/** Share eligibility/variant work across all eight shelves; only bounded winners leave SQLite. */
fun discoveryGenreQuery(type: String, limit: Int): SimpleSQLiteQuery {
    val args = mutableListOf<Any>(type)
    val selections = DiscoveryCollectionSpec.Genres.joinToString(" UNION ALL ") { genre ->
        args += genre
        args += "%|$genre|%"
        args += limit.coerceIn(1, 500)
        """SELECT * FROM (SELECT itemId, trustedRating, releaseDate, normalizedTitle, ? AS shelfGenre FROM candidates
            WHERE genres LIKE ?
            ORDER BY trustedRating DESC, releaseDate DESC, normalizedTitle ASC, itemId ASC LIMIT ?)"""
    }
    args += type
    // A multiply referenced CTE shares the compact candidate set on supported Android SQLite
    // versions, without window functions or a persistent cache to invalidate after refresh.
    return SimpleSQLiteQuery("""WITH candidates AS (
        SELECT m.itemId, m.genres, c.normalizedTitle,
            CASE WHEN m.ratingSource='imdb' AND m.ratingVotes>=1000 THEN COALESCE(m.ratingMilli,0) ELSE 0 END AS trustedRating,
            COALESCE(m.releaseSort,0) AS releaseDate
        FROM catalog_discovery m JOIN safe_catalog_items c
            ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE m.contentType=? AND m.genres != '' AND NOT EXISTS (
            SELECT 1 FROM catalog_discovery other JOIN safe_catalog_items oc
                ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
            WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId)
        ) SELECT c.*, selected.shelfGenre FROM ($selections) selected JOIN safe_catalog_items c
            ON c.itemId=selected.itemId WHERE c.contentType=?
            ORDER BY selected.shelfGenre, selected.trustedRating DESC, selected.releaseDate DESC,
                selected.normalizedTitle ASC, selected.itemId ASC""", args.toTypedArray())
}
