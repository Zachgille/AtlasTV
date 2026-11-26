package com.atlas.atlastv.data.discovery

import androidx.sqlite.db.SimpleSQLiteQuery
import com.atlas.atlastv.data.db.*
import java.time.Instant
import java.time.ZoneOffset

data class DiscoveryCollectionSpec(val type: String, val shelf: DiscoveryShelf, val genre: String? = null) {
    val id: String get() = (if(type=="movie") "movies" else "series") + "_" + when(shelf) {
        DiscoveryShelf.New -> "new"; DiscoveryShelf.HighlyRated -> "rated"
        DiscoveryShelf.RecentlyAdded, DiscoveryShelf.RecentlyUpdated -> "recent"
        DiscoveryShelf.Genre -> "genre-${genre!!.lowercase(java.util.Locale.ROOT)}"
    }
    val title: String get() = when(shelf) {
        DiscoveryShelf.New -> "New $label"; DiscoveryShelf.HighlyRated -> "Highly Rated $label"
        DiscoveryShelf.RecentlyAdded -> "Recently Added $label"; DiscoveryShelf.RecentlyUpdated -> "Recently Updated $label"
        DiscoveryShelf.Genre -> "$genre $label"
    }
    private val label get()=if(type=="movie") "Movies" else "Shows"
    init { require(type in listOf("movie","series"));require(shelf!=DiscoveryShelf.Genre || genre in Genres) }
    companion object {
        val Genres=listOf("Drama","Comedy","Action","Crime","Documentary","Sci-Fi","Family","Animation")
        fun fromId(id: String): DiscoveryCollectionSpec? {
            val type=when {id.startsWith("movies_")->"movie";id.startsWith("series_")->"series";else->return null}
            return when(val key=id.substringAfter('_')) {
                "new"->DiscoveryCollectionSpec(type,DiscoveryShelf.New)
                "rated"->DiscoveryCollectionSpec(type,DiscoveryShelf.HighlyRated)
                "recent"->DiscoveryCollectionSpec(type,if(type=="movie") DiscoveryShelf.RecentlyAdded else DiscoveryShelf.RecentlyUpdated)
                else->Genres.firstOrNull {key=="genre-${it.lowercase(java.util.Locale.ROOT)}"}?.let {DiscoveryCollectionSpec(type,DiscoveryShelf.Genre,it)}
            }
        }
    }
}

data class DiscoveryRank(val rank1: Long,val rank2: Long,val rank3: Long,val normalizedTitle: String,val itemId: Int)

/** Same eligibility, variant and ordering rules as previews. SQL values are always bound parameters. */
class DiscoveryWindowQuery(spec: DiscoveryCollectionSpec, now: Long) {
    private val args=mutableListOf<Any>(spec.type)
    private val sql: String
    init {
        val trusted="CASE WHEN m.ratingSource='imdb' AND m.ratingVotes>=1000 THEN COALESCE(m.ratingMilli,0) ELSE 0 END"
        val date="COALESCE(m.releaseSort,0)"
        val ranks=when(spec.shelf) {
            DiscoveryShelf.New -> listOf(date,trusted,"0")
            DiscoveryShelf.HighlyRated -> listOf(trusted,"COALESCE(m.ratingVotes,0)",date)
            DiscoveryShelf.RecentlyAdded -> listOf("COALESCE(m.addedAtMs,0)","0","0")
            DiscoveryShelf.RecentlyUpdated -> listOf("COALESCE(m.changedAtMs,0)","0","0")
            DiscoveryShelf.Genre -> listOf(trusted,date,"0")
        }
        val filter=when(spec.shelf) {
            DiscoveryShelf.New -> {
                val today=Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
                args+=(today.year-1)*10000+101;args+=today.year*10000+today.monthValue*100+today.dayOfMonth
                "m.releaseSort BETWEEN ? AND ?"
            }
            DiscoveryShelf.HighlyRated -> "m.ratingSource='imdb' AND m.ratingVotes>=1000 AND m.ratingMilli BETWEEN 7000 AND 10000"
            DiscoveryShelf.RecentlyAdded -> {args+=now;"m.addedAtMs BETWEEN 946684800000 AND ?"}
            DiscoveryShelf.RecentlyUpdated -> {args+=now;"m.changedAtMs BETWEEN 946684800000 AND ?"}
            DiscoveryShelf.Genre -> {args+="%|${spec.genre}|%";"m.genres LIKE ?"}
        }
        sql="""SELECT c.*, ${ranks[0]} AS rank1, ${ranks[1]} AS rank2, ${ranks[2]} AS rank3
            FROM catalog_discovery m JOIN safe_catalog_items c
            ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
            WHERE m.contentType=? AND $filter AND NOT EXISTS (
                SELECT 1 FROM catalog_discovery other JOIN safe_catalog_items oc
                ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
                WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId)"""
    }
    fun count()=SimpleSQLiteQuery("SELECT COUNT(*) FROM ($sql)",args.toTypedArray())
    fun selected(id: Int)=SimpleSQLiteQuery("SELECT * FROM ($sql) WHERE itemId=?",(args+id).toTypedArray())
    fun before(rank: DiscoveryRank)=SimpleSQLiteQuery("""SELECT COUNT(*) FROM ($sql) WHERE
        rank1>? OR (rank1=? AND rank2>?) OR (rank1=? AND rank2=? AND rank3>?)
        OR (rank1=? AND rank2=? AND rank3=? AND normalizedTitle<?)
        OR (rank1=? AND rank2=? AND rank3=? AND normalizedTitle=? AND itemId<?)""",
        (args+listOf(rank.rank1,rank.rank1,rank.rank2,rank.rank1,rank.rank2,rank.rank3,
            rank.rank1,rank.rank2,rank.rank3,rank.normalizedTitle,rank.rank1,rank.rank2,rank.rank3,rank.normalizedTitle,rank.itemId)).toTypedArray())
    fun page(offset: Int)=SimpleSQLiteQuery("SELECT * FROM ($sql) ORDER BY rank1 DESC,rank2 DESC,rank3 DESC,normalizedTitle ASC,itemId ASC LIMIT ? OFFSET ?",
        (args+listOf(SafeCatalogWindow.MaxItems,offset)).toTypedArray())
}
