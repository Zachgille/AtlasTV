package com.atlas.atlastv.data.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.home.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in timings of retained catalog work; no provider requests or personal edits. */
class HomeStartupProfileConnectedTest {
    @Test fun reportPolicyHomeAndIndividualDiscoveryQueryCosts() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dao=AtlasDatabaseProvider.database(context).safeCatalogDao()
        val report=StringBuilder()
        suspend fun <T> timed(name: String,block: suspend ()->T): T {
            val start=System.nanoTime();val result=block()
            report.append(name).append(" ms=").append((System.nanoTime()-start)/1_000_000).append('\n');return result
        }
        assertTrue(timed("database-open/count") {dao.countForType("movie")}>500)
        assertEquals(0,timed("catalog-language-policy") {dao.applyEnglishCategoryPolicy()})
        val started=System.nanoTime()
        val stages=mutableListOf<com.atlas.atlastv.ui.home.HomeLoadResult>()
        HomeCatalogSource(context).updates().collect { stage ->
            assertTrue(stage.state is HomeShellState.Ready)
            report.append(if(stage.isComplete) "complete-home-source" else "usable-leading-home-source")
                .append(" ms=").append((System.nanoTime()-started)/1_000_000).append('\n')
            stages += stage
        }
        assertEquals(2,stages.size)
        assertFalse(stages.first().isComplete)
        assertTrue(stages.last().isComplete)
        val leading=stages.first().state as HomeShellState.Ready
        val complete=stages.last().state as HomeShellState.Ready
        assertFalse(leading.rows.any { it.id.contains("_genre-") })
        assertTrue(complete.rows.any { it.id.contains("_genre-") })
        assertEquals(complete.rows.filterNot { it.id.contains("_genre-") },leading.rows)
        assertEquals(leading.initialFocusCardId,complete.initialFocusCardId)
        assertEquals(stages.first().continueWatching,stages.last().continueWatching)
        val repo=SafeCatalogRepository(dao)
        for(type in listOf("movie","series")) {
            timed("$type/new") {repo.discovery(type,DiscoveryShelf.New,12)}
            timed("$type/rated") {repo.discovery(type,DiscoveryShelf.HighlyRated,12)}
            for(genre in DiscoveryCollectionSpec.Genres) timed("$type/$genre") {repo.discovery(type,DiscoveryShelf.Genre,12,genre)}
        }
        File(context.cacheDir,"home-startup-profile-${InstrumentationRegistry.getArguments().getString("atlastv.speedLabel","sample")}.txt").writeText(report.toString())
        println(report)
    }
}
