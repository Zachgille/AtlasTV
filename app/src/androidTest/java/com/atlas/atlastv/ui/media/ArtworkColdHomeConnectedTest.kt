package com.atlas.atlastv.ui.media

import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.home.HomeCatalogSource
import com.atlas.atlastv.ui.home.HomeShellState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Six actual leading-Home images, cold in a private directory; no catalog/playback mutations. */
class ArtworkColdHomeConnectedTest {
    @Test fun reportActualLeadingHomePosterArrivalWithoutExistingArtworkCaches() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val home = HomeCatalogSource(context).updates().first().state as HomeShellState.Ready
        val tokens = home.rows.flatMap { it.cards }.mapNotNull { it.posterImage.providerImageToken }.distinct().take(6)
        assertEquals("This opt-in check requires the retained library", 6, tokens.size)
        val directory = File(context.cacheDir, "artwork-cold-home-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val index = AtlasProviderImageIndex.create(context)
            val start = System.nanoTime()
            val arrivals = withTimeout(25_000) { tokens.map { token -> async {
                val image = AtlasProviderImageLoader.load(token, directory, index, ArtworkSize.forPixels(256, 384))
                ((System.nanoTime() - start) / 1_000_000) to (image != null)
            } }.awaitAll() }
            println("ARTWORK COLD HOME loaded=${arrivals.count { it.second }}/6 arrival-ms=${arrivals.map { it.first }}; isolated empty disk/memory identity, four HTTP slots, no addresses/titles logged")
            assertTrue("At least one real provider poster must decode", arrivals.any { it.second })
        } finally { directory.deleteRecursively() }
    }
}
