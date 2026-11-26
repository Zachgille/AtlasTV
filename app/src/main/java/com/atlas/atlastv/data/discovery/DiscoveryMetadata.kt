package com.atlas.atlastv.data.discovery

import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.catalog.releaseYearLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.ensureActive

/** One source policy for ingestion, upgrade backfill and ranking. Unknown never means popular. */
object DiscoveryMetadata {
    private val fourDigitYear = Regex("[0-9]{4}")
    private val whitespace = Regex("\\s+")
    fun timestamp(raw: String?, nowMs: Long): Long? {
        val value = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val millis = if (value < 100_000_000_000L) runCatching { Math.multiplyExact(value, 1000) }.getOrNull() else value
        return millis?.takeIf { it in 946684800000L..nowMs }
    }
    fun release(raw: String?, title: String, nowMs: Long): Pair<Int, String>? {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        val value = raw?.trim().orEmpty()
        val date = runCatching { LocalDate.parse(value) }.getOrNull()
        if (date != null && date.year >= 1900 && date <= today) return (date.year * 10000 + date.monthValue * 100 + date.dayOfMonth) to "provider-date"
        // A syntactically valid future release must not be replaced with a title-year approximation.
        if (date != null) return null
        val year = value.takeIf { it.matches(fourDigitYear) }?.toIntOrNull()
            ?: releaseYearLabel(title)?.toIntOrNull()
        return year?.takeIf { it in 1900..today.year }?.let { it * 10000 + 101 to if (value == it.toString()) "provider-year" else "title-year" }
    }
    private val genreAliases = linkedMapOf(
        "action" to "Action", "adventure" to "Adventure", "action & adventure" to "Action|Adventure",
        "animation" to "Animation", "comedy" to "Comedy", "crime" to "Crime", "documentary" to "Documentary",
        "drama" to "Drama", "family" to "Family", "kids" to "Kids", "fantasy" to "Fantasy",
        "science fiction" to "Sci-Fi", "sci-fi" to "Sci-Fi", "sci fi" to "Sci-Fi", "sci-fi & fantasy" to "Sci-Fi|Fantasy",
        "horror" to "Horror", "thriller" to "Thriller", "mystery" to "Mystery", "romance" to "Romance",
        "war" to "War", "war & politics" to "War", "western" to "Western", "music" to "Music", "history" to "History", "reality" to "Reality", "talk" to "Talk"
    )
    fun genres(raw: String?): String = raw.orEmpty().take(240).split(',', '/').flatMap {
        genreAliases[it.trim().lowercase(Locale.ROOT)]?.split('|').orEmpty()
    }.distinct().sorted().joinToString("|", prefix = "|", postfix = "|").takeIf { it != "||" }.orEmpty()

    fun create(type: String, id: Int, title: String, stamp: Long, nowMs: Long,
        rating: String? = null, fiveBased: String? = null, release: String? = null,
        added: String? = null, changed: String? = null, genre: String? = null): CatalogDiscoveryEntity {
        val date = release(release, title, nowMs)
        // Provider scores are intentionally ignored: only the independent IMDb index may rank titles.
        val score: Pair<Int,String>? = null
        val safeTitle = title.take(240).takeUnless { it.contains("://") || it.contains("/movie/",true) || it.contains("/series/",true) || it.contains("/live/",true) } ?: "Item $id"
        val clean = cleanProviderTitle(safeTitle).cleanTitle
        // Year remains part of identity, preserving remakes; edition/country words remain in the title.
        val year = releaseYearLabel(clean) ?: date?.first?.div(10000)?.toString()
        val key = displayTitle(clean).trim().replace(whitespace, " ").lowercase(Locale.ROOT) +
            (year?.let { "|$it" } ?: "|unknown-$id")
        return CatalogDiscoveryEntity(type, id, stamp, key.take(280), date?.first, date?.second,
            score?.first, score?.second, timestamp(added, nowMs), timestamp(changed, nowMs), genres(genre))
    }
    fun legacy(item: SafeCatalogItemEntity, nowMs: Long) = create(item.contentType, item.itemId, item.title,
        item.updatedAtMs, nowMs, added = item.addedAtMs?.toString().takeIf { item.contentType == SafeCatalogContentTypes.Movie },
        changed = item.addedAtMs?.toString().takeIf { item.contentType == SafeCatalogContentTypes.Series })
}

enum class DiscoveryShelf(val key: String) { New("new"), HighlyRated("rated"), RecentlyAdded("added"), RecentlyUpdated("updated"), Genre("genre") }

/** Optional migration/legacy fill. Each transaction covers at most 200 records, never the whole library. */
class DiscoveryLegacySeeder(private val dao: SafeCatalogDao, private val clockMs: () -> Long = {System.currentTimeMillis()}) {
    suspend fun run(currentOwner: () -> Boolean = {true}): Int {
        var type="";var id=-1;var changed=0
        while(true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if(!currentOwner()) return changed
            val page=dao.missingDiscoveryPage(type,id)
            if(page.isEmpty()) return changed
            val metadata=page.map {DiscoveryMetadata.legacy(it,clockMs())}
            kotlin.coroutines.coroutineContext.ensureActive()
            if(!currentOwner()) return changed
            changed+=dao.seedDiscovery(metadata)
            type=page.last().contentType;id=page.last().itemId
        }
    }
}
