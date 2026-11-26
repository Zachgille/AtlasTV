package com.atlas.atlastv.data.discovery

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.xtream.model.*
import com.atlas.atlastv.data.catalog.XtreamCatalogImporter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Every write uses a new in-memory Room database. No personal fixture or video. */
class DiscoveryCatalogConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-10-04T18:00:00Z").toEpochMilli()
    private inline fun <T> AtlasDatabase.useDb(block: (AtlasDatabase) -> T): T = try { block(this) } finally { close() }
    private fun db() = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
    private fun row(type: String, id: Int, title: String = "Title $id (2026)", category: String="Drama", stamp: Long=7) =
        SafeCatalogItemEntity(type,id,title,categoryId="category",categoryName=category,providerOrder=id,containerExtension="mp4",posterState="Missing",posterSafeUri=null,posterProviderToken=null,posterRedactedSource=null,updatedAtMs=stamp)
    private fun meta(row: SafeCatalogItemEntity, rating: String? = "8", release: String? = null, added: String? = "1791124020", genre: String? = null) =
        DiscoveryMetadata.create(row.contentType,row.itemId,row.title,row.updatedAtMs,now,release=release,added=added,genre=genre).copy(ratingMilli=rating?.toDoubleOrNull()?.takeIf {it in 1.0..10.0}?.times(1000)?.toInt(),ratingSource="imdb",ratingVotes=20000,imdbId="tt0000001")

    @Test fun completeCollectionWindowsMatchEveryPreviewAndReachFinalBeyond500WithRefreshAnchors() = runBlocking {
        db().useDb {db ->
            val dao=db.safeCatalogDao();val repo=SafeCatalogRepository(dao) {now}
            val rows=(1..720).map {row("movie",it,"Movie $it (2026)")}
            dao.insertAll(rows)
            dao.insertDiscovery(rows.map {meta(it,if(it.itemId%2==0) "8" else "9",added=(1791124020L+it.itemId).toString(),genre="Drama,Comedy")})
            val series=rows.map {it.copy(contentType="series",title="Show ${it.itemId}")};dao.insertAll(series)
            dao.insertDiscovery(series.map {meta(it,if(it.itemId%2==0) "8" else "9",release="2026-10-01",genre="Drama,Comedy").copy(changedAtMs=now-it.itemId)})
            for(type in listOf("movie","series")) for(collection in repo.discoveryCollections(type,12)) {
                val spec=DiscoveryCollectionSpec.fromId(collection.id)!!
                val first=dao.discoveryWindow(spec,now,0,null)
                assertEquals(collection.id,collection.items.map {it.itemId},first.items.take(12).map {it.itemId})
                assertEquals(720,first.total);assertTrue(first.items.size<=200)
                val last=dao.discoveryWindow(spec,now,719,null)
                assertEquals(719,last.anchor);assertEquals(720,last.offset+last.items.size)
                val selected=last.items.last();val anchored=dao.discoveryWindow(spec,now,0,selected.itemId)
                assertEquals(719,anchored.anchor);assertEquals(selected,anchored.items.last())
            }
            val spec=DiscoveryCollectionSpec.fromId("movies_rated")!!
            val last=dao.discoveryWindow(spec,now,719,null).items.last()
            dao.insertDiscovery(listOf(meta(last,"10",genre="Drama")))
            assertEquals(0,dao.discoveryWindow(spec,now,719,last.itemId).anchor)
            dao.removeCatalogIds("movie",listOf(last.itemId))
            val removed=dao.discoveryWindow(spec,now,719,last.itemId)
            assertEquals(719-1,removed.anchor);assertEquals(719,removed.total)
            assertEquals(720,dao.countForType("series"))
        }
    }

    @Test fun providerScoresCannotInfluenceNewGenreOrRatedOrdering() = runBlocking {
        db().useDb {db ->
            val dao=db.safeCatalogDao();val repo=SafeCatalogRepository(dao) {now}
            val provider=row("movie",1,"A Provider favorite (2026)")
            val independent=row("movie",2,"Z Independent favorite (2026)")
            dao.insertAll(listOf(provider,independent))
            dao.insertDiscovery(listOf(
                DiscoveryMetadata.legacy(provider,now).copy(genres="|Drama|",ratingMilli=10000,ratingSource="provider-10",ratingVotes=1000000),
                DiscoveryMetadata.legacy(independent,now).copy(genres="|Drama|",ratingMilli=7500,ratingSource="imdb",ratingVotes=20000,imdbId="tt0000001")))
            assertEquals(listOf(2,1),repo.discovery("movie",DiscoveryShelf.New,12).map {it.itemId})
            assertEquals(listOf(2,1),repo.discovery("movie",DiscoveryShelf.Genre,12,"Drama").map {it.itemId})
            assertEquals(listOf(2),repo.discovery("movie",DiscoveryShelf.HighlyRated,12).map {it.itemId})
        }
    }

    @Test fun packagedIndependentSnapshotLoadsAndMatchesAnExactKnownTitle() {
        val index=ImdbDiscoveryIndex.from(context)
        val movie=index.match("movie","The Godfather (1972)",1972)
        assertNotNull(movie);assertEquals("tt0068646",movie!!.imdbId)
        assertTrue(movie.votes>=1000 && movie.ratingMilli>=7000)
        assertNull(index.match("series","The Godfather (1972)",1972))
        assertNull(index.match("movie","The Godfather (1972)",2026))
    }

    @Test fun fullLibraryRankingBeatsProviderOrderAndDedupsVariantsWithoutLosingRemakes() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao(); val repo=SafeCatalogRepository(dao) { now }
            val movies=(1..600).map { row("movie",it, if(it==600) "Deep winner (2026)" else "Old $it (2010)") }
            val duplicate=row("movie",601,"Deep winner (2026) HEVC")
            val remake=row("movie",602,"Deep winner (1996)")
            dao.insertAll(movies+duplicate+remake)
            dao.insertDiscovery((movies+duplicate+remake).map { meta(it,if(it.itemId>=600) "9.5" else "6") })
            assertEquals(listOf(600),repo.discovery("movie",DiscoveryShelf.New,12).map { it.itemId })
            assertEquals(listOf(600,602),repo.discovery("movie",DiscoveryShelf.HighlyRated,12).map { it.itemId })
            assertEquals(602,dao.countForType("movie")) // Full library and every playable variant remain.
            val shows=(1..300).map { row("series",it,"Show $it", "Netflix") }
            dao.insertAll(shows);dao.insertDiscovery(shows.map { meta(it,if(it.itemId==300) "9" else "6",if(it.itemId==300) "2026-10-01" else "2000-01-01",genre=if(it.itemId==300) "Crime, Drama" else "Comedy") })
            assertEquals(listOf(300),repo.discovery("series",DiscoveryShelf.New,12).map { it.itemId })
            assertEquals(listOf(300),repo.discovery("series",DiscoveryShelf.HighlyRated,12).map { it.itemId })
            assertEquals(listOf(300),repo.discovery("series",DiscoveryShelf.Genre,12,"Drama").map { it.itemId })
            assertTrue(repo.discovery("movie",DiscoveryShelf.Genre,12,"Netflix").isEmpty())
            assertEquals(12,repo.discovery("series",DiscoveryShelf.Genre,12,"Comedy").size)
        }
    }
    @Test fun unknownInvalidFutureStaleAndTypeCollisionsCannotBecomeNewOrHighlyRated() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao();val repo=SafeCatalogRepository(dao) { now }
            val a=row("movie",1,"Unknown");val b=row("movie",2,"Future (2028)");val c=row("movie",3,"Stale (2026)")
            val show=row("series",1,"Real show")
            dao.insertAll(listOf(a,b,c,show))
            dao.insertDiscovery(listOf(meta(a,rating="0",added="bad"),meta(b,rating="bad",added="1799999999"),meta(c).copy(catalogAtMs=6),meta(show,rating="9",release="2026-09-01")))
            assertTrue(repo.discovery("movie",DiscoveryShelf.New,12).isEmpty());assertTrue(repo.discovery("movie",DiscoveryShelf.HighlyRated,12).isEmpty())
            assertTrue(repo.discovery("movie",DiscoveryShelf.RecentlyAdded,12).isEmpty())
            assertEquals(1,repo.discovery("series",DiscoveryShelf.HighlyRated,12).single().itemId)
        }
    }
    @Test fun ingestionCommitsRankingWithCatalogAndEmptyFailurePreservesBoth() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao();val repo=SafeCatalogRepository(dao) { now }
            val movie=XtreamVodStream(1,"Imported (2026)","movie",1,null,"8.5","4.3","1791124020","category","mp4")
            val show=XtreamSeries(1,"Imported show",1,null,null,null,null,"Drama","2026-10-01","1791124020","9","4.5","category")
            val importer=XtreamCatalogImporter(repo,clockMs={now})
            importer.importMoviesAndSeries(listOf(XtreamCategory("category","Movies",null)),listOf(movie),listOf(XtreamCategory("category","Shows",null)),listOf(show))
            assertNull(dao.discoveryMetadata("movie",1)!!.ratingMilli)
            assertEquals("|Drama|",dao.discoveryMetadata("series",1)!!.genres)
            assertTrue(dao.discoveryBackfill("movie","category")!!.complete)
            val old=dao.discoveryMetadata("series",1)
            importer.importMoviesAndSeries(emptyList(),emptyList(),emptyList(),emptyList())
            assertEquals(old,dao.discoveryMetadata("series",1));assertEquals(1,dao.countForType("movie"));assertEquals(1,dao.countForType("series"))
        }
    }
    @Test fun backfillPreservesExactCatalogAndOnlyAcceptsRetainedMatchingIdentityOnce() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao();val a=row("movie",1,"Retained (2026)");val b=row("movie",2,"Different (2026)")
            dao.insertAll(listOf(a,b));var requests=0
            val account=XtreamCredentials("https://fixture.test","opaque","opaque")
            val fill=DiscoveryMetadataBackfill(dao,{_,_,_-> requests++;listOf(DiscoveryProviderItem(1,a.title,"9",null),DiscoveryProviderItem(2,"Changed title","10",null),DiscoveryProviderItem(3,"Not retained","10",null)) },{now})
            assertEquals(1,fill.run(account) {account});assertEquals(0,fill.run(account) {account});assertEquals(1,requests)
            assertEquals(listOf(a,b),dao.itemsForType("movie"));assertNull(dao.discoveryMetadata("movie",2));assertNull(dao.discoveryMetadata("movie",3))
            assertTrue(db.watchProgressDao().records().isEmpty())
        }
    }
    @Test fun failedBackfillKeepsLastGoodAndBackoffAndAccountChangeRejectLateMetadata() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao();val item=row("movie",1,"Retained (2026)");dao.insertAll(listOf(item));dao.insertDiscovery(listOf(meta(item)))
            val account=XtreamCredentials("https://fixture.test","opaque","opaque");var requests=0
            val fail=DiscoveryMetadataBackfill(dao,{_,_,_->requests++;null},{now})
            assertEquals(0,fail.run(account) {account});assertEquals(0,fail.run(account) {account});assertEquals(1,requests)
            assertEquals(8000,dao.discoveryMetadata("movie",1)!!.ratingMilli)
            val newAccount=account.copy(username="another");var current=account
            val delayed=DiscoveryMetadataBackfill(dao,{_,_,_->current=newAccount;listOf(DiscoveryProviderItem(1,item.title,"10",null))},{now+7*60*60*1000})
            delayed.run(account) {current};assertEquals(8000,dao.discoveryMetadata("movie",1)!!.ratingMilli)
        }
    }
    @Test fun independentIndexRejectsProviderScoresMatchesExactYearsAndIsIdempotent() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao();val repo=SafeCatalogRepository(dao) {now}
            val a=row("movie",1,"A Movie (2026)");val b=row("movie",2,"Unknown (2026)");val remake=row("movie",3,"A Movie (1996)")
            dao.insertAll(listOf(a,b,remake))
            dao.insertDiscovery(listOf(a,b,remake).map {DiscoveryMetadata.legacy(it,now).copy(ratingMilli=10000,ratingSource="provider-10")})
            assertTrue(repo.discovery("movie",DiscoveryShelf.HighlyRated,12).isEmpty())
            val text="imdbId\tcontentType\tprimaryTitle\toriginalTitle\tyear\tratingMilli\tvotes\tgenres\n"+
                "tt0000001\tmovie\tA Movie\tA Movie\t2026\t8200\t40000\tDrama,Crime"
            val index=ImdbDiscoveryIndexer(dao,{ImdbDiscoveryIndex.read(text.reader().buffered())})
            assertEquals(3,index.update());assertEquals(0,index.update())
            assertEquals(listOf(1),repo.discovery("movie",DiscoveryShelf.HighlyRated,12).map {it.itemId})
            assertEquals("imdb",dao.discoveryMetadata("movie",1)!!.ratingSource)
            assertEquals(40000,dao.discoveryMetadata("movie",1)!!.ratingVotes)
            assertNull(dao.discoveryMetadata("movie",2)!!.ratingMilli);assertNull(dao.discoveryMetadata("movie",3)!!.ratingMilli)
            assertEquals(listOf(a,b,remake),dao.itemsForType("movie"))
        }
    }

    @Test fun legacyFillIsBoundedResumableAndCannotOverwriteFreshMetadataOrRemovedIdentity() = runBlocking {
        db().useDb { db ->
            val dao=db.safeCatalogDao();val rows=(1..450).map {row("movie",it)}
            dao.insertAll(rows)
            val existing=meta(rows.first());dao.insertDiscovery(listOf(existing))
            assertEquals(200,dao.missingDiscoveryPage("",-1).size)
            var ownerChecks=0
            val fill=DiscoveryLegacySeeder(dao) {now}
            assertEquals(200,fill.run {++ownerChecks<=2})
            assertEquals(existing,dao.discoveryMetadata("movie",1))
            assertEquals(249,fill.run());assertEquals(0,fill.run())
            assertEquals(rows,dao.itemsForType("movie"))
            dao.removeCatalogIds("movie",listOf(450))
            assertEquals(0,dao.seedDiscovery(listOf(DiscoveryMetadata.legacy(rows.last(),now))))
            val replacement=rows[1].copy(title="Replacement (1990)",updatedAtMs=8)
            dao.insertAll(listOf(replacement))
            assertEquals(0,dao.seedDiscovery(listOf(DiscoveryMetadata.legacy(rows[1],now))))
            assertEquals(1,fill.run());assertEquals(19900101,dao.discoveryMetadata("movie",2)!!.releaseSort)
            assertTrue(db.watchProgressDao().records().isEmpty())
        }
    }

}
