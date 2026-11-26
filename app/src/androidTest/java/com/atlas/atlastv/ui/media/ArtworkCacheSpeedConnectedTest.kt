package com.atlas.atlastv.ui.media

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Same 1,000-entry shape as the retained Onn cache, isolated from personal files. */
class ArtworkCacheSpeedConnectedTest {
    @Test fun repeatedHitsAndMissesInLargeCacheRemainValidAndReportElapsedTime() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"artwork-speed-${UUID.randomUUID()}").apply {mkdirs()}
        fun token(id: Int)=PROVIDER_IMAGE_TOKEN_PREFIX+id.toString(16).padStart(64,'0')
        try {
            repeat(1000) {File(directory,"${it.toString(16).padStart(64,'0')}.img").writeBytes(ByteArray(128) {it.toByte()})}
            val cache=AtlasProviderImageCache(directory)
            val samples=(0..2).map {
                val started=System.nanoTime()
                repeat(16) {id -> assertEquals(128,cache.read(token(id))!!.size);assertNull(cache.read(token(1000+id)))}
                (System.nanoTime()-started)/1_000_000
            }
            assertEquals(1000,directory.listFiles()!!.size)
            val report="1,000-file isolated disk cache; each sample is 16 hits + 16 misses; elapsed-ms=$samples\n"
            File(context.cacheDir,"artwork-cache-speed-${InstrumentationRegistry.getArguments().getString("atlastv.speedLabel","sample")}.txt").writeText(report)
            println(report)
        } finally {directory.deleteRecursively()}
    }
}
