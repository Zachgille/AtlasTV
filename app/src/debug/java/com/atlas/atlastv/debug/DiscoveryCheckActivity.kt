package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.room.Room
import androidx.tv.material3.Text
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.data.discovery.*
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.time.Instant

/** Ranked full-library source and real Home UI; isolated database and fake details only. */
class DiscoveryCheckActivity : ComponentActivity() {
    private val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var db: AtlasDatabase
    private val now=Instant.parse("2026-10-04T18:00:00Z").toEpochMilli()
    private val initialized=CompletableDeferred<Unit>()
    lateinit var holder: HomeShellStateHolder
    var destination by mutableStateOf<String?>(null)
    var selected: HomeCard?=null
    lateinit var browser: DiscoveryCollectionBrowser
    var collectionOpen by mutableStateOf(false)
    var detailFromCollection=false
    var genreRelease = CompletableDeferred<Unit>()
    var genreStarts = 0
    var genreCancellations = 0
    private fun row(type: String, id: Int) = SafeCatalogItemEntity(type,id,
        if(type=="movie") "${if(id>=600) "New Movie" else "Older Movie"} $id (${if(id>=600) 2026 else 2010})" else "${if(id>=300) "New Show" else "Older Show"} $id",
        categoryId="category",categoryName=if(type=="movie") "Movies" else "Shows",providerOrder=id,containerExtension="mp4",posterState="Missing",posterSafeUri=null,posterProviderToken=null,posterRedactedSource=null,updatedAtMs=1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db=Room.inMemoryDatabaseBuilder(this,AtlasDatabase::class.java).build()
        browser=DiscoveryCollectionBrowser(db.safeCatalogDao()) {now}
        owner.launch {
            try {
                val rows=(1..620).map { row("movie",it) }+(1..320).map { row("series",it) }
                db.safeCatalogDao().insertAll(rows)
                db.safeCatalogDao().insertDiscovery(rows.map { item -> DiscoveryMetadata.create(item.contentType,item.itemId,item.title,1,now,
                    rating=if(item.itemId>=600 || item.contentType=="series" && item.itemId>=300) "9" else "6",
                    release=if(item.contentType=="series") if(item.itemId>=300) "2026-10-01" else "2000-01-01" else null,
                    added="1791124020",changed="1791124020",genre="Drama, Crime, Comedy, Action, Documentary, Sci-Fi, Family, Animation").copy(ratingMilli=if(item.itemId>=600 || item.contentType=="series" && item.itemId>=300) 9000 else 6000,ratingSource="imdb",ratingVotes=20000,imdbId="tt0000001") })
                initialized.complete(Unit)
            } catch(e: Throwable) { initialized.completeExceptionally(e) }
        }
        suspend fun stage(phase: DiscoveryCollectionPhase, complete: Boolean): HomeLoadResult {
            initialized.await()
            val repository=SafeCatalogRepository(db.safeCatalogDao()) { now }
            val rows=(repository.discoveryCollections("movie",12,phase)+repository.discoveryCollections("series",12,phase)).forHome()
            return HomeLoadResult(HomeShellMapper().map("fixture-room",0,emptyList(),emptyList(),emptySet(),continueWatching=emptyList(),discovery=rows),isComplete=complete)
        }
        val progressive = intent.getBooleanExtra("progressive", false)
        holder=HomeShellStateHolder({ stage(DiscoveryCollectionPhase.All, true) }, catalogUpdates = if (!progressive) null else ({ flow {
            emit(stage(DiscoveryCollectionPhase.Leading, false))
            genreStarts++
            try { genreRelease.await() } catch (cancelled: CancellationException) { genreCancellations++; throw cancelled }
            emit(stage(DiscoveryCollectionPhase.All, true))
        } }))
        setContent { LumaireTheme {
            if(destination!=null) { BackHandler { destination=null }; Text(destination!!,Modifier.testTag("discovery_destination")) }
            else if(collectionOpen) DiscoveryCollectionScreen(browser,0,onBack={collectionOpen=false},onItem={selected=it;detailFromCollection=true;destination="Details ${it.contentType} ${it.streamId}"})
            else AtlasHomeScreen(holder,onLiveTvSelected={},onMoviesSelected={},onSeriesSelected={},
                onCollectionSelected={browser.open(it);collectionOpen=true},
                onHomeCardSelected={selected=it;destination="Play ${it.streamId}"},
                onHomeCardDetailsSelected={selected=it;destination="Details ${it.contentType} ${it.streamId}"})
        } }
    }
    override fun onDestroy() { owner.cancel(); if(::db.isInitialized) db.close();super.onDestroy() }
}
