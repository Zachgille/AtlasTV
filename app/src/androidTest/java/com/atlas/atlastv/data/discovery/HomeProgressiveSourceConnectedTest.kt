package com.atlas.atlastv.data.discovery

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.livetv.LiveTvCachedCatalogStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Isolated actual Room/source checks; no personal database or provider requests. */
class HomeProgressiveSourceConnectedTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AtlasDatabase
    private lateinit var dir: File
    private lateinit var repo: SafeCatalogRepository
    private lateinit var source: HomeCatalogSource
    private val now=Instant.parse("2026-10-04T18:00:00Z").toEpochMilli()
    private fun item(type: String,id: Int)=SafeCatalogItemEntity(type,id,"Fixture $type $id (2026)",
        categoryId="one",categoryName=if(type=="movie") "Movies" else "Shows",providerOrder=id,
        containerExtension="mp4",posterState="Missing",posterSafeUri=null,posterProviderToken=null,posterRedactedSource=null,updatedAtMs=1)
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        dir=File(context.cacheDir,"progressive-source-${System.nanoTime()}").also {check(it.mkdirs())}
        repo=SafeCatalogRepository(db.safeCatalogDao()) {now}
        source=HomeCatalogSource(context,cacheStore=LiveTvCachedCatalogStore(dir),
            watchlistStore=MoviesWatchlistStore(File(dir,MoviesWatchlistStore.FileName),MoviesWatchlistRoomStore(db.movieWatchlistDao())),
            seriesWatchlistStore=SeriesWatchlistStore(File(dir,SeriesWatchlistStore.FileName),SeriesWatchlistRoomStore(db.seriesWatchlistDao())),
            progressStore=WatchProgressStore(File(dir,WatchProgressStore.FileName),WatchProgressRoomStore(db.watchProgressDao())),catalogRepository=repo)
    }
    @After fun cleanup() {db.close();check(dir.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator));dir.deleteRecursively()}
    private suspend fun seed() {
        val rows=(1..30).map {item("movie",it)}+(1..30).map {item("series",it)}
        db.safeCatalogDao().insertAll(rows)
        db.safeCatalogDao().insertDiscovery(rows.map {row -> DiscoveryMetadata.create(row.contentType,row.itemId,row.title,1,now,
            release="2026-10-01",added="1791124020",changed="1791124020",genre="Drama, Comedy, Action, Crime, Documentary, Sci-Fi, Family, Animation")
            .copy(ratingMilli=8000,ratingSource="imdb",ratingVotes=10000,imdbId="tt0000001")})
    }
    @Test fun leadingAndCompleteStagesPreserveEveryCollectionIdentityOrderingAndBound()=runBlocking(Dispatchers.IO) {
        seed()
        val stages=mutableListOf<HomeLoadResult>();source.updates().collect {stages+=it}
        assertEquals(2,stages.size);assertFalse(stages.first().isComplete);assertTrue(stages.last().isComplete)
        val first=stages.first().state as HomeShellState.Ready;val last=stages.last().state as HomeShellState.Ready
        assertEquals(6,first.rows.count {it.collection!=null})
        assertEquals(22,last.rows.count {it.collection!=null})
        assertEquals(last.rows.filterNot {it.id.contains("_genre-")},first.rows)
        assertEquals(first.initialFocusCardId,last.initialFocusCardId)
        val expected=(repo.discoveryCollections("movie",12)+repo.discoveryCollections("series",12)).forHome()
        assertEquals(expected.map {it.id},last.rows.filter {it.collection!=null}.map {it.id})
        for(row in expected) assertEquals(row.items.map {it.itemId},last.rows.first {it.id==row.id}.cards.map {it.streamId})
        assertTrue(last.rows.all {it.cards.size<=12})
        assertEquals(last.rows,(source.load().state as HomeShellState.Ready).rows)
    }
    @Test fun catalogChangingBetweenStagesRejectsTheStaleCompleteSnapshot()=runBlocking(Dispatchers.IO) {
        seed();val stages=mutableListOf<HomeLoadResult>()
        try {
            source.updates().collect { stage ->
                stages+=stage
                if(!stage.isComplete) db.safeCatalogDao().insertAll(listOf(item("movie",1).copy(updatedAtMs=2)))
            }
            fail("Changed catalog must not publish stale combined rows")
        } catch(expected: IllegalStateException) { assertEquals("Library updated while opening Home",expected.message) }
        assertEquals(1,stages.size);assertFalse(stages.single().isComplete)
    }
    @Test fun emptyCatalogEmitsOneCompleteSetupState()=runBlocking(Dispatchers.IO) {
        val stages=mutableListOf<HomeLoadResult>();source.updates().collect {stages+=it}
        assertEquals(1,stages.size);assertTrue(stages.single().isComplete)
        assertTrue((stages.single().state as HomeShellState.Error).needsSetup)
    }
}
