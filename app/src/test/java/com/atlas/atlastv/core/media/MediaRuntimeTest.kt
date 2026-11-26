package com.atlas.atlastv.core.media

import org.junit.Assert.*
import org.junit.Test

class MediaRuntimeTest {
    @Test fun compactLabelsShareRoundingAndOmitInvalidMetadata() {
        assertEquals("2h", MediaRuntime.labelMinutes(120)); assertEquals("2h 1m", MediaRuntime.labelMinutes(121))
        assertEquals("1m", MediaRuntime.labelMillis(500)); assertEquals("1h 1m", MediaRuntime.labelMillis(3_600_001))
        assertEquals("24h", MediaRuntime.labelMillis(MediaRuntime.MaxMillis))
        for (bad in listOf(null, 0L, -1L, Long.MAX_VALUE, MediaRuntime.MaxMillis + 1)) assertNull(MediaRuntime.labelMillis(bad))
        for (bad in listOf(null, -1, 0, 1441, Int.MAX_VALUE)) assertNull(MediaRuntime.labelMinutes(bad))
    }
}
