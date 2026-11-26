package com.atlas.atlastv.data.discovery

import android.content.Context
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.ui.catalog.displayTitle
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

data class ImdbDiscoveryTitle(val imdbId: String, val type: String, val primaryTitle: String, val originalTitle: String,
    val year: Int, val ratingMilli: Int, val votes: Int, val genres: String)

/** Exact typed title+year matching only. Ambiguous aliases/remakes never receive guessed scores. */
class ImdbDiscoveryIndex(private val titles: Map<String,List<ImdbDiscoveryTitle>>) {
    fun match(type: String, title: String, year: Int?): ImdbDiscoveryTitle? {
        if(year==null) return null
        return titles[key(type,displayTitle(cleanProviderTitle(title).cleanTitle),year)]?.distinctBy {it.imdbId}?.singleOrNull()
    }
    companion object {
        const val Dataset = com.atlas.atlastv.BuildConfig.IMDB_DISCOVERY_DATASET
        private fun normalized(title: String) = Normalizer.normalize(title,Normalizer.Form.NFC).trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
        private fun key(type: String,title: String,year: Int)="$type|$year|${normalized(title)}"
        fun read(reader: BufferedReader): ImdbDiscoveryIndex {
            require(reader.readLine()=="imdbId\tcontentType\tprimaryTitle\toriginalTitle\tyear\tratingMilli\tvotes\tgenres")
            val titles=mutableMapOf<String,MutableList<ImdbDiscoveryTitle>>()
            var count=0
            reader.lineSequence().forEach {line ->
                require(++count<=100000 && line.length<=4096)
                val fields=line.split('\t')
                require(fields.size==8)
                val year=fields[4].toIntOrNull();val rating=fields[5].toIntOrNull();val votes=fields[6].toIntOrNull()
                if(!fields[0].matches(Regex("tt[0-9]{7,12}")) || fields[1] !in listOf("movie","series") || year !in 1900..2099 || rating !in 1..10000 || votes==null || votes<1000) return@forEach
                val entry=ImdbDiscoveryTitle(fields[0],fields[1],fields[2],fields[3],year!!,rating!!,votes,DiscoveryMetadata.genres(fields[7]))
                for(title in listOf(entry.primaryTitle,entry.originalTitle).distinct()) titles.getOrPut(key(entry.type,title,entry.year)) { mutableListOf() }.add(entry)
            }
            require(titles.isNotEmpty())
            return ImdbDiscoveryIndex(titles)
        }
        fun from(context: Context): ImdbDiscoveryIndex = context.assets.open("imdb-discovery.tsv.bin").use {
            readVerified(it, com.atlas.atlastv.BuildConfig.IMDB_DISCOVERY_SHA256)
        }
        internal fun readVerified(stream: java.io.InputStream, expectedSha256: String): ImdbDiscoveryIndex {
            require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val indexed = GZIPInputStream(java.security.DigestInputStream(stream,digest)).bufferedReader(Charsets.UTF_8).use(::read)
            require(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256)
            return indexed
        }
    }
}

class ImdbDiscoveryIndexer(private val dao: SafeCatalogDao,private val loadIndex: () -> ImdbDiscoveryIndex,
    private val dataset: String = ImdbDiscoveryIndex.Dataset) {
    suspend fun update(currentOwner: () -> Boolean = {true}): Int {
        if(dao.imdbMatchPage(dataset,"",-1).isEmpty()) return 0
        val index=loadIndex()
        var type="";var id=-1;var changed=0
        while(true) {
            coroutineContext.ensureActive();if(!currentOwner()) return changed
            val page=dao.imdbMatchPage(dataset,type,id)
            if(page.isEmpty()) break
            val existing = page.groupBy {it.contentType}.flatMap { (kind,items) -> dao.discoveryMetadataForIds(kind,items.map {it.itemId}) }.associateBy {it.contentType to it.itemId}
            val metadata=page.mapNotNull { item -> existing[item.contentType to item.itemId]?.takeIf {it.catalogAtMs==item.updatedAtMs}?.let { old ->
                val match=index.match(item.contentType,item.title,old.releaseSort?.div(10000))
                old.copy(ratingMilli=match?.ratingMilli,ratingSource=match?.let {"imdb"},imdbId=match?.imdbId,ratingVotes=match?.votes,
                    ratingDataset=dataset,genres=match?.genres?.takeIf {it.isNotEmpty()} ?: old.genres)
            } }
            coroutineContext.ensureActive();if(!currentOwner()) return changed
            changed+=dao.commitImdb(metadata)
            type=page.last().contentType;id=page.last().itemId
        }
        return changed
    }
}
