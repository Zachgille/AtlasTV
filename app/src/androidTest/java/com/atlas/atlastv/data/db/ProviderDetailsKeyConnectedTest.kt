package com.atlas.atlastv.data.db

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.xtream.model.XtreamCredentials
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic credentials only; measures the 36 calls observed in the actual Home profile. */
@RunWith(AndroidJUnit4::class)
class ProviderDetailsKeyConnectedTest {
    @Test fun unchangedKeysAvoidRepeatedFormatterWork() {
        val credentials = XtreamCredentials(" https://example.invalid/// ", "opaque 用户", " literal 🔐 password ")
        val namespace = "AtlasTV Home hero v1"
        val expected = legacy(credentials, namespace)
        repeat(36) {
            assertEquals(legacy(credentials, namespace), providerDetailsCacheKey(credentials, namespace))
        }
        val before = mutableListOf<Long>()
        val after = mutableListOf<Long>()
        fun sample(operation: () -> String): Long {
            val start = SystemClock.elapsedRealtimeNanos()
            val values = List(36) { operation() }
            val elapsed = SystemClock.elapsedRealtimeNanos() - start
            assertTrue(values.all { it == expected })
            return elapsed
        }
        repeat(7) { round ->
            if (round % 2 == 0) {
                before += sample { legacy(credentials, namespace) }
                after += sample { providerDetailsCacheKey(credentials, namespace) }
            } else {
                after += sample { providerDetailsCacheKey(credentials, namespace) }
                before += sample { legacy(credentials, namespace) }
            }
        }
        val oldMedian = before.sorted()[before.size / 2]
        val newMedian = after.sorted()[after.size / 2]
        val report = "36 exact same account-key calls per sample; alternating order, warmed synthetic inputs.\n" +
            "legacy_ns=$before\noptimized_ns=$after\nlegacy_median_ns=$oldMedian\noptimized_median_ns=$newMedian\n"
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "home-account-key-benchmark.txt").writeText(report)
        println(report)
        assertTrue("Measured encoding should reduce the observed formatter bottleneck", newMedian < oldMedian)
    }

    private fun legacy(credentials: XtreamCredentials, namespace: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (value in listOf(namespace, credentials.baseUrl.trim().trimEnd('/'), credentials.username, credentials.password)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
